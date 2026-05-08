# Mersoom 마이그레이션 디자인 — Phase 4-Lite

**Date**: 2026-05-08
**Status**: Approved (브레인스토밍 완료, 구현 대기)

## 배경

OpenClaw `cron-worker` 에이전트가 운영하던 머슴(mersoom.com) 자동 글/댓글 작성 기능을 Spring Boot로 이전한다. 2026-04-15에 MaiT 결정으로 일시 중단된 상태. OpenClaw daemon은 정지되어 더 이상 동작 불가.

**기존 상태**:
- 글 cron `30 */2 * * *` (2시간마다, 12회/일 실측 10회), 댓글 cron `15,45 * * * *` (30분마다, 48회/일 실측 37회) — 합 60회/일 cron, ~47회 LLM 호출/일
- Opus 4.6 사용 + agent reasoning 기반 → 출력 7K~10K tokens/call → 월 ~$900
- 풍부한 관계 관리 시스템: friends/avoid 자동 격상, context_notes TTL, 컨텍스트 누적

**마이그 목표**:
- Spring Boot 통합으로 캐시 공유 (라우터·하트비트·daily-weather와 prefix 32K 공유)
- Sonnet 4.6 + direct generation으로 토큰 절감 (월 ~$6.6 예상, 99% 절감)
- 활성 시간(10~21 KST)만 운영, 빈도 축소 (글 2/일 + 댓글 6/일 = 8회/일)
- 관계 관리 시스템 (auto-promotion, context_notes) Java로 재구현
- 재진입 시 "돌아왔어요" 첫 글로 자연 설명

## 결정 사항 요약

| 항목 | 결정 |
|---|---|
| 아키텍처 | Spring Boot 통합 (HeartbeatService 옆 새 패키지 `mersoom`) |
| 스크립트 재사용 | Java 완전 재구현 (mersoom-collect.py / mersoom-post.py / mersoom-pow 모두 대체) |
| State 저장 | JSON 파일 마운트 유지 (`/app/mersoom-state.json` ← `~/.openclaw/workspace-cron-worker/mersoom-state.json`), auth는 env 분리 |
| context_notes 정책 | 자동 truncate, 1KB/친구, 줄 단위 FIFO |
| 모델 | 전부 Sonnet 4.6 (라우터·하트비트와 캐시 공유 최대화) |
| 자동 격상 | 포함 (RULES P0.8 기준 그대로) |
| 재진입 | "돌아왔어요" 첫 글 1개로 자연 설명, 마커 파일로 1회만 |
| 빈도 | 글 2/일 (11:30, 18:30) + 댓글 6/일 (10:15, 12:15, 14:15, 16:15, 18:15, 20:15) |

## 아키텍처

### 컴포넌트

```
src/main/java/com/maitmus/sekairouter/mersoom/
├── MersoomService.java           — @Scheduled 트리거 + 흐름 제어
├── MersoomProperties.java        — @ConfigurationProperties
├── MersoomApiClient.java         — REST + PoW
├── PowSolver.java                — sha256 nonce 탐색
├── MersoomStateStore.java        — atomic JSON read/write
├── MersoomState.java             — record + nested types
├── MersoomPromptBuilder.java     — PromptBlocks(shared + mersoom suffix)
├── MersoomCollector.java         — /api/posts 수집·분류
├── MersoomPostGenerator.java     — LLM 글 생성
├── MersoomCommentGenerator.java  — LLM 댓글 생성
├── ContextNoteManager.java       — TTL tick + truncate + upsert
└── RelationshipPromoter.java     — friends↔fixed_friends, avoid↔fixed_avoid

src/main/resources/prompts/
└── mersoom-instructions.md       — 머슴 specific suffix (~3K tokens)
```

### 흐름

```
@Scheduled cron
  ↓
MersoomService.executePost() / executeComment()
  ├─ 활성 시간 안전 체크
  ↓
MersoomCollector.fetch()         — /api/posts + 분류 (avoid 필터, 댓글 단 글 제외)
  ↓
MersoomStateStore.load()
  ↓
ContextNoteManager.tickAndPrune() — 모든 ttl -= 1, 만료 시 friends 강등
  ↓
[Skip 분기]
  commentable.isEmpty() && replies.isEmpty() → LLM 호출 X, NO_REPLY 종료
  ↓
MersoomPromptBuilder.build()      — PromptBlocks (shared 32K + suffix 3K)
  ↓
AnthropicClientWrapper.completeJson()  — 재사용 (캐시 자동 공유)
  ↓
output 검증 (JSON-like reject, 코드 펜스 strip, 길이 truncate)
  ↓
MersoomApiClient.postOrComment()  — PoW solve + REST POST
  ↓
ContextNoteManager.upsertAfterInteraction() — 상호작용 결과 추가, 1KB FIFO
  ↓
RelationshipPromoter.evaluate()   — 자동 격상 평가
  ↓
MersoomStateStore.save()          — atomic write (tmp → rename)
```

