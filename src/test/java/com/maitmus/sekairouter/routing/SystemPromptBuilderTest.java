package com.maitmus.sekairouter.routing;

import com.maitmus.sekairouter.config.PersonaProperties;
import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.persona.PersonaType;
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

    private static SystemPromptBuilder newBuilder(PersonaRegistry registry, PersonaProperties props) {
        SharedPromptContent shared = new SharedPromptContent(registry, props);
        return new SystemPromptBuilder(
                shared,
                new ClassPathResource("prompts/router-base-instructions.md"),
                new ClassPathResource("prompts/output-schema.md"));
    }

    @Test
    void build_includesAllPersonasAndInstructions(@TempDir Path tmp) {
        Map<CharacterId, Persona> personas = new EnumMap<>(CharacterId.class);
        personas.put(CharacterId.EMU, new Persona(CharacterId.EMU, "오오토리 에무", PersonaType.HUMAN_SEKAI, "에무 페르소나 본문"));
        personas.put(CharacterId.NENE, new Persona(CharacterId.NENE, "쿠사나기 네네", PersonaType.HUMAN_SEKAI, "네네 페르소나 본문"));
        PersonaRegistry registry = mock(PersonaRegistry.class);
        when(registry.all()).thenReturn(personas);
        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);

        PromptBlocks blocks = newBuilder(registry, props).build();

        // 페르소나/매트릭스/이벤트는 sharedPrefix에
        assertThat(blocks.sharedPrefix()).contains("에무 페르소나 본문");
        assertThat(blocks.sharedPrefix()).contains("네네 페르소나 본문");
        // 라우터 지시문/출력 스키마는 pathSuffix에
        assertThat(blocks.pathSuffix()).contains("라우팅 규칙");
        assertThat(blocks.pathSuffix()).contains("출력 JSON 스키마");
        // GRADES.md 없으면 매트릭스 섹션 없이 빌드
        assertThat(blocks.sharedPrefix()).doesNotContain("호칭·존댓말 매트릭스");
    }

    @Test
    void build_includesGradesMatrixWhenPresent(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("GRADES.md"), "# 호칭표\n에무 → 네네: 네네쨩\n");
        Map<CharacterId, Persona> personas = new EnumMap<>(CharacterId.class);
        personas.put(CharacterId.EMU, new Persona(CharacterId.EMU, "오오토리 에무", PersonaType.HUMAN_SEKAI, "에무 본문"));
        PersonaRegistry registry = mock(PersonaRegistry.class);
        when(registry.all()).thenReturn(personas);
        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);

        PromptBlocks blocks = newBuilder(registry, props).build();

        assertThat(blocks.sharedPrefix()).contains("호칭·존댓말 매트릭스");
        assertThat(blocks.sharedPrefix()).contains("에무 → 네네: 네네쨩");
    }
}
