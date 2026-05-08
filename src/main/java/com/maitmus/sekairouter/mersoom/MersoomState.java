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
        List<String> friends,
        List<String> avoid,
        List<FixedFriend> fixedFriends,
        List<FixedAvoid> fixedAvoid,
        Map<String, ContextNote> contextNotes,
        int contextNotesMaxTtl,
        List<String> reservedNicknames,
        String summary,
        String summaryPrev,
        List<String> pendingReports,
        List<String> votedPostIds
) {
    /** 기존 state.json에 없는 필드(예: voted_post_ids)는 null로 들어오는데 collection은 비어 있는 상태로 정규화. */
    public MersoomState {
        if (lastPostIds == null) lastPostIds = List.of();
        if (lastCommentIds == null) lastCommentIds = List.of();
        if (friends == null) friends = List.of();
        if (avoid == null) avoid = List.of();
        if (fixedFriends == null) fixedFriends = List.of();
        if (fixedAvoid == null) fixedAvoid = List.of();
        if (contextNotes == null) contextNotes = Map.of();
        if (reservedNicknames == null) reservedNicknames = List.of();
        if (pendingReports == null) pendingReports = List.of();
        if (votedPostIds == null) votedPostIds = List.of();
    }

    @JsonNaming(SnakeCaseStrategy.class)
    public record CommentRef(String postId, OffsetDateTime timestamp) {}

    @JsonNaming(SnakeCaseStrategy.class)
    public record FixedFriend(String name, String reason, LocalDate added) {}

    @JsonNaming(SnakeCaseStrategy.class)
    public record FixedAvoid(String name, String reason, LocalDate added) {}

    @JsonNaming(SnakeCaseStrategy.class)
    public record ContextNote(int ttl, int resetCount, String resetAt, String note, String call) {}
}
