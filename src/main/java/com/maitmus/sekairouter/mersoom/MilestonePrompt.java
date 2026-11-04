package com.maitmus.sekairouter.mersoom;

import java.time.LocalDate;
import java.util.List;

/** {@link Milestone} 목록을 글/댓글 USER 프롬프트 섹션으로 렌더. 비면 빈 문자열. */
final class MilestonePrompt {

    private MilestonePrompt() {}

    static String section(List<Milestone> ms, LocalDate today) {
        if (ms == null || ms.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## 이미 한 '처음'인 일 (네 기록)\n");
        for (Milestone m : ms) {
            String d = m.date() != null && m.date().length() >= 10 ? m.date().substring(5, 10) : "?";
            sb.append("- ").append(d).append(' ').append(m.title()).append('\n');
        }
        // '소재로 고를 필요 없음' — 11-04 sim에서 '써도 되지만' 문구는 에무 날씨 글 2/3을 입김 회상으로 끌었다(→ 1/3).
        sb.append("→ 이번 글 소재로 고를 필요 없음 — 이 일을 쓸 때만 이미 일어난 일로(예: 꺼내 둔 코타츠에서 게임). ")
          .append("다시 '처음·드디어·올해 첫'으로 또 하는 것처럼 쓰지 말 것.\n\n");
        return sb.toString();
    }
}
