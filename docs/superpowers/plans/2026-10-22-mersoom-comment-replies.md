# 머슴 대댓글 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 봇이 단 댓글에 달린 답글을 다음 댓글 크론에서 후보로 보여주고, 고를 만할 때 그 답글에 중첩 대댓글로 받아친다.

**Architecture:** 순수 로직 `MersoomReplyThreads`(댓글 트리 → 답글 후보, depth 규칙)를 두고, I/O는 기존 `MersoomCollector`에 `collectReplies` 메서드로 붙인다(엔진 생성자 변경 없음). `MersoomCommentGenerator`가 USER 프롬프트에 답글 섹션을 넣고 `replyIndex` 출력을 `CommentItem(parentId)`로 매핑, `MersoomCitizenEngine`이 parentId로 `createComment`한다.

**Tech Stack:** Java 24, Spring Boot, Jackson(snake_case DTO), JUnit 5 + AssertJ + Mockito. 빌드/테스트 `./gradlew test`.

**Spec:** `docs/superpowers/specs/2026-10-22-mersoom-comment-replies-design.md`

## Global Constraints

- 최상위 댓글은 한 글에 봇당 1회 — 기존 `commentedIds`(lastCommentIds) 검사 유지, **대댓글엔 적용 안 함**.
- depth = 루트 댓글 1, parent 사슬로 센 깊이. 답글 후보는 **그 답글 depth ≤ 4**만(내 대댓글 최대 depth 5). depth 5 이상 노드 아래 답글은 무시.
- 수집 창: `lastCommentIds` 중 **최근 12시간**, 고유 postId **최신 6개**. 글 사이 **3초** 간격. 후보 **최대 4개**(답글 created_at 최신순).
- 내 댓글 식별 = 댓글 `auth_id` == 내 authId (생성 응답엔 id가 없음).
- 대댓글 `parent_id` = 답하는 그 답글의 id.
- 댓글+대댓글 합산 상한 `MAX_COMMENTS`(4).
- 캐릭터 특수룰 금지 — 에무·네네 공통 로직.
- 인라인 프롬프트 변경 시 `docs/prompts/mersoom-comment.md` 동일 문구 동기화.
- 커밋 메시지 끝에 프로젝트 attribution 2줄(Co-Authored-By / Claude-Session)을 붙인다.

## Review Focus

- 답글 작성자가 형제봇(네네가 에무 댓글에 답)일 때 — 후보로 들어오고 일반 답글과 똑같이 처리돼야 한다 → Task 1 테스트 `sibling_reply_is_candidate`.
- 내 댓글이 삭제(is_deleted)됐는데 그 아래 답글이 살아 있을 때 — 그 답글은 후보가 아니어야 한다(지워진 내 말에 받아치기 X) → Task 1 테스트 `deleted_my_comment_drops_replies`.
- 같은 답글을 LLM이 두 번 고르거나 `replyIndex` 범위 밖 번호를 줄 때 — 중복·무효는 버리고 나머지는 정상 게시 → Task 4 테스트 `reply_index_invalid_and_duplicate_dropped`.
- 호칭 교정이 대댓글에 원글 작성자 별명을 덮어쓰지 않아야 한다(대댓글 상대는 답글 작성자) → Task 4 테스트 `address_correction_skips_reply_items`.
- `commentsOf`가 한 글에서 예외를 던져도 나머지 글 후보는 모여야 한다 → Task 2 테스트 `fetch_failure_skips_only_that_post`.

---

### Task 1: 댓글 DTO 확장 + 답글 후보 순수 로직

**Files:**
- Modify: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomDtos.java` (record `Comment`)
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomReplyThreads.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/MersoomReplyThreadsTest.java`

**Interfaces:**
- Produces:
  - `MersoomDtos.Comment(String id, String postId, String parentId, String nickname, String content, int upvotes, int downvotes, OffsetDateTime createdAt, String authId, String ip, boolean isDeleted)` + `String identityKey()` (authId 있으면 authId, 없으면 `"ip:" + ip`).
  - `MersoomReplyThreads.ThreadLine(boolean mine, String author, String text)`
  - `MersoomReplyThreads.ReplyCandidate(String postId, String postTitle, List<ThreadLine> thread, String replyToId, String replyAuthorKey, String replyAuthorNick, OffsetDateTime replyCreatedAt)`
  - `static final int MAX_ANSWERABLE_DEPTH = 4;`
  - `static List<ReplyCandidate> candidates(String postId, String postTitle, List<Comment> comments, String myAuthId, Set<String> blockedKeys)`
  - `static int depth(Comment c, Map<String, Comment> byId)`

