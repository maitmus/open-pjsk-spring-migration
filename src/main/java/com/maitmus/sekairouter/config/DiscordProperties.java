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
        Map<CharacterId, String> characterTokens
) {
    public DiscordProperties {
        characterTokens = characterTokens != null ? characterTokens : Map.of();
    }
}
