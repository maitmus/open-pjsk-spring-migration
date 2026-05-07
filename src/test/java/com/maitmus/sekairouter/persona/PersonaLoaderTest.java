package com.maitmus.sekairouter.persona;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PersonaLoaderTest {

    @Test
    void loadAll_returnsAllPersonasInDirectory() throws Exception {
        Path fixtureDir = Paths.get("src/test/resources/persona-fixtures");
        PersonaLoader loader = new PersonaLoader();

        Map<CharacterId, Persona> personas = loader.loadAll(fixtureDir);

        assertThat(personas).containsOnlyKeys(CharacterId.AIRI, CharacterId.EMU);
        assertThat(personas.get(CharacterId.AIRI).displayName()).isEqualTo("모모이 아이리");
        assertThat(personas.get(CharacterId.AIRI).content()).contains("당당하고 기 셈");
        assertThat(personas.get(CharacterId.EMU).displayName()).isEqualTo("오오토리 에무");
    }

    @Test
    void loadAll_skipsNonCharacterFiles() throws Exception {
        Path fixtureDir = Paths.get("src/test/resources/persona-fixtures");
        PersonaLoader loader = new PersonaLoader();

        // GRADES.md, quick-ref.md 등은 CharacterId 매칭 안 됨 → 스킵
        Map<CharacterId, Persona> personas = loader.loadAll(fixtureDir);

        assertThat(personas).hasSize(2);  // airi + emu만
    }
}
