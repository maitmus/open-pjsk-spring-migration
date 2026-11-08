package com.maitmus.sekairouter.mersoom;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@link Activity} 목록을 글/댓글 USER 프롬프트 섹션으로 렌더 — 나와 형제가 최근 며칠 '이미 한 일'.
 * 내 오늘 일은 {@link OwnActivityPrompt}(원문)가 맡으니 어제·그저께만, 형제·둘이 함께 한 일은 오늘까지(형제 원문은 내 프롬프트에 없다).
 * 비면 빈 문자열.
 */
final class ActivityPrompt {

    static final int SHOW_DAYS = 2;        // 어제·그저께
    static final int MAX_PER_DAY = 36;
    private static final String[] DOW = {"월", "화", "수", "목", "금", "토", "일"};
    private static final List<String> PART_ORDER = List.of("아침", "오전", "점심", "오후", "하교", "저녁", "밤");

    private ActivityPrompt() {}

    static String section(List<Activity> all, LocalDate today, String me, String sibling) {
        if (all == null || all.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        for (int back = SHOW_DAYS; back >= 0; back--) {
            LocalDate d = today.minusDays(back);
            String ds = d.toString();
            boolean isToday = back == 0;
            List<Activity> day = new ArrayList<>();
            for (Activity a : all) {
                if (!ds.equals(a.date()) || a.actor() == null) continue;
                if (isToday && a.actor().equals(me)) continue;   // 내 오늘 일은 원문 섹션에
                day.add(a);
            }
            if (day.size() > MAX_PER_DAY) day = day.subList(day.size() - MAX_PER_DAY, day.size());
            List<Activity> sorted = new ArrayList<>(day);
            sorted.sort(Comparator.comparingInt(a -> a.part() == null ? 99 : PART_ORDER.indexOf(a.part())));   // stable
            String label = (back == 0 ? "오늘" : back == 1 ? "어제" : "그저께") + "(" + DOW[d.getDayOfWeek().getValue() - 1] + ")";
            for (Activity a : sorted) {
                lines.add("- " + label + (a.part() == null ? "" : " " + a.part()) + " · "
                        + (a.actor().equals(me) ? "너(" + me + ")" : a.actor()) + ": " + a.text());
            }
        }
        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## 최근 너와 ").append(sibling).append("가 한 일 (기록 — 날짜별)\n");
        for (String l : lines) sb.append(l).append('\n');
        sb.append("→ 이미 일어난 일이다. 다시 말할 땐 이 사실대로 — 날짜·누가 했는지까지(").append(sibling)
          .append("가 한 일을 네가 한 일로 쓰지 말 것). 이번 글·댓글 소재로 고를 필요는 없다.\n\n");
        return sb.toString();
    }
}
