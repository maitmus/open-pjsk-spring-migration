package com.maitmus.sekairouter.routing;

import com.maitmus.sekairouter.config.PersonaProperties;
import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.persona.PersonaType;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SharedPromptContentTest {

    private SharedPromptContent withPersonas() {
        PersonaRegistry reg = mock(PersonaRegistry.class);
        Persona emu = new Persona(CharacterId.EMU, "오오토리 에무", PersonaType.HUMAN_SEKAI, "에무 페르소나 내용");
        Persona nene = new Persona(CharacterId.NENE, "쿠사나기 네네", PersonaType.HUMAN_SEKAI, "네네 페르소나 내용");
        Map<CharacterId, Persona> all = new EnumMap<>(CharacterId.class);
        all.put(CharacterId.EMU, emu);
        all.put(CharacterId.NENE, nene);
        when(reg.all()).thenReturn(all);
        when(reg.get(CharacterId.EMU)).thenReturn(emu);
        when(reg.get(CharacterId.NENE)).thenReturn(nene);
        PersonaProperties props = mock(PersonaProperties.class);
        when(props.dir()).thenReturn("/nonexistent");   // 파일들은 없으면 skip → commonBase엔 출력규칙만
        return new SharedPromptContent(reg, props);
    }

    @Test
    void commonBase_has_output_rules_no_personas() {
        String c = withPersonas().commonBase();
        assertThat(c).contains("출력 공통 규칙").contains("전부 한글");
        assertThat(c).doesNotContain("페르소나 정의").doesNotContain("매트릭스");
    }

    @Test
    void voiceRoster_has_all_personas() {
        String v = withPersonas().voiceRoster();
        assertThat(v).contains("페르소나 정의").contains("에무 페르소나 내용").contains("네네 페르소나 내용");
    }

    @Test
    void personaInjection_is_single_character_with_note() {
        String inj = withPersonas().personaInjection(CharacterId.EMU, "특히 말투");
        assertThat(inj).contains("너는 오오토리 에무").contains("특히 말투").contains("에무 페르소나 내용");
        assertThat(inj).doesNotContain("네네 페르소나 내용");
    }
}
