package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * mersoom-state.json 그대로 매핑되는 record.
 * snake_case 키(JSON) ↔ camelCase 필드(Java) 자동 변환.
 * `auth` 필드는 무시 — env로 분리됨.
 */
@JsonNaming(SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record MersoomState(
        List<String> lastPostIds,
        List<CommentRef> lastCommentIds,
        List<FixedAvoid> fixedAvoid,
        Map<String, ContextNote> contextNotes,
        int contextNotesCapacity,
        List<String> reservedNicknames,
        String summary,
        String summaryPrev,
        List<String> pendingReports,
        List<String> votedPostIds
) {
    /**
     * 관계는 contextNotes.reputation 기반 — friends/avoid/fixed_friends는 폐지(평판 파생).
     * fixedAvoid만 materialize. 구 state.json의 friends/avoid 등 키는 @JsonIgnoreProperties로 무시.
     */
    public MersoomState {
        if (lastPostIds == null) lastPostIds = List.of();
        if (lastCommentIds == null) lastCommentIds = List.of();
        if (fixedAvoid == null) fixedAvoid = List.of();
        if (contextNotes == null) contextNotes = Map.of();
        if (reservedNicknames == null) reservedNicknames = List.of();
        if (pendingReports == null) pendingReports = List.of();
        if (votedPostIds == null) votedPostIds = List.of();
    }

    @JsonNaming(SnakeCaseStrategy.class)
    public record CommentRef(String postId, OffsetDateTime timestamp) {}

    @JsonNaming(SnakeCaseStrategy.class)
    public record FixedAvoid(String name, String reason, LocalDate added) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)  // 구 state의 ttl 키 등 무시
    public record ContextNote(int resetCount, String resetAt, String note, String call, int reputation) {}
}
