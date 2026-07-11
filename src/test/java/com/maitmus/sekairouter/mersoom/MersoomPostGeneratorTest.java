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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    void post_prompt_forbids_double_quotes_in_content() {
        // content 안 비이스케이프 큰따옴표로 본문이 절단됐던 사례 → 큰따옴표 회피 룰이 글 프롬프트에 있어야 함.
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        org.mockito.ArgumentCaptor<String> userPrompt = org.mockito.ArgumentCaptor.forClass(String.class);
        when(anthropic.completeJson(any(PromptBlocks.class), userPrompt.capture())).thenReturn("{\"shouldPost\":false}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomPostGenerator g = new MersoomPostGenerator(anthropic, pb, new MersoomSeedPicker(), new OutputSanityGate());

        g.generate(EMU, empty(), feed(), LocalDate.of(2026, 6, 26));

        assertThat(userPrompt.getValue()).contains("큰따옴표").contains("작은따옴표");
    }

    @Test
    void emu_post_prompt_enforces_polite_ending_under_excitement() {
        // 에무 글이 존댓말 베이스인데 흥분 정점에서 반말로 샌 누수('기대된다★'·'최고야!') → 발랄 종결도 존댓말 어미 유지 룰이 글 프롬프트에 들어가야 함.
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        org.mockito.ArgumentCaptor<String> userPrompt = org.mockito.ArgumentCaptor.forClass(String.class);
        when(anthropic.completeJson(any(PromptBlocks.class), userPrompt.capture())).thenReturn("{\"shouldPost\":false}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomPostGenerator g = new MersoomPostGenerator(anthropic, pb, new MersoomSeedPicker(), new OutputSanityGate());

        g.generate(EMU, empty(), feed(), LocalDate.of(2026, 6, 25));

        assertThat(userPrompt.getValue()).contains("존댓말 어미 위에 붙인다").contains("맨 끝 흥분 구호");
    }

    @Test
    void seed_pjsk_name_injects_address_hint() {
        // 시드가 PJSK 인물을 맨이름으로 언급하면(예: 네네 '토우야와의 라이벌' 시드) 페르소나 호칭 힌트가 프롬프트에 주입돼야 함.
        // 시드는 랜덤이라 여러 번 돌려: (a) 힌트 불변식 — 시드에 토우야 있으면 반드시 '아오야기군' 힌트 동반, (b) 배선 검증 — 힌트가 실제로 뜸.
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        org.mockito.ArgumentCaptor<String> userPrompt = org.mockito.ArgumentCaptor.forClass(String.class);
        when(anthropic.completeJson(any(PromptBlocks.class), userPrompt.capture())).thenReturn("{\"shouldPost\":false}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomPostGenerator g = new MersoomPostGenerator(anthropic, pb, new MersoomSeedPicker(), new OutputSanityGate());
        CitizenProfile nene = new CitizenProfile("nene", "네네",
                new MersoomProperties.Auth("nene_wonder", "x"), java.nio.file.Path.of("/tmp/n.json"),
                com.maitmus.sekairouter.persona.CharacterId.NENE, java.util.Set.of("emu_wonder"));

        boolean sawHint = false;
        for (int i = 0; i < 400; i++) {
            g.generate(nene, empty(), feed(), LocalDate.of(2026, 6, 19));
            String p = userPrompt.getValue();
            String seedBlock = p.substring(p.indexOf("## 오늘 글 시드"), p.indexOf("## 지시"));
            if (seedBlock.contains("토우야")) {
                assertThat(seedBlock).contains("아오야기군");   // 불변식: 이름 있으면 호칭 힌트 동반
                sawHint = true;
            }
        }
        assertThat(sawHint).isTrue();   // 배선 검증: 400회 중 토우야 시드가 최소 1회는 뜸
    }

    @Test
    void address_correction_gate_fixes_bare_pjsk_name_in_post() {
        // 시드-스캔 사각지대: 모델이 자발적으로 '루이'(맨이름)를 꺼내면 → 생성 후 게이트가 잡아 교정 콜로 '루이군' 교정.
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString()))
                .thenReturn("{\"title\":\"무대 준비\",\"content\":\"오늘 루이랑 안무 맞췄어요 원더호이\",\"shouldPost\":true}")  // 1차: 맨이름 누수
                .thenReturn("{\"title\":\"무대 준비\",\"content\":\"오늘 루이군이랑 안무 맞췄어요 원더호이\"}");            // 교정 콜: 루이군
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomPostGenerator g = new MersoomPostGenerator(anthropic, pb, new MersoomSeedPicker(), new OutputSanityGate());

        var result = g.generate(EMU, empty(), feed(), LocalDate.of(2026, 7, 11));

        assertThat(result).isNotNull();
        assertThat(result.content()).contains("루이군");                 // 교정 적용
        verify(anthropic, times(2)).completeJson(any(PromptBlocks.class), anyString());  // 원생성 + 교정 = 2콜
    }

    @Test
    void no_correction_call_when_no_bare_leak() {
        // 누수 없으면 교정 콜 안 함(1콜) — 불필요한 비용 방지.
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString()))
                .thenReturn("{\"title\":\"벚꽃\",\"content\":\"오늘 산책 즐거웠어요 원더호이\",\"shouldPost\":true}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomPostGenerator g = new MersoomPostGenerator(anthropic, pb, new MersoomSeedPicker(), new OutputSanityGate());

        g.generate(EMU, empty(), feed(), LocalDate.of(2026, 7, 11));

        verify(anthropic, times(1)).completeJson(any(PromptBlocks.class), anyString());
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
