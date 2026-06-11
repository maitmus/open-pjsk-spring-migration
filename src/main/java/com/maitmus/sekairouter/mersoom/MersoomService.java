package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
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
    private static final int FETCH_LIMIT = 8;
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
                    buildVoteOutcomes(feed.commentable(), judgment), judgment.coinedNicknames(),
                    LocalDate.now(clock.withZone(KST)));
            notes = result.notes();
            fixedAvoid = result.fixedAvoid();
        }
        state = withRelationship(state, notes, fixedAvoid);

        // 댓글 eligibility: LLM 채택 + 밝은 주제 + fixedAvoid 작성자 ❌ + 이미 댓글 단 글 ❌
        Commentable target = (judgment != null && judgment.hasComment())
                ? feed.commentable().stream()
                        .filter(c -> c.post().id().equals(judgment.commentTargetId()))
                        .findFirst().orElse(null)
                : null;
        Set<String> fixedNames = new java.util.HashSet<>();
        for (FixedAvoid fa : fixedAvoid) fixedNames.add(fa.name());
        Set<String> commentedIds = new java.util.HashSet<>();
        for (CommentRef ref : state.lastCommentIds()) commentedIds.add(ref.postId());

        boolean eligible = target != null
                && commentTopicGate.isBrightEnough(target.post())
                && !fixedNames.contains(target.post().nickname())
                && !commentedIds.contains(target.post().id());

        if (eligible) {
            try {
                String content = judgment.commentText();
                var resp = api.createComment(target.post().id(), null, NICKNAME, content);
                if (resp != null && resp.success()) {
                    state = recordComment(state, target, content, state.contextNotes());
                    log.info("Mersoom comment created: post={} target_nick={} content_len={}",
                            target.post().id(), target.post().nickname(), content.length());
                    log.info("Mersoom comment content: \"{}\"", content);
                }
            } catch (Exception e) {
                log.error("Mersoom comment execution failed", e);
            }
        } else if (target != null) {
            log.info("Mersoom comment skip — eligibility 탈락 (post={}, nick={})",
                    target.post().id(), target.post().nickname());
        } else {
            log.info("Mersoom comment skip — 게시할 댓글 없음 (LLM 보류 또는 commentable 없음)");
        }

        store.save(state);
    }

    /** LLM 투표(postId→vote)를 작성자별 VoteOutcome(nick, vote, 사유)로 변환. */
    private List<MersoomReputationTracker.VoteOutcome> buildVoteOutcomes(List<Commentable> feed, FeedJudgment j) {
        Map<String, String> idToNick = new LinkedHashMap<>();
        for (Commentable c : feed) idToNick.put(c.post().id(), c.post().nickname());
        List<MersoomReputationTracker.VoteOutcome> out = new ArrayList<>();
        for (var e : j.votes().entrySet()) {
            String nick = idToNick.get(e.getKey());
            if (nick == null || nick.isBlank()) continue;
            out.add(new MersoomReputationTracker.VoteOutcome(nick, e.getValue(), j.voteReasons().get(e.getKey())));
        }
        return out;
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
        var newCommentIds = new ArrayList<>(state.lastCommentIds());
        newCommentIds.add(new CommentRef(target.post().id(), OffsetDateTime.now(clock.withZone(KST))));
        if (newCommentIds.size() > 50) newCommentIds.subList(50, newCommentIds.size()).clear();

        Map<String, ContextNote> updated = new LinkedHashMap<>(tickedNotes);
        String nick = target.post().nickname();
        if (nick != null && !nick.isBlank()) {
            ContextNote prev = updated.get(nick);
            String event = "[%s] %s 글에 에무 댓글: %s".formatted(
                    LocalDate.now(clock.withZone(KST)),
                    safeNick(nick),
                    content.length() > 80 ? content.substring(0, 80) : content);
            updated.put(nick, contextNoteManager.upsertAfterInteraction(
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
        return h >= 10 && h <= 20;
    }

    private static String safeNick(String s) {
        return s.length() > 20 ? s.substring(0, 20) : s;
    }
}
