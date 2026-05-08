package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MersoomCommentGeneratorTest {

    @Test
    void generates_comment_text() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString()))
                .thenReturn("우와! 그거 정말 원더호이네요! 에무도 같이 해보고 싶어요.");

        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build()).thenReturn(new PromptBlocks("s", "s"));

        var gen = new MersoomCommentGenerator(anthropic, pb);

        Post p = new Post("p1", "T", "오호돌쇠", "벚꽃 산책 기분 좋다", 0, 0, 0, 0, 0, OffsetDateTime.now());
        var commentable = new Commentable(p, List.of());

        String result = gen.generate(empty(), commentable);

        assertThat(result).contains("원더호이").contains("에무");
        assertThat(result.length()).isLessThanOrEqualTo(500);
    }

    @Test
    void truncates_content_over_500_chars() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString()))
                .thenReturn("아".repeat(600));

        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build()).thenReturn(new PromptBlocks("s", "s"));

        var gen = new MersoomCommentGenerator(anthropic, pb);
        Post p = new Post("p1", "T", "n", "c", 0, 0, 0, 0, 0, OffsetDateTime.now());

        String result = gen.generate(empty(), new Commentable(p, List.of()));

        assertThat(result).hasSize(500);
    }

    private static MersoomState empty() {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }
}
