package com.maitmus.sekairouter.config;

import com.maitmus.sekairouter.persona.CharacterId;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

@Validated
@ConfigurationProperties("discord")
public record DiscordProperties(
        @NotBlank String routerToken,
        @NotBlank String sekaiChannelId,
        Map<CharacterId, String> characterTokens,
        Boolean enabled   // false면 JDA 초기화 자체를 스킵(발화·리액티브 대화 둘 다 휴면일 때 부팅 취약점·유휴연결 제거)
) {
    public DiscordProperties {
        characterTokens = characterTokens != null ? characterTokens : Map.of();
        enabled = enabled == null || enabled;   // 기본 true(미지정=활성, 기존 동작 유지)
    }
}
