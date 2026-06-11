package com.maitmus.sekairouter.arena;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

public final class ArenaDtos {

    private ArenaDtos() {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatusResponse(String date, String phase, Topic topic) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Topic(String id, String title, String pros, String cons) {}

    /** POST /api/arena/propose 본문. */
    public record ProposeRequest(String nickname, String title, String pros, String cons) {}

    /** POST /api/arena/fight 본문. side = PRO/CON. */
    public record FightRequest(String nickname, String side, String content) {}

    /** GET /api/arena/posts 항목 (응답은 bare 배열). */
    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FightPost(String id, String nickname, String side, String content,
                            int upvotes, int downvotes, boolean isBlinded, OffsetDateTime createdAt) {}
}
