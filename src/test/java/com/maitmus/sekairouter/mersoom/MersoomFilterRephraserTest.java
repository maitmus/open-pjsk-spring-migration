package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.OutputSanityGate;
import com.maitmus.sekairouter.routing.PromptBlocks;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MersoomFilterRephraserTest {

    private static final CitizenProfile EMU = new CitizenProfile("emu", "에무",
            new MersoomProperties.Auth("emu_wonder", "x"), Path.of("/tmp/emu.json"), CharacterId.EMU, Set.of());

    static HttpClientErrorException badRequest(String body) {
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    static HttpClientErrorException badword(String word) {
        return badRequest("{\"error\":\"부적절한 단어가 포함되어 있습니다: \\\"" + word + "\\\"\"}");
    }

    @Test
    void flaggedWord_extracts_word_from_server_badword_400() {
        // 10-28 17:15 라이브 응답 그대로
        assertThat(MersoomFilterRephraser.flaggedWord(badword("자지"))).isEqualTo("자지");
    }

    @Test
    void flaggedWord_is_null_for_other_errors() {
        assertThat(MersoomFilterRephraser.flaggedWord(badRequest("{\"error\":\"You can have at most 10 active ads\"}"))).isNull();
        assertThat(MersoomFilterRephraser.flaggedWord(new IllegalStateException("부적절한 단어가 포함되어 있습니다: \"자지\""))).isNull();
    }

    private MersoomFilterRephraser rephraser(AnthropicClientWrapper anthropic) {
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(EMU)).thenReturn(new PromptBlocks(List.of(new PromptBlocks.Block("persona", true))));
        return new MersoomFilterRephraser(anthropic, pb, new OutputSanityGate());
    }

    @Test
    void rephrase_returns_text_without_flagged_sequence_and_prompt_names_word_and_original() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(), any())).thenReturn("{\"text\":\"혼자이지만 괜찮아요~!\"}");

        assertThat(rephraser(anthropic).rephrase(EMU, "댓글", "혼자지만 괜찮아요~!", "자지")).isEqualTo("혼자이지만 괜찮아요~!");

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(anthropic).completeJson(any(), user.capture());
        assertThat(user.getValue()).contains("'자지'").contains("혼자지만 괜찮아요~!");
    }

    @Test
    void rephrase_rejects_output_that_still_contains_word_even_across_spaces() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(), any())).thenReturn("{\"text\":\"혼자 지만 괜찮아요\"}", "not json", "{\"text\":\"\"}");
        MersoomFilterRephraser r = rephraser(anthropic);

        assertThat(r.rephrase(EMU, "댓글", "혼자지만 괜찮아요", "자지")).isNull();   // 띄어써도 붙여 읽으면 그대로
        assertThat(r.rephrase(EMU, "댓글", "혼자지만 괜찮아요", "자지")).isNull();   // 파싱 실패
        assertThat(r.rephrase(EMU, "댓글", "혼자지만 괜찮아요", "자지")).isNull();   // 빈 값
    }

    @Test
    void rephrase_swallows_llm_failure() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(), any())).thenThrow(new RuntimeException("overloaded"));
        assertThat(rephraser(anthropic).rephrase(EMU, "댓글", "혼자지만", "자지")).isNull();
    }
}
