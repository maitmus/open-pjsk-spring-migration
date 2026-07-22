# 아레나 반박노트 영속·갱신·일말 초기화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 반박노트를 `arena-state`에 토픽별 저장해 다음 fight 턴까지 유지하고, 상대가 새 글을 올릴 때만 prep을 재생성(아니면 저장본 재사용)해 정체 토픽의 매-틱 헛수고를 없앤다. 하루 마지막 fight 시각(19시)에 노트를 명시적으로 비운다.

**Architecture:** `ArenaState` 레코드에 `rebuttalNotes`·`notesOppCount` 필드 추가. `ArenaStateStore`가 side/notes를 서로 보존하며 저장(load-modify-save). `ArenaService.runFightOnce`가 현재 상대 글 수와 저장된 `notesOppCount`를 비교해 재생성 여부를 결정하고, 마지막 시각엔 clearNotes.

**Tech Stack:** Java 21, Spring Boot, Jackson, Lombok, JUnit 5 + Mockito + AssertJ, Gradle. 상태파일: `arena-state.json`(단일 엔트리, date-scoped).

## Global Constraints

- 반박노트는 `arena-state`에 **토픽별**(date+topicId) 저장. 갱신 트리거 = **비블라인드·비자기 상대 글 수가 저장 시점보다 증가**(또는 저장 노트 없음). 증가 없으면 **저장본 재사용**(prep LLM 콜 스킵).
- `recordSide`와 `saveNotes`는 **서로의 필드(side↔notes/oppCount)를 보존**(load-modify-save). 다른 date/topic이면 상대 필드 null.
- date/topic 불일치 저장 노트는 **무효**(다음날 새 토픽 → 자동 프레시). 추가로 **마지막 fight 시각(hour==19)** 에 명시 `clearNotes`.
- 결정론 게이트 `noOpposingSinceMyLastPost`·`ArenaFightGenerator.generate` 시그니처·**prep 프롬프트 불변**(프롬프트 변경 아님 → sim-refine 불요).
- 기존 `arena-state.json`(노트 필드 없음) **하위호환 로드**(누락 필드 → null).
- `LAST_FIGHT_HOUR=19`는 fight-cron(`0 5 12-19`)의 마지막 시각과 연동 — 상수에 주석.

---

### Task 1: `ArenaState` 노트 필드 + `ArenaStateStore` 영속 메서드

**Files:**
- Modify: `src/main/java/com/maitmus/sekairouter/arena/ArenaState.java`
- Modify: `src/main/java/com/maitmus/sekairouter/arena/ArenaStateStore.java`
- Test: `src/test/java/com/maitmus/sekairouter/arena/ArenaStateStoreTest.java` (기존 — 확장)

**Interfaces:**
- Produces:
  - `ArenaState(String date, String topicId, String side, String rebuttalNotes, Integer notesOppCount)` + `empty()`.
  - `ArenaStateStore.StoredNotes(String notes, int oppCount)` (record).
  - `Optional<StoredNotes> notes(LocalDate date, String topicId)` — date+topic 일치 & 노트 존재 시만.
  - `void saveNotes(LocalDate date, String topicId, String notes, int oppCount)` — 같은 date+topic이면 기존 side 보존.
  - `void clearNotes(LocalDate date, String topicId)` — 같은 date+topic이면 notes/oppCount만 null(side 보존).
  - `recordSide(...)` 시그니처 동일하되 **기존 notes 보존**.

- [ ] **Step 1: 실패 테스트 작성**

