package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import com.maitmus.sekairouter.mersoom.MersoomState.CommentRef;
import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final RelationshipPromoter relationshipPromoter;
    private final Clock clock;
    private final AtomicBoolean reentryPending = new AtomicBoolean(false);
    private final Object lock = new Object();

    public MersoomService(MersoomProperties properties, MersoomStateStore store, MersoomCollector collector,
                          MersoomApiClient api, MersoomPostGenerator postGenerator,
                          MersoomCommentGenerator commentGenerator, VoteHeuristic voteHeuristic,
                          ContextNoteManager contextNoteManager, RelationshipPromoter relationshipPromoter,
                          Clock clock) {
        this.properties = properties;
        this.store = store;
        this.collector = collector;
        this.api = api;
        this.postGenerator = postGenerator;
        this.commentGenerator = commentGenerator;
        this.voteHeuristic = voteHeuristic;
        this.contextNoteManager = contextNoteManager;
        this.relationshipPromoter = relationshipPromoter;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleReentryIfNeeded() {
        if (!properties.enabled()) return;
        try {
            MersoomState state = store.load();
            if (state.lastPostIds().isEmpty()) return;
            Path marker = Paths.get(properties.reentryMarker());
            if (Files.exists(marker)) return;
            log.info("Mersoom re-entry pending — 다음 post-cron에서 첫 글 작성 예정");
            reentryPending.set(true);
        } catch (Exception e) {
            log.warn("Re-entry check failed", e);
        }
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
        boolean isReentry = reentryPending.compareAndSet(true, false);

        MersoomState state = store.load();
        CollectedFeed feed = collector.collect(state, FETCH_LIMIT);
        List<String> updatedVoted = castVotes(state, feed.votable());
        state = withVotedPostIds(state, updatedVoted);
        Map<String, ContextNote> ticked = contextNoteManager.tickAndPrune(state.contextNotes());

        try {
            var generated = postGenerator.generate(state, feed,
                    LocalDate.now(clock.withZone(KST)), isReentry);
            var resp = api.createPost(NICKNAME, generated.title(), generated.content());
            if (resp != null && resp.success()) {
                state = recordPost(state, resp.id(), ticked);
                log.info("Mersoom post created: id={} title='{}' content_len={} (reentry={})",
                        resp.id(), generated.title(), generated.content().length(), isReentry);
                log.info("Mersoom post content: \"{}\"", generated.content());
            }
        } catch (Exception e) {
            log.error("Mersoom post execution failed", e);
            state = withContextNotes(state, ticked);
        }

        state = relationshipPromoter.evaluate(state);
        store.save(state);

        if (isReentry) {
            try {
                Path marker = Paths.get(properties.reentryMarker());
                if (marker.getParent() != null) {
                    Files.createDirectories(marker.getParent());
                }
                Files.createFile(marker);
                log.info("Mersoom re-entry marker created: {}", marker);
            } catch (IOException e) {
                log.warn("Failed to create reentry marker", e);
            }
        }
    }

    private void doExecuteComment() {
        MersoomState state = store.load();
        CollectedFeed feed = collector.collect(state, FETCH_LIMIT);
        List<String> updatedVoted = castVotes(state, feed.votable());
        state = withVotedPostIds(state, updatedVoted);

        Map<String, ContextNote> ticked = contextNoteManager.tickAndPrune(state.contextNotes());

        if (feed.commentable().isEmpty()) {
            log.info("Mersoom comment skip — commentable empty");
            state = withContextNotes(state, ticked);
            state = relationshipPromoter.evaluate(state);
            store.save(state);
            return;
        }

        Commentable target = feed.commentable().get(0);
        try {
            String content = commentGenerator.generate(state, target);
            var resp = api.createComment(target.post().id(), null, NICKNAME, content);
            if (resp != null && resp.success()) {
                state = recordComment(state, target, content, ticked);
                log.info("Mersoom comment created: post={} target_nick={} content_len={}",
                        target.post().id(), target.post().nickname(), content.length());
                log.info("Mersoom comment content: \"{}\"", content);
            }
        } catch (Exception e) {
            log.error("Mersoom comment execution failed", e);
            state = withContextNotes(state, ticked);
        }

        state = relationshipPromoter.evaluate(state);
        store.save(state);
    }

    /** votable 글에 휴리스틱 vote 적용. 새 votedPostIds 반환 (FIFO 한도 적용). */
    private List<String> castVotes(MersoomState state, List<Post> votable) {
        var voted = new LinkedHashSet<>(state.votedPostIds());
        for (Post p : votable) {
            if (voted.contains(p.id())) continue;
            try {
                VoteType vote = voteHeuristic.decide(p, state);
                api.vote(p.id(), vote);
                voted.add(p.id());
                log.info("Mersoom voted: post={} nick={} type={}", p.id(), p.nickname(), vote);
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
                state.lastPostIds(), state.lastCommentIds(), state.friends(), state.avoid(),
                state.fixedFriends(), state.fixedAvoid(),
                state.contextNotes(), state.contextNotesMaxTtl(),
                state.reservedNicknames(), state.summary(), state.summaryPrev(),
                state.pendingReports(), voted);
    }

    private MersoomState recordPost(MersoomState state, String newPostId, Map<String, ContextNote> tickedNotes) {
        var newPostIds = new ArrayList<>(state.lastPostIds());
        newPostIds.add(0, newPostId);
        if (newPostIds.size() > 10) newPostIds.subList(10, newPostIds.size()).clear();
        return new MersoomState(
                newPostIds, state.lastCommentIds(), state.friends(), state.avoid(),
                state.fixedFriends(), state.fixedAvoid(),
                tickedNotes, state.contextNotesMaxTtl(),
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
                    prev, event, prev != null ? prev.call() : null,
                    properties.contextNotesDefaultTtl()));
        }

        return new MersoomState(
                state.lastPostIds(), newCommentIds, state.friends(), state.avoid(),
                state.fixedFriends(), state.fixedAvoid(),
                updated, state.contextNotesMaxTtl(),
                state.reservedNicknames(), state.summary(), state.summaryPrev(),
                state.pendingReports(), state.votedPostIds());
    }

    private MersoomState withContextNotes(MersoomState state, Map<String, ContextNote> tickedNotes) {
        return new MersoomState(
                state.lastPostIds(), state.lastCommentIds(), state.friends(), state.avoid(),
                state.fixedFriends(), state.fixedAvoid(),
                tickedNotes, state.contextNotesMaxTtl(),
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
