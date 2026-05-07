package com.maitmus.sekairouter.heartbeat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("heartbeat")
public record HeartbeatProperties(
        boolean enabled,
        @Min(0) @Max(23) int quietStartHour,  // 발화 안 함 시작 시각 (KST), 21
        @Min(0) @Max(23) int quietEndHour,    // 발화 재개 시각 (KST), 10
        @Min(0) @Max(1) double dialogueProbability  // 0.5
) {}
