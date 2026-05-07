package com.maitmus.sekairouter.config;

import com.maitmus.sekairouter.persona.CharacterId;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class DiscordConfig {

    private final DiscordProperties properties;
    private Map<CharacterId, JDA> characterJdaRefs;

    @Bean(name = "routerJda", destroyMethod = "shutdown")
    public JDA routerJda() throws InterruptedException {
        log.info("Starting router bot JDA...");
        return JDABuilder.createDefault(properties.routerToken())
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .build()
                .awaitReady();
    }

    @Bean
    public Map<CharacterId, JDA> characterJdas() throws InterruptedException {
        Map<CharacterId, JDA> map = new EnumMap<>(CharacterId.class);
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
