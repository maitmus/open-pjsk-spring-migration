package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.persona.PersonaType;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArenaPersonaBlocksTest {

    // ⚠️ Persona는 record라 mock() 불가 — 실제 인스턴스로 만든다(기존 ArenaPromptBlocksTest 패턴).
    private ArenaPersonaBlocks blocks(String commonBase, String personaContent) {
        SharedPromptContent s = mock(SharedPromptContent.class);
        when(s.commonBase()).thenReturn(commonBase);
        PersonaRegistry r = mock(PersonaRegistry.class);
        when(r.get(CharacterId.NENE)).thenReturn(
                new Persona(CharacterId.NENE, "쿠사나기 네네", PersonaType.HUMAN_SEKAI, personaContent));
        return new ArenaPersonaBlocks(s, r);
    }

    @Test
    void cached_prefix_has_commonbase_uncached_and_persona_cached() {
        List<PromptBlocks.Block> b = blocks("COMMON", "네네정의").cachedPrefix();
        assertThat(b).hasSize(2);
        assertThat(b.get(0).text()).isEqualTo("COMMON");
        assertThat(b.get(0).cache()).isFalse();              // commonBase는 캐시 브레이크포인트 아님
        assertThat(b.get(1).cache()).isTrue();               // 페르소나 블록 끝에 cache_control → commonBase+persona 캐시
        assertThat(b.get(1).text()).contains("쿠사나기 네네").contains("네네정의");
    }

    @Test
    void null_persona_is_safe() {
        List<PromptBlocks.Block> b = blocks("COMMON", null).cachedPrefix();
        assertThat(b.get(1).text()).contains("쿠사나기 네네");   // 헤더는 있고 content만 빈 문자열
    }

    @Test
    void prefix_is_stable_across_calls() {
        ArenaPersonaBlocks a = blocks("COMMON", "네네정의");
        assertThat(a.cachedPrefix().get(1).text()).isEqualTo(a.cachedPrefix().get(1).text());  // 바이트-동일(캐시 공유 전제)
    }
}
