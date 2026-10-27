# 머슴 형제봇 공유 이벤트 기억 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 에무·네네가 지어낸 '둘이 함께 얽힌 일'을 공유 파일에 기억하고, 글/댓글 생성 시 날짜·사실을 맞춰 이어 쓰게 한다.

**Architecture:** 순수 로직(`SharedEventLog`: prune·apply 검증)과 파일 저장소(`SharedEventStore`), LLM 추출기(`SharedEventExtractor`+파서),
프롬프트 렌더(`SharedEventPrompt`), 엔진용 파사드(`SharedEventMemory`)로 나눈다. 엔진은 생성 전에 섹션 문자열을 받아
생성기에 넘기고, 게시 성공 직후 파사드의 `observe`를 호출한다(실패 무해).

**Tech Stack:** Java 21, Spring Boot, Jackson, JUnit5/AssertJ/Mockito, Gradle.

**Spec:** docs/superpowers/specs/2026-10-27-mersoom-shared-events-design.md

## Global Constraints

- 저장 파일: 컨테이너 `/app/shared-events.json`, 설정 키 `mersoom.shared-events-file`(env `MERSOOM_SHARED_EVENTS_FILE`).
- 수명: date 있음 → `today > date+3`; done/cancelled → `today > updatedAt+3`; date 없음 upcoming → `today > updatedAt+7`; 활성 상한 8.
- facts 최대 5줄(FIFO)·각 ≤80자, title ≤30자, sources 최대 5, id `[a-z0-9-]{2,40}`, date `today-7 ≤ d ≤ today+60`.
- 추출 트리거: 모든 글 / 형제 글에 단 최상위 댓글 / 답글 작성자가 형제인 대댓글. 일반 유저 대상은 추출 X.
- 추출 실패·예외는 WARN만, 게시 흐름 무영향.
- 댓글 주입은 피드에 형제 글이 있거나 형제 답글 후보가 있을 때만.
- 프롬프트 변경은 docs/prompts/mersoom-post-ad.md·mersoom-comment.md 미러 필수. 배포 전 sim-refine 게이트, 배포는 sekai-deploy.
- `.env` 비밀 출력·커밋 금지.

## Review Focus

- 손상/빈 shared-events.json(0바이트·잘못된 JSON) → 빈 목록으로 동작하고 다음 저장에서 정상화돼야 함 (Task 2 테스트).
- 모델이 같은 일을 다른 id로 add → 중복 등록 위험. 추출 프롬프트에 기존 목록을 넘기고 add의 id 충돌은 update 승격 (Task 1 테스트). title 동일·id 상이 add는 update로 합침 (Task 1 테스트).
- 날짜 경계(오늘·date+3일 당일) → 당일엔 유지, 다음 날 삭제 (Task 1 테스트).
- 형제 아닌 유저 대상 댓글 게시 후 추출 호출 없음 (Task 5 테스트).
- 댓글 프롬프트에서 형제 맥락 없을 때 섹션 미노출 (Task 4 테스트).

---

### Task 1: SharedEvent 모델 + SharedEventLog(prune·apply)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/SharedEvent.java`
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/SharedEventLog.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/SharedEventLogTest.java`

**Interfaces:**
- Produces:
  - `record SharedEvent(String id, String title, String date, String status, List<String> facts, String createdBy, String updatedAt, List<String> sources)`
  - `record SharedEvents(List<SharedEvent> events)` (SharedEvent.java 안 nested 아님 — 같은 파일 top-level 불가하므로 `SharedEvent.File`로 둔다: `record File(List<SharedEvent> events)`)
  - `record SharedEventLog.Op(String op, String id, String title, String date, List<String> facts, List<String> addFacts, String status)`
  - `record SharedEventLog.ApplyResult(List<SharedEvent> events, List<SharedEvent> added, List<SharedEvent> updated)`
  - `static List<SharedEvent> SharedEventLog.prune(List<SharedEvent>, LocalDate today)`
  - `static ApplyResult SharedEventLog.apply(List<SharedEvent> current, List<Op> ops, LocalDate today, String actor, String sourceId, String nowTs)`

- [ ] **Step 1: 실패 테스트** — prune 경계(date+3 당일 유지/다음날 삭제, done updatedAt+3, 미정 +7, 상한 8), apply(add·update·add id충돌→update·title 동일→update·date 범위 밖 null/무시·잘못된 id 무시·facts FIFO 5·80자 컷·sources 5).
- [ ] **Step 2: 실행해 FAIL 확인** — `./gradlew test --tests '*SharedEventLogTest'` → 컴파일 실패.
- [ ] **Step 3: 구현** (아래 코드 그대로).
- [ ] **Step 4: PASS 확인.**
- [ ] **Step 5: 커밋** `feat(mersoom): 공유 이벤트 모델·수명/적용 로직`.

구현 핵심(SharedEventLog):
```java
static final int MAX_EVENTS = 8, MAX_FACTS = 5, MAX_FACT_LEN = 80, MAX_TITLE = 30, MAX_SOURCES = 5;
static final Pattern ID = Pattern.compile("[a-z0-9-]{2,40}");
prune: keep e if !(dateExpired || closedExpired || undatedExpired); then sort by updatedAt desc, keep MAX_EVENTS, restore original order.
apply: for op in ops:
  "add": id valid? title nonblank? ; existing by id or by title(equalsIgnoreCase after strip) → treat as update with addFacts=facts, date=op.date;
         else new SharedEvent(id, cut(title,30), validDate(op.date)/null, "upcoming", capFacts(facts), actor, nowTs, [sourceId])
  "update": existing by id required; merge addFacts (skip duplicates), status in {done,cancelled,upcoming} else keep, date valid → replace, sources add (dedupe, cap 5), updatedAt=nowTs
