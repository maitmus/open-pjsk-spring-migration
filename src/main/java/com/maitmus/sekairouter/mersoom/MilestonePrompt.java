package com.maitmus.sekairouter.mersoom;

import java.time.LocalDate;
import java.util.List;

/** {@link Milestone} 목록을 글/댓글 USER 프롬프트 섹션으로 렌더. 비면 빈 문자열. */
final class MilestonePrompt {

    /** 프롬프트엔 최근 것만 — 파일 보존(120일)과 별개. 항목이 많을수록 기록을 소재로 끌어오는 경향이 세진다. */
    static final int SHOW_DAYS = 30;
    static final int SHOW_MAX = 30;   // 에무는 2주에 24건 — 15로 자르면 10-26 '금색 별 단추'를 또 새로 주웠다(sim)

    private MilestonePrompt() {}

    static String section(List<Milestone> ms, LocalDate today) {
        if (ms == null || ms.isEmpty()) return "";
        List<Milestone> recent = ms.stream()
                .filter(m -> m.date() != null && m.date().length() >= 10
                        && !LocalDate.parse(m.date().substring(0, 10)).isBefore(today.minusDays(SHOW_DAYS)))
                .sorted((a, b) -> a.date().compareTo(b.date()))
                .toList();
        if (recent.size() > SHOW_MAX) recent = recent.subList(recent.size() - SHOW_MAX, recent.size());
        if (recent.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## 이미 쓴 굵직한 일 (네 기록)\n");
        for (Milestone m : recent) sb.append("- ").append(m.date(), 5, 10).append(' ').append(m.title()).append('\n');
        // '소재로 고를 필요 없음' — 11-04 sim에서 '써도 되지만' 문구는 에무 날씨 글 2/3을 입김 회상으로 끌었다(→ 1/3).
        // '새 일은 자유롭게' — 없으면 에무 탐험 글 3/3이 기록된 장소 재방문으로 쏠렸다(→ 1/3).
        sb.append("→ 이번 글 소재로 고를 필요 없음(기록에 없는 새 일은 자유롭게) — 이 일을 다시 꺼낼 때만, 이미 일어난 일의 뒷이야기로")
          .append("(예: 꺼내 둔 코타츠에서 게임, 그 고양이 또 봄, 지난주 쇼 끝나고). ")
          .append("처음·드디어·올해 첫·새로 발견처럼 또 처음 일어나는 것으로 쓰지 말 것.\n\n");
        return sb.toString();
    }
}
