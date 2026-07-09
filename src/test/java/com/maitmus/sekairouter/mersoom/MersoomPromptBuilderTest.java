package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MersoomPromptBuilderTest {

    private MersoomPromptBuilder builder() {
        SharedPromptContent shared = mock(SharedPromptContent.class);
        when(shared.commonBase()).thenReturn("COMMONBASE");
        when(shared.personaInjection(CharacterId.EMU, "특히 말투")).thenReturn("EMU체화");
        when(shared.personaInjection(CharacterId.NENE, "특히 말투")).thenReturn("NENE체화");
        PersonaRegistry reg = mock(PersonaRegistry.class);
        return new MersoomPromptBuilder(shared, reg,
                new ByteArrayResource("에무지침".getBytes()),
                new ByteArrayResource("네네지침".getBytes()));
    }

    @Test
    void emu_block_has_self_sibling_min_instructions_no_grades() {
        var blocks = builder().build().blocks();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).text()).isEqualTo("COMMONBASE");
        String b1 = blocks.get(1).text();
        assertThat(b1).contains("EMU체화").contains("에무지침").contains("네네쨩");  // 형제봇 최소
        assertThat(b1).doesNotContain("매트릭스").doesNotContain("페르소나 정의");
    }

    @Test
    void nene_block_uses_nene_self_and_emu_sibling_min() {
        CitizenProfile nene = mock(CitizenProfile.class);
        when(nene.persona()).thenReturn(CharacterId.NENE);
        var blocks = builder().build(nene).blocks();
        String b1 = blocks.get(1).text();
        assertThat(b1).contains("NENE체화").contains("네네지침").contains("에무");  // 형제봇=에무
        assertThat(b1).doesNotContain("매트릭스");
    }

    @Test
    void build_emu_profile_matches_default() {
        MersoomPromptBuilder b = builder();
        CitizenProfile emu = mock(CitizenProfile.class);
        when(emu.persona()).thenReturn(CharacterId.EMU);
        assertThat(b.build(emu).blocks().get(1).text()).isEqualTo(b.build().blocks().get(1).text());
    }
}
