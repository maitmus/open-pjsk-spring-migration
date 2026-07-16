package com.maitmus.sekairouter.config;

import com.maitmus.sekairouter.discord.RouterEventListener;
import com.maitmus.sekairouter.persona.CharacterId;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class DiscordConfig {

    private final DiscordProperties properties;
    private Map<CharacterId, JDA> characterJdaRefs;

    // discord.enabled=false면 라우터봇 JDA 생성 스킵(리액티브 리스너 미부착 = 인바운드 없음). 아무도 이 빈을 주입받지 않아 안전.
    @Bean(name = "routerJda", destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "discord.enabled", havingValue = "true", matchIfMissing = true)
    public JDA routerJda(RouterEventListener listener) throws InterruptedException {
        log.info("Starting router bot JDA...");
        return JDABuilder.createDefault(properties.routerToken())
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(listener)
                .build()
                .awaitReady();
    }

    @Bean
    public Map<CharacterId, JDA> characterJdas() throws InterruptedException {
        Map<CharacterId, JDA> map = new EnumMap<>(CharacterId.class);
        if (!properties.enabled()) {   // 비활성 — JDABuilder·discord.com DNS 호출 안 함(부팅 취약점 제거). 빈 맵은 발화 때만 쓰여 무해.
            log.info("Discord 비활성(discord.enabled=false) — 캐릭터 봇 JDA 초기화 스킵");
            this.characterJdaRefs = map;
            return map;
        }
        for (Map.Entry<CharacterId, String> entry : properties.characterTokens().entrySet()) {
            String token = entry.getValue();
            if (token == null || token.isBlank()) {
                log.warn("No token for {} — skipping", entry.getKey());
                continue;
            }
            log.info("Starting character bot {} JDA...", entry.getKey());
            JDA jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES)
                    .build()
                    .awaitReady();
            map.put(entry.getKey(), jda);
        }
        this.characterJdaRefs = map;
        return map;
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService typingScheduler() {
        return Executors.newScheduledThreadPool(4);
    }

    @PreDestroy
    public void shutdownCharacterJdas() {
        if (characterJdaRefs == null) return;
        characterJdaRefs.forEach((id, jda) -> {
            log.info("Shutting down character bot {} JDA...", id);
            try {
                jda.shutdown();
            } catch (Exception e) {
                log.warn("Error shutting down {} JDA: {}", id, e.getMessage());
            }
        });
    }
}
