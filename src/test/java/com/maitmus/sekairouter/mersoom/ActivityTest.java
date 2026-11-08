package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);   // 일

    @TempDir Path dir;

    private static final List<ActivityExtractor.Item> ITEMS = List.of(
            new ActivityExtractor.Item("글", "p1", "", "제목: 회전목마\n본문: 에무랑 회전목마 탔어"),
            new ActivityExtractor.Item("댓글", "p2", "(원글) ...", "난 코타츠에서 랭크전 중이야"));

    @Test
    void parse_normalizes_actor_part_date_and_maps_item_to_source() {
        String raw = """
                {"reasoning":"x","activities":[
                 {"item":1,"actor":"에무, 네네","date":null,"part":"오후","text":"회전목마 한 바퀴"},
                 {"item":2,"actor":"Nene","date":"2026-11-07","part":"새벽","text":"코타츠에서 랭크전"},
                 {"item":2,"actor":"시라이시","date":null,"part":null,"text":"도시락"},
                 {"item":1,"actor":"에무","date":"2026-11-09","part":null,"text":"내일 리허설"}]}""";
        var out = ActivityExtractor.parse(raw, TODAY, ITEMS);
        assertThat(out).containsExactly(
                new Activity("2026-11-08", "오후", "에무·네네", "회전목마 한 바퀴", "p1"),
                new Activity("2026-11-07", null, "네네", "코타츠에서 랭크전", "p2"));   // 제3자·미래 제외, 모르는 시간대 null
        assertThat(ActivityExtractor.parse("{broken", TODAY, ITEMS)).isEmpty();
    }

    @Test
    void user_prompt_lists_recent_log_and_numbered_items_with_context() {
        var existing = List.of(new Activity("2026-11-05", null, "네네", "오래된 일", "x"),
                new Activity("2026-11-07", "오후", "에무·네네", "잔디밭 낮잠 대회", "y"));
        String p = ActivityExtractor.buildUserPrompt("네네", "에무", TODAY, LocalTime.of(13, 0), ITEMS, existing);
        assertThat(p).contains("2026-11-08(일) 13:00").contains("네네 (같은 극단 친구: 에무)")
                .contains("- 2026-11-07 오후 에무·네네: 잔디밭 낮잠 대회").doesNotContain("오래된 일")
                .contains("## 1. 방금 올린 글").contains("## 2. 방금 올린 댓글").contains("(맥락) (원글) ...");
    }

    @Test
    void store_dedups_prunes_and_keeps_other_sections() throws Exception {
        Path f = dir.resolve("ev.json");
        Files.writeString(f, """
                {"events":[],"milestones":[{"owner":"emu","id":"kotatsu","title":"코타츠","date":"2026-11-01","sourceId":"m"}],
                 "activities":[{"date":"2026-11-04","actor":"네네","text":"너무 오래됨"},{"date":"2026-11-07","actor":"네네","text":"카레 먹음"}]}
                """);
        var s = new SharedEventStore(new ObjectMapper(), f.toString());
        var added = s.addActivities(List.of(new Activity("2026-11-07", "저녁", "네네", "카레 먹음.", "p"),
                new Activity("2026-11-08", "점심", "에무", "주먹밥", "p")), TODAY);
        assertThat(added).extracting(Activity::text).containsExactly("주먹밥");
        assertThat(s.activities(TODAY)).extracting(Activity::text).containsExactly("카레 먹음", "주먹밥");
        assertThat(s.milestones("emu", TODAY)).hasSize(1);
        // milestone 추가가 activities를 지우지 않음
        s.addMilestones("nene", List.of(new Milestone(null, "first-snow", "첫눈", "2026-11-08", "q")), TODAY);
        assertThat(s.activities(TODAY)).hasSize(2);
    }

    @Test
    void prompt_shows_sibling_and_joint_through_today_but_mine_only_before_today() {
        var all = List.of(
                new Activity("2026-11-05", null, "네네", "사흘 전 일", "a"),
                new Activity("2026-11-06", "점심", "네네", "도서실에서 편의점 소시지빵", "a"),
                new Activity("2026-11-07", "저녁", "에무", "우동 먹음", "b"),
                new Activity("2026-11-07", "오후", "에무·네네", "잔디밭 낮잠 대회", "c"),
                new Activity("2026-11-08", "점심", "에무", "오늘 내 점심", "d"),
                new Activity("2026-11-08", "오전", "네네", "코타츠에서 랭크전", "e"));
        String s = ActivityPrompt.section(all, TODAY, "에무", "네네");
        assertThat(s).startsWith("## 최근 너와 네네가 한 일")
                .contains("- 그저께(금) 점심 · 네네: 도서실에서 편의점 소시지빵")
                .contains("- 어제(토) 오후 · 에무·네네: 잔디밭 낮잠 대회")
                .contains("- 어제(토) 저녁 · 너(에무): 우동 먹음")
                .contains("- 오늘(일) 오전 · 네네: 코타츠에서 랭크전")
                .doesNotContain("오늘 내 점심").doesNotContain("사흘 전 일")
                .contains("네네가 한 일을 네가 한 일로 쓰지 말 것");
        assertThat(s.indexOf("어제(토) 오후")).isLessThan(s.indexOf("어제(토) 저녁"));   // 시간대순
        assertThat(ActivityPrompt.section(List.of(), TODAY, "에무", "네네")).isEmpty();
        assertThat(ActivityPrompt.section(List.of(all.get(4)), TODAY, "에무", "네네")).isEmpty();
    }
}