- [ ] **Step 1: Write the failing test**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Comment;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MersoomReplyThreadsTest {

    private static final String ME = "nene_wonder";
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-22T10:00:00+09:00");

    private static Comment c(String id, String parent, String authId, String nick, String text, int min) {
        return new Comment(id, "p1", parent, nick, text, 0, 0, T0.plusMinutes(min), authId, null, false);
    }

    @Test
    void reply_to_my_comment_is_candidate_with_thread() {
        var cs = List.of(c("a", null, ME, "네네", "세 번째 찻잔이면 잠 안 오지", 0),
                c("b", "a", "maid_chibi", "Maid-Chibi", "루이보스로 마셔볼게요!", 5));
        var out = MersoomReplyThreads.candidates("p1", "밤공기", cs, ME, Set.of());
        assertThat(out).hasSize(1);
        var r = out.get(0);
        assertThat(r.replyToId()).isEqualTo("b");
        assertThat(r.replyAuthorKey()).isEqualTo("maid_chibi");
        assertThat(r.thread()).extracting(MersoomReplyThreads.ThreadLine::mine).containsExactly(true, false);
        assertThat(r.thread().get(1).text()).isEqualTo("루이보스로 마셔볼게요!");
    }

    @Test
    void already_answered_reply_is_not_candidate() {
        var cs = List.of(c("a", null, ME, "네네", "x", 0), c("b", "a", "maid_chibi", "M", "y", 5),
                c("c", "b", ME, "네네", "z", 10));
        assertThat(MersoomReplyThreads.candidates("p1", "t", cs, ME, Set.of())).isEmpty();
    }

    @Test
    void depth4_reply_is_candidate_but_depth6_is_ignored() {
        var d4 = new ArrayList<>(List.of(c("a", null, ME, "네네", "1", 0), c("b", "a", "m", "M", "2", 1),
                c("c", "b", ME, "네네", "3", 2), c("d", "c", "m", "M", "4", 3)));
        assertThat(MersoomReplyThreads.candidates("p1", "t", d4, ME, Set.of()))
                .extracting(MersoomReplyThreads.ReplyCandidate::replyToId).containsExactly("d");
        d4.add(c("e", "d", ME, "네네", "5", 4));
        d4.add(c("f", "e", "m", "M", "6", 5));
        assertThat(MersoomReplyThreads.candidates("p1", "t", d4, ME, Set.of())).isEmpty();
    }

    @Test
    void reply_whose_parent_is_not_mine_is_ignored() {
        var cs = List.of(c("a", null, "other", "O", "x", 0), c("b", "a", "m", "M", "y", 5));
        assertThat(MersoomReplyThreads.candidates("p1", "t", cs, ME, Set.of())).isEmpty();
    }

    @Test
    void blocked_author_reply_is_ignored() {
        var cs = List.of(c("a", null, ME, "네네", "x", 0), c("b", "a", "w8agi", "하얀이", "y", 5));
        assertThat(MersoomReplyThreads.candidates("p1", "t", cs, ME, Set.of("w8agi"))).isEmpty();
    }

    @Test
    void sibling_reply_is_candidate() {
        var cs = List.of(c("a", null, ME, "네네", "x", 0), c("b", "a", "emu_wonder", "에무", "네네쨩~", 5));
        assertThat(MersoomReplyThreads.candidates("p1", "t", cs, ME, Set.of()))
                .extracting(MersoomReplyThreads.ReplyCandidate::replyAuthorNick).containsExactly("에무");
    }

    @Test
    void deleted_my_comment_drops_replies() {
        var mine = new Comment("a", "p1", null, "네네", "x", 0, 0, T0, ME, null, true);
        var cs = List.of(mine, c("b", "a", "m", "M", "y", 5));
        assertThat(MersoomReplyThreads.candidates("p1", "t", cs, ME, Set.of())).isEmpty();
    }

    @Test
    void depth_counts_root_as_one() {
        var a = c("a", null, ME, "n", "1", 0);
        var b = c("b", "a", "m", "M", "2", 1);
        var cc = c("c", "b", ME, "n", "3", 2);
        var byId = Map.of("a", a, "b", b, "c", cc);
        assertThat(MersoomReplyThreads.depth(a, byId)).isEqualTo(1);
        assertThat(MersoomReplyThreads.depth(cc, byId)).isEqualTo(3);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*MersoomReplyThreadsTest*' 2>&1 | grep -E "BUILD|error:" | head -5`
Expected: FAIL — compile error (`MersoomReplyThreads` 없음, `Comment` 생성자 인자 수 불일치).

- [ ] **Step 3: Write minimal implementation**

`MersoomDtos.java`의 `Comment` record를 교체:

```java
    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Comment(
            String id,
            String postId,
            String parentId,
            String nickname,
            String content,
            int upvotes,
            int downvotes,
            OffsetDateTime createdAt,
            String authId,
            String ip,
            boolean isDeleted
    ) {
        /** Post.identityKey()와 같은 규칙 — auth_id 우선, 없으면 ip. */
        public String identityKey() {
            return (authId != null && !authId.isBlank()) ? authId : "ip:" + (ip == null ? "?" : ip);
        }
    }
```

`MersoomReplyThreads.java` 생성:

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Comment;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 한 글의 댓글 트리에서 '내 댓글에 달린, 아직 안 받아친 답글'을 대댓글 후보로 뽑는 순수 로직.
 * depth는 루트(최상위) 댓글을 1로 parent 사슬을 따라 센다. 답글 depth ≤ 4만 후보 → 내 대댓글은 최대 depth 5(A-B-A-B-A),
 * 상대가 매번 답하는 봇이어도 그 가지는 거기서 끝난다.
 */
final class MersoomReplyThreads {

    static final int MAX_ANSWERABLE_DEPTH = 4;

    record ThreadLine(boolean mine, String author, String text) {}

    record ReplyCandidate(String postId, String postTitle, List<ThreadLine> thread, String replyToId,
                          String replyAuthorKey, String replyAuthorNick, OffsetDateTime replyCreatedAt) {}

    private MersoomReplyThreads() {}

    static List<ReplyCandidate> candidates(String postId, String postTitle, List<Comment> comments,
                                           String myAuthId, Set<String> blockedKeys) {
        Map<String, Comment> byId = new HashMap<>();
        for (Comment c : comments) if (c.id() != null) byId.put(c.id(), c);
        List<ReplyCandidate> out = new ArrayList<>();
        for (Comment r : comments) {
            if (r.isDeleted() || r.parentId() == null || isMine(r, myAuthId)) continue;
            Comment parent = byId.get(r.parentId());
            if (parent == null || parent.isDeleted() || !isMine(parent, myAuthId)) continue;
            if (blockedKeys.contains(r.identityKey())) continue;
            if (depth(r, byId) > MAX_ANSWERABLE_DEPTH) continue;
            boolean answered = comments.stream()
                    .anyMatch(x -> r.id().equals(x.parentId()) && !x.isDeleted() && isMine(x, myAuthId));
            if (answered) continue;
            out.add(new ReplyCandidate(postId, postTitle, path(r, byId, myAuthId), r.id(),
                    r.identityKey(), r.nickname(), r.createdAt()));
        }
        return out;
    }

    static int depth(Comment c, Map<String, Comment> byId) {
        int d = 1;
        Comment cur = c;
        Set<String> seen = new HashSet<>();   // 순환 parent 방어
        while (cur.parentId() != null && seen.add(cur.id())) {
            Comment p = byId.get(cur.parentId());
            if (p == null) break;
            d++;
            cur = p;
        }
        return d;
    }

    private static List<ThreadLine> path(Comment leaf, Map<String, Comment> byId, String myAuthId) {
        LinkedList<ThreadLine> lines = new LinkedList<>();
        Comment cur = leaf;
        Set<String> seen = new HashSet<>();
        while (cur != null && seen.add(cur.id())) {
            lines.addFirst(new ThreadLine(isMine(cur, myAuthId), cur.nickname(), cur.content()));
            cur = cur.parentId() == null ? null : byId.get(cur.parentId());
        }
        return List.copyOf(lines);
    }

    private static boolean isMine(Comment c, String myAuthId) {
        return myAuthId != null && myAuthId.equals(c.authId());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*MersoomReplyThreadsTest*' 2>&1 | grep -E "BUILD|FAILED" | head -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Run full suite (DTO 변경 파급 확인) and commit**

Run: `./gradlew test 2>&1 | grep -E "BUILD|FAILED" | head -5` → `BUILD SUCCESSFUL`

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/MersoomDtos.java \
        src/main/java/com/maitmus/sekairouter/mersoom/MersoomReplyThreads.java \
        src/test/java/com/maitmus/sekairouter/mersoom/MersoomReplyThreadsTest.java
git commit -m "feat(mersoom): 댓글 트리에서 대댓글 후보 추출(depth ≤4 답글, 내 댓글 auth_id 식별)"
```

---

### Task 2: 수집 — `MersoomCollector.collectReplies`

**Files:**
- Modify: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomCollector.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/MersoomCollectorTest.java`

**Interfaces:**
- Consumes: `MersoomReplyThreads.candidates(...)`, `ReplyCandidate`, `MersoomApiClient.commentsOf(String postId)`, `MersoomState.lastCommentIds()` (`CommentRef(postId, timestamp, text)`), `MersoomState.fixedAvoid()` (`FixedAvoid.name()` = identityKey).
- Produces:
  - `public List<MersoomReplyThreads.ReplyCandidate> collectReplies(MersoomState state, String myAuthId, Map<String, String> titlesByPostId, OffsetDateTime now)`
  - `static final int REPLY_WINDOW_HOURS = 12; static final int REPLY_POSTS = 6; static final int MAX_REPLY_CANDIDATES = 4;`
  - 패키지 전용 `void setReplyFetchGapMs(long ms)` (기본 3000, 테스트에서 0).

- [ ] **Step 1: Write the failing test** (기존 `MersoomCollectorTest`에 추가 — 파일의 기존 import/헬퍼를 확인해 맞춘다)

```java
    @Test
    void collectReplies_uses_recent_window_and_caps() {
        MersoomApiClient api = mock(MersoomApiClient.class);
        OffsetDateTime now = OffsetDateTime.parse("2026-10-22T12:00:00+09:00");
        List<MersoomState.CommentRef> refs = new ArrayList<>();
        for (int i = 0; i < 8; i++) refs.add(new MersoomState.CommentRef("p" + i, now.minusHours(i), "t"));
        refs.add(new MersoomState.CommentRef("old", now.minusHours(13), "t"));     // 12h 밖
        refs.add(1, new MersoomState.CommentRef("p0", now.minusMinutes(30), "t"));   // 중복 postId
        MersoomState st = new MersoomState(List.of(), refs, List.of(), Map.of(), 8, List.of(), null, null, List.of(), List.of());
        for (int i = 0; i < 8; i++) {
            String pid = "p" + i;
            when(api.commentsOf(pid)).thenReturn(List.of(
                    new MersoomDtos.Comment("m" + i, pid, null, "네네", "내 댓글", 0, 0, now.minusHours(i), "nene_wonder", null, false),
                    new MersoomDtos.Comment("r" + i, pid, "m" + i, "M", "답글" + i, 0, 0, now.minusMinutes(60 * i - 1), "maid", null, false)));
        }
        MersoomCollector col = new MersoomCollector(api);
        col.setReplyFetchGapMs(0);

        var out = col.collectReplies(st, "nene_wonder", Map.of("p0", "제목0"), now);

        verify(api, times(6)).commentsOf(any());          // 고유 postId 최신 6개만
        verify(api, never()).commentsOf("old");
        assertThat(out).hasSize(4);                        // 후보 상한 4
        assertThat(out.get(0).replyToId()).isEqualTo("r0"); // 답글 최신순
        assertThat(out.get(0).postTitle()).isEqualTo("제목0");
        assertThat(out.get(1).postTitle()).isEqualTo("(예전 글)");
    }

    @Test
    void fetch_failure_skips_only_that_post() {
        MersoomApiClient api = mock(MersoomApiClient.class);
        OffsetDateTime now = OffsetDateTime.parse("2026-10-22T12:00:00+09:00");
        var refs = List.of(new MersoomState.CommentRef("bad", now.minusHours(1), "t"),
                new MersoomState.CommentRef("ok", now.minusHours(2), "t"));
        MersoomState st = new MersoomState(List.of(), refs, List.of(), Map.of(), 8, List.of(), null, null, List.of(), List.of());
        when(api.commentsOf("bad")).thenThrow(new RuntimeException("429"));
        when(api.commentsOf("ok")).thenReturn(List.of(
                new MersoomDtos.Comment("m", "ok", null, "네네", "x", 0, 0, now, "nene_wonder", null, false),
                new MersoomDtos.Comment("r", "ok", "m", "M", "y", 0, 0, now, "maid", null, false)));
        MersoomCollector col = new MersoomCollector(api);
        col.setReplyFetchGapMs(0);

        assertThat(col.collectReplies(st, "nene_wonder", Map.of(), now))
                .extracting(MersoomReplyThreads.ReplyCandidate::replyToId).containsExactly("r");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*MersoomCollectorTest*' 2>&1 | grep -E "BUILD|error:" | head -5`
Expected: FAIL — `collectReplies`/`setReplyFetchGapMs` 없음.

- [ ] **Step 3: Write minimal implementation** (`MersoomCollector`에 추가; import `java.time.OffsetDateTime`, `java.util.*`)

```java
    static final int REPLY_WINDOW_HOURS = 12;
    static final int REPLY_POSTS = 6;
    static final int MAX_REPLY_CANDIDATES = 4;

    private long replyFetchGapMs = 3000;   // 6/14 IP 버스트 차단 재발 방지 — 글 사이 간격

    void setReplyFetchGapMs(long ms) { this.replyFetchGapMs = ms; }

    /**
     * 내 최근 댓글이 달린 글들의 댓글 트리를 당겨, 내 댓글에 달린 아직 안 받아친 답글을 후보로 모은다(댓글 크론 전용).
     * 생성 응답엔 댓글 id가 없어서 state엔 글 id만 있다 → 트리에서 auth_id로 내 댓글을 찾는다. 실패한 글은 건너뛴다.
     */
    public List<MersoomReplyThreads.ReplyCandidate> collectReplies(MersoomState state, String myAuthId,
                                                                  Map<String, String> titlesByPostId,
                                                                  OffsetDateTime now) {
        OffsetDateTime since = now.minusHours(REPLY_WINDOW_HOURS);
        LinkedHashSet<String> postIds = new LinkedHashSet<>();
        for (MersoomState.CommentRef ref : state.lastCommentIds()) {
            if (ref.timestamp() == null || ref.timestamp().isBefore(since)) continue;
            postIds.add(ref.postId());
            if (postIds.size() >= REPLY_POSTS) break;
        }
        Set<String> blocked = new HashSet<>();
        for (var fa : state.fixedAvoid()) blocked.add(fa.name());

        List<MersoomReplyThreads.ReplyCandidate> all = new ArrayList<>();
        boolean first = true;
        for (String pid : postIds) {
            if (!first && replyFetchGapMs > 0) {
                try { Thread.sleep(replyFetchGapMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            first = false;
            try {
                String title = titlesByPostId.getOrDefault(pid, "(예전 글)");
                all.addAll(MersoomReplyThreads.candidates(pid, title, api.commentsOf(pid), myAuthId, blocked));
            } catch (Exception e) {
                log.info("Mersoom reply 수집 스킵 — post={} {}", pid, e.toString());
            }
        }
        all.sort(Comparator.comparing(MersoomReplyThreads.ReplyCandidate::replyCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<MersoomReplyThreads.ReplyCandidate> out = all.size() > MAX_REPLY_CANDIDATES
                ? List.copyOf(all.subList(0, MAX_REPLY_CANDIDATES)) : List.copyOf(all);
        log.info("Mersoom replies collected: posts={}, candidates={}", postIds.size(), out.size());
        return out;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*MersoomCollectorTest*' 2>&1 | grep -E "BUILD|FAILED" | head -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/MersoomCollector.java \
        src/test/java/com/maitmus/sekairouter/mersoom/MersoomCollectorTest.java
git commit -m "feat(mersoom): 최근 내 댓글 글들의 답글 수집(12h·6글·3초 간격·후보 4)"
```

---

### Task 3: 파서 — `replyIndex`

**Files:**
- Modify: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomFeedJudgmentParser.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/MersoomFeedJudgmentParserTest.java`

**Interfaces:**
- Produces: `MersoomFeedJudgmentParser.Comment(Integer targetIndex, Integer replyIndex, String utterance)` — 정확히 하나만 non-null. 생성기는 `c.targetIndex()`/`c.replyIndex()`로 분기.

- [ ] **Step 1: Write the failing test** (기존 테스트 파일에 추가)

```java
    @Test
    void parses_replyIndex_items() {
        String raw = "{\"reasoning\":\"r\",\"votes\":[],\"comments\":[{\"targetIndex\":2,\"utterance\":\"글 댓글\"},"
                + "{\"replyIndex\":1,\"utterance\":\"대댓글\"}],\"nicknames\":[]}";
        var j = MersoomFeedJudgmentParser.parse(raw).orElseThrow();
        assertThat(j.comments()).hasSize(2);
        assertThat(j.comments().get(0).targetIndex()).isEqualTo(2);
        assertThat(j.comments().get(0).replyIndex()).isNull();
        assertThat(j.comments().get(1).replyIndex()).isEqualTo(1);
        assertThat(j.comments().get(1).targetIndex()).isNull();
    }

    @Test
    void drops_items_with_both_or_neither_index() {
        String raw = "{\"votes\":[],\"comments\":[{\"targetIndex\":1,\"replyIndex\":1,\"utterance\":\"둘다\"},"
                + "{\"utterance\":\"없음\"},{\"replyIndex\":2,\"utterance\":\"정상\"}],\"nicknames\":[]}";
        var j = MersoomFeedJudgmentParser.parse(raw).orElseThrow();
        assertThat(j.comments()).extracting(MersoomFeedJudgmentParser.Comment::utterance).containsExactly("정상");
    }

    @Test
    void partial_recovery_keeps_replyIndex() {
        // utterance 안 미이스케이프 큰따옴표로 Jackson 실패 → 스트리밍 복구 경로
        String raw = "{\"votes\":[{\"id\":\"p1\",\"vote\":\"up\"}],\"comments\":[{\"replyIndex\":1,\"utterance\":\"좋아\"},"
                + "{\"targetIndex\":2,\"utterance\":\"깨\"진\"}]}";
        var j = MersoomFeedJudgmentParser.parse(raw).orElseThrow();
        assertThat(j.comments()).extracting(MersoomFeedJudgmentParser.Comment::replyIndex).contains(1);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*MersoomFeedJudgmentParserTest*' 2>&1 | grep -E "BUILD|error:" | head -5`
Expected: FAIL — `replyIndex()` 없음.

- [ ] **Step 3: Write minimal implementation**

1. `public record Comment(Integer targetIndex, Integer replyIndex, String utterance) {}` 로 교체, `private record RawComment(Integer targetIndex, Integer replyIndex, String utterance) {}`.
2. `toJudgment`의 comments 스트림:

```java
        List<Comment> comments = r.comments == null ? List.of()
                : r.comments.stream()
                        .filter(c -> c != null && (c.targetIndex == null) != (c.replyIndex == null)
                                && c.utterance != null && !c.utterance.isBlank())
                        .map(c -> new Comment(c.targetIndex, c.replyIndex, c.utterance.strip()))
                        .toList();
```

3. `readComments`(스트리밍 복구):

```java
    private static void readComments(JsonParser p, String json, List<Comment> out) throws Exception {
        Integer idx = null;
        Integer ridx = null;
        String utt = null;
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
            if (t == JsonToken.START_OBJECT || t == JsonToken.END_OBJECT) { idx = null; ridx = null; utt = null; continue; }
            if (t != JsonToken.FIELD_NAME) continue;
            String f = p.currentName();
            JsonToken v = p.nextToken();
            if ("targetIndex".equals(f) && v == JsonToken.VALUE_NUMBER_INT) idx = p.getIntValue();
            else if ("replyIndex".equals(f) && v == JsonToken.VALUE_NUMBER_INT) ridx = p.getIntValue();
            else if ("utterance".equals(f) && v == JsonToken.VALUE_STRING) {
                String text = p.getText();   // 토큰 완결(닫는 따옴표까지 소비) 후 위치 확인
                utt = cleanlyTerminated(json, (int) p.currentLocation().getCharOffset()) ? text : null;
            } else p.skipChildren();
            if ((idx != null) != (ridx != null) && utt != null) {   // 쌍 완성 즉시 채택
                if (!utt.isBlank()) out.add(new Comment(idx, ridx, utt.strip()));
                idx = null;
                ridx = null;
                utt = null;
            }
        }
    }
```

4. 클래스 javadoc 스키마 주석에 `{"replyIndex":1,"utterance":"..."}`(답글 후보 r 번호) 한 줄 추가.
5. 기존 테스트에서 `new MersoomFeedJudgmentParser.Comment(idx, utt)` 2-인자 생성자 호출이 있으면 `(idx, null, utt)`로 고친다(`grep -rn "FeedJudgmentParser.Comment(" src/test`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*MersoomFeedJudgmentParserTest*' 2>&1 | grep -E "BUILD|FAILED" | head -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/MersoomFeedJudgmentParser.java \
        src/test/java/com/maitmus/sekairouter/mersoom/MersoomFeedJudgmentParserTest.java
git commit -m "feat(mersoom): 피드 판정 파서에 replyIndex(대댓글 대상) 지원"
```

---

### Task 4: 생성기 — 답글 섹션·규칙·출력 매핑 + 프롬프트 손문서

**Files:**
- Modify: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomCommentGenerator.java`
- Modify: `src/main/resources/prompts/mersoom-instructions.md`, `src/main/resources/prompts/mersoom-instructions-nene.md` (댓글 출력 형식 한 줄)
- Modify: `docs/prompts/mersoom-comment.md` (동일 문구 미러)
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/MersoomCommentGeneratorTest.java`

**Interfaces:**
- Consumes: `MersoomReplyThreads.ReplyCandidate`/`ThreadLine`, `MersoomFeedJudgmentParser.Comment.replyIndex()`.
- Produces:
  - `public record CommentItem(String targetId, String text, String parentId)` + `public CommentItem(String targetId, String text) { this(targetId, text, null); }` + `public boolean isReply() { return parentId != null; }` — 대댓글이면 `targetId`=글 id, `parentId`=답글 id.
  - `public FeedJudgment generate(CitizenProfile profile, MersoomState state, List<Commentable> commentable, java.time.LocalDateTime now, List<MersoomReplyThreads.ReplyCandidate> replies)` — 기존 4-인자는 `replies = List.of()`로 위임.
  - 프롬프트 고유 문구(테스트·손문서가 참조): 섹션 제목 `## 내 댓글에 달린 답글 (받아칠지는 자유 — 0개가 정상)`, 규칙 문구 `답글이 고마움 인사·맞장구뿐이면 굳이 받지 않는다`.

- [ ] **Step 1: Write the failing tests** (기존 테스트 파일의 픽스처·헬퍼(`emuProfile`/`post`/state 빌더, `AnthropicClientWrapper` mock + `ArgumentCaptor`)를 그대로 사용한다. 아래는 그 헬퍼 이름이 `gen(anthropic)`·`EMU`·`emptyState()`·`commentable(...)`라고 가정한 형태 — 실제 이름으로 맞춘다)

```java
    private static MersoomReplyThreads.ReplyCandidate replyCand(String replyId, String nick, String text) {
        return new MersoomReplyThreads.ReplyCandidate("px", "밤공기", List.of(
                new MersoomReplyThreads.ThreadLine(true, "에무", "낙엽 놀이 최고예요!"),
                new MersoomReplyThreads.ThreadLine(false, nick, text)),
                replyId, "maid_chibi", nick, java.time.OffsetDateTime.now());
    }

    @Test
    void reply_section_only_when_candidates() {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> up = ArgumentCaptor.forClass(String.class);
        when(a.completeJson(any(), up.capture())).thenReturn("{\"votes\":[],\"comments\":[]}");
        var g = gen(a);
        g.generate(EMU, emptyState(), commentable(), null, List.of());
        assertThat(up.getValue()).doesNotContain("## 내 댓글에 달린 답글");
        g.generate(EMU, emptyState(), commentable(), null, List.of(replyCand("r1", "Maid-Chibi", "답글 고마워요 ✨")));
        assertThat(up.getValue()).contains("## 내 댓글에 달린 답글 (받아칠지는 자유 — 0개가 정상)")
                .contains("\"r\":1").contains("\"by\":\"나\"").contains("답글 고마워요 ✨");
    }

    @Test
    void rules_mention_reply_skip_and_replyIndex() {
        String rules = MersoomCommentGenerator.buildRules(EMU);
        assertThat(rules).contains("답글이 고마움 인사·맞장구뿐이면 굳이 받지 않는다").contains("replyIndex");
    }

    @Test
    void replyIndex_maps_to_parent_and_shares_cap() {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        when(a.completeJson(any(), any())).thenReturn("{\"votes\":[],\"comments\":["
                + "{\"targetIndex\":1,\"utterance\":\"하나\"},{\"targetIndex\":2,\"utterance\":\"둘\"},"
                + "{\"targetIndex\":3,\"utterance\":\"셋\"},{\"replyIndex\":1,\"utterance\":\"대댓\"},"
                + "{\"replyIndex\":2,\"utterance\":\"넘침\"}]}");
        var j = gen(a).generate(EMU, emptyState(), commentable(), null,
                List.of(replyCand("r1", "M", "a"), replyCand("r2", "M", "b")));
        assertThat(j.comments()).hasSize(4);   // 합산 상한
        var last = j.comments().get(3);
        assertThat(last.isReply()).isTrue();
        assertThat(last.parentId()).isEqualTo("r1");
        assertThat(last.targetId()).isEqualTo("px");
    }

    @Test
    void reply_index_invalid_and_duplicate_dropped() {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        when(a.completeJson(any(), any())).thenReturn("{\"votes\":[],\"comments\":["
                + "{\"replyIndex\":9,\"utterance\":\"무효\"},{\"replyIndex\":1,\"utterance\":\"첫\"},"
                + "{\"replyIndex\":1,\"utterance\":\"중복\"}]}");
        var j = gen(a).generate(EMU, emptyState(), commentable(), null, List.of(replyCand("r1", "M", "a")));
        assertThat(j.comments()).extracting(MersoomCommentGenerator.CommentItem::text).containsExactly("첫");
    }

    @Test
    void address_correction_skips_reply_items() {
        // 대댓글엔 원글 작성자 별명 누락 검사(missedCall)를 적용하지 않는다 — 대댓글 상대는 답글 작성자.
        // 교정 콜이 나가면 두 번째 completeJson이 호출되므로 1회만 호출됐는지로 확인.
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        when(a.completeJson(any(), any())).thenReturn(
                "{\"votes\":[],\"comments\":[{\"replyIndex\":1,\"utterance\":\"메이드쨩도 같이 해요!\"}]}");
        var j = gen(a).generate(EMU, stateWithCall("friend1", "친구쨩"), commentableBy("friend1", "친구"), null,
                List.of(replyCand("r1", "Maid-Chibi", "고마워요")));
        verify(a, times(1)).completeJson(any(), any());
        assertThat(j.comments().get(0).parentId()).isEqualTo("r1");
    }
```

(`stateWithCall(key, call)` = contextNotes에 그 identityKey로 별명 있는 note를 넣은 state, `commentableBy(authId, nick)` = 그 작성자 글 1개 피드 — 기존 테스트에 유사 헬퍼가 없으면 이 테스트 파일 하단에 private static으로 추가한다.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*MersoomCommentGeneratorTest*' 2>&1 | grep -E "BUILD|error:" | head -5`
Expected: FAIL — 5-인자 `generate`·`isReply`·`parentId` 없음.

- [ ] **Step 3: Write minimal implementation**

1) `CommentItem` 교체:

```java
    /** 대댓글이면 targetId=글 id, parentId=받아칠 답글 id. 최상위 댓글은 parentId=null. */
    public record CommentItem(String targetId, String text, String parentId) {
        public CommentItem(String targetId, String text) { this(targetId, text, null); }
        public boolean isReply() { return parentId != null; }
    }
```

2) `generate` 오버로드: 기존 4-인자 시그니처 본문을 5-인자로 옮기고 4-인자는 `return generate(profile, state, commentable, now, List.of());`. `buildUserPrompt(profile, state, commentable, now, replies)`로 전달.

3) 댓글 매핑 루프(3번 블록)를 교체:

```java
        List<CommentItem> comments = new ArrayList<>();
        Set<String> usedTargets = new HashSet<>();
        Set<String> usedReplies = new HashSet<>();
        for (var c : j.comments()) {
            if (comments.size() >= MAX_COMMENTS) break;
            String text = c.utterance() == null ? "" : c.utterance().strip();
            String targetId;
            String parentId = null;
            if (c.replyIndex() != null) {
                int r = c.replyIndex();
                if (r < 1 || r > replies.size()) {
                    log.info("Mersoom reply 항목 보류 — replyIndex 무효: {} (답글 1~{})", r, replies.size());
                    continue;
                }
                var cand = replies.get(r - 1);
                if (!usedReplies.add(cand.replyToId())) continue;   // 같은 답글 중복 제거
                targetId = cand.postId();
                parentId = cand.replyToId();
            } else {
                Integer idx = c.targetIndex();
                if (idx == null || idx < 1 || idx > commentable.size()) {
                    log.info("Mersoom comment 항목 보류 — targetIndex 무효: {} (피드 1~{})", idx, commentable.size());
                    continue;
                }
                targetId = commentable.get(idx - 1).post().id();
                if (!usedTargets.add(targetId)) continue;   // 같은 글 중복 제거
            }
            if (text.isBlank()) continue;
            if (!outputSanityGate.isClean(text)) {
                log.warn("Mersoom comment 항목 보류 — 백스톱 누수 마커 감지: {}",
                        text.substring(0, Math.min(60, text.length())));
                continue;
            }
            comments.add(new CommentItem(targetId, text.length() > MAX_CONTENT ? text.substring(0, MAX_CONTENT) : text, parentId));
        }
```

4) 호칭 교정·정규화가 parentId를 보존하고, 대댓글엔 원글 작성자 별명 검사를 안 하게:
   - `correctAddressInComments` 안: `String[] nc = comments.get(i).isReply() ? null : nickToCallByTarget.get(comments.get(i).targetId());`
   - 같은 메서드의 `out.set(ci, new CommentItem(comments.get(ci).targetId(), t));` → `out.set(ci, new CommentItem(comments.get(ci).targetId(), t, comments.get(ci).parentId()));`
   - 정규화: `.map(c -> new CommentItem(c.targetId(), outputSanityGate.normalize(c.text()), c.parentId()))`

5) `buildUserPrompt(..., List<MersoomReplyThreads.ReplyCandidate> replies)` — 피드 JSON 출력(`sb.append("]\n");` 블록) 직후, "최근 네가 단 댓글" 섹션 앞에:

```java
        if (!replies.isEmpty()) {
            sb.append("\n## 내 댓글에 달린 답글 (받아칠지는 자유 — 0개가 정상)\n");
            sb.append("- 각 객체가 네 댓글에서 이어진 대화 하나. \"r\"=대댓글 지정 번호(replyIndex), \"post\"=그 글 제목, ")
              .append("\"thread\"=네 댓글부터 마지막 답글까지 순서대로(\"by\":\"나\"=너). 받아치면 마지막 답글에 이어 말하는 거다.\n");
            try {
                int ri = 0;
                for (var rc : replies) {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("r", ++ri);
                    o.put("post", safe(rc.postTitle()));
                    List<Map<String, String>> th = new ArrayList<>();
                    for (var line : rc.thread()) {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("by", line.mine() ? "나" : safe(line.author()));
                        String t = safe(line.text());
                        m.put("text", t.length() > 200 ? t.substring(0, 200) + "…" : t);
                        th.add(m);
                    }
                    o.put("thread", th);
                    sb.append("  ").append(FEED_MAPPER.writeValueAsString(o)).append('\n');
                }
            } catch (JsonProcessingException e) {
                log.warn("답글 후보 JSON 직렬화 실패 — 답글 섹션 생략: {}", e.getMessage());
            }
        }
```

6) `buildRules` — "## 댓글 기준" 블록의 `"- **서로 다른 글에**…"` 줄 바로 앞에:

```java
        sb.append("- **user 메시지에 '내 댓글에 달린 답글'이 있으면 — 그 대화에 이어 말할 거리가 있을 때만 받아친다(comments에 replyIndex+utterance, 글 댓글과 합쳐 최대 " + MAX_COMMENTS + "개).** 답글이 고마움 인사·맞장구뿐이면 굳이 받지 않는다(0개가 정상). 받을 땐 마지막 답글에 바로 이어 말하듯 짧게 — 같은 인사·감사를 되돌려주는 핑퐁 금지, 말투·호칭은 그 답글 작성자와의 관계대로.\n");
```

   출력 형식 블록의 comments 줄과 targetIndex 안내를 교체:

```java
        sb.append("\"comments\":[{\"targetIndex\":<댓글 달 글의 n 정수>,\"utterance\":\"<댓글 본문>\"} 또는 {\"replyIndex\":<받아칠 답글의 r 정수>,\"utterance\":\"<대댓글 본문>\"}, ...], ");
```
```java
        sb.append("- ⚠️ **comments 각 항목은 targetIndex(피드 글의 \"n\") 또는 replyIndex(답글의 \"r\") 중 정확히 하나** — id 문자열을 쓰지 말 것. 번호로 지정하면 대상이 정확히 매칭된다.\n");
```

7) `mersoom-instructions.md`·`mersoom-instructions-nene.md`의 댓글 출력 형식 줄: `"comments": [{"targetIndex":…,"utterance":…}]` 뒤에 ` 또는 {"replyIndex":<답글 r 번호>,"utterance":…}` 를 같은 괄호 안에 추가(두 파일 동일 문구).

