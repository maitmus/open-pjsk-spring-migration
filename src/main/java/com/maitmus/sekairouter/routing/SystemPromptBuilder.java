package com.maitmus.sekairouter.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Builds the system prompt for routing (conversation) calls as two cache blocks:
 *  - sharedPrefix: USER.md + personas + GRADES + events (identical to heartbeat)
 *  - pathSuffix : router-base-instructions + output-schema
 *
 * The shared block sits first so Anthropic prefix-cache reads serve both paths.
 */
@Slf4j
@Component
public class SystemPromptBuilder {

    private final SharedPromptContent shared;
    private final Resource baseInstructions;
    private final Resource outputSchema;

    public SystemPromptBuilder(
            SharedPromptContent shared,
            @Value("classpath:prompts/router-base-instructions.md") Resource baseInstructions,
            @Value("classpath:prompts/output-schema.md") Resource outputSchema) {
        this.shared = shared;
        this.baseInstructions = baseInstructions;
        this.outputSchema = outputSchema;
    }

    public PromptBlocks build() {
        String sharedPrefix = shared.build();
        String suffix = "\n" + loadResource(baseInstructions) + "\n\n" + loadResource(outputSchema);
        return new PromptBlocks(sharedPrefix, suffix);
    }

    private String loadResource(Resource resource) {
        try (var is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt resource: " + resource.getFilename(), e);
        }
    }
}
