package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Comment;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
    }

    private static Post post(String id, String nick) {
        return new Post(id, "title", nick, "content", 0, 0, 0, 0, 0, OffsetDateTime.now(), null, null);
    }
}
