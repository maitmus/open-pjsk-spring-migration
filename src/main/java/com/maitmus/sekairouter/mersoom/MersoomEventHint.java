package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.heartbeat.EventsCalendar;
import com.maitmus.sekairouter.persona.CharacterId;

import java.util.Arrays;

/**
 * 오늘의 이벤트(생일·기념일)를 머슴 글/댓글 *유저 프롬프트*에 point-of-use로 주입하는 힌트.
 *
 * <p>events.json 캘린더는 이미 시스템 프롬프트(commonBase)에 통째로 들어가 있지만, 모델이 큰 표를
 * 능동 참조하지 않아(호칭 GRADES·시드와 같은 교훈) 생일이 있어도 반영이 안 됐다. 그래서 *오늘 것만*
 * 콕 집어 주입한다. 강제 아님(시드 힌트처럼 '어울릴 때만').
 */
final class MersoomEventHint {

    private MersoomEventHint() {}

    /** 오늘 이벤트가 있으면 힌트 섹션(당사자=자축 톤 / 타인=축하 톤), 없으면 빈 문자열. */
    static String todayLine(EventsCalendar calendar, CharacterId speaker) {
        var opt = calendar.todayOverride();
        if (opt.isEmpty()) return "";
        var ev = opt.get();
        boolean self = ev.characters().contains(speaker);
        String body;
        if (ev.kind() == EventsCalendar.EventKind.BIRTHDAY) {
            body = self
                    ? "오늘은 **네 생일**이야 — 자축을 과하게 하지 말고 너답게(담담·발랄 등) 슬쩍 한 마디 해도 좋아."
                    : "오늘은 **" + ev.label() + "**이야 — 원더쇼 동료·친구면 자연스럽게 축하 한 마디 넣어도 좋아.";
        } else {
            body = "오늘은 **" + ev.label() + "**이야 — 어울리면 자연스럽게 언급해도 좋아.";
        }
        return "\n## 오늘의 이벤트\n" + body + " **단 억지로 매 글/댓글에 우겨넣지 말 것 — 어울릴 때만.**\n";
    }

    /**
     * 댓글용 — 글 힌트와 달리 *원글이 그 이벤트를 언급한 글*("mentionsEvent":true)에서만 축하하게 한다.
     * '어울릴 때만' 산문 가드로는 못 막았다: 2026-10-05 하루카 생일에 형제봇 상호 댓글 8개 중 7개 끝에
     * 본문과 무관한 축하가 꼬리로 붙음("어깨 먼저 들어가는 거면… 키리타니 씨 생일이니까 연락 잊지 마.").
     */
    static String commentLine(EventsCalendar calendar, CharacterId speaker) {
        var opt = calendar.todayOverride();
        if (opt.isEmpty()) return "";
        var ev = opt.get();
        boolean self = ev.characters().contains(speaker);
        String what = (ev.kind() == EventsCalendar.EventKind.BIRTHDAY && self) ? "**네 생일**" : "**" + ev.label() + "**";
        return "\n## 오늘의 이벤트\n오늘은 " + what + "이야. 피드에서 **\"mentionsEvent\":true인 글(원글이 이 이벤트를 언급한 글)에서만** "
                + (self ? "너답게 슬쩍 받아도 좋아" : "자연스럽게 축하 한 마디 해도 좋아")
                + ". **표시 없는 글의 댓글엔 이벤트를 꺼내지 말 것** — 본문과 무관한 축하를 끝에 덧붙이면 어색하다."
                + " (축하 대상 인물을 다른 인물과 혼동하지 말 것 — 부르는 호칭은 평소대로.)\n";
    }

    /** 글 제목·본문이 오늘 이벤트를 언급하는지 — 라벨의 이름 토큰(생일·기념일 제외, 2자+) 포함 여부. */
    static boolean mentions(EventsCalendar.EventOverride ev, String text) {
        if (text == null || text.isBlank()) return false;
        return Arrays.stream(ev.label().split("\\s+"))
                .filter(t -> t.length() >= 2 && !t.equals("생일") && !t.equals("기념일"))
                .anyMatch(text::contains);
    }
}
