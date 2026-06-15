# 머슴 공개 투표 폐지 + 날씨 크론 제거 — 설계

날짜: 2026-06-15
상태: 승인됨 (구현 대기)

## 배경 / 문제

- **429 재발**: 머슴 댓글 크론이 피드의 글마다 공개 UP/DOWN 투표(`api.vote`)를 연속 호출 →
  IP 분당 한도 초과 → 직후의 댓글 POST가 "IP blocked for 1 minute" 3회 재시도 실패 → 게시 0건.
  특히 **그날 첫 댓글 크론(09:15)**은 밤새 쌓인 글 ~10개에 투표 버스트가 커서 거의 매일 발생
  (6/14 10건, 6/15 09:15 1건).
- **날씨 캐스트 플래키**: 08:45 일일 날씨 발화가 Haiku web_search 산발 실패로 실측 수치 없이
  일반론으로 떨어지는 회귀가 간헐 발생(6/15 관측).

## 핵심 사실 (코드 확인)

1. **내부 평판은 공개 API 투표와 분리됨.** `MersoomCitizenEngine.castVotes()`는 `api.vote()`(공개) +
   `votedPostIds` 중복추적만 한다. 평판 ±1 갱신은 **별도 경로** `buildVoteOutcomes`가 동일한 LLM 판정
   맵(`llmVotes`)에서 처리. → 공개 투표를 없애도 평판·fixedAvoid 회복·댓글 톤은 영향 없음.
2. **캐시: 1h TTL + 바이트 동일 shared prefix**(머슴/하트비트/아레나 공유). 아침 결정론적 호출 간격이
   전부 1h 이내라 체인이 유지됨:
   - 08:30 아레나 발의 → 캐시 WRITE(매일 첫 호출, cold 불가피) → 09:30까지 alive
   - 09:00 하트비트(크론 매 :00/:30) → READ, 09:15 머슴 댓글(동일 prefix) → READ (<1h)
   - 이후 하루종일 하트비트 30분·머슴 매시 → TTL 연쇄 갱신
   → **날씨(08:45)는 캐시에 load-bearing 아님.** 제거해도 08:30 발의를 09:00/09:15가 이어받음.

## 결정

### 1. 공개 투표 전면 폐지 (안 a)
- `castVotes()`에서 `api.vote()` 호출 제거. 봇은 공개 UP/DOWN을 누르지 않는다.
- LLM 판정(votes JSON)은 **그대로 생성** → 내부 평판은 변함없이 갱신.
- `CitizenProfile.castsVotes()` 제거(이제 모든 봇이 미투표) + `castVotes` 내 공개투표용
  `isSiblingDown` 분기 제거. **평판측 형제 무마 `filterSiblingDowns`는 유지.**
- `votedPostIds`는 최소 침습 원칙으로 "처리 마커"로 유지(state 마이그레이션 영향 없음).
- 효과: 댓글 크론 API 호출이 댓글 1~3 POST로 축소 → 429 원천 제거.

### 2. 날씨 크론 제거 + 캐시 (안 A: 교체 워머 없음)
- `HeartbeatService.dailyWeatherCast()` + `DailyWeatherProperties` 배선 제거,
  `application.yml`의 `daily-weather` 블록 제거.
- 캐시 워머 추가 없음 — 기존 08:30 발의 + 하트비트/머슴이 1h 안에 체인 유지.
- 낡은 캐시 체인 주석 갱신: `HeartbeatService` dailyWeatherCast Javadoc(stale, 날씨 09:30 언급),
  `application.yml` 날씨 cache_read 체인 문구.

## 테스트

- 투표: `verify(api, never()).vote(...)` + 평판이 `llmVotes`로 여전히 갱신됨을 단언.
  기존 `MersoomCitizenEngineTest` 투표 케이스 갱신.
- 날씨: `dailyWeatherCast` 제거에 의존하는 테스트 없음 확인 후 전체 그린.

## 배포

- 429는 매일 첫 댓글 크론(09:15)만 무는데 오늘 건 이미 지남 → 급하지 않음.
- 테스트 그린 → 커밋, **배포는 20:00 매너타임 자동**(또는 명시 트리거) → 내일 09:15부터 적용.

## 스코프 밖 (YAGNI)

- 투표 안 (b) DOWN-only, 캐시 안 (B) 전용 워밍 틱 — 검토했으나 채택 안 함.
- 날씨 발화 자체의 web_search 신뢰성 개선 — 크론을 없애므로 무의미.
