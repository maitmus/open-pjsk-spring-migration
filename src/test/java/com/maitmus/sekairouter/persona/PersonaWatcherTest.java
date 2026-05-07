package com.maitmus.sekairouter.persona;

import com.maitmus.sekairouter.config.PersonaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PersonaWatcherTest {

    @Test
    void onStart_loadsAllPersonas(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("emu.md"), """
                # IDENTITY - 오오토리 에무
                - **Name:** 오오토리 에무 (Emu Otori)
                - **Aliases:** 에무
                """);
        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);
        PersonaRegistry registry = new PersonaRegistry();
        PersonaWatcher watcher = new PersonaWatcher(props, new PersonaLoader(), registry);

        watcher.loadInitial();

        assertThat(registry.all()).containsKey(CharacterId.EMU);
    }

    @Test
    void detectsModification_reloads(@TempDir Path tmp) throws Exception {
        Path emuFile = tmp.resolve("emu.md");
        Files.writeString(emuFile, """
                # IDENTITY - 오오토리 에무
                - **Name:** 오오토리 에무 (Emu Otori)
                """);
        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);
        PersonaRegistry registry = new PersonaRegistry();
        PersonaWatcher watcher = new PersonaWatcher(props, new PersonaLoader(), registry);
        watcher.loadInitial();
        String before = registry.get(CharacterId.EMU).content();

        Thread.sleep(1100);  // mtime 해상도 보장
        Files.writeString(emuFile, """
                # IDENTITY - 오오토리 에무
                - **Name:** 오오토리 에무 (Emu Otori)
                - 새 내용 추가
                """);
        watcher.checkAndReload();

        assertThat(registry.get(CharacterId.EMU).content()).isNotEqualTo(before);
        assertThat(registry.get(CharacterId.EMU).content()).contains("새 내용 추가");
    }

    @Test
    void detectsDeletion_reloads(@TempDir Path tmp) throws Exception {
        Path emuFile = tmp.resolve("emu.md");
        Path airiFile = tmp.resolve("airi.md");
        Files.writeString(emuFile, """
                # IDENTITY - 오오토리 에무
                - **Name:** 오오토리 에무
                """);
        Files.writeString(airiFile, """
                # IDENTITY - 모모이 아이리
                - **Name:** 모모이 아이리
                """);
        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);
        PersonaRegistry registry = new PersonaRegistry();
        PersonaWatcher watcher = new PersonaWatcher(props, new PersonaLoader(), registry);
        watcher.loadInitial();
        assertThat(registry.all()).hasSize(2);

        Files.delete(airiFile);
        watcher.checkAndReload();

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.all()).containsOnlyKeys(CharacterId.EMU);
    }
}
