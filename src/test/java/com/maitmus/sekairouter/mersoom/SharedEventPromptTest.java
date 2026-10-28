package com.maitmus.sekairouter.mersoom;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SharedEventPromptTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);   // 화

    private static SharedEvent ev(String title, String date, String status, String... facts) {
        return new SharedEvent("x-" + title.length(), title, date, status, List.of(facts), "nene", "2026-10-27T10:00", List.of());
    }

    @Test
    void empty_list_renders_nothing() {
        assertThat(SharedEventPrompt.render(List.of(), TODAY, "네네")).isEmpty();
        assertThat(SharedEventPrompt.postSection(List.of(), TODAY, "네네")).isEmpty();
        assertThat(SharedEventPrompt.commentSection(List.of(), TODAY, "네네")).isEmpty();
    }

    @Test
    void renders_relative_dates_weekday_status_and_facts() {
        String s = SharedEventPrompt.render(List.of(
                ev("원더쇼 할로윈 쇼", "2026-10-31", "upcoming", "츠카사 흡혈귀 망토"),
                ev("크레페", "2026-10-28", "upcoming"),
                ev("리허설", "2026-10-27", "upcoming"),
                ev("영화", "2026-10-25", "done"),
                ev("낙엽 세기 약속", null, "upcoming"),
                ev("소풍", "2026-10-29", "cancelled")), TODAY, "네네");
        assertThat(s).contains("## 네네와 얽힌 최근 일")
                .contains("- 원더쇼 할로윈 쇼 — 10-31(토), 4일 뒤\n  · 츠카사 흡혈귀 망토")
                .contains("- 크레페 — 10-28(수), 내일")
                .contains("- 리허설 — 10-27(화), 오늘")
                .contains("- 영화 — 10-25(일), 2일 전(지난 일), 끝남")
                .contains("- 낙엽 세기 약속 — 날짜 미정")
                .contains("- 소풍 — 10-29(목), 모레, 취소됨");
    }

    @Test
    void sections_carry_their_guards() {
        var events = List.of(ev("쇼", "2026-10-31", "upcoming"));
        assertThat(SharedEventPrompt.postSection(events, TODAY, "에무")).contains("## 에무와 얽힌 최근 일", "이번 글 소재로 고를 필요 없음");
        assertThat(SharedEventPrompt.commentSection(events, TODAY, "에무")).contains("일반 유저 글 댓글엔 꺼내지 말 것");
    }

    @Test
    void post_section_lists_only_upcoming_schedule_without_facts() {
        // 10-28 sim: 사실까지 다 준 8개 목록을 글에 넣자 네네 글 3/3이 이벤트를 소재로 골랐다 — 글엔 날짜 정합용 일정만
        String s = SharedEventPrompt.postSection(List.of(
                ev("할로윈 쇼", "2026-10-31", "upcoming", "에무 의상 호박 망토"),
                ev("크레페 약속", null, "upcoming", "평일 낮"),
                ev("공중제비 촬영", "2026-10-26", "upcoming"),
                ev("영화", "2026-10-29", "done")), TODAY, "네네");
        assertThat(s).contains("- 할로윈 쇼 — 10-31(토), 4일 뒤")
                .doesNotContain("호박 망토", "크레페", "공중제비", "영화");
        assertThat(SharedEventPrompt.postSection(List.of(ev("크레페 약속", null, "upcoming")), TODAY, "네네")).isEmpty();
    }
}
