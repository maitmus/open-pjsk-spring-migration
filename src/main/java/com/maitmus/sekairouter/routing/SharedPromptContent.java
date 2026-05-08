package com.maitmus.sekairouter.routing;

import com.maitmus.sekairouter.config.PersonaProperties;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Builds the byte-identical shared prefix used by both {@link SystemPromptBuilder}
 * (routing) and {@code HeartbeatPromptBuilder}. Holds USER.md, persona definitions,
 * GRADES.md matrix, and events.json — content that does NOT vary by path.
 *
 * Path-specific content (instructions, output schema) lives in each builder's suffix.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SharedPromptContent {

    private static final String GRADES_FILE = "GRADES.md";
    private static final String EVENTS_FILE = "events.json";
    private static final String USER_FILE = "USER.md";

    private final PersonaRegistry registry;
    private final PersonaProperties personaProperties;

    public String build() {
        StringBuilder sb = new StringBuilder();
        Path baseDir = Paths.get(personaProperties.dir());
        Path workspaceDir = baseDir.getParent();

        loadFile(workspaceDir, USER_FILE).ifPresent(c ->
                sb.append("## 사용자 정보 (USER.md)\n\n").append(c).append("\n"));

        sb.append("\n## 페르소나 정의\n\n");
        registry.all().values().forEach(p -> appendPersona(sb, p));

        loadFile(baseDir, GRADES_FILE).ifPresent(c ->
                sb.append("\n## 호칭·존댓말 매트릭스 (GRADES.md)\n\n").append(c).append("\n"));

        loadFile(baseDir, EVENTS_FILE).ifPresent(c ->
                sb.append("\n## 이벤트 캘린더 (events.json)\n\n```json\n").append(c).append("```\n"));

        return sb.toString();
    }

    private void appendPersona(StringBuilder sb, Persona p) {
        sb.append("### ").append(p.id().name().toLowerCase())
          .append(" — ").append(p.displayName()).append("\n\n")
          .append(p.content()).append("\n\n");
    }

    private Optional<String> loadFile(Path dir, String name) {
        if (dir == null) return Optional.empty();
        Path p = dir.resolve(name);
        if (!Files.isRegularFile(p)) {
            log.debug("{} not found at {} — skipping", name, p);
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", p, e.getMessage());
            return Optional.empty();
        }
    }
}
