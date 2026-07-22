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
                dir.resolve("arena-state.json").toString(), null, null, 2);
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

    @Test
    void notes_round_trip_and_side_preserved(@TempDir Path dir) {
        var s = store(dir);
        s.recordSide(DAY, "t1", "CON");
        s.saveNotes(DAY, "t1", "- 반박1\n- 반박2", 2);
        var n = s.notes(DAY, "t1");
        assertThat(n).isPresent();
        assertThat(n.get().notes()).contains("반박1");
        assertThat(n.get().oppCount()).isEqualTo(2);
        assertThat(s.lockedSide(DAY, "t1")).contains("CON");   // saveNotes가 side 보존
        s.recordSide(DAY, "t1", "CON");                        // recordSide 재기록해도
        assertThat(s.notes(DAY, "t1")).isPresent();            // notes 보존
    }

    @Test
    void notes_absent_for_other_date_or_topic(@TempDir Path dir) {
        var s = store(dir);
        s.saveNotes(DAY, "t1", "- 노트", 1);
        assertThat(s.notes(DAY, "t2")).isEmpty();                          // 다른 토픽
        assertThat(s.notes(DAY.plusDays(1), "t1")).isEmpty();             // 다른 날짜
    }

    @Test
    void clear_notes_keeps_side(@TempDir Path dir) {
        var s = store(dir);
        s.recordSide(DAY, "t1", "PRO");
        s.saveNotes(DAY, "t1", "- 노트", 1);
        s.clearNotes(DAY, "t1");
        assertThat(s.notes(DAY, "t1")).isEmpty();              // 노트 비움
        assertThat(s.lockedSide(DAY, "t1")).contains("PRO");   // side 유지
    }
}
