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
 * Builds the system prompt for heartbeat (autonomous utterance) calls as three cache blocks:
 *  - commonBase : events.json + 출력 공통 규칙 (byte-identical across all paths — shared cache)
 *  - voiceRoster: USER.md + 7-persona roster + all personas + GRADES (identical to routing)
 *  - instr      : heartbeat-base-instructions
 *
 * commonBase and voiceRoster are byte-identical to {@link com.maitmus.sekairouter.routing.SystemPromptBuilder},
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
        String instr = "\n" + loadResource(baseInstructions);
        return new PromptBlocks(java.util.List.of(
                new PromptBlocks.Block(shared.commonBase(), true),
                new PromptBlocks.Block(shared.voiceRoster(), true),
                new PromptBlocks.Block(instr, true)));
    }

    private String loadResource(Resource resource) {
        try (var is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt resource: " + resource.getFilename(), e);
        }
    }
}
