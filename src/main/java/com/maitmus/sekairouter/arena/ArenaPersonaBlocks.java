package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 아레나 prep·fight가 공유하는 캐시 프리픽스.
 * [Block(commonBase, false), Block(네네페르소나, true)] — 페르소나 블록 끝의 cache_control(TTL_1H)이
 * commonBase+페르소나(≈3300토큰 > Haiku 2048 최소치)를 캐시한다. prep·fight가 이 프리픽스를 바이트-동일하게
 * 공유하고 뒤에 각자 task SUFFIX(uncached)를 붙여, prep 콜이 데운 캐시를 fight 콜이 cache_read 한다.
 */
@Component
@RequiredArgsConstructor
public class ArenaPersonaBlocks {

    private final SharedPromptContent shared;
    private final PersonaRegistry personaRegistry;

    /** prep·fight 공통 캐시 프리픽스 2블록. 두 번째(페르소나) 블록만 cache=true. */
    public List<PromptBlocks.Block> cachedPrefix() {
        Persona nene = personaRegistry.get(CharacterId.NENE);
        String content = (nene.content() != null) ? nene.content() : "";
        String personaBlock = "\n## 너는 쿠사나기 네네 — 아래 정의를 그대로 체화한다\n" + content + "\n";
        return List.of(
                new PromptBlocks.Block(shared.commonBase(), false),
                new PromptBlocks.Block(personaBlock, true));
    }
}
