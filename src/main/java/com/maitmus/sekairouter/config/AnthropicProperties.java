package com.maitmus.sekairouter.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("anthropic")
public record AnthropicProperties(
        @NotBlank String apiKey,
        @NotBlank String model,
        @Positive int maxTokens,
        String effort   // 비면 thinking off, 있으면 adaptive + 이 effort(low|medium|high…)
) {}
