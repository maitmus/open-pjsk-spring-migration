package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.arena.ArenaDtos.FightPost;
import com.maitmus.sekairouter.arena.ArenaDtos.Topic;
import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.persona.PersonaType;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArenaPrepGeneratorTest {

    private static final Topic TOPIC = new Topic("t1", "우정은 솔직함일까 배려일까", "솔직함이 신뢰", "배려 없으면 폭력");

    // ⚠️ Persona는 record라 mock() 불가 — 실제 인스턴스로.
    private static ArenaPersonaBlocks personaBlocks() {
        SharedPromptContent s = mock(SharedPromptContent.class);
        when(s.commonBase()).thenReturn("shared");
        PersonaRegistry r = mock(PersonaRegistry.class);
        when(r.get(CharacterId.NENE)).thenReturn(
                new Persona(CharacterId.NENE, "쿠사나기 네네", PersonaType.HUMAN_SEKAI, "네네정의"));
        return new ArenaPersonaBlocks(s, r);
    }

    @Test
    void returns_rebuttal_notes_text() {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        when(a.completeJson(any(PromptBlocks.class), anyString()))
                .thenReturn("- 상대: 솔직함이 신뢰 → 신뢰는 배려에서 온다\n- 상대: 배려는 회피 → 회피 아니라 존중");
        String notes = new ArenaPrepGenerator(a, personaBlocks())
                .generate(TOPIC, List.of(), "CON", "쿠사나기 네네");
        assertThat(notes).contains("신뢰는 배려에서 온다").contains("존중");
    }

    @Test
    void blank_output_returns_empty() {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        when(a.completeJson(any(PromptBlocks.class), anyString())).thenReturn("   ");
        assertThat(new ArenaPrepGenerator(a, personaBlocks())
                .generate(TOPIC, List.of(), "CON", "쿠사나기 네네")).isEmpty();
    }

    @Test
    void uses_shared_cached_prefix_and_prep_suffix() {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<PromptBlocks> pb = ArgumentCaptor.forClass(PromptBlocks.class);
        when(a.completeJson(pb.capture(), anyString())).thenReturn("- 노트");
        ArenaPersonaBlocks blocks = personaBlocks();
        new ArenaPrepGenerator(a, blocks).generate(TOPIC, List.of(), "CON", "쿠사나기 네네");
        List<PromptBlocks.Block> used = pb.getValue().blocks();
        // 앞 2블록 = 공유 캐시 프리픽스(바이트-동일), 마지막 = prep suffix(uncached)
        assertThat(used.get(1).text()).isEqualTo(blocks.cachedPrefix().get(1).text());
        assertThat(used.get(1).cache()).isTrue();
        assertThat(used.get(used.size() - 1).cache()).isFalse();
        assertThat(used.get(used.size() - 1).text()).contains("토론 준비")
                .contains("휘둘리지 말 것")          // 엉뚱한 상대 글에 견고화
                .contains("찬반(pros/cons) 프레임");   // 토픽 앵커
    }

    @Test
    void prep_user_prompt_includes_opponent_posts() {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> up = ArgumentCaptor.forClass(String.class);
        when(a.completeJson(any(PromptBlocks.class), up.capture())).thenReturn("- 노트");
        List<FightPost> posts = List.of(
                new FightPost("o1", "히후미", "PRO", "솔직함이 최고의 신뢰다", 0, 0, false, null));
        new ArenaPrepGenerator(a, personaBlocks()).generate(TOPIC, posts, "CON", "쿠사나기 네네");
        assertThat(up.getValue()).contains("솔직함이 최고의 신뢰다");
    }
}
