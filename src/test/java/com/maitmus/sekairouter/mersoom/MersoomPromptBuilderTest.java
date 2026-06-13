package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.persona.PersonaType;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MersoomPromptBuilderTest {

    private MersoomPromptBuilder builder(SharedPromptContent shared, PersonaRegistry registry) {
        return new MersoomPromptBuilder(
                shared,
                registry,
                new ClassPathResource("prompts/mersoom-instructions.md"),
                new ClassPathResource("prompts/mersoom-instructions-nene.md"));
    }

    @Test
    void build_returns_PromptBlocks_with_shared_prefix_and_mersoom_suffix() {
        SharedPromptContent shared = mock(SharedPromptContent.class);
        when(shared.build()).thenReturn("SHARED CONTENT (USER + 페르소나 + GRADES)");

        PromptBlocks blocks = builder(shared, mock(PersonaRegistry.class)).build();

        assertThat(blocks.sharedPrefix()).contains("SHARED CONTENT");
        assertThat(blocks.pathSuffix()).contains("머슴 자율 발화 모드");
        assertThat(blocks.pathSuffix()).contains("음슴체 규칙 무시");
    }

    @Test
    void build_nene_injects_nene_persona_and_nene_suffix() {
        SharedPromptContent shared = mock(SharedPromptContent.class);
        when(shared.build()).thenReturn("SHARED CONTENT");
        PersonaRegistry registry = mock(PersonaRegistry.class);
        when(registry.get(CharacterId.NENE)).thenReturn(
                new Persona(CharacterId.NENE, "쿠사나기 네네", PersonaType.HUMAN_SEKAI, "네네 페르소나 정의 본문"));

        CitizenProfile nene = new CitizenProfile("nene", "네네",
                new MersoomProperties.Auth("nene_wonder", "x"),
                Path.of("/tmp/nene.json"), CharacterId.NENE, Set.of("emu_wonder"));

        PromptBlocks blocks = builder(shared, registry).build(nene);

        assertThat(blocks.sharedPrefix()).isEqualTo("SHARED CONTENT");
        assertThat(blocks.pathSuffix()).contains("너는 쿠사나기 네네");
        assertThat(blocks.pathSuffix()).contains("네네 페르소나 정의 본문");
        assertThat(blocks.pathSuffix()).contains("쿠사나기 네네)");   // 네네 지시문 헤더
        assertThat(blocks.pathSuffix()).contains("1인칭");
    }

    @Test
    void build_emu_profile_matches_default() {
        SharedPromptContent shared = mock(SharedPromptContent.class);
        when(shared.build()).thenReturn("SHARED");

        CitizenProfile emu = new CitizenProfile("emu", "에무",
                new MersoomProperties.Auth("emu_wonder", "x"),
                Path.of("/tmp/emu.json"), CharacterId.EMU, Set.of("nene_wonder"));

        MersoomPromptBuilder b = builder(shared, mock(PersonaRegistry.class));
        assertThat(b.build(emu).pathSuffix()).isEqualTo(b.build().pathSuffix());
    }
}
