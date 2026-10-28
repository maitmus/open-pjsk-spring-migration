package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.SharedEventLog.Op;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SharedEventLogTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final String NOW = "2026-10-27T16:30";

    private static SharedEvent ev(String id, String date, String status, String updatedAt) {
        return new SharedEvent(id, id, date, status, List.of("f"), "nene", updatedAt, List.of("s"));
    }

    @Test
    void prune_keeps_dated_event_until_three_days_after() {
        var events = List.of(ev("a", "2026-10-24", "upcoming", "2026-10-20T10:00"),   // +3 = 오늘 → 유지
                ev("b", "2026-10-23", "upcoming", "2026-10-20T10:00"));             // +3 = 어제 → 삭제
        assertThat(SharedEventLog.prune(events, TODAY)).extracting(SharedEvent::id).containsExactly("a");
    }

    @Test
    void prune_drops_closed_three_days_after_update_and_undated_after_seven() {
        var events = List.of(ev("done-old", "2026-11-10", "done", "2026-10-23T10:00"),
                ev("done-new", "2026-11-10", "done", "2026-10-24T10:00"),
                ev("undated-old", null, "upcoming", "2026-10-19T10:00"),
                ev("undated-new", null, "upcoming", "2026-10-20T10:00"));
        assertThat(SharedEventLog.prune(events, TODAY)).extracting(SharedEvent::id)
                .containsExactly("done-new", "undated-new");
    }

    @Test
    void prune_caps_at_eight_keeping_most_recently_updated() {
        List<SharedEvent> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) events.add(ev("e" + i, null, "upcoming", "2026-10-2" + (i % 10 == 9 ? 7 : 6) + "T1" + i % 10 + ":00"));
        var kept = SharedEventLog.prune(events, TODAY);
        assertThat(kept).hasSize(8).extracting(SharedEvent::id).contains("e9").doesNotContain("e0", "e1");
    }

    @Test
    void apply_adds_new_event_with_absolute_date() {
        var r = SharedEventLog.apply(List.of(),
                List.of(new Op("add", "halloween-show", "원더쇼 할로윈 쇼", "2026-10-31", List.of("츠카사 흡혈귀 망토"), null, null)),
                TODAY, "nene", "p1", NOW);
        assertThat(r.events()).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo("halloween-show");
            assertThat(e.date()).isEqualTo("2026-10-31");
            assertThat(e.status()).isEqualTo("upcoming");
            assertThat(e.createdBy()).isEqualTo("nene");
            assertThat(e.sources()).containsExactly("p1");
        });
        assertThat(r.added()).hasSize(1);
    }

    @Test
    void apply_add_with_existing_id_or_title_merges_as_update() {
        var current = List.of(new SharedEvent("halloween-show", "원더쇼 할로윈 쇼", "2026-10-31", "upcoming",
                List.of("a"), "nene", "2026-10-27T16:00", List.of("p1")));
        var r = SharedEventLog.apply(current, List.of(
                new Op("add", "halloween-show", "할로윈", null, List.of("b"), null, null),
                new Op("add", "halloween-2", "원더쇼 할로윈 쇼", null, List.of("c"), null, null)),
                TODAY, "emu", "p2", NOW);
        assertThat(r.events()).singleElement().satisfies(e -> {
            assertThat(e.facts()).containsExactly("a", "b", "c");
            assertThat(e.sources()).containsExactly("p1", "p2");
            assertThat(e.updatedAt()).isEqualTo(NOW);
        });
        assertThat(r.added()).isEmpty();
        assertThat(r.updated()).isNotEmpty();
    }

    @Test
    void apply_update_changes_status_and_skips_unknown_ids() {
        var current = List.of(ev("show", "2026-10-31", "upcoming", "2026-10-27T10:00"));
        var r = SharedEventLog.apply(current, List.of(
                new Op("update", "show", null, null, null, List.of("호박 망토 입음"), "done"),
                new Op("update", "ghost", null, null, null, List.of("x"), null)), TODAY, "emu", "p3", NOW);
        assertThat(r.events()).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo("done");
            assertThat(e.facts()).containsExactly("f", "호박 망토 입음");
        });
    }

    @Test
    void apply_rejects_out_of_range_dates_and_invalid_ids() {
        var r = SharedEventLog.apply(List.of(), List.of(
                new Op("add", "far", "먼 일", "2027-03-01", List.of("x"), null, null),
                new Op("add", "Bad ID!", "잘못", null, List.of("x"), null, null),
                new Op("add", "garbage-date", "날짜 이상", "다음주", List.of("x"), null, null)), TODAY, "emu", "p", NOW);
        assertThat(r.events()).extracting(SharedEvent::id).containsExactly("far", "garbage-date");
        assertThat(r.events()).allSatisfy(e -> assertThat(e.date()).isNull());
    }

    @Test
    void apply_caps_facts_sources_and_lengths() {
        String longFact = "가".repeat(100);
        var current = List.of(new SharedEvent("e", "e", null, "upcoming", List.of("1", "2", "3", "4", "5"), "emu",
                "2026-10-27T10:00", List.of("s1", "s2", "s3", "s4", "s5")));
        var r = SharedEventLog.apply(current, List.of(new Op("update", "e", null, null, null, List.of(longFact, "1"), null)),
                TODAY, "emu", "s6", NOW);
        var e = r.events().get(0);
        assertThat(e.facts()).hasSize(5).first().isEqualTo("2");
        assertThat(e.facts().get(4)).hasSize(80);
        assertThat(e.sources()).hasSize(5).last().isEqualTo("s6");

        var r2 = SharedEventLog.apply(List.of(), List.of(new Op("add", "long-title", "제".repeat(40), null, List.of("x"), null, null)),
                TODAY, "emu", "p", NOW);
        assertThat(r2.events().get(0).title()).hasSize(30);
    }

    @Test
    void prune_cap_keeps_upcoming_scheduled_event_over_newer_undated_chatter() {
        // 최종 리뷰: 날짜 없는 작은 약속 8개가 더 최근이면 4일 뒤 쇼가 상한에서 밀렸다 — 다가오는 일정은 우선 보존
        List<SharedEvent> events = new ArrayList<>();
        events.add(ev("show", "2026-10-31", "upcoming", "2026-10-25T10:00"));
        for (int i = 0; i < 8; i++) events.add(ev("chat" + i, null, "upcoming", "2026-10-26T1" + i + ":00"));
        var kept = SharedEventLog.prune(events, TODAY);
        assertThat(kept).hasSize(8).extracting(SharedEvent::id).contains("show").doesNotContain("chat0");
    }
}
