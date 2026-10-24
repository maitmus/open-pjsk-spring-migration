package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Comment;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** /api/posts 수집 → 분류 (내 글 / 댓글 가능 / 투표 대상). */
@Slf4j
@Component
@RequiredArgsConstructor
public class MersoomCollector {

    private final MersoomApiClient api;

    public CollectedFeed collect(MersoomState state, int limit) {
        List<Post> recent = api.recentPosts(limit);
        Set<String> myPostIds = new HashSet<>(state.lastPostIds());

        // 내 글만 제외하고 전부 LLM 판정 대상(투표). fixedAvoid·이미 댓글 단 글의 '댓글 대상' 제외는
        // 후처리 eligibility에서 — fixedAvoid도 투표는 받아야 평판이 계속 집계되므로.
        // **commentsOf는 피드 글마다 호출하지 않는다(lazy)** — 크론 시작 시 글 10개의 댓글을 연타로
        // 당기면 IP 요청 스파이크 → 차단(댓글 유실)이 나서. 댓글 작성엔 본문·관계로 충분.
        List<Commentable> commentable = recent.stream()
                .filter(p -> !myPostIds.contains(p.id()))
                .map(p -> new Commentable(p, List.of()))
                .toList();

        // 내 글의 답글(reply)은 유지 — ≤3개라 호출 적고, 글 생성 시 내 글 반응 참고에 가치 큼.
        List<Commentable> myTracked = recent.stream()
                .filter(p -> myPostIds.contains(p.id()))
                .limit(3)
                .map(p -> new Commentable(p, api.commentsOf(p.id())))
                .toList();

        List<Post> votable = recent.stream()
                .filter(p -> !myPostIds.contains(p.id()))
                .toList();

        log.info("Mersoom collected: total={}, votable={}, commentable={}, my_tracked={}",
                recent.size(), votable.size(), commentable.size(), myTracked.size());

        return new CollectedFeed(commentable, myTracked, votable);
    }

    static final int REPLY_WINDOW_HOURS = 12;
    static final int REPLY_POSTS = 6;
    static final int MAX_REPLY_CANDIDATES = 4;
    static final int REPLY_MY_POSTS = 2;   // 내 최근 글(하루 4편 → 반나절치) — 남의 최상위 댓글도 후보

    private long replyFetchGapMs = 3000;   // 6/14 IP 버스트 차단 재발 방지 — 글 사이 간격

    void setReplyFetchGapMs(long ms) { this.replyFetchGapMs = ms; }

    /**
     * 내 최근 댓글이 달린 글들 + 내 최근 글 2개의 댓글 트리를 당겨, 내 댓글에 달린 답글·내 글에 달린 남의 최상위 댓글 중
     * 아직 안 받아친 것을 후보로 모은다(댓글 크론 전용).
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

        List<String> myPosts = state.lastPostIds().stream().limit(REPLY_MY_POSTS).toList();
        postIds.removeAll(myPosts);   // 내 글은 아래서 한 번에(답글 + 최상위 댓글) — 중복 조회 X

        List<MersoomReplyThreads.ReplyCandidate> all = new ArrayList<>();
        boolean first = true;
        for (String pid : myPosts) {
            if (!first && !gap()) break;
            first = false;
            try {
                Post p = api.post(pid);
                if (!gap()) break;
                List<Comment> cs = api.commentsOf(pid);
                String title = p == null ? "(내 글)" : p.title();
                all.addAll(MersoomReplyThreads.candidates(pid, title, cs, myAuthId, blocked));
                all.addAll(MersoomReplyThreads.myPostCandidates(pid, title, p == null ? null : p.content(), cs, myAuthId, blocked, since));
            } catch (Exception e) {
                log.info("Mersoom reply 수집 스킵(내 글) — post={} {}", pid, e.toString());
            }
        }
        for (String pid : postIds) {
            if (!first && !gap()) break;
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
        log.info("Mersoom replies collected: posts={}, my_posts={}, candidates={}", postIds.size(), myPosts.size(), out.size());
        return out;
    }

    /** 요청 사이 간격(IP 버스트 방지). 인터럽트면 false — 수집 중단. */
    private boolean gap() {
        if (replyFetchGapMs <= 0) return true;
        try { Thread.sleep(replyFetchGapMs); return true; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    public record Commentable(Post post, List<Comment> comments) {}
    public record CollectedFeed(List<Commentable> commentable, List<Commentable> myTracked, List<Post> votable) {}
}
