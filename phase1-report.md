# Phase 1 완료 보고

마이그레이션 핸드오프(`open-pjsk/migration-handoff.md`) Phase 1 — 라우터 봇 PoC.

## 완료된 작업

### Plan task (1~14, 자동 구현)

| Task | 내용 | Commit |
|---|---|---|
| 1 | GitHub 리포 초기화 | `d9a17bd` |
| 2 | Gradle Kotlin DSL + Spring Boot 3.4.1 + JDA 5.2.1 + Anthropic SDK 의존성 | `07a0cd1` |
| 3 | 메인 애플리케이션 + application.yml + .env.example | `f1c4abf` |
| 4 | CharacterId enum + 검증된 ConfigurationProperties (+ null guard fix) | `a6f6e28` / `2f7d887` |
| 5 | PersonaLoader (TDD, 2 테스트) | `06d06d5` |
| 6 | PersonaRegistry + PersonaWatcher mtime 감지 (+ 파일 삭제 감지 + IOException unchecked) | `dbea744` / `c6a649a` |
| 7 | sealed RoutingDecision + records | `66d420b` |
| 8 | ConversationMemory (TDD, 3 테스트) | `9eac182` |
| 9 | LastSpeakerStore + RandomCharacterSelector (TDD, 6 테스트) | `38f30b8` |
| 10 | SystemPromptBuilder + 정적 프롬프트 리소스 | `a7eab59` |
| 11 | RouterService — Anthropic 호출 + JSON 파싱 (TDD, 3 테스트) | `d6f2989` |
| 12 | DiscordConfig — JDA Bean 8개 (+ 캐릭터 JDA shutdown 라이프사이클) | `182cb02` / `cd244fc` |
| 13 | ProxySpeechService + TypingIndicatorService | `de665bc` |
| 14 | RouterEventListener — 메시지 수신 → 라우팅 → 발화 통합 | `09fdf89` |

총 18개 단위 테스트 통과 (PersonaLoader/PersonaWatcher/ConversationMemory/LastSpeakerStore/RandomCharacterSelector/RouterService/SystemPromptBuilder).

### Plan 외 추가 작업 (운영 검증 중 도출)

| 변경 | Commit | 사유 |
|---|---|---|
| Anthropic SDK `0.8.0 → 2.30.0` 업그레이드 | `d09591a` | SDK 0.8.0 `Tool` 모델이 `web_search_20250305` 미지원 (`input_schema` 강제 포함) |
| Anthropic native `web_search` tool 통합 | `ed9603f` / `d09591a` | 실시간 정보 질의(날씨/뉴스 등) 지원 |
| Multi-block 응답 파싱 (마지막 text block) | `4bf0ad5` | web_search response가 검색 prelude + tool_use + 최종 text 다중 블록 구성 |
| Docker 컨테이너화 (multi-stage Dockerfile + docker-compose) | `38003c9` | 운영 배포 패키지 |
| 프롬프트 캐싱 활성화 (`cache_control: ephemeral`) | `9f6d8e9` | 17,880 토큰 캐시 적중률 100% (5분 TTL 윈도우) |
| Typing indicator race fix (1.5초 분리) | `d845cbf` | sendMessage가 sendTyping보다 먼저 도착하던 race |
| 동적 typing duration (메시지 길이 기반) | `b3e17cd` | base 800ms + 글자당 80ms, max 4초 |
| 수신 메시지 전문 로깅 | `10eeb59` | 디버깅 + 운영 가시성 |
| 동적 read buffer (직전 메시지 길이 기반) | `66115ae` | 사용자 읽기 시간 반영, base 500ms + 글자당 40ms, max 2.5초 |
| GRADES.md 시스템 프롬프트 임베드 | `6c0b933` | 호칭·존댓말 매트릭스 정확도 향상 |
| USER.md / quick-ref.md / events.json 임베드 + 오늘 날짜(KST) user prompt | `7c37157` | 사용자 정체성 + MaiT 대화 어미 + 생일/기념일 자연 언급 |

## 검증 결과

### 검증된 시나리오

