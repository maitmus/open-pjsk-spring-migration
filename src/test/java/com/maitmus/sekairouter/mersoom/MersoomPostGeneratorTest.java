package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.OutputSanityGate;
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

    private MersoomPostGenerator gen(String llmReturn) {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString())).thenReturn(llmReturn);
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build()).thenReturn(new PromptBlocks("s", "s"));
        return new MersoomPostGenerator(anthropic, pb, new MersoomSeedPicker(), new OutputSanityGate());
    }

    private static CollectedFeed feed() {
        return new CollectedFeed(List.of(), List.of(), List.of());
    }

    @Test
    void returns_post_when_shouldPost_true() {
        var gen = gen("{\"reasoning\":\"밝은 일상 글\",\"title\":\"벚꽃 산책기\","
                + "\"content\":\"오늘 산책길에 벚꽃이 만개했어요. 에무는 너무 행복했어요. 원더호이!\",\"shouldPost\":true}");

        var result = gen.generate(empty(), feed(), LocalDate.of(2026, 5, 8));

        assertThat(result).isNotNull();
        assertThat(result.title()).isEqualTo("벚꽃 산책기");
        assertThat(result.content()).contains("벚꽃이 만개").contains("원더호이");
    }

    @Test
    void truncates_title_over_50_chars() {
        var gen = gen("{\"title\":\"" + "에".repeat(60) + "\",\"content\":\"본문\",\"shouldPost\":true}");

        var result = gen.generate(empty(), feed(), LocalDate.of(2026, 5, 8));

        assertThat(result).isNotNull();
        assertThat(result.title()).hasSize(50);
    }

    @Test
    void returns_null_when_shouldPost_false() {
        var gen = gen("{\"reasoning\":\"지금 올릴 적절한 글이 없음\",\"title\":\"\",\"content\":\"\",\"shouldPost\":false}");

        assertThat(gen.generate(empty(), feed(), LocalDate.of(2026, 5, 8))).isNull();
    }

    @Test
    void returns_null_when_shouldPost_missing() {
        var gen = gen("{\"reasoning\":\"r\",\"title\":\"제목\",\"content\":\"본문\"}");

        assertThat(gen.generate(empty(), feed(), LocalDate.of(2026, 5, 8))).isNull();
    }

    @Test
    void returns_null_when_backstop_marker_present() {
        var gen = gen("{\"title\":\"제목\",\"content\":\"AI인 저는 이런 글을 작성할 수 없습니다.\",\"shouldPost\":true}");

        assertThat(gen.generate(empty(), feed(), LocalDate.of(2026, 5, 8))).isNull();
    }

    @Test
    void returns_null_when_unparseable() {
        // 봉투가 아닌 생 텍스트(구 포맷) → 게시 보류
        var gen = gen("벚꽃 산책기\n오늘 산책길에 벚꽃이 만개했어요.");

        assertThat(gen.generate(empty(), feed(), LocalDate.of(2026, 5, 8))).isNull();
    }

    private static MersoomState empty() {
        return new MersoomState(
                List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }
}
