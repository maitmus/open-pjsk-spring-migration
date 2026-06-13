package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 머슴 시스템 프롬프트 빌더 — shared prefix(라우터·하트비트와 공통) + 페르소나별 suffix.
 * 캐시 공유 효과: 라우터·하트비트가 활성 시간 내내 prefix 워밍 → 머슴 호출 시 32K cache_read.
 *
 * 에무는 공유 prefix + 에무 지시문(suffix) 그대로. 네네는 공유 prefix + 네네 페르소나 주입 + 네네 지시문
 * (아레나 발의/토론 생성기와 동일 패턴 — 공유 prefix는 전체 로스터라 페르소나 중립).
 */
@Slf4j
@Component
public class MersoomPromptBuilder {

    private final SharedPromptContent shared;
    private final PersonaRegistry personaRegistry;
    private final Resource emuInstructions;
    private final Resource neneInstructions;

    public MersoomPromptBuilder(
            SharedPromptContent shared,
            PersonaRegistry personaRegistry,
            @Value("classpath:prompts/mersoom-instructions.md") Resource emuInstructions,
            @Value("classpath:prompts/mersoom-instructions-nene.md") Resource neneInstructions) {
        this.shared = shared;
        this.personaRegistry = personaRegistry;
        this.emuInstructions = emuInstructions;
        this.neneInstructions = neneInstructions;
    }

    /** 에무 기본(하위호환). */
    public PromptBlocks build() {
        return new PromptBlocks(shared.build(), "\n" + loadResource(emuInstructions));
    }

    /** 페르소나별 시스템 프롬프트. */
    public PromptBlocks build(CitizenProfile profile) {
        if (profile != null && profile.persona() == CharacterId.NENE) {
            Persona nene = personaRegistry.get(CharacterId.NENE);
            String injection = "\n## 너는 쿠사나기 네네 — 아래 정의를 그대로 체화한다 (특히 말투)\n"
                    + (nene != null && nene.content() != null ? nene.content() : "") + "\n";
            return new PromptBlocks(shared.build(), injection + "\n" + loadResource(neneInstructions));
        }
        return build();
    }

    private String loadResource(Resource resource) {
        try (var is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load mersoom instructions", e);
        }
    }
}
