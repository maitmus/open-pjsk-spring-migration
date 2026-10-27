package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maitmus.sekairouter.mersoom.SharedEventLog.Op;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SharedEventStoreTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    @TempDir Path dir;

    private SharedEventStore store(Path file) {
        return new SharedEventStore(new ObjectMapper(), file.toString());
    }

    @Test
    void missing_file_is_empty() {
        assertThat(store(dir.resolve("none.json")).active(TODAY)).isEmpty();
    }

    @Test
    void empty_or_broken_file_is_empty_and_next_apply_rewrites_it() throws Exception {
        Path f = dir.resolve("broken.json");
        Files.writeString(f, "");
        var s = store(f);
        assertThat(s.active(TODAY)).isEmpty();
        Files.writeString(f, "{not json");
        assertThat(s.active(TODAY)).isEmpty();

        s.apply(List.of(new Op("add", "show", "쇼", "2026-10-31", List.of("x"), null, null)), TODAY, "emu", "p1", "2026-10-27T10:00");
        assertThat(store(f).active(TODAY)).extracting(SharedEvent::id).containsExactly("show");
    }

    @Test
    void apply_persists_and_prunes() throws Exception {
        Path f = dir.resolve("ev.json");
        Files.writeString(f, """
                {"events":[{"id":"old","title":"지난 일","date":"2026-10-20","status":"upcoming","facts":[],"createdBy":"nene","updatedAt":"2026-10-19T10:00","sources":[]}]}
                """);
        var s = store(f);
        var r = s.apply(List.of(new Op("add", "crepe", "크레페 같이 가기", null, List.of("줄 길면 패스"), null, null)),
                TODAY, "nene", "p9", "2026-10-27T10:00");
        assertThat(r.added()).hasSize(1);
        assertThat(store(f).active(TODAY)).extracting(SharedEvent::id).containsExactly("crepe");
        assertThat(Files.readString(f)).doesNotContain("\"old\"");
    }

    @Test
    void empty_ops_do_not_write() throws Exception {
        Path f = dir.resolve("noop.json");
        s(f).apply(List.of(), TODAY, "emu", "p", "2026-10-27T10:00");
        assertThat(Files.exists(f)).isFalse();
    }

    private SharedEventStore s(Path f) { return store(f); }
}