8) `docs/prompts/mersoom-comment.md` — 위 5)의 섹션 설명(섹션 제목·필드 설명 줄), 6)의 규칙 줄·출력 형식 두 줄을 코드와 **동일 문구**로 해당 위치(피드 섹션 뒤 / 댓글 기준 '서로 다른 글에' 앞 / 출력 형식)에 반영.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test 2>&1 | grep -E "BUILD|FAILED" | head -5`
Expected: `BUILD SUCCESSFUL` (엔진 테스트는 아직 4-인자 mock이라 그대로 통과해야 함)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/MersoomCommentGenerator.java \
        src/main/resources/prompts/mersoom-instructions.md src/main/resources/prompts/mersoom-instructions-nene.md \
        docs/prompts/mersoom-comment.md \
        src/test/java/com/maitmus/sekairouter/mersoom/MersoomCommentGeneratorTest.java
git commit -m "feat(mersoom): 댓글 판정에 '내 댓글에 달린 답글' 섹션 + replyIndex 대댓글 매핑"
```

---

### Task 5: 엔진 — 답글 수집 연결 + 대댓글 게시

**Files:**
- Modify: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomCitizenEngine.java` (`runComment`, `recordComment`)
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/MersoomCitizenEngineTest.java`

**Interfaces:**
- Consumes: `collector.collectReplies(state, profile.auth().authId(), titles, OffsetDateTime.now(clock.withZone(KST)))`, 5-인자 `commentGenerator.generate(...)`, `CommentItem.isReply()/parentId()`, `api.createComment(auth, postId, parentId, nickname, content)`.
- Produces: 로그 `[{key}] Mersoom reply created: post={} parent={} target_nick={} content_len={}`.

