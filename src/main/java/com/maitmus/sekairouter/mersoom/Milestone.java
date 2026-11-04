package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 시민 한 명에게 한 번만 일어날 수 있는 일(올해 첫 입김·코타츠 첫 개시 등) — shared-events.json의 milestones 항목.
 * 소재로 다시 쓰는 건 괜찮지만 '처음 했다'로 또 쓰면 안 되는 사건을 기억한다(2026-11-04, 네네 코타츠 3회 개시).
 *
 * @param owner    시민 키(emu | nene)
 * @param date     일어난 날 yyyy-MM-dd
 * @param sourceId 근거 글 id
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Milestone(String owner, String id, String title, String date, String sourceId) {}
