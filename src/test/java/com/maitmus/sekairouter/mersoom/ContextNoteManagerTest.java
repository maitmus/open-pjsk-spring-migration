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
    private final ContextNoteManager mgr = new ContextNoteManager(clock, 1024);

    private static ContextNote note(String resetAt, int reputation) {
        return new ContextNote(1, resetAt, "note", null, reputation);
    }

    @Test
    void capByReputation_keeps_all_when_under_capacity() {
        Map<String, ContextNote> notes = new LinkedHashMap<>();
        notes.put("a", note("2026-01-01", 0));
        notes.put("b", note("2026-02-01", 0));

        assertThat(mgr.capByReputation(notes, 5)).containsOnlyKeys("a", "b");
    }

    @Test
    void capByReputation_evicts_lowest_reputation_then_oldest() {
        Map<String, ContextNote> notes = new LinkedHashMap<>();
        notes.put("oldNeutral", note("2026-01-01", 0));
        notes.put("newNeutral", note("2026-02-01", 0));
        notes.put("friend", note("2026-01-01", 5));

        var capped = mgr.capByReputation(notes, 2);

        // 평판 0 중 더 오래된 oldNeutral 제거, friend(평판≠0)는 보존
        assertThat(capped).containsOnlyKeys("newNeutral", "friend");
    }

    @Test
    void capByReputation_keeps_high_reputation_even_if_old() {
        Map<String, ContextNote> notes = new LinkedHashMap<>();
        notes.put("oldFriend", note("2026-01-01", 8));
        notes.put("recentNeutral", note("2026-12-01", 0));

        // 용량 1 → |reputation| 큰 oldFriend 보존, 최근이어도 중립이면 제거
        assertThat(mgr.capByReputation(notes, 1)).containsOnlyKeys("oldFriend");
    }

    @Test
    void upsertAfterInteraction_creates_new_note() {
        ContextNote result = mgr.upsertAfterInteraction(null, "[2026-05-08] 첫 교류", "오호");

        assertThat(result.resetCount()).isEqualTo(1);
        assertThat(result.note()).contains("[2026-05-08] 첫 교류");
        assertThat(result.call()).isEqualTo("오호");
        assertThat(result.reputation()).isZero();
    }

    @Test
    void upsertAfterInteraction_increments_resetCount_appends_and_preserves_reputation() {
        ContextNote prev = new ContextNote(3, "2026-05-07", "[기존]\n[과거]", "오호", 7);
        ContextNote result = mgr.upsertAfterInteraction(prev, "[새 이벤트]", "오호");

        assertThat(result.resetCount()).isEqualTo(4);
        assertThat(result.note()).contains("[기존]").contains("[과거]").contains("[새 이벤트]");
        assertThat(result.reputation()).isEqualTo(7); // 평판 보존
    }

    @Test
    void truncate_removes_oldest_lines_when_over_limit() {
        ContextNoteManager small = new ContextNoteManager(clock, 50);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 10; i++) big.append("[event-").append(i).append("] aaaa\n");

        ContextNote prev = new ContextNote(5, "2026-05-08", big.toString(), null, 0);
        ContextNote result = small.upsertAfterInteraction(prev, "[new] bbbb", null);

        assertThat(result.note()).contains("[new] bbbb");
        assertThat(result.note().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(50);
    }
}