- ✅ **시나리오 1 — 기명 단발**: "에무 안녕" → emu 응답 (페르소나 ~에요/원더호~이☆/1인칭 "에무" 정확)
- ✅ **시나리오 3 — 무기명 멀티턴**: 직전 emu 응답 후 후속 질문 → emu 유지 응답
- ✅ **시나리오 5 — 다중 호명**: "아이리랑 시즈쿠랑 사이 좋아?" → AIRI + SHIZUKU 순차 응답
- ✅ **web_search 통합**: 날씨 질문 → 검색 결과 → 캐릭터 톤 응답 (`stop_reason=end_turn`)
- ✅ **프롬프트 캐싱**: 첫 호출 cache_creation=17880, 후속 호출 cache_read=17880 (100% 적중)
- ✅ **Typing 흐름**: typing 1.5초 표시 → 응답 도착 (race 해결)
- ✅ **다중 응답 타이밍**: 동적 typing/read buffer 적용 검증 (50자 메시지 기준 cap 정확 일치)

### 미검증 시나리오

- ⚠ 시나리오 2 — 무기명 첫 대화 (랜덤 캐릭터 응답)
- ⚠ 시나리오 4 — 캐릭터 전환
- ⚠ 시나리오 6 — 전원 호명 ("다들/모두")
- ⚠ 시나리오 7 — 리액션 발화 (depth 2)
- ⚠ 시나리오 8 — 스티커 전용 NO_REPLY

## 비용 측정

마이그레이션 ROI 검증 — `baseline.md` 참조. 요약:

```
OpenClaw    : ~$0.10/건 (추정)
Spring Boot : ~$0.01/건 (Sonnet 4.6 + cache 적중)
절감        : 약 10배 (90%)
```

**핸드오프의 80~90% 절감 목표 달성**.

## 운영 상태

- **컨테이너**: `sekai-router` (Docker compose, Sonnet 4.6, persona volume mount)
- **OpenClaw 게이트웨이**: 정지 (점진 컷오버 대신 즉시 전환)
- **세카이 채널** (`1485510333115273339`): Spring Boot 라우터가 직접 처리
- **헤드쿼터 채널 (main 에이전트)**: OpenClaw 의존, 게이트웨이 정지로 일시 비활성

## 알려진 이슈 / 위험

1. **세카이 봇(main 에이전트) 자기 개선 루프 정지** — 페르소나 자동 편집 기능 비활성. Phase 4 (file edit tool 구현)에서 복원 필요
2. **하트비트 비활성** — 자율 발화 시스템 정지. Phase 3.5 마이그레이션 대상
3. **비검증 시나리오 5건** — 운영 중 개별 발생 시 추적 권장
4. **`last-speaker.txt` 공유 위험 회피됨** — OpenClaw 정지 상태이므로 라우터/하트비트 충돌 자체 부재 (Phase 3.5에서 하트비트 마이그레이션 시 Redis로 통합 권장)

## 다음 Phase 준비

### 사용자 결정 필요 사항

- **Phase 3.5 (하트비트 마이그레이션)** 진행 여부 — 자율 발화 복원 필요성
- **Phase 4 (세카이 봇 마이그레이션)** 진행 여부 — main 에이전트의 페르소나 자기 개선 루프 복원 필요성
- **모델 정책** — 현재 Sonnet 4.6. Haiku 4.5 전환 시 추가 ~3~4배 절감 가능하지만 호칭 매트릭스 정확도 검증 필요

### 권장 후속

1. 미검증 시나리오 5건 운영 중 점진 검증 (시나리오 로그)
2. Phase 2 정밀 측정 — 일일 호출량 / 캐시 적중률 / 일일 비용 정량 데이터 수집
3. Phase 3.5 또는 Phase 4 진입 (사용자 결정)
4. PersonaWatcher가 GRADES.md/USER.md/quick-ref.md/events.json 변경도 감지하도록 확장 (현재는 .md 인덱스만 감시)

## 남은 작업 요약

- [ ] 미검증 시나리오 5건 검증
- [ ] Phase 2 정밀 비용·정확도 측정
- [ ] Phase 3.5 하트비트 마이그레이션 (선택)
- [ ] Phase 4 세카이 봇(main 에이전트) 마이그레이션 (선택)
- [ ] Phase 5 OpenClaw 완전 제거
