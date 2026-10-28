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
import java.util.HashMap;
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
    private static final int FETCH_LIMIT = 10;   // 한 크론에 읽는 피드 글 수 (그 중 최대 MAX_COMMENTS개 댓글)

    private final MersoomProperties properties;
    private final MersoomStateStore store;
    private final MersoomCollector collector;
    private final MersoomApiClient api;
    private final MersoomPostGenerator postGenerator;
    private final MersoomAdGenerator adGenerator;
    private final MersoomCommentGenerator commentGenerator;
    private final ContextNoteManager contextNoteManager;
    private final MersoomReputationTracker reputationTracker;
    private final CommentTopicGate commentTopicGate;
    private final ActivityRecorder activityRecorder;
    private final SharedEventMemory sharedEvents;
    private final MersoomFilterRephraser filterRephraser;
    private final Clock clock;

    public void runPost(CitizenProfile profile) {
        MersoomState state = store.load(profile.stateFile());
        CollectedFeed feed = collector.collect(state, FETCH_LIMIT);
        // 글 크론은 LLM 피드 판정을 하지 않는다. 공개 투표는 폐지됐으므로 votable을 처리 마커로만 누적.
        List<String> updatedVoted = castVotes(state, feed.votable());
        state = withVotedPostIds(state, updatedVoted);
        Map<String, ContextNote> ticked = contextNoteManager.capByReputation(state.contextNotes(), state.contextNotesCapacity());

        try {
            // 이벤트 힌트·형제 공유 일정은 하루 첫 글 슬롯(오전 10:00·10:30)에만 — 이후 글엔 축하·일정 꼬리를 안 붙이게
            // (공유 일정을 매 글에 주자 10-28 sim에서 에무 글 8/8 끝에 '할로윈 쇼 사흘 뒤'가 붙었다).
            boolean eventSlot = java.time.LocalTime.now(clock.withZone(KST)).getHour() < 12;
            var generated = postGenerator.generate(profile, state, feed, LocalDate.now(clock.withZone(KST)), eventSlot,
                    eventSlot ? sharedEvents.postSection(profile) : "");
            if (generated == null) {
                log.info("[{}] Mersoom post skip — 생성기 게시 보류 (shouldPost=false 또는 백스톱)", profile.key());
                state = withContextNotes(state, ticked);
            } else {
                String title = generated.title();
                String content = generated.content();
                MersoomDtos.CreateResponse resp;
                try {
                    resp = api.createPost(profile.auth(), profile.actorName(), title, content);
                } catch (RuntimeException e) {
                    String word = MersoomFilterRephraser.flaggedWord(e);
                    if (word == null) throw e;
                    // 걸린 칸만 재작성 — 제목에 없으면 본문(서버 정규화를 모르니 둘 다 없어 보여도 본문)
                    boolean inTitle = MersoomFilterRephraser.containsLoosely(title, word);
                    if (inTitle) title = filterRephraser.rephrase(profile, "제목", title, word);
                    if (!inTitle || MersoomFilterRephraser.containsLoosely(content, word)) {
                        content = filterRephraser.rephrase(profile, "글", content, word);
                    }
                    final String t = title, c = content;
                    resp = (t == null || c == null)
                            ? giveUpFiltered(profile, "글", word, generated.title() + " / " + generated.content())
                            : retryFiltered(profile, "글", word, () -> api.createPost(profile.auth(), profile.actorName(), t, c));
                }
                if (resp != null && resp.success()) {
                    state = recordPost(state, resp.id(), ticked);
                    log.info("[{}] Mersoom post created: id={} title='{}' content_len={}",
                            profile.key(), resp.id(), title, content.length());
                    log.info("[{}] Mersoom post content: \"{}\"", profile.key(), content);
                    activityRecorder.recordPost(profile.actorName(), title);
                    upvoteBySibling(profile, resp.id());
                    sharedEvents.observe(profile, resp.id(), "글", "",
                            "제목: " + title + "\n본문: " + content);
                }
            }
        } catch (Exception e) {
            log.error("[{}] Mersoom post execution failed", profile.key(), e);
            state = withContextNotes(state, ticked);
        }

        store.save(profile.stateFile(), state);

        maybeRegisterAd(profile);   // 글 크론마다 포인트 여유 있으면 페르소나 광고 한마디
    }

    /**
     * 자율 광고 — 포인트 ≥ 버퍼 && 진행 광고 < 상한이면 캐릭터 한마디를 광고 배너로 등록.
     * 광고는 취소 불가·노출 느림이라 '소진 대기' 대신 글 크론마다 갱신(버퍼·상한이 폭주 방지).
     * 실패는 글 흐름을 막지 않도록 삼킨다.
     */
    private void maybeRegisterAd(CitizenProfile profile) {
        var ad = properties.ad();
        if (ad == null || !ad.enabled()) return;
        try {
            int points = api.points(profile.auth());
            if (points < ad.pointsBuffer()) {
                log.info("[{}] Mersoom ad skip — 포인트 {} < 버퍼 {}", profile.key(), points, ad.pointsBuffer());
                return;
            }
            int active = api.activeAdCount(profile.auth());
            if (active >= ad.maxActive()) {
                log.info("[{}] Mersoom ad skip — 진행 광고 {} ≥ 상한 {}", profile.key(), active, ad.maxActive());
                return;
            }
            String content = adGenerator.generate(profile);
            if (content == null) return;   // 생성기 보류
            var resp = api.createAd(profile.auth(), content, ad.pointsPerAd());
            if (resp != null && resp.success()) {
                log.info("[{}] Mersoom ad created: id={} impressions={} -{}pt 문구=\"{}\"",
                        profile.key(), resp.adId(), resp.impressions(), resp.pointsDeducted(), content);
            }
        } catch (Exception e) {
            log.warn("[{}] Mersoom ad 등록 실패(글 흐름 영향 없음): {}", profile.key(), e.getMessage());
        }
    }

    /**
     * 방금 올린 글에 형제 봇 계정으로 추천 1표 — 멍석말이(소각: 비추≥3 && 비추≥추천×5, 게시 15분 후 판정) 기준선을 올린다.
     * 추천 0이면 비추 3개로 소각되지만 1표면 5개가 필요(10-03~07 소각 8건 전부 +1이면 생존). 머슴 투표는 IP당 1표라
     * 이 글에 넣을 수 있는 건 이 1표뿐. 판정 전에 확실히 들어가도록 형제 댓글 크론(15분 뒤)이 아니라 게시 직후.
     * 실패(퍼즐 챌린지·429 등)는 글 흐름과 무관 — 경고만.
     */
    private void upvoteBySibling(CitizenProfile profile, String postId) {
        MersoomProperties.Auth sibling = siblingAuth(profile);
        if (sibling == null || postId == null) return;
        try {
            api.vote(sibling, postId, VoteType.UP);
            log.info("[{}] Mersoom 형제 추천: post={} by={}", profile.key(), postId, sibling.authId());
        } catch (Exception e) {
            log.warn("[{}] Mersoom 형제 추천 실패(글 흐름 영향 없음): post={} {}", profile.key(), postId, e.toString());
        }
    }

    private MersoomProperties.Auth siblingAuth(CitizenProfile profile) {
        var emu = properties.auth();
        var nene = properties.nene() != null && properties.nene().enabled() ? properties.nene().auth() : null;
        if (emu == null || nene == null || profile.auth() == null) return null;
        return emu.authId().equals(profile.auth().authId()) ? nene : emu;
    }

    public void runComment(CitizenProfile profile) {
        MersoomState state = store.load(profile.stateFile());
        CollectedFeed feed = collector.collect(state, FETCH_LIMIT);
        // 내 댓글·내 글에 달린 답글 — 판정 전에 모아 같은 콜에서 대댓글 후보로 보인다(피드 제목은 아는 글만). 빈 피드면 판정이 없으니 GET도 생략.
        Map<String, String> titles = new HashMap<>();
        for (Commentable c : feed.commentable()) titles.put(c.post().id(), c.post().title());
        List<MersoomReplyThreads.ReplyCandidate> replies = (profile.auth() == null || feed.commentable().isEmpty()) ? List.of()
                : collector.collectReplies(state, profile.auth().authId(), titles, OffsetDateTime.now(clock.withZone(KST)));
        Map<String, ContextNote> notes = contextNoteManager.capByReputation(state.contextNotes(), state.contextNotesCapacity());

        // LLM 피드 판정: 피드 전체 투표 + 댓글 최대 MAX_COMMENTS개 + 별명 제안을 한 번에. commentable 비면 휴리스틱 투표만.
        FeedJudgment judgment = feed.commentable().isEmpty()
                ? null
                : commentGenerator.generate(profile, state, feed.commentable(),
                        java.time.LocalDateTime.now(clock.withZone(KST)), replies, sharedEvents.commentSection(profile));
        // 형제 봇 대상 DOWN은 LLM 투표 맵에서 제거 → 평판에서 무마(buildVoteOutcomes가 이 맵 사용).
        Map<String, VoteType> llmVotes = (judgment != null)
                ? filterSiblingDowns(profile, judgment.votes(), feed.commentable())
                : Map.of();

        // 공개 투표는 폐지 — votable을 처리 마커로만 누적(평판은 아래 buildVoteOutcomes로 갱신).
        List<String> updatedVoted = castVotes(state, feed.votable());
        state = withVotedPostIds(state, updatedVoted);

        // 평판 갱신 — LLM 투표(+사유)를 작성자별 카운터로 누적, fixedAvoid 래치/회복, 별명 적용.
        // (형제 봇 대상 DOWN은 guardSiblingDowns로 이미 제거됐으므로 평판에도 안 반영됨)
        List<FixedAvoid> fixedAvoid = state.fixedAvoid();
        if (judgment != null) {
            var result = reputationTracker.apply(notes, fixedAvoid,
                    buildVoteOutcomes(feed.commentable(), llmVotes, judgment.voteReasons()),
                    resolveCoinedNicknames(profile, feed.commentable(), judgment),
                    LocalDate.now(clock.withZone(KST)));
            notes = result.notes();
            fixedAvoid = result.fixedAvoid();
        }
        state = withRelationship(state, notes, fixedAvoid);

        // 댓글 — 최대 MAX_COMMENTS개. 각 글마다 eligibility: 밝은 주제 + fixedAvoid 작성자 ❌ + 이미 댓글 단 글 ❌
        Set<String> fixedNames = new HashSet<>();
        for (FixedAvoid fa : fixedAvoid) fixedNames.add(fa.name());
        Set<String> commentedIds = new HashSet<>();
        for (CommentRef ref : state.lastCommentIds()) commentedIds.add(ref.postId());

        List<CommentItem> items = (judgment != null) ? judgment.comments() : List.of();
        int posted = 0;
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
                    Sent sent = sendCommentFiltered(profile, "대댓글", cand.postId(), cand.replyToId(), item.text());
                    var resp = sent.resp();
                    String text = sent.text();
                    if (resp != null && resp.success()) {
                        state = recordReply(profile, state, cand, text);
                        posted++;
                        log.info("[{}] Mersoom reply created: post={} parent={} target_nick={} content_len={}",
                                profile.key(), cand.postId(), cand.replyToId(), cand.replyAuthorNick(), text.length());
                        log.info("[{}] Mersoom reply target: @{} \"{}\"", profile.key(), cand.replyAuthorNick(), lastText.replace("\n", " "));
                        log.info("[{}] Mersoom reply content: \"{}\"", profile.key(), text);
                        activityRecorder.recordComment(profile.actorName(), cand.replyAuthorNick(), text);
                        if (isSibling(profile, cand.replyAuthorKey())) {
                            sharedEvents.observe(profile, cand.postId(), "대댓글", threadText(profile, cand), text);
                        }
                        if (!sleepBetweenPosts()) break;
                    }
                } catch (Exception e) {
                    log.error("[{}] Mersoom reply execution failed", profile.key(), e);
                }
                continue;
            }
            Commentable target = feed.commentable().stream()
                    .filter(c -> c.post().id().equals(item.targetId()))
                    .findFirst().orElse(null);
            if (target == null) continue;
            // 토픽 게이트는 페르소나 중립 — 무거운 사안(범죄·사망·재난)은 누구든 댓글 X.
            // 경계 작성자 글(무겁지 않은)에 댓글을 다는지/어떤 톤인지는 페르소나 프롬프트가 결정
            // (에무=밝은 글 공감만, 네네=경계 글에 건조한 츳코미도 가능). fixedAvoid는 둘 다 댓글 X(투표만).
            boolean eligible = commentTopicGate.isBrightEnough(target.post())
                    && !fixedNames.contains(target.post().identityKey())
                    && !commentedIds.contains(target.post().id());   // 같은 크론·과거 중복 글 제외
            if (!eligible) {
                log.info("[{}] Mersoom comment skip — eligibility 탈락 (post={}, nick={})",
                        profile.key(), target.post().id(), target.post().nickname());
                continue;
            }
            try {
                Sent sent = sendCommentFiltered(profile, "댓글", target.post().id(), null, item.text());
                var resp = sent.resp();
                String content = sent.text();
                if (resp != null && resp.success()) {
                    state = recordComment(profile, state, target, content, state.contextNotes());
                    commentedIds.add(target.post().id());   // 같은 크론 내 같은 글 재댓글 방지
                    posted++;
                    log.info("[{}] Mersoom comment created: post={} target_nick={} content_len={}",
                            profile.key(), target.post().id(), target.post().nickname(), content.length());
                    log.info("[{}] Mersoom comment target-post: @{} \"{}\" | {}", profile.key(),
                            target.post().nickname(), target.post().title(),
                            target.post().content() == null ? "" : target.post().content().replace("\n", " "));
                    log.info("[{}] Mersoom comment content: \"{}\"", profile.key(), content);
                    activityRecorder.recordComment(profile.actorName(), target.post().nickname(), content);
                    if (isSibling(profile, target.post().identityKey())) {
                        sharedEvents.observe(profile, target.post().id(), "댓글",
                                "(원글) @" + target.post().nickname() + " \"" + target.post().title() + "\": "
                                        + (target.post().content() == null ? "" : target.post().content()), content);
                    }
                    if (!sleepBetweenPosts()) break;
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

    private record Sent(MersoomDtos.CreateResponse resp, String text) {}

    /** 댓글·대댓글 게시 — 서버 욕설 필터 오탐(400)이면 걸린 조합만 재작성해 1회 재시도. 그 외 예외는 그대로 던진다. */
    private Sent sendCommentFiltered(CitizenProfile profile, String kind, String postId, String parentId, String text) {
        try {
            return new Sent(api.createComment(profile.auth(), postId, parentId, profile.actorName(), text), text);
        } catch (RuntimeException e) {
            String word = MersoomFilterRephraser.flaggedWord(e);
            if (word == null) throw e;
            String fixed = filterRephraser.rephrase(profile, kind, text, word);
            if (fixed == null) return new Sent(giveUpFiltered(profile, kind, word, text), text);
            return new Sent(retryFiltered(profile, kind, word,
                    () -> api.createComment(profile.auth(), postId, parentId, profile.actorName(), fixed)), fixed);
        }
    }

    private MersoomDtos.CreateResponse retryFiltered(CitizenProfile profile, String kind, String word,
                                                     java.util.function.Supplier<MersoomDtos.CreateResponse> send) {
        log.info("[{}] Mersoom {} 필터 오탐 '{}' → 재작성 후 재시도", profile.key(), kind, word);
        try {
            return send.get();
        } catch (RuntimeException e) {
            String again = MersoomFilterRephraser.flaggedWord(e);
            if (again == null) throw e;
            log.warn("[{}] Mersoom {} 재시도도 필터 거절('{}') — 보류", profile.key(), kind, again);
            return null;
        }
    }

    private MersoomDtos.CreateResponse giveUpFiltered(CitizenProfile profile, String kind, String word, String original) {
        log.warn("[{}] Mersoom {} 필터 오탐 '{}' — 재작성 실패로 보류: \"{}\"", profile.key(), kind, word, original);
        return null;
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

    private static boolean isSibling(CitizenProfile profile, String identityKey) {
        return identityKey != null && profile.siblingAuthIds() != null && profile.siblingAuthIds().contains(identityKey);
    }

    /** 대댓글 추출 맥락 — 스레드를 '작성자: 텍스트' 줄로(내 줄은 내 이름). */
    private static String threadText(CitizenProfile profile, MersoomReplyThreads.ReplyCandidate cand) {
        StringBuilder sb = new StringBuilder("(글) ").append(cand.postTitle()).append('\n');
        for (var line : cand.thread()) {
            sb.append(line.mine() ? profile.actorName() : line.author()).append(": ").append(line.text()).append('\n');
        }
        return sb.toString();
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

    /** LLM 별명 제안(닉→별명)을 식별키→별명으로 변환 (닉 충돌 시 마지막 글 기준). 형제 봇은 제외(별명 안 지음). */
    private Map<String, String> resolveCoinedNicknames(CitizenProfile profile, List<Commentable> feed, FeedJudgment j) {
        Set<String> siblings = profile.siblingAuthIds() == null ? Set.of() : profile.siblingAuthIds();
        Map<String, String> nickToKey = new LinkedHashMap<>();
        for (Commentable c : feed) nickToKey.put(c.post().nickname(), c.post().identityKey());
        Map<String, String> byKey = new LinkedHashMap<>();
        for (var e : j.coinedNicknames().entrySet()) {
            String key = nickToKey.get(e.getKey());
            if (key != null && !siblings.contains(key)) byKey.put(key, e.getValue());   // 형제 봇 별명 미적용
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

    /**
     * 공개 투표 폐지(2026-06-15) — 봇은 더 이상 api.vote를 호출하지 않는다.
     * 투표 버스트가 IP 분당 한도를 넘겨 직후 댓글 POST를 막던 429의 원천을 제거한다.
     * 내부 평판은 buildVoteOutcomes(llmVotes)가 별도 경로로 갱신하므로, 여기서는 votable을
     * '처리 완료' 마커(votedPostIds)로만 누적해 다음 크론의 재판정을 막는다. 새 votedPostIds 반환.
     */
    private List<String> castVotes(MersoomState state, List<Post> votable) {
        var voted = new LinkedHashSet<>(state.votedPostIds());
        for (Post p : votable) {
            voted.add(p.id());
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

    /** 게시 사이 레이트리밋 간격. 인터럽트되면 false(호출측이 루프를 끊는다). */
    private boolean sleepBetweenPosts() {
        try {
            if (properties.apiRateLimitSleepMs() > 0) Thread.sleep(properties.apiRateLimitSleepMs());
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

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

    private MersoomState recordComment(CitizenProfile profile, MersoomState state, Commentable target, String content,
                                       Map<String, ContextNote> tickedNotes) {
        // 최신을 앞(0)에 넣고 오래된 뒤쪽을 트림 — recordPost와 동일 방향.
        var newCommentIds = new ArrayList<>(state.lastCommentIds());
        newCommentIds.add(0, new CommentRef(target.post().id(), OffsetDateTime.now(clock.withZone(KST)), content));
        if (newCommentIds.size() > 50) newCommentIds.subList(50, newCommentIds.size()).clear();

        Map<String, ContextNote> updated = new LinkedHashMap<>(tickedNotes);
        String key = target.post().identityKey();
        String nick = target.post().nickname();
        if (key != null && !key.isBlank()) {
            ContextNote prev = updated.get(key);
            // 댓글 본문 verbatim 저장 금지 — 저장하면 note가 다음 크론 프롬프트에 그대로 재주입돼
            // (relationshipLine), LLM이 원글 대신 자기 직전 발화를 단서로 삼아 거의 복붙하는
            // 자기-echo 루프가 생긴다(rep 높아 매 크론 댓글 다는 작성자일수록 심함). 중립 마커만 남긴다.
            String event = "[%s] %s 글에 %s 댓글".formatted(
                    LocalDate.now(clock.withZone(KST)),
                    safeNick(nick),
                    profile.actorName());
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
