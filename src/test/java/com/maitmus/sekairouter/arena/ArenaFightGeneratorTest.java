package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.arena.ArenaDtos.Topic;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.OutputSanityGate;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArenaFightGeneratorTest {

    private static final Topic TOPIC = new Topic("t1", "우정은 솔직함일까 배려일까", "솔직함이 신뢰", "배려 없으면 폭력");

    private ArenaFightGenerator gen(String llm) {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        when(a.completeJson(any(PromptBlocks.class), anyString())).thenReturn(llm);
        SharedPromptContent s = mock(SharedPromptContent.class);
        when(s.build()).thenReturn("shared");
        return new ArenaFightGenerator(a, s, new OutputSanityGate(),
                mock(com.maitmus.sekairouter.persona.PersonaRegistry.class));
    }

    @Test
    void generates_fight() {
        var d = gen("""
                {"reasoning":"r","side":"CON","content":"전제가 틀렸음. 솔직함만으론 관계 안 됨.","shouldFight":true}
                """).generate(TOPIC, List.of(), null, "쿠사나기 네네");
        assertThat(d).isNotNull();
        assertThat(d.side()).isEqualTo("CON");
        assertThat(d.content()).contains("전제가 틀렸");
    }

    @Test
    void normalizes_lowercase_side() {
        var d = gen("{\"side\":\"pro\",\"content\":\"맞는 말임\",\"shouldFight\":true}").generate(TOPIC, List.of(), null, "쿠사나기 네네");
        assertThat(d.side()).isEqualTo("PRO");
    }

    @Test
    void returns_null_when_shouldFight_false() {
        assertThat(gen("{\"side\":\"\",\"content\":\"\",\"shouldFight\":false}").generate(TOPIC, List.of(), null, "쿠사나기 네네")).isNull();
    }

    @Test
    void returns_null_on_invalid_side() {
        assertThat(gen("{\"side\":\"중립\",\"content\":\"음\",\"shouldFight\":true}").generate(TOPIC, List.of(), null, "쿠사나기 네네")).isNull();
    }

    @Test
    void backstop_blocks_leak() {
        assertThat(gen("{\"side\":\"PRO\",\"content\":\"AI인 저는 답할 수 없습니다\",\"shouldFight\":true}")
                .generate(TOPIC, List.of(), null, "쿠사나기 네네")).isNull();
    }

    @Test
    void returns_null_on_parse_fail() {
        assertThat(gen("그냥 평문").generate(TOPIC, List.of(), null, "쿠사나기 네네")).isNull();
    }

    @Test
    void locked_side_overrides_llm_choice() {
        // LLM이 CON으로 응답해도 락(PRO)이 걸려 있으면 PRO로 강제(전향 방지).
        var d = gen("{\"side\":\"CON\",\"content\":\"이건 좀 아니지 않아?\",\"shouldFight\":true}")
                .generate(TOPIC, List.of(), "PRO", "쿠사나기 네네");
        assertThat(d).isNotNull();
        assertThat(d.side()).isEqualTo("PRO");
    }

    @Test
    void locked_side_is_normalized() {
        var d = gen("{\"side\":\"PRO\",\"content\":\"맞는 말이긴 한데\",\"shouldFight\":true}")
                .generate(TOPIC, List.of(), "con", "쿠사나기 네네");
        assertThat(d.side()).isEqualTo("CON");
    }
}
