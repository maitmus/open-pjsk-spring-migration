package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.persona.CharacterId;
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
 * 머슴 시스템 프롬프트 빌더 — 2블록 PromptBlocks.
 * block[0]: commonBase (events + 출력규칙) — 캐시 공유.
 * block[1]: 본인 체화 + 형제봇 최소 + 지침 — 캐시.
 * GRADES·타 페르소나 미포함.
 */
@Slf4j
@Component
public class MersoomPromptBuilder {

    private static final java.util.Map<CharacterId, String> SIBLING_MINIMAL = java.util.Map.of(
            CharacterId.NENE,
            "\n## 형제봇(원더쇼 동료) — 네네\n쿠사나기 네네: 원더랜즈×쇼타임 동료. 호칭 '네네쨩' + 반말. 까다롭고 직설·츤데레. 머슴에서 네네 글/언급엔 이 관계로 당사자처럼.\n",
            CharacterId.EMU,
            "\n## 형제봇(원더쇼 동료) — 에무\n오오토리 에무: 원더랜즈×쇼타임 동료. 호칭 '에무' + 반말. 천진·텐션 폭발. 머슴에서 에무 글/언급엔 이 관계로 당사자처럼.\n");

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

    /** 에무 기본. */
    public PromptBlocks build() {
        String b1 = shared.personaInjection(CharacterId.EMU, "특히 말투")
                + SIBLING_MINIMAL.get(CharacterId.NENE)
                + shared.gradesMatrix()
                + "\n" + loadResource(emuInstructions);
        return new PromptBlocks(java.util.List.of(
                new PromptBlocks.Block(shared.commonBase(), true),
                new PromptBlocks.Block(b1, true)));
    }

    /** 페르소나별 시스템 프롬프트. */
    public PromptBlocks build(CitizenProfile profile) {
        if (profile != null && profile.persona() == CharacterId.NENE) {
            String b1 = shared.personaInjection(CharacterId.NENE, "특히 말투")
                    + SIBLING_MINIMAL.get(CharacterId.EMU)
                    + shared.gradesMatrix()
                    + "\n" + loadResource(neneInstructions);
            return new PromptBlocks(java.util.List.of(
                    new PromptBlocks.Block(shared.commonBase(), true),
                    new PromptBlocks.Block(b1, true)));
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
