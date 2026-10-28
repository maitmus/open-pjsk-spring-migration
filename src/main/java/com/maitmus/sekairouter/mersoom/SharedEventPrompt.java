package com.maitmus.sekairouter.mersoom;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 공유 이벤트를 글/댓글 USER 프롬프트 섹션으로 렌더 — 날짜 상대화(오늘·내일·모레·N일 뒤/전)와 요일은 코드가 계산한다
 * (모델에 맡기면 '나흘 남음'이 하루 지나도 그대로 복사된다).
 */
final class SharedEventPrompt {

    private static final String[] DOW = {"월", "화", "수", "목", "금", "토", "일"};

    private SharedEventPrompt() {}

    static String render(List<SharedEvent> events, LocalDate today, String siblingName) {
        if (events == null || events.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(siblingName).append("와 얽힌 최근 일 (공유 기억 — 이어 쓸 수 있는 배경일 뿐)\n");
        for (SharedEvent e : events) {
            sb.append("- ").append(e.title()).append(" — ").append(when(e.date(), today));
            if ("done".equals(e.status())) sb.append(", 끝남");
            else if ("cancelled".equals(e.status())) sb.append(", 취소됨");
            sb.append('\n');
            if (e.facts() != null) for (String f : e.facts()) sb.append("  · ").append(f).append('\n');
        }
        return sb.toString();
    }

    /**
     * 글엔 다가오는 날짜 있는 일정만, 사실 없이 — 10-28 sim에서 사실까지 다 준 목록을 넣자 네네 글 3/3이 이벤트를 소재로 골랐다.
     * 글에서 어긋나면 티 나는 건 날짜라 그것만 맞추고, 사실 정합은 형제 글을 받는 댓글 쪽에서 맡는다.
     */
    static String postSection(List<SharedEvent> events, LocalDate today, String siblingName) {
        if (events == null) return "";
        List<SharedEvent> upcoming = events.stream()
                .filter(e -> "upcoming".equals(e.status()) && !unscheduledOrPast(e.date(), today))
                .map(e -> new SharedEvent(e.id(), e.title(), e.date(), e.status(), List.of(), e.createdBy(), e.updatedAt(), e.sources()))
                .toList();
        String body = render(upcoming, today, siblingName);
        if (body.isEmpty()) return "";
        return body + "→ 이번 글 소재로 고를 필요 없음 — 이 일을 쓸 때만 날짜가 여기와 어긋나지 않게.\n\n";
    }

    /** 날짜가 없거나(미정) 깨졌으면 일정이 아니므로 true로 걸러낸다. */
    private static boolean unscheduledOrPast(String date, LocalDate today) {
        try {
            return date == null || LocalDate.parse(date).isBefore(today);
        } catch (DateTimeParseException ex) {
            return true;
        }
    }

    static String commentSection(List<SharedEvent> events, LocalDate today, String siblingName) {
        String body = render(events, today, siblingName);
        if (body.isEmpty()) return "";
        return body + "→ " + siblingName + "의 글·답글이 이 일을 다룰 때만 날짜·사실을 맞춰 받을 것. 일반 유저 글 댓글엔 꺼내지 말 것.\n";
    }

    private static String when(String date, LocalDate today) {
        LocalDate d;
        try {
            d = date == null ? null : LocalDate.parse(date);
        } catch (DateTimeParseException ex) {
            d = null;
        }
        if (d == null) return "날짜 미정";
        long diff = ChronoUnit.DAYS.between(today, d);
        String rel = diff == 0 ? "오늘" : diff == 1 ? "내일" : diff == 2 ? "모레" : diff > 2 ? diff + "일 뒤"
                : diff == -1 ? "어제(지난 일)" : (-diff) + "일 전(지난 일)";
        return "%02d-%02d(%s), %s".formatted(d.getMonthValue(), d.getDayOfMonth(), DOW[d.getDayOfWeek().getValue() - 1], rel);
    }
}
