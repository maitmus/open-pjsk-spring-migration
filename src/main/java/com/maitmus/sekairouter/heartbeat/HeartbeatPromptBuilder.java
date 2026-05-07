package com.maitmus.sekairouter.heartbeat;

import com.maitmus.sekairouter.config.PersonaProperties;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Builds the system prompt for heartbeat (autonomous character utterance) calls.
 *
 * Mirrors {@link com.maitmus.sekairouter.routing.SystemPromptBuilder} but:
 *  - uses heartbeat-base-instructions.md instead of router-base-instructions.md
 *  - does NOT include output-schema.md (heartbeat returns plain text, not JSON)
 *
 * The {@code loadFile} helper is intentionally copied from SystemPromptBuilder to keep
 * the two classes independent — heartbeat prompt lifecycle may diverge from router prompt
 * lifecycle in future phases.
 */
@Slf4j
@Component
public class HeartbeatPromptBuilder {

    private static final String GRADES_FILE = "GRADES.md";
    private static final String EVENTS_FILE = "events.json";
    private static final String USER_FILE = "USER.md";
    // quick-ref.md는 GRADES.md의 Haiku용 압축본 — Sonnet 4.6 사용 시 GRADES만으로 충분

    private final PersonaRegistry registry;
    private final PersonaProperties personaProperties;
    private final Resource baseInstructions;

    public HeartbeatPromptBuilder(
            PersonaRegistry registry,
            PersonaProperties personaProperties,
            @Value("classpath:prompts/heartbeat-base-instructions.md") Resource baseInstructions) {
        this.registry = registry;
        this.personaProperties = personaProperties;
        this.baseInstructions = baseInstructions;
    }

    public String build() {
        StringBuilder sb = new StringBuilder();
        sb.append(loadResource(baseInstructions)).append("\n\n");

        Path baseDir = Paths.get(personaProperties.dir());
        Path workspaceDir = baseDir.getParent();

        // 사용자(MaiT) 정보
        loadFile(workspaceDir, USER_FILE).ifPresent(c ->
                sb.append("## 사용자 정보 (USER.md)\n\n").append(c).append("\n"));

        // 페르소나 정의
        sb.append("\n## 페르소나 정의\n\n");
        registry.all().values().forEach(p -> appendPersona(sb, p));

        // 호칭·존댓말 매트릭스
        loadFile(baseDir, GRADES_FILE).ifPresent(c ->
                sb.append("\n## 호칭·존댓말 매트릭스 (GRADES.md)\n\n").append(c).append("\n"));

        // 이벤트 캘린더
        loadFile(baseDir, EVENTS_FILE).ifPresent(c ->
                sb.append("\n## 이벤트 캘린더 (events.json)\n\n```json\n").append(c).append("```\n"));

        // 출력 JSON 스키마 없음 — 자율 발화는 플레인 텍스트 대사만 반환
        return sb.toString();
    }

    private void appendPersona(StringBuilder sb, Persona p) {
        sb.append("### ").append(p.id().name().toLowerCase())
          .append(" — ").append(p.displayName()).append("\n\n")
          .append(p.content()).append("\n\n");
    }

    private String loadResource(Resource resource) {
        try (var is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt resource: " + resource.getFilename(), e);
        }
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
