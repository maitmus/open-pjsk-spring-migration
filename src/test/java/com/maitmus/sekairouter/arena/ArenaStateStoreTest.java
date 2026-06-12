package com.maitmus.sekairouter.arena;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ArenaStateStoreTest {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 12);

    private ArenaStateStore store(Path dir) {
        ArenaProperties props = new ArenaProperties(
                true, "0 30 8 * * *", "0 30 12 * * *", "http://x/api",
                dir.resolve("arena-state.json").toString(), null, null);
        return new ArenaStateStore(props, new ObjectMapper());
    }

    @Test
    void missing_file_yields_no_lock(@TempDir Path dir) {
        assertThat(store(dir).lockedSide(DAY, "t1")).isEmpty();
    }

    @Test
    void records_and_reads_back_lock(@TempDir Path dir) {
        var s = store(dir);
        s.recordSide(DAY, "t1", "PRO");
        assertThat(s.lockedSide(DAY, "t1")).contains("PRO");
    }

    @Test
    void lock_does_not_apply_to_other_topic(@TempDir Path dir) {
        var s = store(dir);
        s.recordSide(DAY, "t1", "PRO");
        assertThat(s.lockedSide(DAY, "t2")).isEmpty();
    }

    @Test
    void lock_does_not_apply_to_other_day(@TempDir Path dir) {
        var s = store(dir);
        s.recordSide(DAY, "t1", "PRO");
        assertThat(s.lockedSide(DAY.plusDays(1), "t1")).isEmpty();
    }

    @Test
    void new_topic_overwrites_prior_lock(@TempDir Path dir) {
        var s = store(dir);
        s.recordSide(DAY, "t1", "PRO");
        s.recordSide(DAY, "t2", "CON");
        assertThat(s.lockedSide(DAY, "t1")).isEmpty();
        assertThat(s.lockedSide(DAY, "t2")).contains("CON");
    }
}
