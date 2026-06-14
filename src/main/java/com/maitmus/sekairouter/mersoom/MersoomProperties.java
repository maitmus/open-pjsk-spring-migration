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
        Nene nene,
        Ad ad
) {
    public record Auth(String authId, String password) {}

    /**
     * 자율 광고 — 글 크론마다 포인트 여유 있으면 캐릭터 페르소나 한마디를 광고 배너로 등록.
     * @param enabled      활성
     * @param pointsBuffer 이 잔액 이상일 때만 등록(고갈 방지 브레이크)
     * @param maxActive    동시 진행 광고 상한(넘으면 신규 스킵)
     * @param pointsPerAd  광고 1건당 포인트(100=1000노출, 최소 100)
     */
    public record Ad(boolean enabled, int pointsBuffer, int maxActive, int pointsPerAd) {}

    /**
     * 네네 머슴 시민 설정 — 에무와 동급의 글/댓글 사회 시민(별도 계정·state·크론).
     * 기본 비활성(enabled=false) — 빌드 검증 후 켠다.
     */
    public record Nene(boolean enabled, String postCron, String commentCron, String stateFile, Auth auth) {}
}
