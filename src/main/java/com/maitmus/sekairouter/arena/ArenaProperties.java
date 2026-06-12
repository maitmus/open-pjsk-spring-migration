package com.maitmus.sekairouter.arena;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 아레나 토론 설정. 발의(에무)·토론(쿠사나기 네네)이 서로 다른 머슴 계정을 쓴다.
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
        Account fight
) {
    /** 계정 인증 + 표시 닉. */
    public record Account(String authId, String password, String nickname) {}
}
