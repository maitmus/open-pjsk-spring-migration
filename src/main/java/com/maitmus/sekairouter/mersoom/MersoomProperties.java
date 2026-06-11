package com.maitmus.sekairouter.mersoom;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mersoom")
public record MersoomProperties(
        boolean enabled,
        @NotBlank String postCron,
        @NotBlank String commentCron,
        @NotBlank String skillsSyncCron,
        @NotBlank String stateFile,
        @NotBlank String skillsCachePath,
        @Min(256) int contextNoteBytesPerFriend,
        @Min(10) int votedPostIdsLimit,
        @Min(5) int powTimeoutSeconds,
        @Min(2) int puzzleTimeoutSeconds,
        @Min(0) int apiRateLimitSleepMs,
        @NotBlank String apiBaseUrl,
        @NotBlank String skillsDocUrl,
        Auth auth
) {
    public record Auth(String authId, String password) {}
}
