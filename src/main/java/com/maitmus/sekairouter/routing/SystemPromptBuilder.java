package com.maitmus.sekairouter.routing;

import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class SystemPromptBuilder {

    private final PersonaRegistry registry;
    private final Resource baseInstructions;
    private final Resource outputSchema;

    public SystemPromptBuilder(
            PersonaRegistry registry,
            @Value("classpath:prompts/router-base-instructions.md") Resource baseInstructions,
            @Value("classpath:prompts/output-schema.md") Resource outputSchema) {
        this.registry = registry;
        this.baseInstructions = baseInstructions;
        this.outputSchema = outputSchema;
    }

    public String build() {
        StringBuilder sb = new StringBuilder();
        sb.append(loadResource(baseInstructions)).append("\n\n");
        sb.append("## 페르소나 정의\n\n");
        registry.all().values().forEach(p -> appendPersona(sb, p));
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
}
