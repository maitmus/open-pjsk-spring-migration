package com.maitmus.sekairouter.heartbeat;

import com.maitmus.sekairouter.config.PersonaProperties;
import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.persona.PersonaType;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
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

    private static HeartbeatPromptBuilder newBuilder(PersonaRegistry registry, PersonaProperties props) {
        SharedPromptContent shared = new SharedPromptContent(registry, props);
        return new HeartbeatPromptBuilder(
                shared,
                new ClassPathResource("prompts/heartbeat-base-instructions.md"));
    }

    @Test
    void build_containsHeartbeatInstructionsAndAllSections(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("GRADES.md"), "# 호칭표\n에무 → 네네: 네네쨩\n");
        Files.writeString(tmp.resolve("quick-ref.md"), "# 빠른 참조\n반말 규칙\n");
        Files.writeString(tmp.resolve("events.json"), """
                {
                  "birthdays": [{"date": "09-09", "label": "에무 생일", "characters": ["EMU"]}],
                  "anniversaries": []
                }
                """);

        Map<CharacterId, Persona> personas = new EnumMap<>(CharacterId.class);
        personas.put(CharacterId.EMU, new Persona(CharacterId.EMU, "오오토리 에무", PersonaType.HUMAN_SEKAI, "에무 페르소나 본문"));
        personas.put(CharacterId.NENE, new Persona(CharacterId.NENE, "쿠사나기 네네", PersonaType.HUMAN_SEKAI, "네네 페르소나 본문"));

        PersonaRegistry registry = mock(PersonaRegistry.class);
        when(registry.all()).thenReturn(personas);

        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);

        PromptBlocks blocks = newBuilder(registry, props).build();

        // Heartbeat-specific instruction markers — blocks[1](pathSuffix)
        assertThat(blocks.blocks().get(1).text()).contains("자율 발화 모드");
        // 출력은 이제 {"reasoning":"...","utterance":"..."} JSON 형식
        assertThat(blocks.blocks().get(1).text()).contains("\"utterance\"");
        assertThat(blocks.blocks().get(1).text()).contains("reasoning");

        // Persona section — blocks[0](sharedPrefix)
        assertThat(blocks.blocks().get(0).text()).contains("## 페르소나 정의");
        assertThat(blocks.blocks().get(0).text()).contains("에무 페르소나 본문");
        assertThat(blocks.blocks().get(0).text()).contains("네네 페르소나 본문");

        // GRADES section — blocks[0](sharedPrefix)
        assertThat(blocks.blocks().get(0).text()).contains("## 호칭·존댓말 매트릭스");
        assertThat(blocks.blocks().get(0).text()).contains("에무 → 네네: 네네쨩");

        // events section — blocks[0](sharedPrefix)
        assertThat(blocks.blocks().get(0).text()).contains("## 이벤트 캘린더");
        assertThat(blocks.blocks().get(0).text()).contains("에무 생일");

        // quick-ref는 더 이상 임베드되지 않음
        assertThat(blocks.blocks().get(0).text()).doesNotContain("## 빠른 참조");
        assertThat(blocks.blocks().get(1).text()).doesNotContain("## 빠른 참조");

        // CRITICAL: must NOT contain JSON schema (heartbeat is plain text)
        assertThat(blocks.blocks().get(1).text()).doesNotContain("출력 JSON 스키마");
    }

    @Test
    void build_worksWithoutOptionalFiles(@TempDir Path tmp) {
        Map<CharacterId, Persona> personas = new EnumMap<>(CharacterId.class);
        personas.put(CharacterId.EMU, new Persona(CharacterId.EMU, "오오토리 에무", PersonaType.HUMAN_SEKAI, "에무 내용"));

        PersonaRegistry registry = mock(PersonaRegistry.class);
        when(registry.all()).thenReturn(personas);

        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);

        PromptBlocks blocks = newBuilder(registry, props).build();

        assertThat(blocks.blocks().get(1).text()).contains("자율 발화 모드");
        assertThat(blocks.blocks().get(0).text()).contains("에무 내용");
        assertThat(blocks.blocks().get(1).text()).doesNotContain("출력 JSON 스키마");
        assertThat(blocks.blocks().get(0).text()).doesNotContain("호칭·존댓말 매트릭스");
    }

    @Test
    void build_includesUserMdFromParentDir(@TempDir Path tmp) throws IOException {
        Path personasDir = tmp.resolve("personas");
        Files.createDirectories(personasDir);
        Files.writeString(tmp.resolve("USER.md"), "# 사용자\nMaiT입니다.\n");

        Map<CharacterId, Persona> personas = new EnumMap<>(CharacterId.class);
        personas.put(CharacterId.MINORI, new Persona(CharacterId.MINORI, "하나사토 미노리", PersonaType.HUMAN_SEKAI, "미노리 내용"));

        PersonaRegistry registry = mock(PersonaRegistry.class);
        when(registry.all()).thenReturn(personas);

        PersonaProperties props = new PersonaProperties(personasDir.toString(), 60_000);

        PromptBlocks blocks = newBuilder(registry, props).build();

        assertThat(blocks.blocks().get(0).text()).contains("## 사용자 정보 (USER.md)");
        assertThat(blocks.blocks().get(0).text()).contains("MaiT입니다.");
    }
}
