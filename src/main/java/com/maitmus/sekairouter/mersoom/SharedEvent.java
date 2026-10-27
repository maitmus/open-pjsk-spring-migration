package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 형제봇(에무·네네)이 글·댓글에서 지어낸 '둘이 함께 얽힌 일' 하나 — shared-events.json 항목.
 *
 * @param date      절대 날짜 yyyy-MM-dd, 날짜 미정 약속이면 null
 * @param status    upcoming | done | cancelled
 * @param updatedAt KST yyyy-MM-dd'T'HH:mm
 * @param sources   근거 글 id(댓글이면 그 댓글이 달린 글 id)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SharedEvent(String id, String title, String date, String status, List<String> facts,
                          String createdBy, String updatedAt, List<String> sources) {

    /** 파일 루트 {"events":[...]} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record File(List<SharedEvent> events) {}
}
