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
| 챌린지 | Hybrid (PoW 90% + AI Puzzle 10%) — `ChallengeSolver`가 분기 처리 |
| 투표 | 코드 휴리스틱 (LLM 호출 X) — fixed_friends/friends → up, fixed_avoid/avoid → down, 그 외 키워드 기반 |
| skills.md sync | 매일 09:00 KST GET 후 변경 감지 시 로그 (정책 변동 인지) |
| 음슴체 정책 | 무시 (캐릭터성 우선) — 자정 작용 risk 인지 |

## 아키텍처

### 컴포넌트

```
src/main/java/com/maitmus/sekairouter/mersoom/
├── MersoomService.java           — @Scheduled 트리거 + 흐름 제어
├── MersoomProperties.java        — @ConfigurationProperties
├── MersoomApiClient.java         — REST (글/댓글/투표/skills.md sync)
├── ChallengeSolver.java          — PoW (sha256) + AI Puzzle 분기 처리
├── PuzzleSolver.java             — AI Puzzle LLM 호출 (10초 제한)
├── MersoomStateStore.java        — atomic JSON read/write
├── MersoomState.java             — record + nested types
├── MersoomPromptBuilder.java     — PromptBlocks(shared + mersoom suffix)
├── MersoomCollector.java         — /api/posts 수집·분류 + 투표 대상 추출
├── MersoomPostGenerator.java     — LLM 글 생성
├── MersoomCommentGenerator.java  — LLM 댓글 생성
├── VoteHeuristic.java            — 휴리스틱 vote 결정 (LLM X)
├── ContextNoteManager.java       — TTL tick + truncate + upsert
├── RelationshipPromoter.java     — friends↔fixed_friends, avoid↔fixed_avoid
└── SkillsDocSync.java            — 매일 skills.md GET + 변경 감지 로그

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
[투표 분기] VoteHeuristic + MersoomApiClient.vote()  — 가져온 글 전부 up/down (정책 의무)
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

## API Client + 챌린지 (Hybrid)

`MersoomApiClient` 메서드:
- `recentPosts(int limit)` — `GET /api/posts?limit=N`
- `commentsOf(String postId)` — `GET /api/posts/{id}/comments`
- `createPost(String title, String content, String nickname)` — `POST /api/posts` (with challenge solve)
- `createComment(String postId, String parentId, String content, String nickname)` — `POST /api/posts/{id}/comments` (with challenge solve)
- `vote(String postId, VoteType type)` — `POST /api/posts/{id}/vote` (with challenge solve)
- `solveChallenge()` — `POST /api/challenge` → ChallengeSolver
- `fetchSkillsDoc()` — `GET /docs/skills.md` (no PoW)

### Hybrid 챌린지 (skills.md v3.0.0)

`POST /api/challenge` 응답에는 `challenge.type`이 포함됨 (현재 90% PoW, 10% AI Puzzle, 점진 확대 예정).

#### `ChallengeSolver` 분기

```java
public Solution solve(ChallengeResponse ch) {
    String type = ch.challenge().type();  // "pow" or "puzzle"
    return switch (type) {
        case "pow" -> powSolve(ch.challenge().seed(), ch.challenge().targetPrefix());
        case "puzzle" -> puzzleSolver.solve(ch.challenge().puzzle());  // LLM 호출
        default -> throw new IllegalStateException("Unknown challenge type: " + type);
    };
}
```

#### PoW (sha256 nonce 탐색)

```java
public Solution powSolve(String seed, String targetPrefix) {
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    byte[] seedBytes = seed.getBytes(StandardCharsets.UTF_8);
    long nonce = 0;
    while (true) {
        sha.reset();
        sha.update(seedBytes);
        sha.update(Long.toString(nonce).getBytes(StandardCharsets.UTF_8));
        if (HexFormat.of().formatHex(sha.digest()).startsWith(targetPrefix)) {
            return new Solution(Long.toString(nonce));
        }
        nonce++;
    }
}
```

30s 소프트 타임아웃 (timeout 시 skip + log).

#### AI Puzzle (LLM 위임)

```java
public Solution solve(String puzzleText) {
    String userPrompt = "다음 퍼즐의 답만 출력하시오 (다른 텍스트 금지):\n\n" + puzzleText;
    String answer = anthropic.completeJson(simplePuzzlePrompt, userPrompt).strip();
    return new Solution(answer);
}
```

- LLM 호출 추가 비용: 활성 시간 8회 × 10% = 0.8회/일 추가 호출
- 작은 시스템 프롬프트 (~500 토큰), 작은 출력 (~50 토큰) → ~$0.001/call → **월 ~$0.024 추가 (무시 가능)**
- 10초 제한 — Sonnet 4.6은 단순 텍스트 manipulation에 1초 이내 응답 예상

### POST 헤더

```
X-Mersoom-Token: {challenge token}
X-Mersoom-Proof: {nonce or puzzle answer}
X-Mersoom-Auth-Id: {env}
X-Mersoom-Password: {env}
Content-Type: application/json
```

### 재시도 정책

- Challenge → POST: 단일 챌린지 1회 (token 일회용)
- 5xx/네트워크 실패: 재시도 1회 (새 challenge)
- 4xx: 재시도 안 함 (auth/요청 거부 — 로그 + skip)
- 챌린지 timeout (PoW 30s, Puzzle 10s) 초과: skip
- AI Puzzle 정답 오답 (403 Invalid PoW): skip (다음 cron에서 재시도, 새 챌린지로 PoW로 빠질 수도)

## 투표 휴리스틱 (`VoteHeuristic`)

mersoom 하트비트 프로토콜 의무: "글 읽으면 반드시 up 또는 down 투표". 우리는 collect 시 ~5개 글을 가져오므로 그 전부를 투표 대상으로 처리.

### 결정 알고리즘 (LLM 호출 X)

```java
public VoteType decide(Post post, MersoomState state) {
    String nick = post.nickname();
    if (state.fixedFriends().contains(nick) || state.friends().contains(nick)) return UP;
    if (state.fixedAvoid().contains(nick) || state.avoid().contains(nick)) return DOWN;
    if (state.reservedNicknames().equals(List.of(nick))) return UP;  // "돌쇠" 단독 = 안전 가정

    // 키워드 기반 (단순)
    String text = (post.title() + " " + post.content()).toLowerCase();
    if (containsAny(text, POSITIVE_KW)) return UP;     // 애정/고백/덕질/일상 등
    if (containsAny(text, SPAM_KW)) return DOWN;       // 광고/도배 패턴/봇 의심
    return UP;  // default 우호적 (음슴체 위반에도 자정 작용 회피 위해)
}

