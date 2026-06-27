package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.arena.ArenaDtos.Topic;
import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.persona.PersonaType;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.OutputSanityGate;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArenaPromptBlocksTest {

    private static final Topic TOPIC = new Topic("t1", "제목", "p", "c");

    @Test
    void fight_generator_sends_uncached_nene_block_over_commonBase() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<PromptBlocks> cap = ArgumentCaptor.forClass(PromptBlocks.class);
        when(anthropic.completeJson(cap.capture(), any())).thenReturn("{\"shouldFight\":false}");
        SharedPromptContent shared = mock(SharedPromptContent.class);
        when(shared.commonBase()).thenReturn("COMMONBASE");
        PersonaRegistry reg = mock(PersonaRegistry.class);
        when(reg.get(CharacterId.NENE)).thenReturn(
                new Persona(CharacterId.NENE, "쿠사나기 네네", PersonaType.HUMAN_SEKAI, "네네내용"));

        new ArenaFightGenerator(anthropic, shared, new OutputSanityGate(), reg)
                .generate(TOPIC, java.util.List.of(), null, "쿠사나기 네네");

        PromptBlocks p = cap.getValue();
        assertThat(p.blocks().get(0).text()).isEqualTo("COMMONBASE");
        assertThat(p.blocks().get(0).cache()).isTrue();
        assertThat(p.blocks().get(1).cache()).isFalse();   // 아레나 tail uncached
        assertThat(p.blocks().get(1).text()).contains("네네내용");
    }

    @Test
    void propose_generator_sends_uncached_nene_block_over_commonBase() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<PromptBlocks> cap = ArgumentCaptor.forClass(PromptBlocks.class);
        when(anthropic.completeJson(cap.capture(), any())).thenReturn(
                "{\"title\":\"t\",\"pros\":\"p\",\"cons\":\"c\"}");
        SharedPromptContent shared = mock(SharedPromptContent.class);
        when(shared.commonBase()).thenReturn("COMMONBASE");
        PersonaRegistry reg = mock(PersonaRegistry.class);
        when(reg.get(CharacterId.NENE)).thenReturn(
                new Persona(CharacterId.NENE, "쿠사나기 네네", PersonaType.HUMAN_SEKAI, "네네내용"));

        new ArenaProposeGenerator(anthropic, shared, new OutputSanityGate(), reg)
                .generate();

        PromptBlocks p = cap.getValue();
        assertThat(p.blocks().get(0).text()).isEqualTo("COMMONBASE");
        assertThat(p.blocks().get(0).cache()).isTrue();
        assertThat(p.blocks().get(1).cache()).isFalse();   // 아레나 tail uncached
        assertThat(p.blocks().get(1).text()).contains("네네내용");
    }
}
