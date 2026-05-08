package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 머슴 시스템 프롬프트 빌더 — shared prefix(라우터·하트비트와 공통) + mersoom suffix.
 * 캐시 공유 효과: 라우터·하트비트가 활성 시간 내내 prefix 워밍 → 머슴 호출 시 32K cache_read.
 */
@Slf4j
@Component
public class MersoomPromptBuilder {

    private final SharedPromptContent shared;
    private final Resource baseInstructions;

    public MersoomPromptBuilder(
            SharedPromptContent shared,
            @Value("classpath:prompts/mersoom-instructions.md") Resource baseInstructions) {
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
            throw new IllegalStateException("Failed to load mersoom instructions", e);
        }
    }
}
