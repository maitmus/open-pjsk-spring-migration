package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
                clock);
    }

    private static MersoomState empty() {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }
}
