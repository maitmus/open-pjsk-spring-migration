package com.maitmus.sekairouter.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * activity-feed.json 직렬화/역직렬화. {@link com.maitmus.sekairouter.mersoom.MersoomStateStore}와 동일한
 * tmp+rename atomic 시도 → Docker single-file bind-mount면 직접 write 폴백.
 * 대시보드가 ro로 읽는 소스 오브 트루스라 항상 유효 JSON 유지가 중요.
 */
@Slf4j
@Component
public class ActivityFeedStore {

    private final ObjectMapper objectMapper;
    private final Path file;

    public ActivityFeedStore(ObjectMapper objectMapper,
                             @Value("${activity.feed-file:/app/activity-feed.json}") String feedFile) {
        this.objectMapper = objectMapper;
        this.file = Paths.get(feedFile);
    }

    /** 없거나 깨졌으면 빈 피드 반환(시작 실패 방지). */
    public ActivityFeed load() {
        if (!Files.isRegularFile(file)) return new ActivityFeed(java.util.List.of());
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), ActivityFeed.class);
        } catch (IOException e) {
            log.warn("activity feed 파싱 실패 — 빈 피드로 시작: {}", e.getMessage());
            return new ActivityFeed(java.util.List.of());
        }
    }

    public void save(ActivityFeed feed) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(feed);
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicErr) {
            Files.deleteIfExists(tmp);
            Files.writeString(file, json, StandardCharsets.UTF_8);   // single-file mount 폴백
        }
    }
}
