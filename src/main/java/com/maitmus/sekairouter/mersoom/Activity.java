package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 에무·네네가 글·댓글에서 말한 '이미 한 일'(먹은 것·간 곳·한 일·만난 사람) 한 줄 — shared-events.json의 activities 항목.
 * 두 시민이 함께 읽어 서로 한 말과도 어긋나지 않게 한다(2026-11-07 네네 '어제 점심 도시락' ↔ 11-06 '도서실에서 소시지빵').
 *
 * @param date     일어난 날 yyyy-MM-dd
 * @param part     시간대(아침·오전·점심·오후·하교·저녁·밤), 모르면 null
 * @param actor    한 사람 — 에무 | 네네 | 에무·네네
 * @param sourceId 근거 글 id(댓글이면 달린 글 id)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Activity(String date, String part, String actor, String text, String sourceId) {}
