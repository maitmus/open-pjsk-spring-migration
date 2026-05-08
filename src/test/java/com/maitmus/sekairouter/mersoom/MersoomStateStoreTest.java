package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MersoomStateStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void load_save_roundtrip(@TempDir Path tmp) throws Exception {
        Path stateFile = tmp.resolve("state.json");
        Files.writeString(stateFile, """
                {
                  "last_post_ids": ["p1"],
                  "last_comment_ids": [],
                  "friends": ["clovi"],
                  "avoid": [],
                  "fixed_friends": [],
                  "fixed_avoid": [],
                  "context_notes": {},
                  "context_notes_max_ttl": 8,
                  "reserved_nicknames": ["돌쇠"],
                  "pending_reports": [],
                  "voted_post_ids": []
                }
                """);

        MersoomProperties props = mockProps(stateFile.toString());
        MersoomStateStore store = new MersoomStateStore(props, objectMapper);

        MersoomState loaded = store.load();
        assertThat(loaded.lastPostIds()).containsExactly("p1");
        assertThat(loaded.friends()).containsExactly("clovi");

        // modify + save
        MersoomState updated = new MersoomState(
                List.of("p1", "p2"), List.of(), List.of("clovi"),
                List.of(), List.of(), List.of(), Map.of(), 8,
                List.of("돌쇠"), null, null, List.of(), List.of()
        );
        store.save(updated);

        MersoomState reloaded = store.load();
        assertThat(reloaded.lastPostIds()).containsExactly("p1", "p2");
    }

    @Test
    void atomic_write_uses_tmp_then_rename(@TempDir Path tmp) throws Exception {
        Path stateFile = tmp.resolve("state.json");
        Files.writeString(stateFile, "{}");

        MersoomProperties props = mockProps(stateFile.toString());
        MersoomStateStore store = new MersoomStateStore(props, objectMapper);

        MersoomState s = new MersoomState(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
        store.save(s);

        assertThat(Files.exists(stateFile.resolveSibling("state.json.tmp"))).isFalse();
        String content = Files.readString(stateFile);
        assertThat(content).contains("last_post_ids");
    }

    private MersoomProperties mockProps(String stateFile) {
        MersoomProperties p = mock(MersoomProperties.class);
        when(p.stateFile()).thenReturn(stateFile);
        return p;
    }
}
