package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Comment;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MersoomCollectorTest {

    @Test
    void commentable_excludes_only_my_posts() {
        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.recentPosts(anyInt())).thenReturn(List.of(
                post("p1", "내글돌쇠"),
                post("p2", "오호돌쇠"),
                post("p3", "이미댓글돌쇠"),
                post("p4", "자동돌쇠"),
                post("p5", "신규돌쇠")
        ));
        when(api.commentsOf(anyString())).thenReturn(List.of());

        MersoomState state = new MersoomState(
                List.of("p1"),
                List.of(new MersoomState.CommentRef("p3", OffsetDateTime.now())),
                List.of(),
                Map.of(),
                8,
                List.of("돌쇠"),
                null, null,
                List.of(),
                List.of());

        MersoomCollector collector = new MersoomCollector(api);
        var feed = collector.collect(state, 10);

        // 내 글(p1)만 제외. 이미 댓글(p3)·과거 avoid 닉(p4) 제외는 폐지 → 전부 LLM 판정 대상(투표).
        assertThat(feed.commentable()).extracting(c -> c.post().id())
                .containsExactlyInAnyOrder("p2", "p3", "p4", "p5");
        assertThat(feed.myTracked()).hasSize(1);
        assertThat(feed.myTracked().get(0).post().id()).isEqualTo("p1");
        assertThat(feed.votable()).hasSize(4);
        assertThat(feed.votable()).extracting(Post::id).doesNotContain("p1");

        // lazy commentsOf — 내 글(p1, myTracked)에만 호출, 피드 글엔 호출 안 함(IP 스파이크 방지)
        verify(api).commentsOf("p1");
        verify(api, never()).commentsOf("p2");
        verify(api, never()).commentsOf("p5");
        assertThat(feed.commentable()).allSatisfy(c -> assertThat(c.comments()).isEmpty());
    }

    @Test
    void collectReplies_uses_recent_window_and_caps() {
        MersoomApiClient api = mock(MersoomApiClient.class);
        OffsetDateTime now = OffsetDateTime.parse("2026-10-22T12:00:00+09:00");
        List<MersoomState.CommentRef> refs = new ArrayList<>();
        for (int i = 0; i < 8; i++) refs.add(new MersoomState.CommentRef("p" + i, now.minusHours(i), "t"));
        refs.add(new MersoomState.CommentRef("old", now.minusHours(13), "t"));     // 12h 밖
        refs.add(1, new MersoomState.CommentRef("p0", now.minusMinutes(30), "t"));   // 중복 postId
        MersoomState st = new MersoomState(List.of(), refs, List.of(), Map.of(), 8, List.of(), null, null, List.of(), List.of());
        for (int i = 0; i < 8; i++) {
            String pid = "p" + i;
            when(api.commentsOf(pid)).thenReturn(List.of(
                    new MersoomDtos.Comment("m" + i, pid, null, "네네", "내 댓글", 0, 0, now.minusHours(i), "nene_wonder", null, false),
                    new MersoomDtos.Comment("r" + i, pid, "m" + i, "M", "답글" + i, 0, 0, now.minusMinutes(60 * i - 1), "maid", null, false)));
        }
        MersoomCollector col = new MersoomCollector(api);
        col.setReplyFetchGapMs(0);

        var out = col.collectReplies(st, "nene_wonder", Map.of("p0", "제목0"), now);

        verify(api, times(6)).commentsOf(any());          // 고유 postId 최신 6개만
        verify(api, never()).commentsOf("old");
        assertThat(out).hasSize(4);                        // 후보 상한 4
        assertThat(out.get(0).replyToId()).isEqualTo("r0"); // 답글 최신순
        assertThat(out.get(0).postTitle()).isEqualTo("제목0");
        assertThat(out.get(1).postTitle()).isEqualTo("(예전 글)");
    }

    @Test
    void fetch_failure_skips_only_that_post() {
        MersoomApiClient api = mock(MersoomApiClient.class);
        OffsetDateTime now = OffsetDateTime.parse("2026-10-22T12:00:00+09:00");
        var refs = List.of(new MersoomState.CommentRef("bad", now.minusHours(1), "t"),
                new MersoomState.CommentRef("ok", now.minusHours(2), "t"));
        MersoomState st = new MersoomState(List.of(), refs, List.of(), Map.of(), 8, List.of(), null, null, List.of(), List.of());
        when(api.commentsOf("bad")).thenThrow(new RuntimeException("429"));
        when(api.commentsOf("ok")).thenReturn(List.of(
                new MersoomDtos.Comment("m", "ok", null, "네네", "x", 0, 0, now, "nene_wonder", null, false),
                new MersoomDtos.Comment("r", "ok", "m", "M", "y", 0, 0, now, "maid", null, false)));
        MersoomCollector col = new MersoomCollector(api);
        col.setReplyFetchGapMs(0);

        assertThat(col.collectReplies(st, "nene_wonder", Map.of(), now))
                .extracting(MersoomReplyThreads.ReplyCandidate::replyToId).containsExactly("r");
    }

    @Test
    void collectReplies_includes_comments_on_my_recent_posts() {
        MersoomApiClient api = mock(MersoomApiClient.class);
        OffsetDateTime now = OffsetDateTime.parse("2026-10-22T12:00:00+09:00");
        // 내 글 3개 중 최신 2개만, my1은 내가 댓글도 단 글(lastCommentIds) — 중복 조회·중복 후보 없어야
        var refs = List.of(new MersoomState.CommentRef("my1", now.minusHours(1), "t"));
        MersoomState st = new MersoomState(List.of("my1", "my2", "my3"), refs, List.of(), Map.of(), 8, List.of(), null, null, List.of(), List.of());
        when(api.post("my1")).thenReturn(new Post("my1", "내 글1", "네네", "본문1", 0, 0, 0, 0, 0, now.minusHours(2), "nene_wonder", null));
        when(api.post("my2")).thenReturn(new Post("my2", "내 글2", "네네", "본문2", 0, 0, 0, 0, 0, now.minusHours(4), "nene_wonder", null));
        when(api.commentsOf("my1")).thenReturn(List.of(
                new MersoomDtos.Comment("e1", "my1", null, "에무", "네네쨩~", 0, 0, now.minusMinutes(30), "emu_wonder", null, false),
                new MersoomDtos.Comment("n1", "my1", "e1", "네네", "왜", 0, 0, now.minusMinutes(20), "nene_wonder", null, false),
                new MersoomDtos.Comment("e2", "my1", "n1", "에무", "헤헤", 0, 0, now.minusMinutes(10), "emu_wonder", null, false)));
        when(api.commentsOf("my2")).thenReturn(List.of(
                new MersoomDtos.Comment("c2", "my2", null, "M", "좋아요", 0, 0, now.minusMinutes(90), "maid", null, false)));
        MersoomCollector col = new MersoomCollector(api);
        col.setReplyFetchGapMs(0);

        var out = col.collectReplies(st, "nene_wonder", Map.of(), now);

        assertThat(out).extracting(MersoomReplyThreads.ReplyCandidate::replyToId).containsExactly("e2", "c2");
        assertThat(out.get(1).postTitle()).isEqualTo("내 글2");
        verify(api, times(1)).commentsOf("my1");
        verify(api, never()).post("my3");
        verify(api, never()).commentsOf("my3");
    }

    private static Post post(String id, String nick) {
        return new Post(id, "title", nick, "content", 0, 0, 0, 0, 0, OffsetDateTime.now(), null, null);
    }
}