validDate: parse yyyy-MM-dd, within [today-7, today+60] else null.
```

### Task 2: SharedEventStore (파일 load/save, synchronized)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/SharedEventStore.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/SharedEventStoreTest.java`

**Interfaces:**
- Consumes: Task 1 `SharedEventLog`, `SharedEvent.File`.
- Produces: `SharedEventStore(ObjectMapper, @Value("${mersoom.shared-events-file:/app/shared-events.json}") String path)`;
  `synchronized List<SharedEvent> active(LocalDate today)`;
  `synchronized SharedEventLog.ApplyResult apply(List<Op> ops, LocalDate today, String actor, String sourceId, String nowTs)` — ops 비면 저장 안 함.

- [ ] Step 1: 테스트 — 파일 없음→빈 목록, 빈/깨진 파일→빈 목록, apply 후 재로드 일치, prune 반영 저장.
- [ ] Step 2: FAIL 확인. - [ ] Step 3: 구현(MersoomStateStore와 같은 atomic→direct write). - [ ] Step 4: PASS. - [ ] Step 5: 커밋.

### Task 3: SharedEventExtractor + 파서 + SharedEventPrompt 렌더

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/SharedEventExtractor.java`
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/SharedEventPrompt.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/SharedEventExtractorTest.java`, `SharedEventPromptTest.java`

**Interfaces:**
- Produces:
  - `record SharedEventExtractor.Input(LocalDate today, String actorName, String siblingName, String kind, String context, String text)`
  - `List<SharedEventLog.Op> SharedEventExtractor.extract(Input in, List<SharedEvent> current)` — 실패 시 빈 리스트.
  - `static List<SharedEventLog.Op> SharedEventExtractor.parse(String raw)` (package-private, 실패 시 빈 리스트)
  - `static String SharedEventPrompt.render(List<SharedEvent>, LocalDate today, String siblingName)` — 비면 "".
  - `static String SharedEventPrompt.postSection(...)`, `commentSection(...)` — render + 가드 줄.
- 추출 시스템 프롬프트: 스펙 §3 기준 그대로(둘 다 관련만, 기존과 같으면 update, 상대 날짜 절대화, 대부분 none, JSON 형식).
- 렌더 형식: `- 원더쇼 할로윈 쇼 — 10-31(토), 4일 뒤` / 오늘·내일·모레·어제(지난 일)·N일 전(지난 일) / `날짜 미정` / 끝남·취소됨 표시, facts는 `  · ` 들여쓰기.

- [ ] Step 1: 테스트 — parse(펜스 감싼 JSON, ops 빈 배열, 깨진 JSON→빈), extract가 USER에 오늘·목록·텍스트 포함(ArgumentCaptor), render 상대 날짜·요일·빈 목록·상태 표기.
- [ ] Step 2: FAIL. - [ ] Step 3: 구현. - [ ] Step 4: PASS. - [ ] Step 5: 커밋.

### Task 4: 생성기 주입 (글·댓글) + 손문서