기존 `ArenaStateStoreTest.java`에 아래 테스트를 추가. **기존 헬퍼 `private ArenaStateStore store(Path dir)` + `@TempDir Path dir` 패턴을 그대로 재사용**한다(기존 테스트와 동일). `DAY` 상수(2026-06-12)를 그대로 써도 무방:
```java
    @Test
    void notes_round_trip_and_side_preserved(@TempDir Path dir) {
        var s = store(dir);
        s.recordSide(DAY, "t1", "CON");
        s.saveNotes(DAY, "t1", "- 반박1\n- 반박2", 2);
        var n = s.notes(DAY, "t1");
        assertThat(n).isPresent();
        assertThat(n.get().notes()).contains("반박1");
        assertThat(n.get().oppCount()).isEqualTo(2);
        assertThat(s.lockedSide(DAY, "t1")).contains("CON");   // saveNotes가 side 보존
        s.recordSide(DAY, "t1", "CON");                        // recordSide 재기록해도
        assertThat(s.notes(DAY, "t1")).isPresent();            // notes 보존
    }

    @Test
    void notes_absent_for_other_date_or_topic(@TempDir Path dir) {
        var s = store(dir);
        s.saveNotes(DAY, "t1", "- 노트", 1);
        assertThat(s.notes(DAY, "t2")).isEmpty();                          // 다른 토픽
        assertThat(s.notes(DAY.plusDays(1), "t1")).isEmpty();             // 다른 날짜
    }

    @Test
    void clear_notes_keeps_side(@TempDir Path dir) {
        var s = store(dir);
        s.recordSide(DAY, "t1", "PRO");
        s.saveNotes(DAY, "t1", "- 노트", 1);
        s.clearNotes(DAY, "t1");
        assertThat(s.notes(DAY, "t1")).isEmpty();              // 노트 비움
        assertThat(s.lockedSide(DAY, "t1")).contains("PRO");   // side 유지
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*ArenaStateStoreTest"`
Expected: 컴파일 실패(`saveNotes`/`notes`/`clearNotes`/`StoredNotes` 없음, ArenaState 5-arg 생성자 없음).

- [ ] **Step 3: `ArenaState` 필드 추가**

`ArenaState.java` 교체:
```java
package com.maitmus.sekairouter.arena;

/**
 * 아레나 토론 상태(단일 엔트리, date-scoped). (date, topicId)가 현재 토픽과 일치할 때:
 * side = 그 토픽 고정 입장, rebuttalNotes = 저장된 반박노트, notesOppCount = 노트 생성 시점의 상대 글 수.
 * 날짜/토픽이 바뀌면 다음 첫 fight/노트 저장에서 통째로 덮어쓴다. null = 미설정.
 */
public record ArenaState(String date, String topicId, String side,
                         String rebuttalNotes, Integer notesOppCount) {

    public static ArenaState empty() {
        return new ArenaState(null, null, null, null, null);
    }
}
```
(Jackson은 구 JSON의 누락 필드를 null로 역직렬화 → 하위호환.)

- [ ] **Step 4: `ArenaStateStore` 메서드 추가/수정**

