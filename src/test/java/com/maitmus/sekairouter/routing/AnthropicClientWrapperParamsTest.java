package com.maitmus.sekairouter.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicClientWrapperParamsTest {

    private static final PromptBlocks PROMPT = new PromptBlocks("shared", "persona");

    @Test
    void json_call_disables_thinking_and_attaches_no_tools() {
        var p = AnthropicClientWrapper.buildParams("claude-sonnet-5", 5000, PROMPT, "u", false);
        // Sonnet 5는 thinking 생략 시 adaptive가 켜진다 — 검증한 설정(off)을 명시로 고정.
        assertThat(p.thinking()).hasValueSatisfying(t -> assertThat(t.isDisabled()).isTrue());
        assertThat(p.tools().orElse(java.util.List.of())).isEmpty();
    }

    @Test
    void web_search_call_attaches_tool_and_still_disables_thinking() {
        var p = AnthropicClientWrapper.buildParams("claude-sonnet-5", 5000, PROMPT, "u", true);
        assertThat(p.thinking()).hasValueSatisfying(t -> assertThat(t.isDisabled()).isTrue());
        assertThat(p.tools()).hasValueSatisfying(ts -> assertThat(ts).hasSize(1));
    }
}
