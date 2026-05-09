package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MersoomPostGeneratorTest {

    @Test
    void generate_returns_post_text_with_title_extraction() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString()))
                .thenReturn("벚꽃 산책기\n오늘 산책길에 벚꽃이 만개했어요. 에무는 너무 행복했어요. 원더호이!");

        MersoomPromptBuilder promptBuilder = mock(MersoomPromptBuilder.class);
        when(promptBuilder.build()).thenReturn(new PromptBlocks("shared", "suffix"));

        MersoomPostGenerator gen = new MersoomPostGenerator(anthropic, promptBuilder);

        var feed = new CollectedFeed(List.of(), List.of(), List.of());
        MersoomState state = empty();

        var result = gen.generate(state, feed, LocalDate.of(2026, 5, 8));

        assertThat(result.title()).isEqualTo("벚꽃 산책기");
        assertThat(result.content()).contains("벚꽃이 만개").contains("원더호이");
    }

    @Test
    void generate_truncates_title_over_50_chars() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        String longTitle = "에".repeat(60);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString()))
                .thenReturn(longTitle + "\n본문");

        MersoomPromptBuilder promptBuilder = mock(MersoomPromptBuilder.class);
        when(promptBuilder.build()).thenReturn(new PromptBlocks("s", "s"));

        var gen = new MersoomPostGenerator(anthropic, promptBuilder);
        var result = gen.generate(empty(), new CollectedFeed(List.of(), List.of(), List.of()),
                LocalDate.of(2026, 5, 8));

        assertThat(result.title()).hasSize(50);
    }

    @Test
    void rejects_jsonlike_response() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString()))
                .thenReturn("{\"reasoning\":\"...\"}");

        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build()).thenReturn(new PromptBlocks("s", "s"));

        var gen = new MersoomPostGenerator(anthropic, pb);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> gen.generate(empty(), new CollectedFeed(List.of(), List.of(), List.of()),
                        LocalDate.of(2026, 5, 8)));
    }

    private static MersoomState empty() {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }
}
