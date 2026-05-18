# 시간대 인지 발화 (Time-of-Day Aware Utterance)

작성일: 2026-05-18
대상 모듈: `com.maitmus.sekairouter.heartbeat`

## 배경

현재 heartbeat user prompt에는 `## 오늘 날짜 (KST)\n2026-05-18` 블록만 들어가고 시각 정보는 없다. 결과적으로 모델이 시간대를 고려하지 못해, 예를 들어 저녁 슬롯에 "동아리 연습 중이야" 같이 학생 일과상 어색한 발화가 나오는 경우가 있다.

캐릭터가 미야마스자카/카미야마 학생이라는 사실은 systemPrompt에 이미 들어있지만, 시각 컨텍스트가 없어 모델 자체 추론으로는 토픽 plausibility를 일관되게 보장하지 못한다.

## 목표

모든 자율 발화 및 이벤트 발화에서 모델이 **현재 시각 + 시간대 라벨**을 받아, 그 시간대에 어울리는 토픽으로 발화하도록 한다.

비목표:
- 시간대별 활동 화이트리스트/블랙리스트 강제 (모델 자율성 보존)
- Daily weather cast의 프롬프트 변경 (별도 컨텍스트가 충분히 강함)
- 공휴일/대체휴일 처리 (YAGNI)

## 결정 사항 요약

| 항목 | 결정 |
|---|---|
| 주입 형태 | 시각 + 라벨 (B 옵션) |
| 평일/주말 분기 | 분기함 (토/일 = 주말) |
| 세분도 | 평일 6 구간, 주말 4 구간 (D1) |
| 적용 범위 | normal solo / dialogue 첫 발화 / dialogue 응답 / event (E3, weather 제외) |

## 라벨 매핑

**평일 (월~금)** — 6 구간:

| 시각 범위 | 라벨 |
|---|---|
| 10:00 ~ 11:59 | 오전 수업 |
| 12:00 ~ 12:59 | 점심시간 |
| 13:00 ~ 14:59 | 오후 수업 |
| 15:00 ~ 17:59 | 방과 후 |
| 18:00 ~ 19:59 | 귀가/저녁 |
| 20:00 ~ 20:59 | 밤 휴식 |

**주말 (토/일)** — 4 구간:

| 시각 범위 | 라벨 |
|---|---|
| 10:00 ~ 11:59 | 주말 오전 |
| 12:00 ~ 12:59 | 주말 점심 |
| 13:00 ~ 17:59 | 주말 오후 |
| 18:00 ~ 20:59 | 주말 저녁 |

- 경계 컨벤션: `[start, end)`. 평일 기준 12:00:00 → 점심시간, 11:59:59 → 오전 수업. 주말 기준 12:00:00 → 주말 점심, 11:59:59 → 주말 오전
- 활성 시간(10:00 ~ 20:59) 밖은 라벨 정의 안 함. heartbeat가 quiet hours(21:00~10:00)에 발화하지 않으므로 정상 경로에서는 도달 불가
- 비활성 시간에 호출될 경우 fallback 라벨 `"활성 시간 외"` 반환 (NPE 방지)

## 프롬프트 포맷

**Before** (모든 경로 공통):
```
## 오늘 날짜 (KST)
2026-05-18
```

**After** (normal solo / dialogue 첫 / dialogue 응답 / event):
```
## 현재 시각 (KST)
2026-05-18 (월) 14:23 (오후 수업)
```

- 헤더: `## 오늘 날짜 (KST)` → `## 현재 시각 (KST)`
- 본문: `<YYYY-MM-DD> (<요일 1자>) HH:mm (<라벨>)`
  - 요일은 한글 1자 (월/화/수/목/금/토/일)
  - 시각은 24시간 `HH:mm`
- Daily weather cast는 기존 `## 오늘 날짜 (KST)\n<LocalDate>` 유지

## 아키텍처

**새 컴포넌트:** `TimeOfDayLabeler` (`heartbeat` 패키지, `@Component`)

```
TimeOfDayLabeler
├─ String label(LocalDateTime now)
└─ String promptBlock(LocalDateTime now)
```

- Stateless. Clock 주입 안 함. 호출자가 `LocalDateTime` 인자를 만들어 넘김
- 단위 테스트는 `LocalDateTime` 인자만으로 가능 (clock 모킹 불필요)

**HeartbeatService 변경:**
- 각 `execute*Heartbeat` 진입부에서 `LocalDateTime now = LocalDateTime.now(clock)`을 한 번 캡처
- 4 경로의 user prompt 빌더에서 기존 날짜 블록을 `timeOfDayLabeler.promptBlock(now)` 호출로 교체
- Dialogue 2단계는 첫 발화 시점 캡처값을 응답 프롬프트에서도 재사용 (같은 슬롯의 같은 시점 대화로 모델링)

**기존 `LocalDate.now(clock)` 호출**(event recordEvent 등)은 변경 없음.

## 엣지 케이스

- **시각 비결정성:** 같은 발화 내 여러 호출로 시각이 어긋나지 않도록 함수 진입부에서 한 번만 캡처
- **비활성 시간 호출:** `"활성 시간 외"` fallback
- **요일 판정:** `DayOfWeek.SATURDAY`, `DayOfWeek.SUNDAY` → 주말
- **Clock 존:** `HeartbeatConfig.heartbeatClock()`이 `Asia/Seoul`로 고정되어 있어 KST 변환 별도 처리 불필요

## 테스트 계획

**새 클래스 `TimeOfDayLabelerTest`:**

- 평일 경계 (수요일 같은 임의 평일 사용): 09:59 / 10:00 / 11:59 / 12:00 / 12:59 / 13:00 / 14:59 / 15:00 / 17:59 / 18:00 / 19:59 / 20:00 / 20:59 / 21:00
  - 09:59, 21:00 → `"활성 시간 외"`
  - 나머지는 라벨 매핑 표와 일치
- 주말 경계 (토요일 / 일요일 각각): 09:59 / 10:00 / 11:59 / 12:00 / 12:59 / 13:00 / 17:59 / 18:00 / 20:59 / 21:00
- 요일 7개: 월~금 → 평일 라벨, 토/일 → 주말 라벨 (각 1 케이스, 12:30 같은 안전 시각)
- `promptBlock()` 포맷: `2026-05-18 (월) 14:23 (오후 수업)` 정확 매치

**기존 `HeartbeatServiceTest` 영향:**

- 기존 prompt assertion에서 `## 오늘 날짜 (KST)` 또는 `LocalDate.now(clock)` 문자열을 검증하던 케이스를 `## 현재 시각 (KST)`로 갱신 (4 경로)
- Weather cast 테스트는 영향 없음
- 신규 통합 검증: 각 경로 1건씩 user prompt에 시각 라인이 포함되는지 ArgumentCaptor로 확인

## 구현 순서 (TDD)

1. RED: `TimeOfDayLabelerTest` 작성 (compile fail)
2. GREEN: `TimeOfDayLabeler` 구현
3. RED: `HeartbeatServiceTest` 새 assertion 추가 (실패)
4. GREEN: `HeartbeatService` 4 경로 프롬프트 교체
5. Refactor: 4 경로의 시각 캡처 로직 정리

## 빌드/배포 게이트

- `./gradlew test` 전체 통과 (기존 110 + Labeler 신규 + Service 추가)
- 컨테이너 재시작은 사용자 명시 요청 시에만 (기존 메모리 컨벤션 준수)
