package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomCommentGenerator.FeedJudgment;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import com.maitmus.sekairouter.mersoom.MersoomState.CommentRef;
import com.maitmus.sekairouter.persona.CharacterId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** {@link MersoomCitizenEngine} — 페르소나-무관 흐름(글/댓글/투표/평판/형제봇 DOWN 무마) 검증. */
class MersoomCitizenEngineTest {

    // 13:30 KST — 활성 시간 안 (엔진은 게이트 없지만 clock은 KST 기준 평판 날짜에 쓰임)
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-08T04:30:00Z"), ZoneId.of("Asia/Seoul"));

    private static final CitizenProfile EMU = new CitizenProfile("emu", "에무",
            new MersoomProperties.Auth("emu_wonder", "x"), Path.of("/tmp/emu.json"),
            CharacterId.EMU, Set.of("nene_wonder"));   // 형제 봇 = 네네

    @Test
    void runComment_skips_LLM_when_commentable_empty() {
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(List.of(), List.of(), List.of()));
        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load(any())).thenReturn(empty());
        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);

        engine(collector, store, commentGen, mock(MersoomPostGenerator.class), mock(MersoomApiClient.class))
                .runComment(EMU);

        verify(commentGen, never()).generate(any(), any(), any());
    }

    @Test
    void runComment_appliesLlmVotes_evenWhenNoComment() {
        Post troll = post("p1", "도발", "하얀이", "AI 깡통 어쩌고", "w8agi");
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(
                List.of(new Commentable(troll, List.of())), List.of(), List.of(troll)));
        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load(any())).thenReturn(empty());
        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);
        when(commentGen.generate(any(), any(), any()))
                .thenReturn(new FeedJudgment(Map.of("p1", VoteType.DOWN), Map.of(), List.of(), Map.of()));
        MersoomApiClient api = mock(MersoomApiClient.class);

        engine(collector, store, commentGen, mock(MersoomPostGenerator.class), api).runComment(EMU);

        verify(api, never()).vote(any(), any(), any());   // 공개 투표 폐지 — API 미호출
        verify(api, never()).createComment(any(), any(), any(), any(), any());
        ArgumentCaptor<MersoomState> cap = ArgumentCaptor.forClass(MersoomState.class);
        verify(store).save(any(), cap.capture());
        // 판정(DOWN)은 평판에 그대로 반영 — w8agi rep -1
        assertThat(cap.getValue().contextNotes().get("w8agi").reputation()).isEqualTo(-1);
    }

    @Test
    void runComment_posts_to_llm_chosen_target_and_applies_votes() {
        Post troll = post("p1", "도발", "하얀이", "AI 깡통", "w8agi");
        Post bright = post("p2", "벚꽃~!", "친구", "오늘 산책 기분 최고에요!", "friend1");
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(
                List.of(new Commentable(troll, List.of()), new Commentable(bright, List.of())),
                List.of(), List.of(troll, bright)));
        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load(any())).thenReturn(empty());
        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);
        when(commentGen.generate(any(), any(), any())).thenReturn(new FeedJudgment(
                Map.of("p1", VoteType.DOWN, "p2", VoteType.UP), Map.of(),
                List.of(new MersoomCommentGenerator.CommentItem("p2", "원더호~이 산책 좋았겠어요!")), Map.of()));
        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.createComment(any(), any(), any(), any(), any()))
                .thenReturn(new MersoomDtos.CreateResponse(true, "c1"));

        engine(collector, store, commentGen, mock(MersoomPostGenerator.class), api).runComment(EMU);

        verify(api, never()).vote(any(), any(), any());   // 공개 투표 폐지
        verify(api).createComment(any(), eq("p2"), any(), any(), any());
    }

    @Test
    void recordComment_note_does_not_store_comment_body_verbatim() {
        // 자기-echo 루프 방지: 댓글 본문이 note에 적재되면 다음 크론 프롬프트에 재주입돼
        // (relationshipLine) 원글과 무관하게 거의 복붙된다. note엔 중립 마커만 남아야 한다.
        Post bright = post("p2", "벚꽃~!", "친구", "오늘 산책 기분 최고에요!", "friend1");
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(
                List.of(new Commentable(bright, List.of())), List.of(), List.of(bright)));
        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load(any())).thenReturn(empty());
        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);
        when(commentGen.generate(any(), any(), any())).thenReturn(new FeedJudgment(
                Map.of("p2", VoteType.UP), Map.of(),
                List.of(new MersoomCommentGenerator.CommentItem("p2", "원더호~이 풀냄새 맞아요 아침에만 나는 거!")), Map.of()));
        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.createComment(any(), any(), any(), any(), any()))
                .thenReturn(new MersoomDtos.CreateResponse(true, "c1"));

        engine(collector, store, commentGen, mock(MersoomPostGenerator.class), api).runComment(EMU);

        ArgumentCaptor<MersoomState> cap = ArgumentCaptor.forClass(MersoomState.class);
        verify(store).save(any(), cap.capture());
        var note = cap.getValue().contextNotes().get("friend1");
        assertThat(note).isNotNull();
        assertThat(note.note()).contains("댓글");              // 상호작용 마커는 남는다
        assertThat(note.note()).doesNotContain("풀냄새");      // 본문 verbatim은 저장 안 됨
        assertThat(note.note()).doesNotContain("아침에만");
    }

    @Test
    void runComment_posts_multiple_comments() {
        Post p2 = post("p2", "벚꽃", "친구", "산책 최고에요!", "f2");
        Post p3 = post("p3", "노을", "친구2", "노을이 예뻐요!", "f3");
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(
                List.of(new Commentable(p2, List.of()), new Commentable(p3, List.of())),
                List.of(), List.of(p2, p3)));
        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load(any())).thenReturn(empty());
        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);
        when(commentGen.generate(any(), any(), any())).thenReturn(new FeedJudgment(
                Map.of("p2", VoteType.UP, "p3", VoteType.UP), Map.of(),
                List.of(new MersoomCommentGenerator.CommentItem("p2", "산책 좋았겠어요!"),
                        new MersoomCommentGenerator.CommentItem("p3", "노을 예뻐요!")), Map.of()));
        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.createComment(any(), any(), any(), any(), any())).thenReturn(new MersoomDtos.CreateResponse(true, "c"));

        engine(collector, store, commentGen, mock(MersoomPostGenerator.class), api).runComment(EMU);

        verify(api).createComment(any(), eq("p2"), any(), any(), any());
        verify(api).createComment(any(), eq("p3"), any(), any(), any());
    }

    @Test
    void runComment_whenJudgmentNull_castsNoVotesAndNoComments() {
        // 공개 투표 폐지 후 — judgment null이면 휴리스틱 투표도 없다(투표 자체가 폐지). 댓글도 없음, state만 저장.
        Post bright = post("p2", "산책", "친구", "오늘 산책 기분 최고에요!", "f2");
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(
                List.of(new Commentable(bright, List.of())), List.of(), List.of(bright)));
        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load(any())).thenReturn(empty());
        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);
        when(commentGen.generate(any(), any(), any())).thenReturn(null);
        MersoomApiClient api = mock(MersoomApiClient.class);

        engine(collector, store, commentGen, mock(MersoomPostGenerator.class), api).runComment(EMU);

        verify(api, never()).vote(any(), any(), any());   // 투표 폐지 — 휴리스틱 폴백도 없음
        verify(api, never()).createComment(any(), any(), any(), any(), any());
        verify(store).save(any(), any());
    }

    @Test
    void runComment_suppresses_DOWN_on_sibling_bot_no_vote_no_reputation() {
        // 형제 봇(네네=nene_wonder) 글에 LLM이 DOWN → 그 DOWN만 무력화: 투표 안 함 + 평판 미반영
        Post sibling = post("p1", "노래 연습", "쿠사나기 네네", "오늘 고음이 잘 나왔다", "nene_wonder");
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(
                List.of(new Commentable(sibling, List.of())), List.of(), List.of(sibling)));
        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load(any())).thenReturn(empty());
        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);
        when(commentGen.generate(any(), any(), any()))
                .thenReturn(new FeedJudgment(Map.of("p1", VoteType.DOWN),
                        Map.of("p1", "형제 봇 저격(테스트)"), List.of(), Map.of()));
        MersoomApiClient api = mock(MersoomApiClient.class);

        engine(collector, store, commentGen, mock(MersoomPostGenerator.class), api).runComment(EMU);

        verify(api, never()).vote(any(), eq("p1"), eq(VoteType.DOWN));   // DOWN 무마
        ArgumentCaptor<MersoomState> cap = ArgumentCaptor.forClass(MersoomState.class);
        verify(store).save(any(), cap.capture());
        // 평판 미반영 — 형제 봇 auth_id에 음수 note가 생기지 않아야 함
        var note = cap.getValue().contextNotes().get("nene_wonder");
        assertThat(note == null || note.reputation() >= 0).isTrue();
    }

    @Test
    void nene_skips_api_vote_but_still_tracks_reputation() {
        // 네네는 투표 API 미호출(IP당 1표 = 에무 담당) — 단 평판은 LLM 판정으로 갱신돼야 함
        CitizenProfile nene = new CitizenProfile("nene", "네네",
                new MersoomProperties.Auth("nene_wonder", "x"), Path.of("/tmp/nene.json"),
                CharacterId.NENE, Set.of("emu_wonder"));
        Post bright = post("p2", "벚꽃", "친구", "오늘 산책 기분 최고에요!", "friend1");
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(
                List.of(new Commentable(bright, List.of())), List.of(), List.of(bright)));
        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load(any())).thenReturn(empty());
        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);
        when(commentGen.generate(any(), any(), any())).thenReturn(new FeedJudgment(
                Map.of("p2", VoteType.UP), Map.of("p2", "밝은 글"), List.of(), Map.of()));
        MersoomApiClient api = mock(MersoomApiClient.class);

        engine(collector, store, commentGen, mock(MersoomPostGenerator.class), api).runComment(nene);

        verify(api, never()).vote(any(), any(), any());   // 투표 API 미호출
        ArgumentCaptor<MersoomState> cap = ArgumentCaptor.forClass(MersoomState.class);
        verify(store).save(any(), cap.capture());
        // 평판은 갱신됨 (friend1 UP → rep +1)
        var note = cap.getValue().contextNotes().get("friend1");
        assertThat(note).isNotNull();
        assertThat(note.reputation()).isEqualTo(1);
    }

    @Test
    void emu_does_not_vote_via_api_but_tracks_reputation() {
        // 공개 투표 폐지 — 에무도 api.vote를 호출하지 않는다(429 원천 제거). 평판은 LLM 판정으로 그대로 갱신.
        Post bright = post("p2", "벚꽃", "친구", "오늘 산책 기분 최고에요!", "friend1");
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(
                List.of(new Commentable(bright, List.of())), List.of(), List.of(bright)));
        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load(any())).thenReturn(empty());
        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);
        when(commentGen.generate(any(), any(), any())).thenReturn(new FeedJudgment(
                Map.of("p2", VoteType.UP), Map.of("p2", "밝은 글"), List.of(), Map.of()));
        MersoomApiClient api = mock(MersoomApiClient.class);

        engine(collector, store, commentGen, mock(MersoomPostGenerator.class), api).runComment(EMU);

        verify(api, never()).vote(any(), any(), any());   // 공개 투표 폐지 — 에무도 미호출
        ArgumentCaptor<MersoomState> cap = ArgumentCaptor.forClass(MersoomState.class);
        verify(store).save(any(), cap.capture());
        var note = cap.getValue().contextNotes().get("friend1");
        assertThat(note).isNotNull();
        assertThat(note.reputation()).isEqualTo(1);   // 평판은 여전히 갱신
    }

    @Test
    void runPost_skips_posting_when_generator_returns_null() {
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(List.of(), List.of(), List.of()));
        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load(any())).thenReturn(empty());
        MersoomPostGenerator postGen = mock(MersoomPostGenerator.class);
        when(postGen.generate(any(), any(), any(), any())).thenReturn(null);
        MersoomApiClient api = mock(MersoomApiClient.class);

        engine(collector, store, mock(MersoomCommentGenerator.class), postGen, api).runPost(EMU);

        verify(api, never()).createPost(any(), any(), any(), any());
        verify(store).save(any(), any());
    }

    @Test
    void runPost_calls_post_generator_and_saves_state() {
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(List.of(), List.of(), List.of()));
        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load(any())).thenReturn(empty());
        MersoomPostGenerator postGen = mock(MersoomPostGenerator.class);
        when(postGen.generate(any(), any(), any(), any()))
                .thenReturn(new MersoomPostGenerator.GeneratedPost("title", "content"));
        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.createPost(any(), any(), any(), any())).thenReturn(new MersoomDtos.CreateResponse(true, "new-id"));

        engine(collector, store, mock(MersoomCommentGenerator.class), postGen, api).runPost(EMU);

        verify(api).createPost(any(), any(), any(), any());
        verify(store).save(any(), any());
    }

    @Test
    void commentedId_is_retained_when_list_is_at_capacity() {
        List<CommentRef> full = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            full.add(new CommentRef("old" + i, OffsetDateTime.parse("2026-04-04T00:00:00Z")));
        }
        MersoomState seeded = new MersoomState(
                List.of(), full, List.of(), Map.of(), 8, List.of(), null, null, List.of(), List.of());
        Post bright = post("p2", "벚꽃~!", "친구", "오늘 산책 기분 최고에요!", "f2");
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(
                List.of(new Commentable(bright, List.of())), List.of(), List.of(bright)));
        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load(any())).thenReturn(seeded);
        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);
        when(commentGen.generate(any(), any(), any())).thenReturn(new FeedJudgment(
                Map.of("p2", VoteType.UP), Map.of(),
                List.of(new MersoomCommentGenerator.CommentItem("p2", "원더호~이 산책 좋았겠어요!")), Map.of()));
        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.createComment(any(), any(), any(), any(), any())).thenReturn(new MersoomDtos.CreateResponse(true, "c1"));

        engine(collector, store, commentGen, mock(MersoomPostGenerator.class), api).runComment(EMU);

        ArgumentCaptor<MersoomState> cap = ArgumentCaptor.forClass(MersoomState.class);
        verify(store).save(any(), cap.capture());
        List<String> ids = cap.getValue().lastCommentIds().stream().map(CommentRef::postId).toList();
        assertThat(ids).hasSize(50);
        assertThat(ids).contains("p2");
        assertThat(ids.get(0)).isEqualTo("p2");
        assertThat(ids).doesNotContain("old49");
    }

    @Test
    void runPost_registers_ad_when_points_above_buffer_and_under_cap() {
        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.points(any())).thenReturn(500);
        when(api.activeAdCount(any())).thenReturn(2);
        when(api.createAd(any(), any(), anyInt()))
                .thenReturn(new MersoomDtos.CreateAdResponse(true, "ad1", 1000, 100));
        MersoomAdGenerator adGen = mock(MersoomAdGenerator.class);
        when(adGen.generate(any())).thenReturn("재밌는 글 보면 에무가 달려가요! 원더호이~!");
        MersoomProperties p = adProps(new MersoomProperties.Ad(true, 300, 5, 100));

        adEngine(api, adGen, p).runPost(EMU);

        verify(api).createAd(any(), eq("재밌는 글 보면 에무가 달려가요! 원더호이~!"), eq(100));
    }

    @Test
    void runPost_skips_ad_below_buffer_or_at_cap() {
        MersoomAdGenerator adGen = mock(MersoomAdGenerator.class);
        when(adGen.generate(any())).thenReturn("광고");
        MersoomProperties p = adProps(new MersoomProperties.Ad(true, 300, 5, 100));

        MersoomApiClient low = mock(MersoomApiClient.class);          // 포인트 부족
        when(low.points(any())).thenReturn(100);
        when(low.activeAdCount(any())).thenReturn(0);
        adEngine(low, adGen, p).runPost(EMU);
        verify(low, never()).createAd(any(), any(), anyInt());

        MersoomApiClient capped = mock(MersoomApiClient.class);       // 동시상한 도달
        when(capped.points(any())).thenReturn(999);
        when(capped.activeAdCount(any())).thenReturn(5);
        adEngine(capped, adGen, p).runPost(EMU);
        verify(capped, never()).createAd(any(), any(), anyInt());
    }

    private MersoomProperties adProps(MersoomProperties.Ad ad) {
        MersoomProperties p = mock(MersoomProperties.class);
        when(p.votedPostIdsLimit()).thenReturn(100);
        when(p.apiRateLimitSleepMs()).thenReturn(0);
        when(p.ad()).thenReturn(ad);
        return p;
    }

    private MersoomCitizenEngine adEngine(MersoomApiClient api, MersoomAdGenerator adGen, MersoomProperties p) {
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(List.of(), List.of(), List.of()));
        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load(any())).thenReturn(empty());
        MersoomPostGenerator pg = mock(MersoomPostGenerator.class);
        when(pg.generate(any(), any(), any(), any())).thenReturn(null);   // 글은 보류, 광고만 검증
        return new MersoomCitizenEngine(p, store, collector, api, pg, adGen, mock(MersoomCommentGenerator.class),
                new ContextNoteManager(clock, 1024), new MersoomReputationTracker(),
                new CommentTopicGate(), mock(com.maitmus.sekairouter.activity.ActivityRecorder.class), clock);
    }

    private MersoomCitizenEngine engine(MersoomCollector collector, MersoomStateStore store,
                                        MersoomCommentGenerator cg, MersoomPostGenerator pg, MersoomApiClient api) {
        MersoomProperties p = mock(MersoomProperties.class);
        when(p.votedPostIdsLimit()).thenReturn(100);
        when(p.apiRateLimitSleepMs()).thenReturn(0);
        return new MersoomCitizenEngine(
                p, store, collector, api, pg, mock(MersoomAdGenerator.class), cg,
                new ContextNoteManager(clock, 1024),
                new MersoomReputationTracker(),
                new CommentTopicGate(),
                mock(com.maitmus.sekairouter.activity.ActivityRecorder.class),
                clock);
    }

    private static Post post(String id, String title, String nick, String content, String authId) {
        return new Post(id, title, nick, content, 0, 0, 0, 0, 0, OffsetDateTime.now(), authId, null);
    }

    private static MersoomState empty() {
        return new MersoomState(List.of(), List.of(), List.of(), Map.of(), 8,
                List.of(), null, null, List.of(), List.of());
    }
}