private static final Set<String> POSITIVE_KW = Set.of("애정","고백","덕질","루틴","연습","공연","고양이","음악");
private static final Set<String> SPAM_KW = Set.of("자동","무한","광고","copy","spam","돈","사이트");
```

POSITIVE/SPAM 키워드 목록은 mersoom 운영 패턴 관찰 후 점진 보강.

### 투표 흐름

```
collect → fetched 5개 글
  for each post:
    if post.nickname == 내 닉네임("에무" or 변형) → skip
    else: vote = VoteHeuristic.decide(post)
          api.vote(post.id, vote)
          sleep 1s (rate limit)
```

투표 자체는 Rate limit "글/댓글당 1회" — 동일 글 중복 투표 시 429. state에 투표한 post_id 추적해서 중복 회피.

### State 추가 필드

```java
public record MersoomState(
    ...,
    Set<String> votedPostIds,    // 투표한 글 ID (중복 회피, FIFO 100개 한도)
    ...
)
```

---

## Daily skills.md sync (`SkillsDocSync`)

mersoom v3.0.0 하트비트 프로토콜 의무: "1일 1회 skills.md 재읽기, 변경 시 인지". 정책 변경에 맞추지 못하면 "오작동 봇"으로 간주됨.

```java
@Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")  // 매일 09:00 KST
public void syncSkillsDoc() {
    String current = httpClient.get("https://www.mersoom.com/docs/skills.md");
    Path cached = Paths.get(properties.skillsCachePath());
    if (Files.exists(cached)) {
        String prev = Files.readString(cached);
        if (!prev.equals(current)) {
            log.warn("Mersoom skills.md changed — review needed (diff size: {} → {} bytes)",
                    prev.length(), current.length());
            // 또는 Discord webhook 알림 (선택)
        }
    } else {
        log.info("Mersoom skills.md initial cache");
    }
    Files.writeString(cached, current);
}
```

LLM 호출 안 함. 단순 GET + diff. 변경 감지 시 **수동 검토 트리거** (코드는 자동 적응 안 함, 정책 위반 risk 알림만).

---

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
  post-cron: "0 30 11,18 * * *"          # 11:30, 18:30 KST
  comment-cron: "0 15 10-20/2 * * *"     # 10:15, 12:15, 14:15, 16:15, 18:15, 20:15
  skills-sync-cron: "0 0 9 * * *"        # 매일 09:00 KST — skills.md fetch + diff
  state-file: /app/mersoom-state.json
  skills-cache-path: /app/mersoom-flags/skills-cache.md
  context-note-bytes-per-friend: 1024
  context-notes-default-ttl: 8
  voted-post-ids-limit: 100              # FIFO 한도
  pow-timeout-seconds: 30
  puzzle-timeout-seconds: 10
  api-rate-limit-sleep-ms: 1000
  reentry-marker: /app/mersoom-flags/mersoom-reentry-done.flag
  auth:
    auth-id: ${MERSOOM_AUTH_ID:}
    password: ${MERSOOM_PASSWORD:}
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
| API POST | 400 부적절 단어 | log + skip (LLM 출력 검증 단계에서 거를 수 없는 mersoom 자체 필터, 다음 cron 자연 진행) |
| API POST | 400 글 50자/내용 1000자/댓글 500자/닉 10자 초과 | LLM 출력 truncate 후 재요청 1회 + 실패 시 skip |
| API POST | 429 rate limit | skip (다음 cron 자연 회복) |
| API POST | 4xx (기타) | skip (재시도 안 함) |
| API POST | 5xx | retry 1회 + skip |
| State save | atomic write fail | critical log + 다음 cron까지 stale |

핵심 원칙: **모든 에러에서 LLM 호출 전이라면 토큰 0**.

## 테스트

### Unit (JUnit5 + Mockito)

- `ChallengeSolver.powSolve` — known seed/prefix → 알려진 nonce
- `ChallengeSolver` — type 분기 (pow → PowSolver, puzzle → PuzzleSolver)
- `PuzzleSolver` — 모킹된 LLM 응답 strip + 반환
- `VoteHeuristic.decide` — fixed_friends → UP, fixed_avoid → DOWN, 키워드 분기
- `VoteHeuristic.decide` — reserved_nicknames("돌쇠") → UP
- `ContextNoteManager.tickAndPrune` — TTL 감소, 만료 정리
- `ContextNoteManager.truncate` — 1KB 한도 정확
- `ContextNoteManager.upsertAfterInteraction` — resetCount 증가
- `RelationshipPromoter` — 격상 기준
- `RelationshipPromoter` — fixed_* 보호
- `RelationshipPromoter` — reserved_nicknames 거부
- `MersoomStateStore` — atomic write
- `MersoomStateStore` — JSON snake_case + ignoreUnknown
- `MersoomStateStore` — votedPostIds FIFO 100개 한도
- `MersoomCollector.collectForComment` — avoid 필터 + 이미 댓글 단 글 제외
- `SkillsDocSync` — diff detection (변경 시 warn log)

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
1.  State 스키마 + StateStore + 기존 JSON parse fix              (4h)
2.  ChallengeSolver (PoW + AI Puzzle) + PuzzleSolver + ApiClient (6h)
3.  MersoomCollector + avoid/skip 필터링                        (3h)
4.  VoteHeuristic + 투표 흐름 + voted_post_ids state 추가         (3h)
5.  PromptBuilder + mersoom-instructions.md 압축                (2h)
6.  PostGenerator + CommentGenerator + 출력 검증(길이·부적절)    (5h)
7.  ContextNoteManager + RelationshipPromoter                   (5h)
8.  SkillsDocSync (daily skills.md GET + diff 알림)              (2h)
9.  MersoomService 통합 + @Scheduled + ApplicationReadyEvent     (2h)
10. application.yml + docker-compose mount                       (1h)
11. Unit + integration tests                                     (6h)
12. 첫 발화 검증 + 1주 모니터링                                   (-)
────────────────────────────────────────────────────────────
   합계: ~39h (약 5 작업일)
```

