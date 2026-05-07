package com.maitmus.sekairouter.routing;

import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemPromptBuilderTest {

    @Test
    void build_includesAllPersonasAndInstructions() {
        Map<CharacterId, Persona> personas = new EnumMap<>(CharacterId.class);
        personas.put(CharacterId.EMU, new Persona(CharacterId.EMU, "오오토리 에무", "에무 페르소나 본문"));
        personas.put(CharacterId.NENE, new Persona(CharacterId.NENE, "쿠사나기 네네", "네네 페르소나 본문"));
        PersonaRegistry registry = mock(PersonaRegistry.class);
        when(registry.all()).thenReturn(personas);

        SystemPromptBuilder builder = new SystemPromptBuilder(
                registry,
                new ClassPathResource("prompts/router-base-instructions.md"),
                new ClassPathResource("prompts/output-schema.md"));
        String prompt = builder.build();

        assertThat(prompt).contains("라우팅 규칙");
        assertThat(prompt).contains("에무 페르소나 본문");
        assertThat(prompt).contains("네네 페르소나 본문");
        assertThat(prompt).contains("출력 JSON 스키마");
    }
}