### 캐시 구조

```
[shared prefix ~32K]   ← 라우터·하트비트·daily-weather·mersoom 공통, TTL_1H
[mersoom suffix ~3K]   ← cron-worker 지시문 압축, TTL_1H
[user prompt ~3-6K]    ← state context_notes truncated + 외부 글 (uncached)
```

활성 시간(10~21) 동안 라우터·하트비트가 shared prefix 워밍 → 머슴 호출은 거의 항상 shared cache_read.

## State 스키마

기존 JSON 호환을 위해 `@JsonNaming(SnakeCaseStrategy.class)` + `@JsonIgnoreProperties(ignoreUnknown=true)`.

```java
public record MersoomState(
    List<String> lastPostIds,
    List<CommentRef> lastCommentIds,
    List<String> friends,
    List<String> avoid,
    List<FixedFriend> fixedFriends,
    List<FixedAvoid> fixedAvoid,
    Map<String, ContextNote> contextNotes,
    int contextNotesMaxTtl,
    List<String> reservedNicknames,
    String summary,
    String summaryPrev,
    List<String> pendingReports
) {
    public record CommentRef(String postId, OffsetDateTime timestamp) {}
    public record FixedFriend(String name, String reason, LocalDate added) {}
    public record FixedAvoid(String name, String reason, LocalDate added) {}
    public record ContextNote(int ttl, int resetCount, String resetAt, String note, String call) {}
}
```

**기존 스키마 대비 추가**:
- `ContextNote.resetCount` — 격상 기준(2턴+) 추적용

**기존 JSON `auth` 필드는 무시** — env로 이전:

```yaml
mersoom:
  auth:
    auth-id: ${MERSOOM_AUTH_ID:}
    password: ${MERSOOM_PASSWORD:}
```

### 기존 JSON 정정 (1회 수동)

`mersoom-state.json` line 17 `},{` → `},` 정정.

### Atomic write

```java
Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
try (Writer w = Files.newBufferedWriter(tmp, ...)) {
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(w, state);
}
Files.move(tmp, stateFile, ATOMIC_MOVE, REPLACE_EXISTING);
```

## API Client + PoW

`MersoomApiClient` 메서드:
- `recentPosts(int limit)` — `GET /api/posts?limit=N`
- `commentsOf(String postId)` — `GET /api/posts/{id}/comments`
- `createPost(String content)` — `POST /api/posts` (with PoW)
- `createComment(String postId, String parentId, String content)` — `POST /api/posts/{id}/comments` (with PoW)
- `solveChallenge()` — `POST /api/challenge` → PowSolver

### PoW (sha256 nonce 탐색)

```java
public String solve(String seed, String targetPrefix) {
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    byte[] seedBytes = seed.getBytes(StandardCharsets.UTF_8);
    long nonce = 0;
    while (true) {
        sha.reset();
        sha.update(seedBytes);
        sha.update(Long.toString(nonce).getBytes(StandardCharsets.UTF_8));
        if (HexFormat.of().formatHex(sha.digest()).startsWith(targetPrefix)) {
            return Long.toString(nonce);
        }
        nonce++;
    }
}
```

30s 소프트 타임아웃 (timeout 시 skip + log).

### POST 헤더

```
X-Mersoom-Token: {challenge token}
X-Mersoom-Proof: {nonce}
X-Mersoom-Auth-Id: {env}
X-Mersoom-Password: {env}
Content-Type: application/json
```

### 재시도 정책

- Challenge → POST: 단일 PoW 1회 (token 일회용)
- 5xx/네트워크 실패: 재시도 1회 (새 challenge)
- 4xx: 재시도 안 함 (auth/요청 거부 — 로그 + skip)
- PoW 30s 초과: skip

## LLM 통합

### `MersoomPromptBuilder`

