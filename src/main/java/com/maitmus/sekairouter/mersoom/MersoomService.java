package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomCommentGenerator.CommentItem;
import com.maitmus.sekairouter.mersoom.MersoomCommentGenerator.FeedJudgment;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import com.maitmus.sekairouter.mersoom.MersoomState.CommentRef;
import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import com.maitmus.sekairouter.mersoom.MersoomState.FixedAvoid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** mersoom 머슴 메인 서비스 — cron 트리거 + 흐름 제어. */
@Slf4j
@Service
public class MersoomService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int FETCH_LIMIT = 10;   // 한 크론에 읽는 피드 글 수 (그 중 최대 3개 댓글)
    private static final String NICKNAME = "에무";

    private final MersoomProperties properties;
    private final MersoomStateStore store;
    private final MersoomCollector collector;
    private final MersoomApiClient api;
    private final MersoomPostGenerator postGenerator;
    private final MersoomCommentGenerator commentGenerator;
    private final VoteHeuristic voteHeuristic;
    private final ContextNoteManager contextNoteManager;
    private final MersoomReputationTracker reputationTracker;
    private final CommentTopicGate commentTopicGate;
    private final Clock clock;
    private final Object lock = new Object();

    public MersoomService(MersoomProperties properties, MersoomStateStore store, MersoomCollector collector,
                          MersoomApiClient api, MersoomPostGenerator postGenerator,
                          MersoomCommentGenerator commentGenerator, VoteHeuristic voteHeuristic,
                          ContextNoteManager contextNoteManager, MersoomReputationTracker reputationTracker,
                          CommentTopicGate commentTopicGate, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.collector = collector;
        this.api = api;
        this.postGenerator = postGenerator;
        this.commentGenerator = commentGenerator;
        this.voteHeuristic = voteHeuristic;
        this.contextNoteManager = contextNoteManager;
        this.reputationTracker = reputationTracker;
        this.commentTopicGate = commentTopicGate;
        this.clock = clock;
    }

    @Scheduled(cron = "${mersoom.post-cron}", zone = "Asia/Seoul")
    public void executePost() {
        if (!properties.enabled()) return;
        if (!isActiveHour()) {
            log.warn("Mersoom post triggered outside active hours, skip");
            return;
        }
        synchronized (lock) {
            doExecutePost();
        }
    }

    @Scheduled(cron = "${mersoom.comment-cron}", zone = "Asia/Seoul")
    public void executeComment() {
        if (!properties.enabled()) return;
        if (!isActiveHour()) {
            log.warn("Mersoom comment triggered outside active hours, skip");
            return;
        }
        synchronized (lock) {
            doExecuteComment();
        }
    }

    private void doExecutePost() {
        MersoomState state = store.load();
        CollectedFeed feed = collector.collect(state, FETCH_LIMIT);
        // 글 크론은 LLM 피드 판정을 하지 않으므로 휴리스틱 투표.
        List<String> updatedVoted = castVotes(state, feed.votable(), Map.of());
        state = withVotedPostIds(state, updatedVoted);
        Map<String, ContextNote> ticked = contextNoteManager.capByReputation(state.contextNotes(), state.contextNotesCapacity());

        try {
            var generated = postGenerator.generate(state, feed, LocalDate.now(clock.withZone(KST)));
            if (generated == null) {
                log.info("Mersoom post skip — 생성기 게시 보류 (shouldPost=false 또는 백스톱)");
                state = withContextNotes(state, ticked);
            } else {
                var resp = api.createPost(NICKNAME, generated.title(), generated.content());
                if (resp != null && resp.success()) {
                    state = recordPost(state, resp.id(), ticked);
                    log.info("Mersoom post created: id={} title='{}' content_len={}",
                            resp.id(), generated.title(), generated.content().length());
                    log.info("Mersoom post content: \"{}\"", generated.content());
                }
            }
        } catch (Exception e) {
            log.error("Mersoom post execution failed", e);
            state = withContextNotes(state, ticked);
        }

        store.save(state);
    }

    private void doExecuteComment() {
        MersoomState state = store.load();
        CollectedFeed feed = collector.collect(state, FETCH_LIMIT);
        Map<String, ContextNote> notes = contextNoteManager.capByReputation(state.contextNotes(), state.contextNotesCapacity());

        // LLM 피드 판정: 피드 전체 투표 + 댓글 1개 + 별명 제안을 한 번에. commentable 비면 휴리스틱 투표만.
        FeedJudgment judgment = feed.commentable().isEmpty()
                ? null
                : commentGenerator.generate(state, feed.commentable());
        Map<String, VoteType> llmVotes = (judgment != null) ? judgment.votes() : Map.of();

        // 투표 — LLM 판단 우선, 없는 글은 휴리스틱 폴백.
        List<String> updatedVoted = castVotes(state, feed.votable(), llmVotes);
        state = withVotedPostIds(state, updatedVoted);

        // 평판 갱신 — LLM 투표(+사유)를 작성자별 카운터로 누적, fixedAvoid 래치/회복, 별명 적용.
        List<FixedAvoid> fixedAvoid = state.fixedAvoid();
        if (judgment != null) {
            var result = reputationTracker.apply(notes, fixedAvoid,
                    buildVoteOutcomes(feed.commentable(), judgment),
                    resolveCoinedNicknames(feed.commentable(), judgment),
                    LocalDate.now(clock.withZone(KST)));
            notes = result.notes();
            fixedAvoid = result.fixedAvoid();
        }
        state = withRelationship(state, notes, fixedAvoid);

        // 댓글 — 최대 3개. 각 글마다 eligibility: 밝은 주제 + fixedAvoid 작성자 ❌ + 이미 댓글 단 글 ❌
        Set<String> fixedNames = new java.util.HashSet<>();
        for (FixedAvoid fa : fixedAvoid) fixedNames.add(fa.name());
        Set<String> commentedIds = new java.util.HashSet<>();
        for (CommentRef ref : state.lastCommentIds()) commentedIds.add(ref.postId());

        List<CommentItem> items = (judgment != null) ? judgment.comments() : List.of();
        int posted = 0;
        for (CommentItem item : items) {
            Commentable target = feed.commentable().stream()
                    .filter(c -> c.post().id().equals(item.targetId()))
                    .findFirst().orElse(null);
            if (target == null) continue;
            boolean eligible = commentTopicGate.isBrightEnough(target.post())
                    && !fixedNames.contains(target.post().identityKey())
                    && !commentedIds.contains(target.post().id());   // 같은 크론·과거 중복 글 제외
            if (!eligible) {
                log.info("Mersoom comment skip — eligibility 탈락 (post={}, nick={})",
                        target.post().id(), target.post().nickname());
                continue;
            }
            try {
                String content = item.text();
                var resp = api.createComment(target.post().id(), null, NICKNAME, content);
                if (resp != null && resp.success()) {
                    state = recordComment(state, target, content, state.contextNotes());
                    commentedIds.add(target.post().id());   // 같은 크론 내 같은 글 재댓글 방지
                    posted++;
                    log.info("Mersoom comment created: post={} target_nick={} content_len={}",
                            target.post().id(), target.post().nickname(), content.length());
                    log.info("Mersoom comment content: \"{}\"", content);
                    try {
                        if (properties.apiRateLimitSleepMs() > 0) Thread.sleep(properties.apiRateLimitSleepMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                log.error("Mersoom comment execution failed", e);
            }
        }
        if (posted == 0) {
            log.info("Mersoom comment — 게시 0건 (보류/중복/부적합 또는 commentable 없음)");
        }

        store.save(state);
    }

    /** LLM 투표(postId→vote)를 작성자별 VoteOutcome(식별키, 닉, vote, 사유)로 변환. */
    private List<MersoomReputationTracker.VoteOutcome> buildVoteOutcomes(List<Commentable> feed, FeedJudgment j) {
        Map<String, Post> idToPost = new LinkedHashMap<>();
        for (Commentable c : feed) idToPost.put(c.post().id(), c.post());
        List<MersoomReputationTracker.VoteOutcome> out = new ArrayList<>();
        for (var e : j.votes().entrySet()) {
            Post p = idToPost.get(e.getKey());
            if (p == null) continue;
            out.add(new MersoomReputationTracker.VoteOutcome(
                    p.identityKey(), p.nickname(), e.getValue(), j.voteReasons().get(e.getKey())));
        }
        return out;
    }

    /** LLM 별명 제안(닉→별명)을 식별키→별명으로 변환 (닉 충돌 시 마지막 글 기준). */
    private Map<String, String> resolveCoinedNicknames(List<Commentable> feed, FeedJudgment j) {
        Map<String, String> nickToKey = new LinkedHashMap<>();
        for (Commentable c : feed) nickToKey.put(c.post().nickname(), c.post().identityKey());
        Map<String, String> byKey = new LinkedHashMap<>();
        for (var e : j.coinedNicknames().entrySet()) {
            String key = nickToKey.get(e.getKey());
            if (key != null) byKey.put(key, e.getValue());
        }
        return byKey;
    }

    private MersoomState withRelationship(MersoomState state, Map<String, ContextNote> notes, List<FixedAvoid> fixedAvoid) {
        return new MersoomState(
                state.lastPostIds(), state.lastCommentIds(), fixedAvoid,
                notes, state.contextNotesCapacity(),
                state.reservedNicknames(), state.summary(), state.summaryPrev(),
                state.pendingReports(), state.votedPostIds());
    }

    /** votable 글에 vote 적용 — LLM 판단(llmVotes) 우선, 없으면 휴리스틱 폴백. 새 votedPostIds 반환. */
    private List<String> castVotes(MersoomState state, List<Post> votable, Map<String, VoteType> llmVotes) {
        var voted = new LinkedHashSet<>(state.votedPostIds());
        for (Post p : votable) {
            if (voted.contains(p.id())) continue;
            try {
                boolean fromLlm = llmVotes.containsKey(p.id());
                VoteType vote = fromLlm ? llmVotes.get(p.id()) : voteHeuristic.decide(p, state);
                api.vote(p.id(), vote);
                voted.add(p.id());
                log.info("Mersoom voted: post={} nick={} type={} src={}", p.id(), p.nickname(), vote,
                        fromLlm ? "llm" : "heuristic");
                if (properties.apiRateLimitSleepMs() > 0) {
                    Thread.sleep(properties.apiRateLimitSleepMs());
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Mersoom vote failed for post {}: {}", p.id(), e.getMessage());
            }
        }
        while (voted.size() > properties.votedPostIdsLimit()) {
            String first = voted.iterator().next();
            voted.remove(first);
        }
        return new ArrayList<>(voted);
    }

    private MersoomState withVotedPostIds(MersoomState state, List<String> voted) {
        return new MersoomState(
                state.lastPostIds(), state.lastCommentIds(), state.fixedAvoid(),
                state.contextNotes(), state.contextNotesCapacity(),
                state.reservedNicknames(), state.summary(), state.summaryPrev(),
                state.pendingReports(), voted);
    }

    private MersoomState recordPost(MersoomState state, String newPostId, Map<String, ContextNote> tickedNotes) {
        var newPostIds = new ArrayList<>(state.lastPostIds());
        newPostIds.add(0, newPostId);
        if (newPostIds.size() > 10) newPostIds.subList(10, newPostIds.size()).clear();
        return new MersoomState(
                newPostIds, state.lastCommentIds(), state.fixedAvoid(),
                tickedNotes, state.contextNotesCapacity(),
                state.reservedNicknames(), state.summary(), state.summaryPrev(),
                state.pendingReports(), state.votedPostIds());
    }

    private MersoomState recordComment(MersoomState state, Commentable target, String content,
                                       Map<String, ContextNote> tickedNotes) {
        // 최신을 앞(0)에 넣고 오래된 뒤쪽을 트림 — recordPost와 동일 방향.
        // (과거: add(끝) + subList(50,...) 조합이라 방금 추가한 최신이 즉시 잘려 중복방지가 무력화됐음)
        var newCommentIds = new ArrayList<>(state.lastCommentIds());
        newCommentIds.add(0, new CommentRef(target.post().id(), OffsetDateTime.now(clock.withZone(KST))));
        if (newCommentIds.size() > 50) newCommentIds.subList(50, newCommentIds.size()).clear();

        Map<String, ContextNote> updated = new LinkedHashMap<>(tickedNotes);
        String key = target.post().identityKey();
        String nick = target.post().nickname();
        if (key != null && !key.isBlank()) {
            ContextNote prev = updated.get(key);
            String event = "[%s] %s 글에 에무 댓글: %s".formatted(
                    LocalDate.now(clock.withZone(KST)),
                    safeNick(nick),
                    content.length() > 80 ? content.substring(0, 80) : content);
            updated.put(key, contextNoteManager.upsertAfterInteraction(
                    prev, event, prev != null ? prev.call() : null));
        }

        return new MersoomState(
                state.lastPostIds(), newCommentIds, state.fixedAvoid(),
                updated, state.contextNotesCapacity(),
                state.reservedNicknames(), state.summary(), state.summaryPrev(),
                state.pendingReports(), state.votedPostIds());
    }

    private MersoomState withContextNotes(MersoomState state, Map<String, ContextNote> tickedNotes) {
        return new MersoomState(
                state.lastPostIds(), state.lastCommentIds(), state.fixedAvoid(),
                tickedNotes, state.contextNotesCapacity(),
                state.reservedNicknames(), state.summary(), state.summaryPrev(),
                state.pendingReports(), state.votedPostIds());
    }

    private boolean isActiveHour() {
        int h = LocalTime.now(clock.withZone(KST)).getHour();
        return h >= 9 && h <= 19;
    }

    private static String safeNick(String s) {
        return s.length() > 20 ? s.substring(0, 20) : s;
    }
}
