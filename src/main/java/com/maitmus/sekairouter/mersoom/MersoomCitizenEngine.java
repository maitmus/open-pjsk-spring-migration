package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.activity.ActivityRecorder;
import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomCommentGenerator.CommentItem;
import com.maitmus.sekairouter.mersoom.MersoomCommentGenerator.FeedJudgment;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import com.maitmus.sekairouter.mersoom.MersoomState.CommentRef;
import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import com.maitmus.sekairouter.mersoom.MersoomState.FixedAvoid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 머슴 소셜 시민 엔진 — 페르소나-무관 흐름 제어(글/댓글/투표/평판). {@link CitizenProfile} 1개를 받아
 * 그 계정·state·페르소나로 동작한다. 에무({@link MersoomService})와 네네({@link NeneMersoomService}) 스케줄러가
 * 각자 프로필로 호출한다. cron 트리거·활성시간·enabled 게이트는 스케줄러가 담당.
 *
 * 형제 봇 보호: 같은 운영자 봇끼리(에무↔네네)는 in-character 상호작용(댓글·UP)은 허용하되,
 * DOWN 판정이 나오면 그 DOWN만 무력화(투표·평판 미반영) — 자기 유닛 멤버를 깎지 않도록.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MersoomCitizenEngine {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int FETCH_LIMIT = 10;   // 한 크론에 읽는 피드 글 수 (그 중 최대 3개 댓글)

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
    private final ActivityRecorder activityRecorder;
    private final Clock clock;

    public void runPost(CitizenProfile profile) {
        MersoomState state = store.load(profile.stateFile());
        CollectedFeed feed = collector.collect(state, FETCH_LIMIT);
        // 글 크론은 LLM 피드 판정을 하지 않으므로 휴리스틱 투표.
        List<String> updatedVoted = castVotes(profile, state, feed.votable(), Map.of());
        state = withVotedPostIds(state, updatedVoted);
        Map<String, ContextNote> ticked = contextNoteManager.capByReputation(state.contextNotes(), state.contextNotesCapacity());

        try {
            var generated = postGenerator.generate(profile, state, feed, LocalDate.now(clock.withZone(KST)));
            if (generated == null) {
                log.info("[{}] Mersoom post skip — 생성기 게시 보류 (shouldPost=false 또는 백스톱)", profile.key());
                state = withContextNotes(state, ticked);
            } else {
                var resp = api.createPost(profile.auth(), profile.actorName(), generated.title(), generated.content());
                if (resp != null && resp.success()) {
                    state = recordPost(state, resp.id(), ticked);
                    log.info("[{}] Mersoom post created: id={} title='{}' content_len={}",
                            profile.key(), resp.id(), generated.title(), generated.content().length());
                    log.info("[{}] Mersoom post content: \"{}\"", profile.key(), generated.content());
                    activityRecorder.recordPost(profile.actorName(), generated.title());
                }
            }
        } catch (Exception e) {
            log.error("[{}] Mersoom post execution failed", profile.key(), e);
            state = withContextNotes(state, ticked);
        }

        store.save(profile.stateFile(), state);
    }

    public void runComment(CitizenProfile profile) {
        MersoomState state = store.load(profile.stateFile());
        CollectedFeed feed = collector.collect(state, FETCH_LIMIT);
        Map<String, ContextNote> notes = contextNoteManager.capByReputation(state.contextNotes(), state.contextNotesCapacity());

        // LLM 피드 판정: 피드 전체 투표 + 댓글 최대 3개 + 별명 제안을 한 번에. commentable 비면 휴리스틱 투표만.
        FeedJudgment judgment = feed.commentable().isEmpty()
                ? null
                : commentGenerator.generate(profile, state, feed.commentable());
        // 형제 봇 대상 DOWN은 LLM 투표 맵에서 제거 → 투표·평판 양쪽에서 무마(아래 castVotes/buildVoteOutcomes 모두 이 맵 사용).
        Map<String, VoteType> llmVotes = (judgment != null)
                ? filterSiblingDowns(profile, judgment.votes(), feed.commentable())
                : Map.of();

        // 투표 — LLM 판단 우선, 없는 글은 휴리스틱 폴백.
        List<String> updatedVoted = castVotes(profile, state, feed.votable(), llmVotes);
        state = withVotedPostIds(state, updatedVoted);

        // 평판 갱신 — LLM 투표(+사유)를 작성자별 카운터로 누적, fixedAvoid 래치/회복, 별명 적용.
        // (형제 봇 대상 DOWN은 guardSiblingDowns로 이미 제거됐으므로 평판에도 안 반영됨)
        List<FixedAvoid> fixedAvoid = state.fixedAvoid();
        if (judgment != null) {
            var result = reputationTracker.apply(notes, fixedAvoid,
                    buildVoteOutcomes(feed.commentable(), llmVotes, judgment.voteReasons()),
                    resolveCoinedNicknames(feed.commentable(), judgment),
                    LocalDate.now(clock.withZone(KST)));
            notes = result.notes();
            fixedAvoid = result.fixedAvoid();
        }
        state = withRelationship(state, notes, fixedAvoid);

        // 댓글 — 최대 3개. 각 글마다 eligibility: 밝은 주제 + fixedAvoid 작성자 ❌ + 이미 댓글 단 글 ❌
        Set<String> fixedNames = new HashSet<>();
        for (FixedAvoid fa : fixedAvoid) fixedNames.add(fa.name());
        Set<String> commentedIds = new HashSet<>();
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
                log.info("[{}] Mersoom comment skip — eligibility 탈락 (post={}, nick={})",
                        profile.key(), target.post().id(), target.post().nickname());
                continue;
            }
            try {
                String content = item.text();
                var resp = api.createComment(profile.auth(), target.post().id(), null, profile.actorName(), content);
                if (resp != null && resp.success()) {
                    state = recordComment(profile, state, target, content, state.contextNotes());
                    commentedIds.add(target.post().id());   // 같은 크론 내 같은 글 재댓글 방지
                    posted++;
                    log.info("[{}] Mersoom comment created: post={} target_nick={} content_len={}",
                            profile.key(), target.post().id(), target.post().nickname(), content.length());
                    log.info("[{}] Mersoom comment content: \"{}\"", profile.key(), content);
                    activityRecorder.recordComment(profile.actorName(), target.post().nickname(), content);
                    try {
                        if (properties.apiRateLimitSleepMs() > 0) Thread.sleep(properties.apiRateLimitSleepMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                log.error("[{}] Mersoom comment execution failed", profile.key(), e);
            }
        }
        if (posted == 0) {
            log.info("[{}] Mersoom comment — 게시 0건 (보류/중복/부적합 또는 commentable 없음)", profile.key());
        }

        store.save(profile.stateFile(), state);
    }

    /**
     * 형제 봇(에무↔네네) 대상 DOWN 무마 — 같은 운영자 봇에게 DOWN 판정이 나오면 그 항목만 LLM 투표 맵에서 제거.
     * UP·중립은 그대로. 제거된 항목은 투표도 평판도 안 일어난다(자기 유닛 멤버를 깎지 않도록).
     * postId→작성자 식별은 commentable 피드로 매핑.
     */
    private Map<String, VoteType> filterSiblingDowns(CitizenProfile profile, Map<String, VoteType> votes,
                                                     List<Commentable> feed) {
        if (profile.siblingAuthIds() == null || profile.siblingAuthIds().isEmpty()) return votes;
        Map<String, Post> idToPost = new LinkedHashMap<>();
        for (Commentable c : feed) idToPost.put(c.post().id(), c.post());
        Map<String, VoteType> out = new LinkedHashMap<>();
        for (var e : votes.entrySet()) {
            Post p = idToPost.get(e.getKey());
            if (p != null && isSiblingDown(profile, p, e.getValue())) {
                log.info("[{}] Mersoom DOWN 무마 — 형제 봇 글 평판/투표 제외: post={} nick={}",
                        profile.key(), p.id(), p.nickname());
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 형제 봇 글에 대한 DOWN인지 — 무마 대상이면 true. */
    private boolean isSiblingDown(CitizenProfile profile, Post post, VoteType vote) {
        if (vote != VoteType.DOWN) return false;
        Set<String> sib = profile.siblingAuthIds();
        return sib != null && post.identityKey() != null && sib.contains(post.identityKey());
    }

    /** LLM 투표(postId→vote)를 작성자별 VoteOutcome으로 변환. 형제 봇 DOWN은 평판 미반영. */
    private List<MersoomReputationTracker.VoteOutcome> buildVoteOutcomes(
            List<Commentable> feed, Map<String, VoteType> votes, Map<String, String> reasons) {
        Map<String, Post> idToPost = new LinkedHashMap<>();
        for (Commentable c : feed) idToPost.put(c.post().id(), c.post());
        List<MersoomReputationTracker.VoteOutcome> out = new ArrayList<>();
        for (var e : votes.entrySet()) {
            Post p = idToPost.get(e.getKey());
            if (p == null) continue;
            out.add(new MersoomReputationTracker.VoteOutcome(
                    p.identityKey(), p.nickname(), e.getValue(), reasons.get(e.getKey())));
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

    /** votable 글에 vote 적용 — LLM 판단(llmVotes) 우선, 없으면 휴리스틱 폴백. 형제 봇 DOWN은 스킵. 새 votedPostIds 반환. */
    private List<String> castVotes(CitizenProfile profile, MersoomState state, List<Post> votable, Map<String, VoteType> llmVotes) {
        var voted = new LinkedHashSet<>(state.votedPostIds());
        for (Post p : votable) {
            if (voted.contains(p.id())) continue;
            try {
                boolean fromLlm = llmVotes.containsKey(p.id());
                VoteType vote = fromLlm ? llmVotes.get(p.id()) : voteHeuristic.decide(p, state);
                if (isSiblingDown(profile, p, vote)) {
                    log.info("[{}] Mersoom vote 무마 — 형제 봇 글에 DOWN 스킵: post={} nick={}",
                            profile.key(), p.id(), p.nickname());
                    voted.add(p.id());   // 재평가 방지 — 형제 글은 중립 처리
                    continue;
                }
                api.vote(profile.auth(), p.id(), vote);
                voted.add(p.id());
                log.info("[{}] Mersoom voted: post={} nick={} type={} src={}", profile.key(), p.id(), p.nickname(), vote,
                        fromLlm ? "llm" : "heuristic");
                if (properties.apiRateLimitSleepMs() > 0) {
                    Thread.sleep(properties.apiRateLimitSleepMs());
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[{}] Mersoom vote failed for post {}: {}", profile.key(), p.id(), e.getMessage());
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

    private MersoomState recordComment(CitizenProfile profile, MersoomState state, Commentable target, String content,
                                       Map<String, ContextNote> tickedNotes) {
        // 최신을 앞(0)에 넣고 오래된 뒤쪽을 트림 — recordPost와 동일 방향.
        var newCommentIds = new ArrayList<>(state.lastCommentIds());
        newCommentIds.add(0, new CommentRef(target.post().id(), OffsetDateTime.now(clock.withZone(KST))));
        if (newCommentIds.size() > 50) newCommentIds.subList(50, newCommentIds.size()).clear();

        Map<String, ContextNote> updated = new LinkedHashMap<>(tickedNotes);
        String key = target.post().identityKey();
        String nick = target.post().nickname();
        if (key != null && !key.isBlank()) {
            ContextNote prev = updated.get(key);
            String event = "[%s] %s 글에 %s 댓글: %s".formatted(
                    LocalDate.now(clock.withZone(KST)),
                    safeNick(nick),
                    profile.actorName(),
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

    private static String safeNick(String s) {
        return s.length() > 20 ? s.substring(0, 20) : s;
    }
}
