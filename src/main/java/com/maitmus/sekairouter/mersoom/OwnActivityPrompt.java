package com.maitmus.sekairouter.mersoom;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 내가 쓴 글·댓글을 글/댓글 USER 프롬프트 섹션으로 렌더 — 매 크론이 백지라 같은 날 사실이 어긋나던 것(11-06 네네
 * 대댓글 '매점 야키소바빵' 15분 뒤 글 '매점 안 갔어', 에무 점심이 카레·계란말이·주먹밥으로 세 번 바뀜)을 막는다.
 * 비면 빈 문자열.
 */
final class OwnActivityPrompt {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int MAX_COMMENTS_TODAY = 12;
    static final int MIN_COMMENTS = 6;   // 아침 첫 크론처럼 오늘 댓글이 적으면 어제 것으로 채워 소재·구절 반복 방지는 유지
    private static final int COMMENT_CHARS = 160;
    private static final int POST_CHARS = 500;

    private OwnActivityPrompt() {}

    private record Item(OffsetDateTime at, String line) {}

    /** 댓글 크론용 — 오늘 글·댓글 + (오늘 댓글이 적으면) 직전 댓글. */
    static String commentSection(MersoomState state, LocalDate today) {
        List<Item> items = collect(state, today, true);
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## 네가 쓴 글·댓글 (네 기억 — 시간순)\n");
        for (Item it : items) sb.append("- ").append(it.line()).append('\n');
        sb.append("- '오늘' 줄에서 말한 네 일(먹은 것·간 곳·한 일·만난 사람)은 이미 정해진 오늘의 사실이다. 다시 말할 땐 같은 사실로 — ")
          .append("점심을 또 다른 메뉴로 바꾸는 식으로 어긋나게 쓰지 말 것. 이번 출력의 댓글끼리도 마찬가지(한 끼는 한 메뉴). 일부러 꺼낼 필요는 없다.\n");
        sb.append("- 구절·오프닝·같은 경험담(소재)은 되풀이하지 말 것(시그니처 구호는 예외 — 평소대로). ")
          .append("(댓글 달 글 고르기와는 무관 — 비슷한 글·같은 작성자여도 평소대로 고르고, 말만 새로 한다.)\n");
        return sb.toString();
    }

    /** 글 크론용 — 오늘 글·댓글만. */
    static String postSection(MersoomState state, LocalDate today) {
        List<Item> items = collect(state, today, false);
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## 오늘 네가 쓴 글·댓글 (네 기억 — 시간순)\n");
        for (Item it : items) sb.append("- ").append(it.line()).append('\n');
        sb.append("→ 여기서 말한 네 일(먹은 것·간 곳·한 일·만난 사람)은 이미 정해진 오늘의 사실이다. 이번 글이 그 일을 다룰 땐 같은 사실로 — ")
          .append("어긋나게 고쳐 쓰지 말 것. 이걸 이번 글 소재로 고를 필요는 없다.\n\n");
        return sb.toString();
    }

    private static List<Item> collect(MersoomState state, LocalDate today, boolean padWithOlder) {
        List<Item> items = new ArrayList<>();
        for (MersoomState.PostRef p : state.lastPosts()) {
            if (p.timestamp() == null || !isToday(p.timestamp(), today)) continue;
            items.add(new Item(p.timestamp(), label(p.timestamp(), today) + " 글 「" + oneLine(p.title(), 60) + "」 "
                    + oneLine(p.text(), POST_CHARS)));
        }
        int todayCount = 0, total = 0;
        for (MersoomState.CommentRef c : state.lastCommentIds()) {   // 최신순
            if (c.timestamp() == null || c.text() == null || c.text().isBlank()) continue;
            if (isToday(c.timestamp(), today)) {
                if (todayCount >= MAX_COMMENTS_TODAY) continue;
                todayCount++;
            } else if (!padWithOlder || total >= MIN_COMMENTS) {
                break;
            }
            items.add(new Item(c.timestamp(), label(c.timestamp(), today) + " 댓글: " + oneLine(c.text(), COMMENT_CHARS)));
            total++;
        }
        items.sort(Comparator.comparing(Item::at));
        return items;
    }

    private static boolean isToday(OffsetDateTime at, LocalDate today) {
        return at.atZoneSameInstant(KST).toLocalDate().equals(today);
    }

    private static String label(OffsetDateTime at, LocalDate today) {
        var k = at.atZoneSameInstant(KST);
        String hm = String.format("%02d:%02d", k.getHour(), k.getMinute());
        if (k.toLocalDate().equals(today)) return "오늘 " + hm;
        if (k.toLocalDate().equals(today.minusDays(1))) return "어제 " + hm;
        return String.format("%02d-%02d %s", k.getMonthValue(), k.getDayOfMonth(), hm);
    }

    private static String oneLine(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
