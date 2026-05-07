package com.maitmus.sekairouter.heartbeat;

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

class HeartbeatPromptBuilderTest {

    @Test
    void build_containsHeartbeatInstructionsAndAllSections(@TempDir Path tmp) throws IOException {
        // Write required fixture files into temp dir (acts as personaProperties.dir())
        Files.writeString(tmp.resolve("GRADES.md"), "# 호칭표\n에무 → 네네: 네네쨩\n");
        Files.writeString(tmp.resolve("quick-ref.md"), "# 빠른 참조\n반말 규칙\n");
        Files.writeString(tmp.resolve("events.json"), """
                {
                  "birthdays": [{"date": "09-09", "label": "에무 생일", "characters": ["EMU"]}],
                  "anniversaries": []
                }
                """);

        Map<CharacterId, Persona> personas = new EnumMap<>(CharacterId.class);
        personas.put(CharacterId.EMU, new Persona(CharacterId.EMU, "오오토리 에무", "에무 페르소나 본문"));
        personas.put(CharacterId.NENE, new Persona(CharacterId.NENE, "쿠사나기 네네", "네네 페르소나 본문"));

        PersonaRegistry registry = mock(PersonaRegistry.class);
        when(registry.all()).thenReturn(personas);

        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);

        HeartbeatPromptBuilder builder = new HeartbeatPromptBuilder(
                registry,
                props,
                new ClassPathResource("prompts/heartbeat-base-instructions.md"));

        String prompt = builder.build();

        // Heartbeat-specific instruction markers
        assertThat(prompt).contains("자율 발화 모드");
        assertThat(prompt).contains("JSON 없음");

        // Persona section
        assertThat(prompt).contains("## 페르소나 정의");
        assertThat(prompt).contains("에무 페르소나 본문");
        assertThat(prompt).contains("네네 페르소나 본문");

        // GRADES section
        assertThat(prompt).contains("## 호칭·존댓말 매트릭스");
        assertThat(prompt).contains("에무 → 네네: 네네쨩");

        // events section
        assertThat(prompt).contains("## 이벤트 캘린더");
        assertThat(prompt).contains("에무 생일");

        // quick-ref는 더 이상 임베드되지 않음 (Sonnet 4.6 + GRADES만으로 충분)
        assertThat(prompt).doesNotContain("## 빠른 참조");

        // CRITICAL: must NOT contain JSON schema (heartbeat is plain text, not JSON)
        assertThat(prompt).doesNotContain("출력 JSON 스키마");
    }

    @Test
    void build_worksWithoutOptionalFiles(@TempDir Path tmp) {
        // No GRADES.md / quick-ref.md / events.json — should still build cleanly
        Map<CharacterId, Persona> personas = new EnumMap<>(CharacterId.class);
        personas.put(CharacterId.EMU, new Persona(CharacterId.EMU, "오오토리 에무", "에무 내용"));

        PersonaRegistry registry = mock(PersonaRegistry.class);
        when(registry.all()).thenReturn(personas);

        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);

        HeartbeatPromptBuilder builder = new HeartbeatPromptBuilder(
                registry,
                props,
                new ClassPathResource("prompts/heartbeat-base-instructions.md"));

        String prompt = builder.build();

        assertThat(prompt).contains("자율 발화 모드");
        assertThat(prompt).contains("에무 내용");
        assertThat(prompt).doesNotContain("출력 JSON 스키마");
        // Optional sections absent when files don't exist
        assertThat(prompt).doesNotContain("호칭·존댓말 매트릭스");
    }

    @Test
    void build_includesUserMdFromParentDir(@TempDir Path tmp) throws IOException {
        // PersonaProperties.dir() is tmp/personas; USER.md is in tmp (parent)
        Path personasDir = tmp.resolve("personas");
        Files.createDirectories(personasDir);
        Files.writeString(tmp.resolve("USER.md"), "# 사용자\nMaiT입니다.\n");

        Map<CharacterId, Persona> personas = new EnumMap<>(CharacterId.class);
        personas.put(CharacterId.MINORI, new Persona(CharacterId.MINORI, "하나사토 미노리", "미노리 내용"));

        PersonaRegistry registry = mock(PersonaRegistry.class);
        when(registry.all()).thenReturn(personas);

        PersonaProperties props = new PersonaProperties(personasDir.toString(), 60_000);

        HeartbeatPromptBuilder builder = new HeartbeatPromptBuilder(
                registry,
                props,
                new ClassPathResource("prompts/heartbeat-base-instructions.md"));

        String prompt = builder.build();

        assertThat(prompt).contains("## 사용자 정보 (USER.md)");
        assertThat(prompt).contains("MaiT입니다.");
    }
}