`ArenaStateStore.java`:
1. `StoredNotes` 레코드 추가(클래스 안):
```java
    public record StoredNotes(String notes, int oppCount) {}
```
2. `recordSide` 를 notes 보존형으로:
```java
    public void recordSide(LocalDate date, String topicId, String side) {
        ArenaState cur = load();
        boolean sameTopic = Objects.equals(cur.date(), date.toString())
                && Objects.equals(cur.topicId(), topicId);
        String notes = sameTopic ? cur.rebuttalNotes() : null;
        Integer cnt = sameTopic ? cur.notesOppCount() : null;
        save(new ArenaState(date.toString(), topicId, side, notes, cnt));
    }
```
3. 신규 메서드:
```java
    /** 오늘·해당 토픽의 저장된 반박노트. 날짜/토픽 불일치나 노트 없으면 빈 값. */
    public Optional<StoredNotes> notes(LocalDate date, String topicId) {
        ArenaState s = load();
        if (s.rebuttalNotes() == null || s.rebuttalNotes().isBlank()) return Optional.empty();
        if (!Objects.equals(s.date(), date.toString())) return Optional.empty();
        if (!Objects.equals(s.topicId(), topicId)) return Optional.empty();
        int cnt = s.notesOppCount() == null ? 0 : s.notesOppCount();
        return Optional.of(new StoredNotes(s.rebuttalNotes(), cnt));
    }

    /** 반박노트 저장(같은 date+topic이면 기존 side 보존). */
    public void saveNotes(LocalDate date, String topicId, String notes, int oppCount) {
        ArenaState cur = load();
        boolean sameTopic = Objects.equals(cur.date(), date.toString())
                && Objects.equals(cur.topicId(), topicId);
        String side = sameTopic ? cur.side() : null;
        save(new ArenaState(date.toString(), topicId, side, notes, oppCount));
    }

    /** 반박노트만 비움(같은 date+topic이면 side 보존). */
    public void clearNotes(LocalDate date, String topicId) {
        ArenaState cur = load();
        boolean sameTopic = Objects.equals(cur.date(), date.toString())
                && Objects.equals(cur.topicId(), topicId);
        if (!sameTopic) return;   // 다른 토픽이면 건드릴 노트 없음
        save(new ArenaState(date.toString(), topicId, cur.side(), null, null));
    }
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests "*ArenaStateStoreTest"`
Expected: PASS (기존 + 신규 3).

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/arena/ArenaState.java \
        src/main/java/com/maitmus/sekairouter/arena/ArenaStateStore.java \
        src/test/java/com/maitmus/sekairouter/arena/ArenaStateStoreTest.java
