package com.maitmus.sekairouter.routing;

import com.maitmus.sekairouter.config.PersonaProperties;
import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemPromptBuilderTest {

    @Test
    void build_includesAllPersonasAndInstructions(@TempDir Path tmp) {
        Map<CharacterId, Persona> personas = new EnumMap<>(CharacterId.class);
        personas.put(CharacterId.EMU, new Persona(CharacterId.EMU, "오오토리 에무", "에무 페르소나 본문"));
        personas.put(CharacterId.NENE, new Persona(CharacterId.NENE, "쿠사나기 네네", "네네 페르소나 본문"));
        PersonaRegistry registry = mock(PersonaRegistry.class);
        when(registry.all()).thenReturn(personas);
        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);

        SystemPromptBuilder builder = new SystemPromptBuilder(
                registry,
                props,
                new ClassPathResource("prompts/router-base-instructions.md"),
                new ClassPathResource("prompts/output-schema.md"));
        String prompt = builder.build();

        assertThat(prompt).contains("라우팅 규칙");
        assertThat(prompt).contains("에무 페르소나 본문");
        assertThat(prompt).contains("네네 페르소나 본문");
        assertThat(prompt).contains("출력 JSON 스키마");
        // GRADES.md 없으면 매트릭스 섹션 없이 빌드
        assertThat(prompt).doesNotContain("호칭·존댓말 매트릭스");
    }

    @Test
    void build_includesGradesMatrixWhenPresent(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("GRADES.md"), "# 호칭표\n에무 → 네네: 네네쨩\n");
        Map<CharacterId, Persona> personas = new EnumMap<>(CharacterId.class);
        personas.put(CharacterId.EMU, new Persona(CharacterId.EMU, "오오토리 에무", "에무 본문"));
        PersonaRegistry registry = mock(PersonaRegistry.class);
        when(registry.all()).thenReturn(personas);
        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);

        SystemPromptBuilder builder = new SystemPromptBuilder(
                registry,
                props,
                new ClassPathResource("prompts/router-base-instructions.md"),
                new ClassPathResource("prompts/output-schema.md"));
        String prompt = builder.build();

        assertThat(prompt).contains("호칭·존댓말 매트릭스");
        assertThat(prompt).contains("에무 → 네네: 네네쨩");
    }
}
