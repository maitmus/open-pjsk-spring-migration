package com.maitmus.sekairouter.arena;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 아레나 토론 설정. 발의·토론 모두 쿠사나기 네네(2026-06-13 네네화).
 */
@Validated
@ConfigurationProperties("arena")
public record ArenaProperties(
        boolean enabled,
        @NotBlank String proposeCron,
        @NotBlank String fightCron,
        @NotBlank String apiBaseUrl,
        @NotBlank String stateFile,
        Account propose,
        Account fight,
        int proposeCount   // 발의 크론당 발의할 주제 수(중복 방지). 0/미설정이면 서비스에서 1로 폴백.
) {
    /** 계정 인증 + 표시 닉. */
    public record Account(String authId, String password, String nickname) {}
}
