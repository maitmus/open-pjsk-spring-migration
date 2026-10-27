package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SharedEventExtractorTest {

    @Test
    void parse_reads_fenced_json_ops() {
        var ops = SharedEventExtractor.parse("""
                ```json
                {"reasoning":"둘 다 출연","ops":[{"op":"add","id":"halloween-show","title":"원더쇼 할로윈 쇼","date":"2026-10-31","facts":["리허설"]},
                 {"op":"update","id":"crepe","addFacts":["줄 김"],"status":"done","date":null}]}
                ```""");
        assertThat(ops).hasSize(2);
        assertThat(ops.get(0).op()).isEqualTo("add");
        assertThat(ops.get(0).facts()).containsExactly("리허설");
        assertThat(ops.get(1).addFacts()).containsExactly("줄 김");
        assertThat(ops.get(1).status()).isEqualTo("done");
        assertThat(ops.get(1).date()).isNull();
    }

    @Test
    void parse_empty_or_broken_is_empty() {
        assertThat(SharedEventExtractor.parse("{\"reasoning\":\"없음\",\"ops\":[]}")).isEmpty();
        assertThat(SharedEventExtractor.parse("헛소리")).isEmpty();
        assertThat(SharedEventExtractor.parse(null)).isEmpty();
    }

    @Test
    void extract_sends_today_current_events_and_text() {
        var anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(), any())).thenReturn("{\"ops\":[]}");
        var ex = new SharedEventExtractor(anthropic, new ObjectMapper());
        var current = List.of(new SharedEvent("halloween-show", "원더쇼 할로윈 쇼", "2026-10-31", "upcoming",
                List.of("리허설"), "nene", "2026-10-27T16:00", List.of("p1")));

        var ops = ex.extract(new SharedEventExtractor.Input(LocalDate.of(2026, 10, 27), "에무", "네네", "글",
                "", "제목: 의상 못 고르겠어요\n본문: 할로윈 쇼까지 나흘"), current);

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(anthropic).completeJson(any(PromptBlocks.class), user.capture());
        assertThat(ops).isEmpty();
        assertThat(user.getValue()).contains("2026-10-27(화)", "에무", "네네", "halloween-show", "할로윈 쇼까지 나흘");
    }

    @Test
    void extract_swallows_client_errors() {
        var anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(), any())).thenThrow(new RuntimeException("429"));
        var ex = new SharedEventExtractor(anthropic, new ObjectMapper());
        assertThat(ex.extract(new SharedEventExtractor.Input(LocalDate.of(2026, 10, 27), "에무", "네네", "글", "", "x"),
                List.of())).isEmpty();
    }
}