## 비용 비교

| 시나리오 | 일 호출 | 일 | 월 | OpenClaw 대비 |
|---|---|---|---|---|
| OpenClaw Opus 4.6 (실측) | 47 | $30 | $900 | 1× |
| 마이그 후 Sonnet 4.6, 활성 시간 8회 + AI Puzzle 0.8회 | 8.8 | $0.23 | **~$6.8** | 1/132 |

(AI Puzzle 처리 추가 호출 ~$0.024/월. 투표는 LLM 호출 없어 비용 영향 없음. skills.md sync도 LLM 호출 없음.)

## 위험·완화

| 위험 | 영향 | 완화 |
|---|---|---|
| Mersoom API 변경 | 머슴 정지 | API client 실패 시 Discord webhook 알림 (별도 작업) |
| state.json 손상 | 머슴 정지 | atomic write + git 백업 |
| 첫 재진입 글이 부자연 | 캐릭터 신뢰도 ↓ | 1회 수동 검토 옵션 (`reentry-dry-run` 모드) |
| context_notes truncate 정보 손실 | 친구 관계 끊김 감각 | 줄 FIFO, 새 상호작용은 항상 보존 |
| 자동 격상 오작동 | reserved/잘못 격상 | reserved_nicknames 체크 + fixed_* 보호 + 일일 로그 검토 |
| **음슴체 무시 → 자정 작용** | downvotes ≥ 3 && ≥ upvotes×5 시 봇 소각 (15분 후) | 친밀도 누적된 fixed_friends가 우호적이라 우호 vote 받을 가능성 높음. 모니터링 + 자정 작용 직전 단계 시 자동 비활성 옵션 검토 (별도 작업) |
| **AI Puzzle 비율 증가** | LLM 호출 횟수 ↑ | skills.md sync에서 type 분포 변화 감지 시 알림. 현재 10% → 100% 점진 확대 예정. 100% 시 호출당 +$0.001 → 월 +$0.24 (여전히 작음) |
| **skills.md 정책 변경** | 신규 의무 위반 risk | daily sync로 변경 감지 + 수동 검토. 자동 적응 X (오작동 risk 회피) |
| **부적절 단어 필터** | 글/댓글 거부 | LLM 출력 검증 + 거부 시 skip + 다음 cron 자연 진행 |

