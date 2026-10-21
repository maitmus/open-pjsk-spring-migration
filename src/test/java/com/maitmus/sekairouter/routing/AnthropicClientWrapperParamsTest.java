package com.maitmus.sekairouter.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicClientWrapperParamsTest {

    private static final PromptBlocks PROMPT = new PromptBlocks("shared", "persona");

    @Test
    void json_call_disables_thinking_and_attaches_no_tools() {
        var p = AnthropicClientWrapper.buildParams("claude-sonnet-5", 5000, "", PROMPT, "u", false);
        // Sonnet 5는 thinking 생략 시 adaptive가 켜진다 — 검증한 설정(off)을 명시로 고정.
        assertThat(p.thinking()).hasValueSatisfying(t -> assertThat(t.isDisabled()).isTrue());
        assertThat(p.tools().orElse(java.util.List.of())).isEmpty();
    }

    @Test
    void web_search_call_attaches_tool_and_still_disables_thinking() {
        var p = AnthropicClientWrapper.buildParams("claude-sonnet-5", 5000, "", PROMPT, "u", true);
        assertThat(p.thinking()).hasValueSatisfying(t -> assertThat(t.isDisabled()).isTrue());
        assertThat(p.tools()).hasValueSatisfying(ts -> assertThat(ts).hasSize(1));
    }

    @Test
    void effort_set_uses_adaptive_thinking_with_that_effort() {
        // Opus 5.5는 thinking disabled를 400으로 거부 — effort로만 사고량을 조절한다.
        var p = AnthropicClientWrapper.buildParams("claude-opus-5-5", 5000, "low", PROMPT, "u", false);
        assertThat(p.thinking()).hasValueSatisfying(t -> assertThat(t.isAdaptive()).isTrue());
        assertThat(p.outputConfig()).hasValueSatisfying(o ->
                assertThat(o.effort()).hasValue(com.anthropic.models.messages.OutputConfig.Effort.LOW));
    }

    @Test
    void blank_effort_keeps_thinking_disabled_without_output_config() {
        var p = AnthropicClientWrapper.buildParams("claude-sonnet-5", 5000, null, PROMPT, "u", false);
        assertThat(p.thinking()).hasValueSatisfying(t -> assertThat(t.isDisabled()).isTrue());
        assertThat(p.outputConfig()).isEmpty();
    }
}