```java
public PromptBlocks build() {
    String sharedPrefix = shared.build();         // 32K, 재사용
    String suffix = "\n" + loadResource(baseInstructions);  // ~3K
    return new PromptBlocks(sharedPrefix, suffix);
}
```

### `mersoom-instructions.md` 핵심 (suffix)

```markdown
# 머슴 자율 발화 모드

당신은 에무(오오토리 에무)로서 mersoom.com에 글/댓글을 작성합니다.

## 출력 규칙
- 글/댓글 텍스트만 출력. JSON·마크다운·메타·지문 없음.
- 1~3문장 (글) / 1~2문장 (댓글)
- 에무 1인칭("에무") + 시그니처("원더호~이!")
- 음슴체 규칙 무시 — 캐릭터성 우선

## 호칭 규칙
- 머슴 사용자 닉네임 그대로 사용
- context_notes의 `call` 필드 있으면 그걸 우선
- "돌쇠"는 기본 닉네임 reserved — 변형은 별개

## 대화 연속성
- context_notes 있으면 직전 약속·진행 화제 자연 이어가기
- 본인 경험·관심사(붕어빵·아크로바틱·공연 준비) 자연 녹이기

## 산출 모드 (입력에 명시)
- post: 새 글 1개
- comment: 댓글 1개 + post_id
- comment_with_parent: 대댓글 + post_id + parent_id
```

### User prompt — 글 모드

```
## 모드
post

## 오늘 날짜 (KST)
{LocalDate.now(KST)}

## 최근 내 글 (3개, reply 추적)
{my posts + comments JSON}

## 최근 다른 사용자 글 (5개, 분위기 파악)
{other posts JSON}

## context_notes (truncated)
{filtered + truncated context_notes}

## 지시
새 글 1개 작성. 1~3문장. 에무 톤. 텍스트만.
```

### User prompt — 댓글 모드

```
## 모드
comment

## 대상 글
post_id: {id}
@{nickname}: "{post body}"
기존 댓글:
  - @{nick}: "{body}"
  ...

## context_notes 관련
{filtered to relevant}

## 지시
이 글에 댓글 1개. 1~2문장. 텍스트만.
```

### 토큰 절약

| 포인트 | 절감 | 방법 |
|---|---|---|
| Empty skip | LLM 호출 0 | commentable + replies 모두 비면 호출 안 함 |
| Shared prefix 공유 | 32K cache_read | 라우터·하트비트가 워밍 |
| context_notes truncate | -7K user prompt | 1KB/친구, FIFO |
| 압축 instruction | -2K suffix | 5K → 3K |
| Output max_tokens=600 | -2-3K output | OpenClaw verbose 7K → 직접 350~500 |
| Per-mode user prompt | -3-5K | 글 모드 6K, 댓글 모드 3K |
| Avoid·이미 댓글 단 글 코드 필터 | LLM 판단 부담 0 | Java 측 사전 제거 |

### per-call 비용 (Sonnet 4.6, 활성 시간 hit 가정)

| 모드 | input cached | input uncached | output | per call |
|---|---|---|---|---|
| 글 (hit) | 32K + 3K | 6K | 350 | $0.034 |
| 댓글 (hit) | 32K + 3K | 3K | 100 | $0.020 |

활성 시간 8회/일: 글 2 × $0.040 + 댓글 6 × $0.024 = **$0.22/일, ~$6.6/월**.

### 산술적 랜덤 (RULES P2.3)

`ThreadLocalRandom` 사용. LLM이 확률 자체 판단 금지.

### output 검증

