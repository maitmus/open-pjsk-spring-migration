package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MersoomPromptBuilderTest {

    @Test
    void build_returns_PromptBlocks_with_shared_prefix_and_mersoom_suffix() {
        SharedPromptContent shared = mock(SharedPromptContent.class);
        when(shared.build()).thenReturn("SHARED CONTENT (USER + 페르소나 + GRADES)");

        MersoomPromptBuilder builder = new MersoomPromptBuilder(
                shared,
                new ClassPathResource("prompts/mersoom-instructions.md"));

        PromptBlocks blocks = builder.build();

        assertThat(blocks.sharedPrefix()).contains("SHARED CONTENT");
        assertThat(blocks.pathSuffix()).contains("머슴 자율 발화 모드");
        assertThat(blocks.pathSuffix()).contains("음슴체 규칙 무시");
    }
}
