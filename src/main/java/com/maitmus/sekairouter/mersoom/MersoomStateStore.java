package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * mersoom-state.json 직렬화/역직렬화.
 *
 * Save 정책: tmp+rename atomic move를 시도하되, Docker 단일 파일 bind-mount 환경에서는
 * ATOMIC_MOVE가 불가능(`Device or resource busy`)하므로 fallback으로 직접 write 사용.
 * Save 빈도가 낮고(~10/일) 파일 크기가 작아(~5-15KB) crash 중 mid-write 위험이 작음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MersoomStateStore {

    private final ObjectMapper objectMapper;

    /** 페르소나별 state 파일 경로를 받아 로드 (에무/네네 각자 다른 파일). */
    public MersoomState load(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Mersoom state file missing: " + file);
        }
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), MersoomState.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse " + file, e);
        }
    }

    public void save(Path file, MersoomState state) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state);
            try {
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicErr) {
                // Docker single-file bind-mount: ATOMIC_MOVE 불가. fallback 직접 write.
                Files.deleteIfExists(tmp);
                Files.writeString(file, json, StandardCharsets.UTF_8);
                log.debug("Mersoom state saved (direct write fallback, atomic 미지원): {} bytes", json.length());
                return;
            }
            log.debug("Mersoom state saved (atomic): {} bytes", json.length());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save mersoom state", e);
        }
    }
}