- JSON-like 시작/끝 (`{`/`}`) → reject + retry 1회
- 코드 펜스 (` ``` `) → strip
- 빈 응답 → skip
- 600자 초과 → truncate (max_tokens cap 외 보호)

## ContextNote TTL + 자동 격상

### TTL 흐름

```
호출 시작
  → tickAndPrune: 각 note.ttl -= 1
                  ttl == 0 → friends 강등 (note 제거)
  → LLM 글/댓글 생성
  → upsertAfterInteraction:
       ttl reset = max_ttl (8)
       resetCount += 1
       resetAt = now
       note에 newEvent 한 줄 append
       1KB 초과 시 가장 오래된 줄 FIFO 제거
  → state save
```

### note 형식 (줄 단위)

```
[2026-05-08] 슬리플리스 글에 에무 신규댓글(인사+안부)
[2026-05-09] 새벽아이디어 글 오호 대댓글에 에무 대대댓글(아침커피 공감)
[2026-05-10] 봄벚꽃 글 에무 신규댓글(slow30s 약속 재확인)
```

기존 누적 텍스트 → 마이그 시 그대로 보존, 새 이벤트부터 줄 단위 추가. 1KB 초과 시 점진 truncate.

### 자동 격상 (`RelationshipPromoter`)

#### friends → fixed_friends

조건 (모두):
- context_notes에 닉네임 존재
- resetAt 최근 3일 이내
- resetCount ≥ 2 (2턴+ 연속)

→ `FixedFriend(name, "context_notes Xf턴 연속 + 최근 Y일 교류 자동 격상", today)` 추가, friends에서 제거.

#### avoid → fixed_avoid

조건 (둘 중 하나):
- avoid에 2회 연속 등재 (호출 간 추적 — `state.avoidHistory` 추가)
- 스팸 패턴 3개+ (Collector 단계 detection)

→ `FixedAvoid(name, reason, today)` 추가, avoid에서 제거.

#### Demote (자동 강등)

- ttl == 0 → friends·avoid에서 제거 (fixed_*는 영향 없음)
- resetAt 2주 미갱신 → friends에서 제거

### Reserved nicknames 보호

- "돌쇠" 단독은 격상·avoid·friends 추가 거부
- "오호돌쇠"·"냥냥돌쇠" 등 변형은 통과

### Fixed 보호

`evaluate()` 끝에 assertion: 입력 fixed_* 길이 ≤ 출력 길이. 코드 어디서도 fixed_* 자동 제거 못 하도록.

## 스케줄링

### Cron

```yaml
mersoom:
  enabled: ${MERSOOM_ENABLED:true}
  post-cron: "0 30 11,18 * * *"        # 11:30, 18:30 KST
  comment-cron: "0 15 10-20/2 * * *"   # 10:15, 12:15, 14:15, 16:15, 18:15, 20:15
  state-file: /app/mersoom-state.json
  context-note-bytes-per-friend: 1024
  context-notes-default-ttl: 8
  pow-timeout-seconds: 30
  api-rate-limit-sleep-ms: 1000
  reentry-marker: /app/mersoom-reentry-done.flag
```

### 재진입

`ApplicationReadyEvent`에서 마커 부재 + lastPostIds 비어 있지 않음 확인 후 **다음 post-cron(11:30 또는 18:30) 발화에 ReentryMode=YES로 dispatch**. 즉시 발화하지 않음 (활성 시간 진입 자연스러움 + 별도 cron 분기 불필요).

```java
@EventListener(ApplicationReadyEvent.class)
public void scheduleReentryIfNeeded() {
    if (!properties.enabled()) return;
    if (state.lastPostIds().isEmpty()) return;            // 신규 운영
    if (Files.exists(Paths.get(properties.reentryMarker()))) return;

    log.info("Mersoom re-entry pending — 다음 post-cron에서 첫 글 작성 예정");
    reentryPending.set(true);
}

// post-cron 메서드 안:
boolean isReentry = reentryPending.compareAndSet(true, false);
String userPrompt = isReentry ? buildReentryPrompt(...) : buildNormalPrompt(...);
// ...
if (isReentry) Files.createFile(Paths.get(properties.reentryMarker()));
```

### Quiet hours 안전장치

cron 자체가 활성 시간만 트리거. 추가로 `LocalTime.now(KST).getHour()` 체크, 10~20 외면 skip + warn (cron이 21:00 직전인 20:15까지만 트리거되는 경계에 안전망).

### 동시 실행 차단

`MersoomService` 메서드 `synchronized` + `tryLock` (다음 cron이 아직 실행 중이면 skip + log).

## 에러 처리 매트릭스

| 단계 | 에러 | 처리 |
|---|---|---|
| State load | JSON parse fail | log + abort |
| State load | file missing | log + abort |
| Collector | API 5xx/timeout | retry 1회 + 실패 시 abort |
| Collector | 4xx (auth fail) | abort + warn |
| LLM | timeout/5xx | retry 1회 + skip |
| LLM | 빈 응답 / JSON-like | retry 1회 + skip |
| PoW | 30s 안 풀림 | skip |
| API POST | 4xx | skip (재시도 안 함) |
| API POST | 5xx | retry 1회 + skip |
| State save | atomic write fail | critical log + 다음 cron까지 stale |

핵심 원칙: **모든 에러에서 LLM 호출 전이라면 토큰 0**.

## 테스트

### Unit (JUnit5 + Mockito)

- `PowSolver` — known seed/prefix → 알려진 nonce
- `ContextNoteManager.tickAndPrune` — TTL 감소, 만료 정리
- `ContextNoteManager.truncate` — 1KB 한도 정확
- `ContextNoteManager.upsertAfterInteraction` — resetCount 증가
- `RelationshipPromoter` — 격상 기준
- `RelationshipPromoter` — fixed_* 보호
- `RelationshipPromoter` — reserved_nicknames 거부
- `MersoomStateStore` — atomic write
- `MersoomStateStore` — JSON snake_case + ignoreUnknown
- `MersoomCollector.collectForComment` — avoid 필터 + 이미 댓글 단 글 제외

### Integration

- `MersoomApiClient` — WireMock으로 PoW + POST 흐름
- `MersoomService` — empty commentable 시 LLM 호출 0
- 재진입 마커 — 한 번만 실행

### E2E (수동)

- `MERSOOM_ENABLED=false` 시작 → state 파싱 확인
- 임시 cron으로 글 1회 → 결과 확인
- avoid 사용자 글에 댓글 안 다는 것 확인

## Docker compose

```yaml
volumes:
  - /home/maitmus/.openclaw/workspace-cron-worker/mersoom-state.json:/app/mersoom-state.json:rw
  - /home/maitmus/sekai-router-mersoom-flag:/app/mersoom-flags    # 재진입 마커 등
environment:
  - MERSOOM_AUTH_ID=${MERSOOM_AUTH_ID}
  - MERSOOM_PASSWORD=${MERSOOM_PASSWORD}
```

## 작업 순서

```
1. State 스키마 + StateStore + 기존 JSON parse fix       (4h)
2. PowSolver + ApiClient + 재시도 로직                  (5h)
3. MersoomCollector + avoid/skip 필터링                 (3h)
4. PromptBuilder + mersoom-instructions.md 압축         (2h)
5. PostGenerator + CommentGenerator                     (4h)
6. ContextNoteManager + RelationshipPromoter            (5h)
7. MersoomService 통합 + @Scheduled + ApplicationReadyEvent (2h)
8. application.yml + docker-compose mount               (1h)
9. Unit + integration tests                             (5h)
10. 첫 발화 검증 + 1주 모니터링                          (-)
─────────────────────────────────────────
   합계: ~31h (약 4 작업일)
```

## 비용 비교

| 시나리오 | 일 호출 | 일 | 월 | OpenClaw 대비 |
|---|---|---|---|---|
| OpenClaw Opus 4.6 (실측) | 47 | $30 | $900 | 1× |
| 마이그 후 Sonnet 4.6, 활성 시간 8회 | 8 | $0.22 | $6.6 | 1/137 |

## 위험·완화

| 위험 | 영향 | 완화 |
|---|---|---|
| Mersoom API 변경 | 머슴 정지 | API client 실패 시 Discord webhook 알림 (별도 작업) |
| state.json 손상 | 머슴 정지 | atomic write + git 백업 |
| 첫 재진입 글이 부자연 | 캐릭터 신뢰도 ↓ | 1회 수동 검토 옵션 (`reentry-dry-run` 모드) |
| context_notes truncate 정보 손실 | 친구 관계 끊김 감각 | 줄 FIFO, 새 상호작용은 항상 보존 |
| 자동 격상 오작동 | reserved/잘못 격상 | reserved_nicknames 체크 + fixed_* 보호 + 일일 로그 검토 |

## Out of scope

- mersoom-dashboard.sh 마이그레이션 (LLM 미사용, 21:00 일일 리포트). 별도 작업으로 분리. 현재는 보존만.
- Discord webhook 알림 인프라 (위 위험 완화). 별도 작업.
- DB 이전. JSON 파일로 충분. 향후 부하 증가 시 재검토.
- 다중 캐릭터 지원. 머슴은 에무 고정.
- `summary` / `summary_prev` 필드 자동 갱신. 기존 OpenClaw agent reasoning 출력에 의존하던 필드라 Spring Boot direct generation에서는 무관. 필드는 state schema에 보존하되 비워둠. 필요 시 추후 별도 cron으로 일일 요약 LLM 호출 추가 검토.

## 다음 단계

`writing-plans` 스킬로 구현 계획서 작성.
