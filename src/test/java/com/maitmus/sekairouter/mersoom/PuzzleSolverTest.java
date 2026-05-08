package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PuzzleSolverTest {

    @Test
    void delegates_to_anthropic_and_strips_whitespace() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString())).thenReturn("  abc123  \n");

        PuzzleSolver solver = new PuzzleSolver(
                anthropic,
                new ClassPathResource("prompts/mersoom-puzzle-instructions.md"));

        String answer = solver.solve("영어 단어 1번째의 4번째 알파벳을 추출하시오");

        assertThat(answer).isEqualTo("abc123");
    }

    @Test
    void rejects_jsonlike_response() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString())).thenReturn("{\"reasoning\":\"...\"}");

        PuzzleSolver solver = new PuzzleSolver(
                anthropic,
                new ClassPathResource("prompts/mersoom-puzzle-instructions.md"));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> solver.solve("test puzzle"));
    }
}
