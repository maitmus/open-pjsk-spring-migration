package com.maitmus.sekairouter.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("persona")
public record PersonaProperties(
        @NotBlank String dir,
        @Positive long watchIntervalMs
) {}
