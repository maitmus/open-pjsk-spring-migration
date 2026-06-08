package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
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

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-08T11:30:00Z"), ZoneId.of("Asia/Seoul"));

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
    void executeComment_skips_LLM_when_only_heavy_topic_commentable() {
        // 무거운 주제(범죄/구속)만 commentable이면 댓글 생성 자체를 하지 않는다 (LLM 비용 0)
        Post heavy = new Post("p1", "속보", "닉",
                "어떤 사람이 범죄를 저질러 구속되었다고 해요.", 0, 0, 0, 0, 0, OffsetDateTime.now());

        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(
                new CollectedFeed(List.of(new Commentable(heavy, List.of())), List.of(), List.of()));

        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load()).thenReturn(empty());

        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);

        MersoomService service = service(collector, store, commentGen, mock(MersoomPostGenerator.class));
        service.executeComment();

        verify(commentGen, never()).generate(any(), any());
        verify(store).save(any());
    }

    @Test
    void executeComment_picks_first_bright_topic_skipping_heavy() {
        Post heavy = new Post("p1", "사건", "닉", "교통사고로 사망 소식이에요.", 0, 0, 0, 0, 0, OffsetDateTime.now());
        Post bright = new Post("p2", "벚꽃~!", "친구", "오늘 산책 기분 최고에요!", 0, 0, 0, 0, 0, OffsetDateTime.now());

        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(new CollectedFeed(
                List.of(new Commentable(heavy, List.of()), new Commentable(bright, List.of())),
                List.of(), List.of()));

        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load()).thenReturn(empty());

        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);
        when(commentGen.generate(any(), any())).thenReturn("원더호~이 산책 좋았겠어요!");

        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.createComment(any(), any(), any(), any()))
                .thenReturn(new MersoomDtos.CreateResponse(true, "c1"));

        MersoomService service = service(collector, store, commentGen, mock(MersoomPostGenerator.class), api);
        service.executeComment();

        // 밝은 글(p2)에만 댓글, 무거운 글(p1)은 건너뜀
        verify(api).createComment(eq("p2"), any(), any(), any());
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
        when(p.enabled()).thenReturn(true);
        when(p.contextNotesDefaultTtl()).thenReturn(8);
        when(p.contextNoteBytesPerFriend()).thenReturn(1024);
        when(p.votedPostIdsLimit()).thenReturn(100);
        when(p.apiRateLimitSleepMs()).thenReturn(0);

        return new MersoomService(
                p, store, collector, api, pg, cg,
                new VoteHeuristic(),
                new ContextNoteManager(clock, 1024),
                new RelationshipPromoter(clock),
                new CommentTopicGate(),
                clock);
    }

    private static MersoomState empty() {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }
}