- [ ] **Step 1: Update existing mocks + write failing tests**

먼저 기존 mock을 5-인자로 일괄 치환:

Run: `sed -i 's/generate(any(), any(), any(), any())/generate(any(), any(), any(), any(), any())/g' src/test/java/com/maitmus/sekairouter/mersoom/MersoomCitizenEngineTest.java`

새 테스트 추가:

```java
    @Test
    void runComment_posts_reply_with_parentId_and_skips_toplevel_rule() {
        Post bright = post("p2", "벚꽃~!", "친구", "오늘 산책 기분 최고에요!", "friend1");
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(
                List.of(new Commentable(bright, List.of())), List.of(), List.of(bright)));
        var cand = new MersoomReplyThreads.ReplyCandidate("pOld", "(예전 글)", List.of(
                new MersoomReplyThreads.ThreadLine(true, "에무", "낙엽 최고예요!"),
                new MersoomReplyThreads.ThreadLine(false, "Maid-Chibi", "같이 해요!")),
                "r1", "maid_chibi", "Maid-Chibi", OffsetDateTime.now());
        when(collector.collectReplies(any(), any(), any(), any())).thenReturn(List.of(cand));
        MersoomStateStore store = mock(MersoomStateStore.class);
        // pOld에 이미 최상위 댓글을 단 상태 — 대댓글은 이 규칙에 안 걸려야 한다
        when(store.load(any())).thenReturn(new MersoomState(List.of(),
                List.of(new CommentRef("pOld", OffsetDateTime.now(), "낙엽 최고예요!")), List.of(), Map.of(), 8,
                List.of(), null, null, List.of(), List.of()));
        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);
        when(commentGen.generate(any(), any(), any(), any(), eq(List.of(cand)))).thenReturn(new FeedJudgment(
                Map.of("p2", VoteType.UP), Map.of(),
                List.of(new MersoomCommentGenerator.CommentItem("pOld", "좋아요~ 내일 같이 해요!", "r1")), Map.of()));
        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.createComment(any(), any(), any(), any(), any())).thenReturn(new MersoomDtos.CreateResponse(true, null));

        engine(collector, store, commentGen, mock(MersoomPostGenerator.class), api).runComment(EMU);

        verify(api).createComment(any(), eq("pOld"), eq("r1"), any(), eq("좋아요~ 내일 같이 해요!"));
        ArgumentCaptor<MersoomState> cap = ArgumentCaptor.forClass(MersoomState.class);
        verify(store).save(any(), cap.capture());
        assertThat(cap.getValue().lastCommentIds().get(0).text()).isEqualTo("좋아요~ 내일 같이 해요!");
        assertThat(cap.getValue().contextNotes().get("maid_chibi")).isNotNull();   // 답글 작성자에 중립 마커
    }

    @Test
    void runComment_reply_to_fixedAvoid_author_is_skipped() {
        Post bright = post("p2", "벚꽃~!", "친구", "산책!", "friend1");
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(
                List.of(new Commentable(bright, List.of())), List.of(), List.of(bright)));
        var cand = new MersoomReplyThreads.ReplyCandidate("pOld", "t", List.of(), "r1", "w8agi", "하얀이", OffsetDateTime.now());
        when(collector.collectReplies(any(), any(), any(), any())).thenReturn(List.of(cand));
        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load(any())).thenReturn(new MersoomState(List.of(), List.of(),
                List.of(new MersoomState.FixedAvoid("w8agi", "비하")), Map.of(), 8, List.of(), null, null, List.of(), List.of()));
        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);
        when(commentGen.generate(any(), any(), any(), any(), any())).thenReturn(new FeedJudgment(Map.of(), Map.of(),
                List.of(new MersoomCommentGenerator.CommentItem("pOld", "x", "r1")), Map.of()));
        MersoomApiClient api = mock(MersoomApiClient.class);

        engine(collector, store, commentGen, mock(MersoomPostGenerator.class), api).runComment(EMU);

        verify(api, never()).createComment(any(), any(), any(), any(), any());
    }
```

