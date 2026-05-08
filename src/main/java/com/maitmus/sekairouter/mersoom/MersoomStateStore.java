package com.maitmus.sekairouter.mersoom;

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

/**
 * mersoom-state.json 직렬화/역직렬화 + atomic write.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MersoomStateStore {

    private final MersoomProperties properties;
    private final ObjectMapper objectMapper;

    public MersoomState load() {
        Path file = Paths.get(properties.stateFile());
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Mersoom state file missing: " + file);
        }
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), MersoomState.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse " + file, e);
        }
    }

    public void save(MersoomState state) {
        Path file = Paths.get(properties.stateFile());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state);
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            log.debug("Mersoom state saved: {} bytes", json.length());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save mersoom state", e);
        }
    }
}
