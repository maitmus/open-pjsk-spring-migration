package com.maitmus.sekairouter.heartbeat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class HeartbeatConfig {

    @Bean
    public Clock heartbeatClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