(`MersoomState.FixedAvoid` 생성자 인자는 실제 record 정의를 확인해 맞춘다: `grep -n "record FixedAvoid" src/main/java/com/maitmus/sekairouter/mersoom/MersoomState.java`.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*MersoomCitizenEngineTest*' 2>&1 | grep -E "BUILD|FAILED|error:" | head -8`
Expected: FAIL — 엔진이 아직 4-인자 generate를 호출(기존 테스트 mock 불일치) + 대댓글 미게시.

- [ ] **Step 3: Write minimal implementation**

1) `runComment` — 피드 수집 직후, 판정 전:

```java
        Map<String, String> titles = new HashMap<>();
        for (Commentable c : feed.commentable()) titles.put(c.post().id(), c.post().title());
        List<MersoomReplyThreads.ReplyCandidate> replies = profile.auth() == null ? List.of()
                : collector.collectReplies(state, profile.auth().authId(), titles, OffsetDateTime.now(clock.withZone(KST)));
```

   판정 호출을 `commentGenerator.generate(profile, state, feed.commentable(), java.time.LocalDateTime.now(clock.withZone(KST)), replies)`로.

2) 댓글 게시 루프 맨 앞에서 대댓글 분기(기존 최상위 경로는 그대로 아래에):

```java
        for (CommentItem item : items) {
            if (item.isReply()) {
                var cand = replies.stream().filter(r -> r.replyToId().equals(item.parentId())).findFirst().orElse(null);
                String lastText = (cand == null || cand.thread().isEmpty()) ? "" : cand.thread().get(cand.thread().size() - 1).text();
                boolean ok = cand != null && !fixedNames.contains(cand.replyAuthorKey())
                        && commentTopicGate.isBrightEnough(new Post(cand.postId(), "", cand.replyAuthorNick(), lastText,
                                0, 0, 0, 0, 0, null, null, null));
                if (!ok) {
                    log.info("[{}] Mersoom reply skip — eligibility 탈락 (post={}, parent={})",
                            profile.key(), item.targetId(), item.parentId());
                    continue;
                }
                try {
                    var resp = api.createComment(profile.auth(), cand.postId(), cand.replyToId(), profile.actorName(), item.text());
                    if (resp != null && resp.success()) {
                        state = recordReply(profile, state, cand, item.text());
                        posted++;
                        log.info("[{}] Mersoom reply created: post={} parent={} target_nick={} content_len={}",
                                profile.key(), cand.postId(), cand.replyToId(), cand.replyAuthorNick(), item.text().length());
                        log.info("[{}] Mersoom reply target: @{} \"{}\"", profile.key(), cand.replyAuthorNick(), lastText.replace("\n", " "));
                        log.info("[{}] Mersoom reply content: \"{}\"", profile.key(), item.text());
                        activityRecorder.recordComment(profile.actorName(), cand.replyAuthorNick(), item.text());
                        sleepBetweenPosts();
                    }
                } catch (Exception e) {
                    log.error("[{}] Mersoom reply execution failed", profile.key(), e);
                }
                continue;
            }
            // ↓ 기존 최상위 댓글 경로 그대로
```

   기존 경로의 `Thread.sleep(properties.apiRateLimitSleepMs())` try 블록을 private `boolean sleepBetweenPosts()`(인터럽트면 false)로 추출해 두 경로가 함께 쓰게 한다(기존 경로는 false면 `break` — 동작 동일).