git commit -m "feat(arena): arena-state에 반박노트 영속(side 보존 저장/조회/클리어)"
```

---

### Task 2: `ArenaService.runFightOnce` — 저장·갱신·재사용 + 일말 초기화

**Files:**
- Modify: `src/main/java/com/maitmus/sekairouter/arena/ArenaService.java`
- Test: `src/test/java/com/maitmus/sekairouter/arena/ArenaServiceFightWiringTest.java` (기존 — 확장)

**Interfaces:**
- Consumes (Task 1): `ArenaStateStore.notes(...)`, `saveNotes(...)`, `clearNotes(...)`, `StoredNotes`.

- [ ] **Step 1: 실패 테스트 작성**

`ArenaServiceFightWiringTest.java`에 추가(기존 헬퍼 `svc(...)`·`battleStatus()` 재사용). Clock은 fixed 12:00(UTC 03:00 → KST 12:00). 시각 제어가 필요한 일말 테스트는 별도 Clock:
```java
    @org.junit.jupiter.api.Test
    void prep_regenerated_only_when_opponent_count_increased() {
        ArenaApiClient api = mock(ArenaApiClient.class);
        when(api.status()).thenReturn(battleStatus());
        java.time.OffsetDateTime t = java.time.OffsetDateTime.parse("2026-07-23T02:00:00Z");
        // 상대 PRO 글 2개
        when(api.fightPosts(any())).thenReturn(java.util.List.of(
                new FightPost("o1","특붕이","PRO","A",0,0,false,t),
                new FightPost("o2","히후미","PRO","B",0,0,false,t)));
        ArenaStateStore store = mock(ArenaStateStore.class);
        when(store.lockedSide(any(), eq("t1"))).thenReturn(java.util.Optional.of("CON"));
        // 저장된 노트가 oppCount=2 (현재도 2) → 재생성 안 함, 재사용
        when(store.notes(any(), eq("t1")))
                .thenReturn(java.util.Optional.of(new ArenaStateStore.StoredNotes("- 저장된 노트", 2)));
        ArenaPrepGenerator prep = mock(ArenaPrepGenerator.class);
        ArenaFightGenerator fight = mock(ArenaFightGenerator.class);
        when(fight.generate(any(),any(),any(),anyString(),eq("- 저장된 노트"))).thenReturn(null);

        svc(api, mock(ArenaProposeGenerator.class), fight, prep, store).runFightOnce();

        verify(prep, never()).generate(any(),any(),any(),anyString());        // 재생성 안 함
        verify(store, never()).saveNotes(any(),any(),anyString(),anyInt());
        verify(fight).generate(any(),any(),any(),anyString(),eq("- 저장된 노트"));  // 저장본 사용
    }

    @org.junit.jupiter.api.Test
    void prep_regenerated_and_saved_when_new_opponent_post() {
        ArenaApiClient api = mock(ArenaApiClient.class);
        when(api.status()).thenReturn(battleStatus());
        java.time.OffsetDateTime t = java.time.OffsetDateTime.parse("2026-07-23T02:00:00Z");
        when(api.fightPosts(any())).thenReturn(java.util.List.of(
                new FightPost("o1","특붕이","PRO","A",0,0,false,t),
                new FightPost("o2","히후미","PRO","B",0,0,false,t)));   // 현재 상대 2개
        ArenaStateStore store = mock(ArenaStateStore.class);
        when(store.lockedSide(any(), eq("t1"))).thenReturn(java.util.Optional.empty());  // 첫 턴 → 게이트 통과
        when(store.notes(any(), eq("t1"))).thenReturn(java.util.Optional.empty());       // 저장 노트 없음
        ArenaPrepGenerator prep = mock(ArenaPrepGenerator.class);
        when(prep.generate(any(),any(),any(),anyString())).thenReturn("- 새 노트");
        ArenaFightGenerator fight = mock(ArenaFightGenerator.class);
        when(fight.generate(any(),any(),any(),anyString(),eq("- 새 노트")))
                .thenReturn(new ArenaFightGenerator.FightDecision("CON","논거"));
        when(api.fight(any(),anyString(),anyString()))
                .thenReturn(new com.maitmus.sekairouter.mersoom.MersoomDtos.CreateResponse(true,"p1"));

        svc(api, mock(ArenaProposeGenerator.class), fight, prep, store).runFightOnce();

        verify(prep).generate(any(),any(),any(),anyString());                 // 재생성
        verify(store).saveNotes(any(), eq("t1"), eq("- 새 노트"), eq(2));       // 저장(현재 상대수 2)
        verify(fight).generate(any(),any(),any(),anyString(),eq("- 새 노트"));
    }

    @org.junit.jupiter.api.Test
    void clears_notes_at_last_fight_hour() {
        // Clock을 19시(KST)로 → 마지막 fight 시각 → clearNotes 호출
        java.time.Clock c19 = java.time.Clock.fixed(
                java.time.Instant.parse("2026-07-23T10:00:00Z"),   // UTC 10:00 = KST 19:00
                java.time.ZoneId.of("Asia/Seoul"));
        ArenaApiClient api = mock(ArenaApiClient.class);
        when(api.status()).thenReturn(battleStatus());
        when(api.fightPosts(any())).thenReturn(java.util.List.of());
        ArenaStateStore store = mock(ArenaStateStore.class);
        when(store.lockedSide(any(), eq("t1"))).thenReturn(java.util.Optional.empty());
        ArenaPrepGenerator prep = mock(ArenaPrepGenerator.class);
        ArenaFightGenerator fight = mock(ArenaFightGenerator.class);
        when(fight.generate(any(),any(),any(),anyString(),anyString())).thenReturn(null);
        ArenaProperties props = mock(ArenaProperties.class);
        when(props.enabled()).thenReturn(true);
        when(props.fight()).thenReturn(new ArenaProperties.Account("id","pw","쿠사나기 네네"));
        new ArenaService(props, api, mock(ArenaProposeGenerator.class), fight, prep, store, c19)
                .runFightOnce();

        verify(store).clearNotes(any(), eq("t1"));
    }
```
※ `battleStatus()` 헬퍼가 topicId="t1"을 쓰는지 확인(기존 테스트와 동일). `anyInt()` import 필요(`org.mockito.ArgumentMatchers.anyInt`).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*ArenaServiceFightWiringTest"`
Expected: 실패(현재 prep이 매 틱 무조건 생성·저장/클리어 없음).

- [ ] **Step 3: 구현 — runFightOnce prep 로직 교체**

