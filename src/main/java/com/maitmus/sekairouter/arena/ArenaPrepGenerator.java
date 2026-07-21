package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.arena.ArenaDtos.FightPost;
import com.maitmus.sekairouter.arena.ArenaDtos.Topic;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 아레나 토론 준비(prep) — 실제 fight 전, 상대 논지 예상 + 네네 반박 포인트를 미리 생성한다.
 * fight와 **동일한 캐시 프리픽스**(ArenaPersonaBlocks.cachedPrefix)를 써서 이 콜이 프리픽스를 데우고,
 * 이어지는 fight 콜이 cache_read 한다. 산출물(반박노트)은 fight의 컨텍스트로 전달돼 논변을 강화.
 * 출력은 게시물이 아니라 내부 준비 메모(불릿 텍스트).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArenaPrepGenerator {

    private final AnthropicClientWrapper anthropic;
    private final ArenaPersonaBlocks personaBlocks;

    private static final String PREP_SUFFIX = """

            ## 아레나 토론 준비 모드 (쿠사나기 네네)
            곧 이 토픽 토론(BATTLE)에 참여한다. 지금은 준비 단계 — 게시물이 아니라 너의 준비 메모다.
            - 상대(반대편)가 펼칠 만한 주장을 2~4개 예상한다.
            - 각 예상 주장에, 네네답게 받아칠 반박 포인트를 한 줄씩 붙인다(짧고 날카롭게, 논리·팩트).
            - 출력은 불릿 텍스트만. JSON·머리말·메타·자기지칭(AI/어시스턴트) 금지. 실제 토론에 쓸 탄약 목록.
            """;

    /** 반박노트(불릿 텍스트) 반환. 실패/빈 출력이면 빈 문자열(fight는 노트 없이 진행). */
    public String generate(Topic topic, List<FightPost> existing, String lockedSide, String selfNickname) {
        try {
            List<PromptBlocks.Block> blocks = new ArrayList<>(personaBlocks.cachedPrefix());
            blocks.add(new PromptBlocks.Block(PREP_SUFFIX, false));
            String raw = anthropic.completeJson(new PromptBlocks(blocks),
                    buildUserPrompt(topic, existing, lockedSide, selfNickname));
            String notes = raw == null ? "" : raw.strip();
            if (!notes.isBlank()) {
                log.info("Arena prep 반박노트 생성 ({}자)", notes.length());
            }
            return notes;
        } catch (Exception e) {
            log.warn("Arena prep 실패 — 노트 없이 진행: {}", e.getMessage());
            return "";
        }
    }

    private String buildUserPrompt(Topic topic, List<FightPost> existing, String lockedSide, String selfNickname) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 모드\narena-prep\n");
        sb.append("## 오늘의 토론 주제\n");
        sb.append("제목: ").append(safe(topic.title())).append("\n");
        sb.append("PRO(찬성): ").append(safe(topic.pros())).append("\n");
        sb.append("CON(반대): ").append(safe(topic.cons())).append("\n\n");
        if (lockedSide != null && !lockedSide.isBlank()) {
            sb.append("## 너의 입장\n").append(lockedSide).append(" — 이 입장에서 상대(반대편) 주장을 예상·반박 준비.\n\n");
        } else {
            sb.append("## 너의 입장\n아직 미정 — 논리적으로 더 맞는 쪽을 정할 것을 전제로 양쪽 상대 논지를 예상·반박 준비.\n\n");
        }
        boolean anyOpp = false;
        StringBuilder opp = new StringBuilder();
        if (existing != null) {
            for (FightPost p : existing) {
                if (p.isBlinded()) continue;
                if (selfNicknameEquals(p, selfNickname)) continue;
                opp.append("- [").append(safe(p.side())).append("] @").append(safe(p.nickname()))
                   .append(": ").append(safe(p.content())).append("\n");
                anyOpp = true;
            }
        }
        if (anyOpp) {
            sb.append("## 이미 올라온 상대·기타 주장 (이걸 토대로 반박 준비)\n").append(opp).append("\n");
        }
        sb.append("## 지시\n상대가 펼칠 주장을 예상하고 각각에 네네다운 반박 포인트를 불릿으로 정리해.\n");
        return sb.toString();
    }

    private static boolean selfNicknameEquals(FightPost p, String selfNickname) {
        return selfNickname != null && selfNickname.equals(p.nickname());
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\n", " ").strip();
    }
}
