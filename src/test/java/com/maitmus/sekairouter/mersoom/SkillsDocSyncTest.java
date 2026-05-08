package com.maitmus.sekairouter.mersoom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillsDocSyncTest {

    @Test
    void initial_cache_writes_file_no_warning(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("skills-cache.md");
        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.fetchSkillsDoc(anyString())).thenReturn("v1 content");

        MersoomProperties props = mock(MersoomProperties.class);
        when(props.enabled()).thenReturn(true);
        when(props.skillsDocUrl()).thenReturn("https://www.mersoom.com/docs/skills.md");
        when(props.skillsCachePath()).thenReturn(cache.toString());

        SkillsDocSync sync = new SkillsDocSync(api, props);
        sync.run();

        assertThat(Files.readString(cache)).isEqualTo("v1 content");
    }

    @Test
    void detects_change_and_writes_new(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("skills-cache.md");
        Files.writeString(cache, "v1 content");

        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.fetchSkillsDoc(anyString())).thenReturn("v2 NEW content");

        MersoomProperties props = mock(MersoomProperties.class);
        when(props.enabled()).thenReturn(true);
        when(props.skillsDocUrl()).thenReturn("https://www.mersoom.com/docs/skills.md");
        when(props.skillsCachePath()).thenReturn(cache.toString());

        SkillsDocSync sync = new SkillsDocSync(api, props);
        sync.run();

        assertThat(Files.readString(cache)).isEqualTo("v2 NEW content");
    }

    @Test
    void no_change_does_nothing(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("skills-cache.md");
        Files.writeString(cache, "same content");

        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.fetchSkillsDoc(anyString())).thenReturn("same content");

        MersoomProperties props = mock(MersoomProperties.class);
        when(props.enabled()).thenReturn(true);
        when(props.skillsDocUrl()).thenReturn("...");
        when(props.skillsCachePath()).thenReturn(cache.toString());

        SkillsDocSync sync = new SkillsDocSync(api, props);
        sync.run();

        assertThat(Files.readString(cache)).isEqualTo("same content");
    }
}
