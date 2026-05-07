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

@Slf4j
@Component
public class SystemPromptBuilder {

    private static final String GRADES_FILENAME = "GRADES.md";

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
        sb.append("## 페르소나 정의\n\n");
        registry.all().values().forEach(p -> appendPersona(sb, p));
        loadGrades().ifPresent(grades -> sb.append("\n## 호칭·존댓말 매트릭스 (GRADES.md)\n\n").append(grades).append("\n"));
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

    private java.util.Optional<String> loadGrades() {
        Path gradesPath = Paths.get(personaProperties.dir()).resolve(GRADES_FILENAME);
        if (!Files.isRegularFile(gradesPath)) {
            log.debug("GRADES.md not found at {} — skipping", gradesPath);
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Files.readString(gradesPath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Failed to read GRADES.md at {}: {}", gradesPath, e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
