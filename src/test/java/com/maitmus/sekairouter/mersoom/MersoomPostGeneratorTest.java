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

    private static final CitizenProfile EMU = new CitizenProfile("emu", "에무",
            new MersoomProperties.Auth("emu_wonder", "x"), java.nio.file.Path.of("/tmp/e.json"),
            com.maitmus.sekairouter.persona.CharacterId.EMU, java.util.Set.of());

    private MersoomPostGenerator gen(String llmReturn) {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString())).thenReturn(llmReturn);
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        return new MersoomPostGenerator(anthropic, pb, new MersoomSeedPicker(), new OutputSanityGate());
    }

    private static CollectedFeed feed() {
        return new CollectedFeed(List.of(), List.of(), List.of());
    }

    @Test
    void nene_post_prompt_demands_spoken_register() {
        // 네네 글 본문이 ~다체(깨달았다/확인해야겠다 등)로 드리프트하던 문제 → 구어 해체 지향 룰이 글 프롬프트에 들어가야 함.
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        org.mockito.ArgumentCaptor<String> userPrompt = org.mockito.ArgumentCaptor.forClass(String.class);
        when(anthropic.completeJson(any(PromptBlocks.class), userPrompt.capture())).thenReturn("{\"shouldPost\":false}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomPostGenerator g = new MersoomPostGenerator(anthropic, pb, new MersoomSeedPicker(), new OutputSanityGate());
        CitizenProfile nene = new CitizenProfile("nene", "네네",
                new MersoomProperties.Auth("nene_wonder", "x"), java.nio.file.Path.of("/tmp/n.json"),
                com.maitmus.sekairouter.persona.CharacterId.NENE, java.util.Set.of("emu_wonder"));

        g.generate(nene, empty(), feed(), LocalDate.of(2026, 6, 19));

        assertThat(userPrompt.getValue()).contains("일기장이다").contains("깨달았어");
    }

    @Test
    void emu_post_prompt_blocks_perfection_aphorism_self_replication() {
        // 에무 글이 '완벽함을 포기하는 순간'·'몸이 기억'·완벽/불완전 교훈으로 매번 환원하던 자기복제 → 차단 룰이 글 프롬프트에 들어가야 함.
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        org.mockito.ArgumentCaptor<String> userPrompt = org.mockito.ArgumentCaptor.forClass(String.class);
        when(anthropic.completeJson(any(PromptBlocks.class), userPrompt.capture())).thenReturn("{\"shouldPost\":false}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomPostGenerator g = new MersoomPostGenerator(anthropic, pb, new MersoomSeedPicker(), new OutputSanityGate());

        g.generate(EMU, empty(), feed(), LocalDate.of(2026, 6, 20));

        assertThat(userPrompt.getValue()).contains("완벽보다 불완전이 진짜").contains("매 글을 '완벽하지 않아도 괜찮다");
    }

    @Test
    void returns_post_when_shouldPost_true() {
        var gen = gen("{\"reasoning\":\"밝은 일상 글\",\"title\":\"벚꽃 산책기\","
                + "\"content\":\"오늘 산책길에 벚꽃이 만개했어요. 에무는 너무 행복했어요. 원더호이!\",\"shouldPost\":true}");

        var result = gen.generate(EMU, empty(), feed(), LocalDate.of(2026, 5, 8));

        assertThat(result).isNotNull();
        assertThat(result.title()).isEqualTo("벚꽃 산책기");
        assertThat(result.content()).contains("벚꽃이 만개").contains("원더호이");
    }

    @Test
    void truncates_title_over_50_chars() {
        var gen = gen("{\"title\":\"" + "에".repeat(60) + "\",\"content\":\"본문\",\"shouldPost\":true}");

        var result = gen.generate(EMU, empty(), feed(), LocalDate.of(2026, 5, 8));

        assertThat(result).isNotNull();
        assertThat(result.title()).hasSize(50);
    }

    @Test
    void returns_null_when_shouldPost_explicitly_false() {
        // 모델이 명시적으로 게시 거부(false)하면 — 내용이 유효해도 보류.
        var gen = gen("{\"reasoning\":\"지금 올릴 적절한 글이 없음\",\"title\":\"제목\",\"content\":\"본문 내용이 있어요 원더호이\",\"shouldPost\":false}");

        assertThat(gen.generate(EMU, empty(), feed(), LocalDate.of(2026, 5, 8))).isNull();
    }

    @Test
    void posts_when_shouldPost_missing_but_content_valid() {
        // shouldPost 누락(모델이 깜빡)이고 title/content가 유효하면 — 기본 게시(누락은 보류 사유 아님).
        var gen = gen("{\"reasoning\":\"r\",\"title\":\"제목\",\"content\":\"오늘 산책 즐거웠어요 원더호이!\"}");

        assertThat(gen.generate(EMU, empty(), feed(), LocalDate.of(2026, 5, 8))).isNotNull();
    }

    @Test
    void returns_null_when_backstop_marker_present() {
        var gen = gen("{\"title\":\"제목\",\"content\":\"AI인 저는 이런 글을 작성할 수 없습니다.\",\"shouldPost\":true}");

        assertThat(gen.generate(EMU, empty(), feed(), LocalDate.of(2026, 5, 8))).isNull();
    }

    @Test
    void returns_null_when_unparseable() {
        // 봉투가 아닌 생 텍스트(구 포맷) → 게시 보류
        var gen = gen("벚꽃 산책기\n오늘 산책길에 벚꽃이 만개했어요.");

        assertThat(gen.generate(EMU, empty(), feed(), LocalDate.of(2026, 5, 8))).isNull();
    }

    private static MersoomState empty() {
        return new MersoomState(
                List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }
}
