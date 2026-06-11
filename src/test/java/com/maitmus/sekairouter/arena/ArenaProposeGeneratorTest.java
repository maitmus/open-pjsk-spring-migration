package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.OutputSanityGate;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArenaProposeGeneratorTest {

    private ArenaProposeGenerator gen(String llm) {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        when(a.completeJson(any(PromptBlocks.class), anyString())).thenReturn(llm);
        SharedPromptContent s = mock(SharedPromptContent.class);
        when(s.build()).thenReturn("shared");
        return new ArenaProposeGenerator(a, s, new OutputSanityGate());
    }

    @Test
    void generates_topic() {
        var t = gen("""
                {"reasoning":"r","title":"우정은 솔직함일까 배려일까","pros":"솔직함이 신뢰","cons":"배려 없으면 폭력"}
                """).generate();
        assertThat(t).isNotNull();
        assertThat(t.title()).contains("우정");
        assertThat(t.pros()).contains("신뢰");
        assertThat(t.cons()).contains("배려");
    }

    @Test
    void returns_null_on_parse_fail() {
        assertThat(gen("그냥 평문").generate()).isNull();
    }

    @Test
    void returns_null_when_field_blank() {
        assertThat(gen("{\"title\":\"제목\",\"pros\":\"\",\"cons\":\"c\"}").generate()).isNull();
    }

    @Test
    void backstop_blocks_leak() {
        assertThat(gen("{\"title\":\"이 요청은 거절하겠습니다\",\"pros\":\"p\",\"cons\":\"c\"}").generate()).isNull();
    }
}
