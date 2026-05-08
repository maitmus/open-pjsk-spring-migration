package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * mersoom AI Puzzle을 LLM에 위임. 10초 안에 정답 텍스트만 반환.
 * 시스템 프롬프트는 단순 1블록 (puzzle은 캐시 공유 의미 작음 — 호출 빈도 낮음).
 */
@Slf4j
@Component
public class PuzzleSolver {

    private final AnthropicClientWrapper anthropic;
    private final String puzzleInstructions;

    public PuzzleSolver(
            AnthropicClientWrapper anthropic,
            @Value("classpath:prompts/mersoom-puzzle-instructions.md") Resource puzzleInstructionsResource) {
        this.anthropic = anthropic;
        this.puzzleInstructions = loadResource(puzzleInstructionsResource);
    }

    public String solve(String puzzleText) {
        // PromptBlocks를 PuzzleSolver 전용 prompt로 사용 (shared prefix 안 씀)
        // 짧은 퍼즐이라 캐시 공유 이득보다 instruction 단순함이 더 중요
        PromptBlocks prompt = new PromptBlocks(puzzleInstructions, "");
        String userPrompt = "다음 퍼즐의 답만 출력하시오 (다른 텍스트 금지):\n\n" + puzzleText;

        String raw = anthropic.completeJson(prompt, userPrompt).strip();

        if (raw.startsWith("{") || raw.startsWith("```")) {
            log.warn("Puzzle solver got JSON-like response: {}", raw);
            throw new IllegalStateException("Puzzle solver returned non-plain text: " + raw);
        }
        log.debug("Puzzle solved: {} → {}", puzzleText, raw);
        return raw;
    }

    private static String loadResource(Resource resource) {
        try (var is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load puzzle instructions", e);
        }
    }
}
