package com.maitmus.sekairouter.arena;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * arena-state.json 직렬화 — 토론 side 락(토픽별 입장 고정)의 진실의 원천.
 *
 * 닉네임 매칭이 아니라 파일 기록 기반이라 사칭·닉 변경·컨테이너 재시작에 견고하다.
 * Save 정책은 MersoomStateStore와 동일 — Docker 단일 파일 bind-mount에선 ATOMIC_MOVE가
 * 불가하므로 직접 write fallback. 빈도 낮고(≤4/일) 파일이 작아 mid-write 위험이 작다.
 * 파일이 없거나 깨졌으면 빈 상태로 시작(락 미적용) — 토론 자체는 막지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArenaStateStore {

    private final ArenaProperties properties;
    private final ObjectMapper objectMapper;

    public ArenaState load() {
        Path file = Paths.get(properties.stateFile());
        if (!Files.isRegularFile(file)) {
            return ArenaState.empty();
        }
        try {
            String body = Files.readString(file, StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return ArenaState.empty();
            }
            return objectMapper.readValue(body, ArenaState.class);
        } catch (IOException e) {
            log.warn("arena-state 파싱 실패, 빈 상태로 시작: {}", e.getMessage());
            return ArenaState.empty();
        }
    }

    /** 오늘·해당 토픽에 고정된 side. 일치 기록이 없으면 빈 값(=첫 턴, 자유 선택). */
    public Optional<String> lockedSide(LocalDate date, String topicId) {
        ArenaState s = load();
        if (s.side() == null || s.side().isBlank()) return Optional.empty();
        if (!Objects.equals(s.date(), date.toString())) return Optional.empty();
        if (!Objects.equals(s.topicId(), topicId)) return Optional.empty();
        return Optional.of(s.side());
    }

    /** 토픽에 입장을 고정(첫 fight 성공 시). 같은 값 재기록은 무해(idempotent). */
    public void recordSide(LocalDate date, String topicId, String side) {
        save(new ArenaState(date.toString(), topicId, side));
    }

    private void save(ArenaState state) {
        Path file = Paths.get(properties.stateFile());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state);
            try {
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicErr) {
                Files.deleteIfExists(tmp);
                Files.writeString(file, json, StandardCharsets.UTF_8);
                log.debug("arena-state saved (direct write fallback): {}", json);
                return;
            }
            log.debug("arena-state saved (atomic): {}", json);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save arena state", e);
        }
    }
}