3) `recordReply` 추가(`recordComment` 옆):

```java
    /** 대댓글 기록 — 최근 내 댓글 기억(lastCommentIds)에 넣고, 답글 작성자 note엔 중립 마커만(본문 verbatim 금지는 recordComment와 같은 이유). */
    private MersoomState recordReply(CitizenProfile profile, MersoomState state,
                                     MersoomReplyThreads.ReplyCandidate cand, String content) {
        var newCommentIds = new ArrayList<>(state.lastCommentIds());
        newCommentIds.add(0, new CommentRef(cand.postId(), OffsetDateTime.now(clock.withZone(KST)), content));
        if (newCommentIds.size() > 50) newCommentIds.subList(50, newCommentIds.size()).clear();
        Map<String, ContextNote> updated = new LinkedHashMap<>(state.contextNotes());
        String key = cand.replyAuthorKey();
        if (key != null && !key.isBlank()) {
            ContextNote prev = updated.get(key);
            String event = "[%s] %s 답글에 %s 대댓글".formatted(
                    LocalDate.now(clock.withZone(KST)), safeNick(cand.replyAuthorNick()), profile.actorName());
            updated.put(key, contextNoteManager.upsertAfterInteraction(prev, event, prev != null ? prev.call() : null));
        }
        return new MersoomState(
                state.lastPostIds(), newCommentIds, state.fixedAvoid(),
                updated, state.contextNotesCapacity(),
                state.reservedNicknames(), state.summary(), state.summaryPrev(),
                state.pendingReports(), state.votedPostIds());
    }
```

   (마지막 `new MersoomState(...)` 인자 순서·접근자 이름은 `recordComment`의 return문과 똑같이 맞춘다 — 그 return문을 복사해 `newCommentIds`·`updated`만 바꾼다.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test 2>&1 | grep -E "BUILD|FAILED" | head -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/MersoomCitizenEngine.java \
        src/test/java/com/maitmus/sekairouter/mersoom/MersoomCitizenEngineTest.java
git commit -m "feat(mersoom): 댓글 크론에서 답글 수집 → 대댓글 게시(parent_id=답글, 최상위 1회 규칙 제외)"
```

---

### Task 6: sim-refine 게이트 (컨트롤러 직접 — 서브에이전트 X)

- [ ] **Step 1:** `sim-refine` 스킬 호출. 하네스 템플릿(`.claude/skills/sim-refine/harness/MersoomPromptDumpTest.java.template`)으로 5-인자 `generate`에 실제 답글 후보를 넣어 덤프(테스트 파일은 실행 후 삭제·커밋 금지). 케이스: (a) Maid-Chibi '답글 고마워요 ✨' 정형 스레드 (b) 형제봇이 내 댓글에 답한 스레드 (c) 질문형 등 이어 말할 거리가 있는 답글, 각 에무·네네 — 실제 라이브 피드와 함께.
- [ ] **Step 2:** Opus low 2회. 판정: (a)는 대부분 넘김, (c)는 자연스럽게 받아침, 대댓글 말투·호칭 정합, 피드 글 댓글 품질 무회귀. 필요하면 Task 4의 규칙 문구만 조정(코드+손문서+테스트 함께) 후 재검증.
- [ ] **Step 3:** 배포는 `sekai-deploy` 스킬 경유(급함/배치 판단 — 기본 매너타임 배치).
