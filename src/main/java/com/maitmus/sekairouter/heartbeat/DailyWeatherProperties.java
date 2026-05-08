package com.maitmus.sekairouter.heartbeat;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Daily scheduled weather utterance — random character casts the day's weather for a fixed
 * location. Cron defaults to 09:30 KST so the cache_creation it incurs is still alive when
 * the next heartbeat fires (latest at 10:29 KST after the 10:00 reroll), enabling cross-call
 * cache hits across daily-weather → first morning heartbeat.
 */
@Validated
@ConfigurationProperties("daily-weather")
public record DailyWeatherProperties(
        boolean enabled,
        @NotBlank String cron,        // 기본 "0 30 9 * * *" — 매일 09:30 KST
        @NotBlank String location     // 기본 "부산 중앙동"
) {}
