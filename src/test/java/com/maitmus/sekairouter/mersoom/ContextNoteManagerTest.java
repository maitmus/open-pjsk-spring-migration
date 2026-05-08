package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextNoteManagerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void tickAndPrune_decrements_ttl_and_removes_expired() {
        ContextNoteManager mgr = new ContextNoteManager(clock, 1024);

        Map<String, ContextNote> notes = new LinkedHashMap<>();
        notes.put("active", new ContextNote(3, 2, "2026-05-07T10:00", "note", "오호"));
        notes.put("about_to_expire", new ContextNote(1, 1, "2026-05-07T10:00", "note", null));
        notes.put("frozen_at_zero", new ContextNote(0, 0, "2026-04-01T10:00", "note", null));

        var pruned = mgr.tickAndPrune(notes);

        assertThat(pruned).containsOnlyKeys("active", "about_to_expire");
        assertThat(pruned.get("active").ttl()).isEqualTo(2);
        assertThat(pruned.get("about_to_expire").ttl()).isEqualTo(0);
    }

    @Test
    void upsertAfterInteraction_creates_new_note_with_ttl_max() {
        ContextNoteManager mgr = new ContextNoteManager(clock, 1024);

        ContextNote result = mgr.upsertAfterInteraction(null, "[2026-05-08] 첫 교류", "오호", 8);

        assertThat(result.ttl()).isEqualTo(8);
        assertThat(result.resetCount()).isEqualTo(1);
        assertThat(result.note()).contains("[2026-05-08] 첫 교류");
        assertThat(result.call()).isEqualTo("오호");
    }

    @Test
    void upsertAfterInteraction_increments_resetCount_and_appends_event() {
        ContextNoteManager mgr = new ContextNoteManager(clock, 1024);

        ContextNote prev = new ContextNote(2, 3, "2026-05-07", "[기존]\n[과거]", "오호");
        ContextNote result = mgr.upsertAfterInteraction(prev, "[새 이벤트]", "오호", 8);

        assertThat(result.ttl()).isEqualTo(8);
        assertThat(result.resetCount()).isEqualTo(4);
        assertThat(result.note()).contains("[기존]").contains("[과거]").contains("[새 이벤트]");
    }

    @Test
    void truncate_removes_oldest_lines_when_over_limit() {
        ContextNoteManager mgr = new ContextNoteManager(clock, 50);

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 10; i++) big.append("[event-").append(i).append("] aaaa\n");

        ContextNote prev = new ContextNote(8, 5, "2026-05-08", big.toString(), null);
        ContextNote result = mgr.upsertAfterInteraction(prev, "[new] bbbb", null, 8);

        assertThat(result.note()).contains("[new] bbbb");
        assertThat(result.note().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(50);
    }
}
