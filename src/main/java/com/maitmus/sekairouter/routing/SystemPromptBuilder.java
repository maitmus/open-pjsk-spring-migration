package com.maitmus.sekairouter.routing;

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

@Slf4j
@Component
public class SystemPromptBuilder {

    private static final String GRADES_FILE = "GRADES.md";
    private static final String EVENTS_FILE = "events.json";
    private static final String USER_FILE = "USER.md";
    // quick-ref.md는 GRADES.md의 Haiku용 압축본 — Sonnet 4.6 사용 시 GRADES만으로 충분

    private final PersonaRegistry registry;
    private final PersonaProperties personaProperties;
    private final Resource baseInstructions;
    private final Resource outputSchema;

    public SystemPromptBuilder(
            PersonaRegistry registry,
            PersonaProperties personaProperties,
            @Value("classpath:prompts/router-base-instructions.md") Resource baseInstructions,
            @Value("classpath:prompts/output-schema.md") Resource outputSchema) {
        this.registry = registry;
        this.personaProperties = personaProperties;
        this.baseInstructions = baseInstructions;
        this.outputSchema = outputSchema;
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

        // 이벤트 캘린더 (생일/기념일 — user prompt의 '오늘 날짜'와 매칭해서 자연스럽게 언급)
        loadFile(baseDir, EVENTS_FILE).ifPresent(c ->
                sb.append("\n## 이벤트 캘린더 (events.json)\n\n```json\n").append(c).append("```\n"));

        sb.append("\n").append(loadResource(outputSchema));
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
