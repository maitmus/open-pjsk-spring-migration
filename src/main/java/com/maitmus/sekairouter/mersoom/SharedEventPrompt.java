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

    static String postSection(List<SharedEvent> events, LocalDate today, String siblingName) {
        String body = render(events, today, siblingName);
        if (body.isEmpty()) return "";
        return body + "→ 이번 글 소재로 고를 필요 없음 — 이 일을 쓸 때만 날짜·사실이 여기와 어긋나지 않게.\n\n";
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