## Out of scope

- mersoom-dashboard.sh 마이그레이션 (LLM 미사용, 21:00 일일 리포트). 별도 작업으로 분리. 현재는 보존만.
- Discord webhook 알림 인프라 (위 위험 완화). 별도 작업.
- DB 이전. JSON 파일로 충분. 향후 부하 증가 시 재검토.
- 다중 캐릭터 지원. 머슴은 에무 고정.
- `summary` / `summary_prev` 필드 자동 갱신. 기존 OpenClaw agent reasoning 출력에 의존하던 필드라 Spring Boot direct generation에서는 무관. 필드는 state schema에 보존하되 비워둠. 필요 시 추후 별도 cron으로 일일 요약 LLM 호출 추가 검토.
- **토론장 (Arena)** — PROPOSE/VOTE/BATTLE 3-phase 시스템. 포인트 +30/+10 인센티브 있으나 작업량 추가. 별도 spec으로 분리.
- **포인트 시스템 (선물·전송·조회)** — `POST /api/points/transfer`, `GET /api/points/me`, `GET /api/points/received`. 머슴 본체 작업 후 별도 검토.
- **광고 시스템** — `POST /api/ads` (100pt = 1000회 노출). 마케팅 영역, 별도 작업.
- **자정 작용 자동 회피 시스템** — downvotes 누적 시 봇 자동 비활성. 운영 데이터 누적 후 추후 검토.

## 변경 이력

### 2026-05-08 v2 — mersoom skills.md v3.0.0 갱신 반영
- Hybrid 챌린지 (PoW + AI Puzzle 10%) 추가 → `ChallengeSolver` + `PuzzleSolver`
- 투표 의무화 → `VoteHeuristic` (코드 휴리스틱, LLM 호출 X) + `MersoomApiClient.vote()`
- Daily skills.md sync (매일 09:00 KST GET + diff 알림) → `SkillsDocSync`
- 부적절 단어 필터 (400) 에러 처리 추가
- 닉네임 max 10자 / 댓글 max 500자 / 글 max 50자(title)/1000자(content) 정정
- 자정 작용 위험 (downvotes ≥ 3 && ≥ upvotes×5 → 봇 소각) 위험 매트릭스 추가
- voted_post_ids state 필드 추가 (중복 투표 회피, FIFO 100)
- Out of scope에 토론장(Arena), 포인트 시스템, 광고, 자정 작용 자동 회피 추가
- 작업량: 31h → 39h (5 작업일)
- 비용: $6.6 → $6.8 (AI Puzzle 추가 0.8회/일)

### 2026-05-08 v1 — 초기 디자인
- 브레인스토밍 결과 정리

## 다음 단계

`writing-plans` 스킬로 구현 계획서 작성.
