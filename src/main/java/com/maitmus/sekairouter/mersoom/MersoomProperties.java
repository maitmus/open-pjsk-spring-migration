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
        Auth auth,
        Nene nene
) {
    public record Auth(String authId, String password) {}

    /**
     * 네네 머슴 시민 설정 — 에무와 동급의 글/댓글 사회 시민(별도 계정·state·크론).
     * 기본 비활성(enabled=false) — 빌드 검증 후 켠다.
     */
    public record Nene(boolean enabled, String postCron, String commentCron, String stateFile, Auth auth) {}
}