**Files:**
- Modify: `MersoomPostGenerator.java` — `generate(profile,state,feed,today,eventSlot,String sharedSection)` 추가(기존 5-arg는 `""` 위임), buildUserPrompt 시드 섹션 뒤에 `sharedSection`(null/blank면 생략).
- Modify: `MersoomCommentGenerator.java` — `generate(profile,state,commentable,now,replies,String sharedSection)` 추가(기존 5-arg는 `""` 위임), buildUserPrompt 답글 섹션 뒤에 조건부(피드에 형제 글 or 형제 답글 후보) 삽입.
- Modify: `docs/prompts/mersoom-post-ad.md`, `docs/prompts/mersoom-comment.md` — 섹션·가드 미러.
- Test: `MersoomPostGeneratorTest`, `MersoomCommentGeneratorTest` 케이스 추가.

- [ ] Step 1: 테스트 — 글: 섹션 문자열이 USER에 포함 / blank면 헤더 없음. 댓글: 형제 글 있으면 포함, 형제 없으면 미포함, 형제 답글 후보만 있어도 포함.
- [ ] Step 2: FAIL. - [ ] Step 3: 구현. - [ ] Step 4: PASS(전체 mersoom 테스트). - [ ] Step 5: 커밋.

### Task 5: SharedEventMemory 파사드 + 엔진 연결 + 배선

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/SharedEventMemory.java`
- Modify: `MersoomCitizenEngine.java` — 의존성 `SharedEventMemory sharedEvents` 추가; runPost: `postSection` 전달 + 게시 후 `observe(profile, id, "글", "", "제목: …\n본문: …")`; runComment: `commentSection` 전달 + 형제 글 댓글 후 `observe(…,"댓글", "(원글) @닉 \"제목\": 본문", content)` + 형제 답글 대댓글 후 `observe(…,"대댓글", 스레드, content)`.
- Modify: `MersoomCitizenEngineTest.java` — 생성자에 `mock(SharedEventMemory.class)`, generate 스텁 인자 수 갱신, 트리거 테스트 3개(글 observe, 형제 댓글 observe, 일반 유저 댓글 observe 안 함).
- Modify: `src/main/resources/application.yml` — `mersoom.shared-events-file: ${MERSOOM_SHARED_EVENTS_FILE:/app/shared-events.json}`.
- Modify: `docker-compose.yml` — sekai-router rw 마운트 + dashboard ro 마운트.

**Interfaces:**
- `SharedEventMemory(SharedEventStore, SharedEventExtractor, Clock)`:
  `String postSection(CitizenProfile)`, `String commentSection(CitizenProfile)`,
  `void observe(CitizenProfile, String sourceId, String kind, String context, String text)` — 예외 삼킴, 로그 `[key] Shared event added: id=… title=… date=…` / `updated: id=… status=… facts=N`.
  `static String siblingName(CitizenProfile)` — NENE→"에무", 그 외→"네네".

- [ ] Step 1: 엔진 테스트 실패 작성. - [ ] Step 2: FAIL. - [ ] Step 3: 구현·배선. - [ ] Step 4: 전체 `./gradlew test` PASS. - [ ] Step 5: 커밋·푸시.

### Task 6: 소급(일회성) + 검토

**Files (임시, 커밋 안 함):**
- Create: 스크래치 `backfill_items.py` — 최근 7일 로그 → JSONL `{ts, actor, kind, sourceId, context, text}` (글/형제 댓글/형제 대댓글).
- Create: `src/test/java/com/maitmus/sekairouter/mersoom/SharedEventBackfillRunner.java` — env `SIM_BACKFILL_IN`/`SIM_BACKFILL_OUT` 있을 때만 실행; 항목마다 고정 Clock(ts)으로 실제 `SharedEventExtractor`(실 AnthropicClientWrapper, opus-5-5 low)+`SharedEventStore` 적용, 결과 파일 출력.

- [ ] 실행 → 결과 이벤트 목록을 사용자에게 보고 → 확정본을 호스트 `/home/maitmus/.openclaw/workspace-cron-worker/shared-events.json`에 기록 → 러너 삭제.

### Task 7: sim-refine 게이트 → 배포 확인

- [ ] sim-refine 스킬: 실제 코드 덤프(주입 vs 미주입)로 에무·네네 글/댓글 각 2런+, 스펙 §6 판정 ①②③. GREEN 2회.
- [ ] 사용자에게 급함/배치 확인 후 sekai-deploy (inode 목록에 shared-events.json 추가).
