package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomCommentGenerator.FeedJudgment;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MersoomServiceTest {

    // 13:30 KST — isActiveHour(9–19) 안
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-08T04:30:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void executeComment_skips_LLM_when_commentable_empty() {
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(
                new CollectedFeed(List.of(), List.of(), List.of()));

        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load()).thenReturn(empty());

        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);

        MersoomService service = service(collector, store, commentGen, mock(MersoomPostGenerator.class));
        service.executeComment();

        verify(commentGen, never()).generate(any(), any());
    }

    @Test
    void executeComment_appliesLlmVotes_evenWhenNoComment() {
        // LLM이 안티-AI 도발글에 DOWN을 주고 댓글은 보류 → 투표는 적용, 댓글은 미게시
        Post troll = new Post("p1", "도발", "하얀이", "AI 깡통 어쩌고", 0, 0, 0, 0, 0, OffsetDateTime.now(), null, null);

        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(
                List.of(new Commentable(troll, List.of())), List.of(), List.of(troll)));

        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load()).thenReturn(empty());

        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);
        when(commentGen.generate(any(), any()))
                .thenReturn(new FeedJudgment(Map.of("p1", VoteType.DOWN), Map.of(), null, null, Map.of()));

        MersoomApiClient api = mock(MersoomApiClient.class);

        MersoomService service = service(collector, store, commentGen, mock(MersoomPostGenerator.class), api);
        service.executeComment();

        verify(api).vote("p1", VoteType.DOWN);                       // LLM 투표 적용
        verify(api, never()).createComment(any(), any(), any(), any()); // 댓글 미게시
        verify(store).save(any());
    }

    @Test
    void executeComment_posts_to_llm_chosen_target_and_applies_votes() {
        Post troll = new Post("p1", "도발", "하얀이", "AI 깡통", 0, 0, 0, 0, 0, OffsetDateTime.now(), null, null);
        Post bright = new Post("p2", "벚꽃~!", "친구", "오늘 산책 기분 최고에요!", 0, 0, 0, 0, 0, OffsetDateTime.now(), null, null);

        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(
                List.of(new Commentable(troll, List.of()), new Commentable(bright, List.of())),
                List.of(), List.of(troll, bright)));

        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load()).thenReturn(empty());

        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);
        when(commentGen.generate(any(), any())).thenReturn(new FeedJudgment(
                Map.of("p1", VoteType.DOWN, "p2", VoteType.UP), Map.of(), "p2", "원더호~이 산책 좋았겠어요!", Map.of()));

        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.createComment(any(), any(), any(), any()))
                .thenReturn(new MersoomDtos.CreateResponse(true, "c1"));

        MersoomService service = service(collector, store, commentGen, mock(MersoomPostGenerator.class), api);
        service.executeComment();

        verify(api).vote("p1", VoteType.DOWN);   // 도발글 비추
        verify(api).vote("p2", VoteType.UP);     // 밝은 글 추천
        verify(api).createComment(eq("p2"), any(), any(), any()); // LLM이 고른 target에 댓글
    }

    @Test
    void executeComment_fallsBackToHeuristicVotes_whenJudgmentNull() {
        // 파싱 실패(null) → 휴리스틱 투표 폴백 + 댓글 미게시
        Post bright = new Post("p2", "산책", "친구", "오늘 산책 기분 최고에요!", 0, 0, 0, 0, 0, OffsetDateTime.now(), null, null);

        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(
                List.of(new Commentable(bright, List.of())), List.of(), List.of(bright)));

        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load()).thenReturn(empty());

        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);
        when(commentGen.generate(any(), any())).thenReturn(null);

        MersoomApiClient api = mock(MersoomApiClient.class);

        MersoomService service = service(collector, store, commentGen, mock(MersoomPostGenerator.class), api);
        service.executeComment();

        verify(api).vote("p2", VoteType.UP);   // 휴리스틱: '산책' POSITIVE_KW → UP
        verify(api, never()).createComment(any(), any(), any(), any());
        verify(store).save(any());
    }

    @Test
    void executePost_skips_posting_when_generator_returns_null() {
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(
                new CollectedFeed(List.of(), List.of(), List.of()));

        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load()).thenReturn(empty());

        MersoomPostGenerator postGen = mock(MersoomPostGenerator.class);
        when(postGen.generate(any(), any(), any())).thenReturn(null);

        MersoomApiClient api = mock(MersoomApiClient.class);

        MersoomService service = service(collector, store, mock(MersoomCommentGenerator.class), postGen, api);
        service.executePost();

        verify(api, never()).createPost(any(), any(), any());
        verify(store).save(any());
    }

    @Test
    void executePost_calls_post_generator_and_saves_state() {
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(
                new CollectedFeed(List.of(), List.of(), List.of()));

        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load()).thenReturn(empty());

        MersoomPostGenerator postGen = mock(MersoomPostGenerator.class);
        when(postGen.generate(any(), any(), any()))
                .thenReturn(new MersoomPostGenerator.GeneratedPost("title", "content"));

        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.createPost(any(), any(), any()))
                .thenReturn(new MersoomDtos.CreateResponse(true, "new-id"));

        MersoomService service = service(collector, store, mock(MersoomCommentGenerator.class), postGen, api);
        service.executePost();

        verify(api).createPost(any(), any(), any());
        verify(store).save(any());
    }

    private MersoomService service(MersoomCollector collector, MersoomStateStore store,
                                   MersoomCommentGenerator cg, MersoomPostGenerator pg) {
        return service(collector, store, cg, pg, mock(MersoomApiClient.class));
    }

    private MersoomService service(MersoomCollector collector, MersoomStateStore store,
                                   MersoomCommentGenerator cg, MersoomPostGenerator pg,
                                   MersoomApiClient api) {
        MersoomProperties p = mock(MersoomProperties.class);
        when(p.enabled()).thenReturn(true);        when(p.contextNoteBytesPerFriend()).thenReturn(1024);
        when(p.votedPostIdsLimit()).thenReturn(100);
        when(p.apiRateLimitSleepMs()).thenReturn(0);

        return new MersoomService(
                p, store, collector, api, pg, cg,
                new VoteHeuristic(),
                new ContextNoteManager(clock, 1024),
                new MersoomReputationTracker(),
                new CommentTopicGate(),
                clock);
    }

    private static MersoomState empty() {
        return new MersoomState(
                List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }
}
