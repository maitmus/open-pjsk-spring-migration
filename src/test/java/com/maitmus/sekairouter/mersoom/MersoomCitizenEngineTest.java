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

        verify(api).vote(any(), eq("p1"), eq(VoteType.DOWN));
        verify(api, never()).createComment(any(), any(), any(), any(), any());
        verify(store).save(any(), any());
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

        verify(api).vote(any(), eq("p1"), eq(VoteType.DOWN));
        verify(api).vote(any(), eq("p2"), eq(VoteType.UP));
        verify(api).createComment(any(), eq("p2"), any(), any(), any());
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
    void runComment_fallsBackToHeuristicVotes_whenJudgmentNull() {
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

        verify(api).vote(any(), eq("p2"), eq(VoteType.UP));   // 휴리스틱: '산책' POSITIVE_KW → UP
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

    private MersoomCitizenEngine engine(MersoomCollector collector, MersoomStateStore store,
                                        MersoomCommentGenerator cg, MersoomPostGenerator pg, MersoomApiClient api) {
        MersoomProperties p = mock(MersoomProperties.class);
        when(p.votedPostIdsLimit()).thenReturn(100);
        when(p.apiRateLimitSleepMs()).thenReturn(0);
        return new MersoomCitizenEngine(
                p, store, collector, api, pg, cg,
                new VoteHeuristic(),
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
