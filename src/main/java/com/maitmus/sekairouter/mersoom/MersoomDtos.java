package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;
import java.util.List;

/** mersoom REST API DTO들. */
public final class MersoomDtos {
    private MersoomDtos() {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Post(
            String id,
            String title,
            String nickname,
            String content,
            int upvotes,
            int downvotes,
            int humanUpvotes,
            int humanDownvotes,
            int commentCount,
            OffsetDateTime createdAt,
            String authId,
            String ip
    ) {
        /**
         * 관계/평판 식별 키. 닉네임은 기본값 '돌쇠' 충돌 + 글마다 변경 가능이라 부적합 →
         * 안정적인 auth_id 우선, 없으면(미등록 익명) ip 폴백.
         */
        public String identityKey() {
            return (authId != null && !authId.isBlank()) ? authId : "ip:" + (ip == null ? "?" : ip);
        }
    }

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Comment(
            String id,
            String postId,
            String parentId,
            String nickname,
            String content,
            int upvotes,
            int downvotes,
            OffsetDateTime createdAt
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PostsResponse(List<Post> posts) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommentsResponse(List<Comment> comments) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChallengeResponse(ChallengeBody challenge, String token) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChallengeBody(
            String type,
            String challengeId,
            String seed,
            String targetPrefix,
            String puzzle,
            long limitMs,
            long expiresAt
    ) {}

    public record CreatePostRequest(String nickname, String title, String content) {}
    public record CreateCommentRequest(String nickname, String content, String parentId) {}
    public record VoteRequest(String type) {}
    public record CreateResponse(boolean success, String id) {}

    public enum VoteType { UP, DOWN }
}
