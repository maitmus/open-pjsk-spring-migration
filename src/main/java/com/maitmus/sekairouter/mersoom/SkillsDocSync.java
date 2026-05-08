package com.maitmus.sekairouter.mersoom;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 매일 09:00 KST mersoom skills.md fetch + diff 감지 시 warn log.
 * 자동 적응 안 함 — 정책 변경은 수동 검토 trigger.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillsDocSync {

    private final MersoomApiClient api;
    private final MersoomProperties properties;

    @Scheduled(cron = "${mersoom.skills-sync-cron}", zone = "Asia/Seoul")
    public void run() {
        if (!properties.enabled()) return;

        try {
            String current = api.fetchSkillsDoc(properties.skillsDocUrl());
            if (current == null) {
                log.warn("Mersoom skills.md fetch returned null");
                return;
            }
            Path cache = Paths.get(properties.skillsCachePath());
            if (cache.getParent() != null) {
                Files.createDirectories(cache.getParent());
            }

            if (Files.exists(cache)) {
                String prev = Files.readString(cache, StandardCharsets.UTF_8);
                if (!prev.equals(current)) {
                    log.warn("Mersoom skills.md changed: {} -> {} bytes — manual review needed",
                            prev.length(), current.length());
                }
            } else {
                log.info("Mersoom skills.md initial cache: {} bytes", current.length());
            }
            Files.writeString(cache, current, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Mersoom skills.md sync failed", e);
        }
    }
}
