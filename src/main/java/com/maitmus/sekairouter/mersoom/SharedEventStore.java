package com.maitmus.sekairouter.mersoom;

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
import java.time.LocalDate;
import java.util.List;

/**
 * shared-events.json 저장소 — 에무·네네 두 시민이 한 파일을 공유한다(같은 JVM, synchronized로 load-modify-save).
 * 파일이 없거나 비었거나 깨졌으면 빈 목록으로 보고, 다음 저장에서 정상 파일로 덮는다.
 * 저장은 {@link MersoomStateStore}와 같은 atomic→direct write fallback(single-file bind mount).
 */
@Slf4j
@Component
public class SharedEventStore {

    private final ObjectMapper objectMapper;
    private final Path file;

    public SharedEventStore(ObjectMapper objectMapper,
                            @Value("${mersoom.shared-events-file:/app/shared-events.json}") String path) {
        this.objectMapper = objectMapper;
        this.file = Paths.get(path);
    }

    /** 수명 정리된 활성 이벤트. */
    public synchronized List<SharedEvent> active(LocalDate today) {
        return SharedEventLog.prune(load(), today);
    }

    /** 추출 연산 적용 후 저장. 연산이 없으면 파일을 건드리지 않는다. */
    public synchronized SharedEventLog.ApplyResult apply(List<SharedEventLog.Op> ops, LocalDate today,
                                                         String actor, String sourceId, String nowTs) {
        List<SharedEvent> current = SharedEventLog.prune(load(), today);
        if (ops == null || ops.isEmpty()) return new SharedEventLog.ApplyResult(current, List.of(), List.of());
        var result = SharedEventLog.apply(current, ops, today, actor, sourceId, nowTs);
        List<SharedEvent> pruned = SharedEventLog.prune(result.events(), today);
        save(pruned);
        return new SharedEventLog.ApplyResult(pruned, result.added(), result.updated());
    }

    private List<SharedEvent> load() {
        if (!Files.isRegularFile(file)) return List.of();
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) return List.of();
            var parsed = objectMapper.readValue(json, SharedEvent.File.class);
            return parsed == null || parsed.events() == null ? List.of() : parsed.events();
        } catch (IOException e) {
            log.warn("shared-events 파싱 실패 — 빈 목록으로 진행: {}", e.getMessage());
            return List.of();
        }
    }

    private void save(List<SharedEvent> events) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(new SharedEvent.File(events));
            try {
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicErr) {
                Files.deleteIfExists(tmp);   // Docker single-file bind-mount: ATOMIC_MOVE 불가
                Files.writeString(file, json, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("shared-events 저장 실패: {}", e.getMessage());
        }
    }
}
