package com.maitmus.sekairouter.heartbeat;

import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Builds the system prompt for heartbeat (autonomous utterance) calls as two cache blocks:
 *  - sharedPrefix: USER.md + personas + GRADES + events (identical to routing)
 *  - pathSuffix : heartbeat-base-instructions
 *
 * The shared block is byte-identical to {@link com.maitmus.sekairouter.routing.SystemPromptBuilder},
 * so Anthropic prefix cache writes from either path serve the other.
 */
@Slf4j
@Component
public class HeartbeatPromptBuilder {

    private final SharedPromptContent shared;
    private final Resource baseInstructions;

    public HeartbeatPromptBuilder(
            SharedPromptContent shared,
            @Value("classpath:prompts/heartbeat-base-instructions.md") Resource baseInstructions) {
        this.shared = shared;
        this.baseInstructions = baseInstructions;
    }

    public PromptBlocks build() {
        String sharedPrefix = shared.build();
        String suffix = "\n" + loadResource(baseInstructions);
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
