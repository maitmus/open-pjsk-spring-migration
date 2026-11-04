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

/** '한 번만 일어나는 일'(올해 첫 X·처음 꺼냄) 기록 — 2026-11-04, 네네 코타츠 3회 개시(10-29·10-31·11-03) 재발 방지. */
class MilestoneTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    @TempDir Path dir;

    @Test
    void store_keeps_milestones_per_owner_dedupes_by_id_and_prunes_after_120_days() throws Exception {
        Path f = dir.resolve("ev.json");
        var s = new SharedEventStore(new ObjectMapper(), f.toString());
        s.addMilestones("nene", List.of(new Milestone("nene", "kotatsu-out", "코타츠 꺼냄", "2026-10-29", "p1")), TODAY);
        s.addMilestones("nene", List.of(new Milestone("nene", "kotatsu-out", "코타츠 또 꺼냄", "2026-11-03", "p2")), TODAY);
        s.addMilestones("emu", List.of(new Milestone("emu", "first-breath-fog", "올해 첫 입김", "2026-11-03", "p3")), TODAY);
        assertThat(s.milestones("nene", TODAY)).extracting(Milestone::id, Milestone::date)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("kotatsu-out", "2026-10-29"));   // 먼저 기록된 날짜 유지
        assertThat(s.milestones("emu", TODAY)).extracting(Milestone::title).containsExactly("올해 첫 입김");
        assertThat(s.milestones("nene", TODAY.plusDays(130))).isEmpty();
    }

    @Test
    void shared_event_apply_preserves_milestones_and_vice_versa() throws Exception {
        Path f = dir.resolve("ev.json");
        var s = new SharedEventStore(new ObjectMapper(), f.toString());
        s.addMilestones("nene", List.of(new Milestone("nene", "kotatsu-out", "코타츠 꺼냄", "2026-10-29", "p1")), TODAY);
        s.apply(List.of(new Op("add", "show", "쇼", "2026-11-08", List.of("x"), null, null)), TODAY, "emu", "p9", "2026-11-04T10:00");
        var fresh = new SharedEventStore(new ObjectMapper(), f.toString());
        assertThat(fresh.milestones("nene", TODAY)).hasSize(1);
        assertThat(fresh.active(TODAY)).extracting(SharedEvent::id).containsExactly("show");
        fresh.addMilestones("emu", List.of(new Milestone("emu", "first-breath-fog", "올해 첫 입김", "2026-11-03", "p3")), TODAY);
        assertThat(new SharedEventStore(new ObjectMapper(), f.toString()).active(TODAY)).hasSize(1);
    }

    @Test
    void old_file_without_milestones_still_loads() throws Exception {
        Path f = dir.resolve("old.json");
        Files.writeString(f, "{\"events\":[]}");
        assertThat(new SharedEventStore(new ObjectMapper(), f.toString()).milestones("nene", TODAY)).isEmpty();
    }

    @Test
    void extractor_parses_milestones_with_default_date() {
        var ms = MilestoneExtractor.parse("""
                ```json
                {"reasoning":"코타츠 처음 꺼냄","milestones":[{"id":"kotatsu-out","title":"코타츠 꺼냄","date":null},
                 {"id":"first-snow","title":"올해 첫눈 봄","date":"2026-11-03"}]}
                ```""", "nene", TODAY, "p1");
        assertThat(ms).extracting(Milestone::id, Milestone::date).containsExactly(
                org.assertj.core.groups.Tuple.tuple("kotatsu-out", "2026-11-04"),
                org.assertj.core.groups.Tuple.tuple("first-snow", "2026-11-03"));
        assertThat(MilestoneExtractor.parse("{\"milestones\":[]}", "nene", TODAY, "p")).isEmpty();
        assertThat(MilestoneExtractor.parse("헛소리", "nene", TODAY, "p")).isEmpty();
    }

    @Test
    void prompt_section_lists_done_firsts_and_blocks_redoing_them() {
        String sec = MilestonePrompt.section(List.of(new Milestone("nene", "kotatsu-out", "코타츠 꺼냄", "2026-10-29", "p1")), TODAY);
        assertThat(sec).contains("## 이미 한 '처음'인 일", "- 10-29 코타츠 꺼냄", "다시 '처음");
        assertThat(MilestonePrompt.section(List.of(), TODAY)).isEmpty();
    }
}
