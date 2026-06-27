package com.maitmus.sekairouter.routing;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PromptBlocksTest {

    @Test
    void list_constructor_keeps_blocks_and_cache_flags() {
        PromptBlocks p = new PromptBlocks(List.of(
                new PromptBlocks.Block("common", true),
                new PromptBlocks.Block("arena", false)));
        assertThat(p.blocks()).hasSize(2);
        assertThat(p.blocks().get(0).cache()).isTrue();
        assertThat(p.blocks().get(1).text()).isEqualTo("arena");
        assertThat(p.blocks().get(1).cache()).isFalse();
    }

    @Test
    void legacy_two_arg_constructor_makes_two_cached_blocks() {
        PromptBlocks p = new PromptBlocks("prefix", "suffix");
        assertThat(p.blocks()).hasSize(2);
        assertThat(p.blocks()).allMatch(PromptBlocks.Block::cache);
        assertThat(p.blocks().get(0).text()).isEqualTo("prefix");
        assertThat(p.blocks().get(1).text()).isEqualTo("suffix");
    }

    @Test
    void buildSystemBlocks_omits_cache_control_on_uncached_block() {
        PromptBlocks p = new PromptBlocks(List.of(
                new PromptBlocks.Block("c", true),
                new PromptBlocks.Block("u", false)));
        var blocks = com.maitmus.sekairouter.routing.AnthropicClientWrapper.buildSystemBlocks(p);
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).cacheControl()).isPresent();
        assertThat(blocks.get(1).cacheControl()).isEmpty();
    }

    @Test
    void buildSystemBlocks_skips_empty_blocks() {
        PromptBlocks p = new PromptBlocks(List.of(
                new PromptBlocks.Block("only", true),
                new PromptBlocks.Block("", true)));
        assertThat(com.maitmus.sekairouter.routing.AnthropicClientWrapper.buildSystemBlocks(p)).hasSize(1);
    }
}