`ArenaService.java`:
1. 상수 추가(클래스 상단, `KST` 옆):
```java
    private static final int LAST_FIGHT_HOUR = 19;   // fight-cron(0 5 12-19)의 마지막 시각 — 이 틱 뒤 노트 초기화
```
2. `runFightOnce`의 prep 블록(`String rebuttalNotes = ""; boolean hasOpponentPost = ...; if (hasOpponentPost) {...}`)을 아래로 교체:
```java
        // 반박노트 — 상대 글 수가 저장 시점보다 늘었을 때만 재생성·저장, 아니면 저장본 재사용(정체 토픽 헛수고 방지)
        String rebuttalNotes = "";
        int oppCount = (int) existing.stream()
                .filter(p -> !p.isBlinded() && (selfNick == null || !selfNick.equals(p.nickname())))
                .count();
        if (oppCount > 0) {
            var stored = stateStore.notes(today, topicId);
            if (stored.isEmpty() || oppCount > stored.get().oppCount()) {
                String notes = prepGenerator.generate(status.topic(), existing, lockedSide, selfNick);
                rebuttalNotes = notes == null ? "" : notes;
                stateStore.saveNotes(today, topicId, rebuttalNotes, oppCount);
            } else {
                rebuttalNotes = stored.get().notes();
            }
        }
```
   (`hasOpponentPost` 변수 제거.)
3. **일말 초기화 — topic 확정 후 본문을 `try/finally`로 감싸 모든 경로(게이트 skip·fight 성공)에서 19시면 clearNotes.** `topicId`·`today` 확정 라인 다음부터 메서드 끝까지를 try로 감싸고 finally에 초기화를 둔다. 구조:
```java
        LocalDate today = LocalDate.now(clock.withZone(KST));
        String topicId = status.topic().id();
        try {
            List<FightPost> existing;
            try {
                existing = api.fightPosts(today);
            } catch (Exception e) {
                existing = List.of();
            }
            String lockedSide = stateStore.lockedSide(today, topicId).orElse(null);
            String selfNick = properties.fight().nickname();

            // (위 Step 3-2의 반박노트 로직)
            // 결정론 게이트 — 그대로 (skip 시 return → finally 실행됨)
            // fight 생성·게시·recordSide — 그대로
        } finally {
            // 하루 마지막 fight 시각이면 이 토픽 노트 초기화(게이트 skip·보류·성공 무관).
            // date-scope 자동 리셋의 명시 안전망 — 다음날 새 토픽 대비.
            if (LocalTime.now(clock.withZone(KST)).getHour() == LAST_FIGHT_HOUR) {
                stateStore.clearNotes(today, topicId);
            }
        }
```
   (기존 본문의 `existing`·`lockedSide`·`selfNick`·prep·게이트·fight 블록을 통째로 try 안으로 이동. phase/topic 미확정 이른 return은 try 밖이라 clearNotes 안 함 — 토픽 없으니 정상. `import java.time.LocalTime;` 추가.)

- [ ] **Step 4: 통과 확인 + 전체 회귀**

Run: `./gradlew test --tests "*ArenaServiceFightWiringTest"`
Expected: PASS (신규 3 + 기존).
Run: `./gradlew test 2>&1 | grep -E "BUILD (SUCCESSFUL|FAILED)"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/arena/ArenaService.java \
        src/test/java/com/maitmus/sekairouter/arena/ArenaServiceFightWiringTest.java
git commit -m "feat(arena): 반박노트 상대-증가시만 갱신·저장본 재사용 + 19시 일말 초기화"
```

---

## 실행 후 (별도)
- 배포: sekai-deploy 스킬 경유(매너타임 직접 배포 승인됨). 프롬프트 변경 아님 → sim-refine 불요.
- 배포 후 관측: 정체 토픽에서 `Arena prep 반박노트 생성` 로그가 상대 새 글 있을 때만 뜨는지(매 틱 아님), 저장본 재사용 시 prep 콜 없음, 19시 이후 노트 초기화.
