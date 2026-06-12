package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.arena.ArenaDtos.FightPost;
import com.maitmus.sekairouter.arena.ArenaDtos.Topic;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.OutputSanityGate;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 아레나 토론 참여 (쿠사나기 네네). 공유 prefix(캐시) + 토론 모드 suffix.
 * {reasoning, side, content, shouldFight} 봉투. 극단 부적합이면 shouldFight=false → 보류({@code null}).
 * 네네는 분석적·직설 톤이라 논리 토론이 인캐릭터 — 단 인신공격(감정적 비난)은 금지.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArenaFightGenerator {

    private static final int MAX_CONTENT = 1000;

    private final AnthropicClientWrapper anthropic;
    private final SharedPromptContent shared;
    private final OutputSanityGate backstop;

    public record FightDecision(String side, String content) {}

    private static final String SUFFIX = """

            ## 아레나 토론 모드 (쿠사나기 네네)
            당신은 쿠사나기 네네로서 머슴 토론장(BATTLE)에 참여합니다. 네네는 직설적·분석적이고 틀린 건 틀렸다고 합니다.
            - PRO/CON 중 **논리적으로 더 맞다고 보는 쪽**을 골라 **팩트와 논리로** 논거를 폅니다 (300~500자, 최대 1000).
            - 기존 상대 논거가 있으면 그 약점을 **논점 중심으로 반박**. **인신공격·감정적 비난 금지**(블라인드 당함).
            - 네네 톤(직설·퉁명, 음슴체 가능) 유지하되 논리는 진지하게. reasoning은 비공개.
            - content에 거절·메타·자기지칭(AI/어시스턴트) 쓰지 말 것.
            - 이 주제가 네네가 도저히 다룰 수 없는 극단적 부적합(혐오 선동 등)이면 shouldFight:false.

            ## 출력 (JSON 1개)
            {"reasoning":"<비공개>", "side":"PRO|CON", "content":"<네네 논거>", "shouldFight":true}
            ⚠️ JSON 안전: 문자열 값 안에 큰따옴표(") 쓰지 말 것 — 인용은 작은따옴표(')나 「」 사용. reasoning은 짧게.
            """;

    /** @return PRO/CON + 논거, 또는 보류 시 {@code null}. */
    public FightDecision generate(Topic topic, List<FightPost> existing) {
        String raw = anthropic.completeJson(new PromptBlocks(shared.build(), SUFFIX), buildUserPrompt(topic, existing));
        var parsed = ArenaEnvelopeParser.parse(raw);
        if (parsed.isEmpty()) {
            log.warn("Arena fight 보류 — 봉투 파싱 실패: {}",
                    raw == null ? "null" : raw.substring(0, Math.min(120, raw.length())));
            return null;
        }
        var e = parsed.get();
        if (e.reasoning() != null && !e.reasoning().isBlank()) {
            log.info("Arena fight reasoning (not posted): {}", e.reasoning());
        }
        if (!Boolean.TRUE.equals(e.shouldFight())) {
            log.info("Arena fight 보류 — shouldFight={} (극단 부적합)", e.shouldFight());
            return null;
        }
        String side = normalizeSide(e.side());
        String content = e.content() == null ? "" : e.content().strip();
        if (side == null) {
            log.info("Arena fight 보류 — side 무효: '{}'", e.side());
            return null;
        }
        if (content.isBlank()) {
            log.info("Arena fight 보류 — content 비어있음");
            return null;
        }
        if (!backstop.isClean(content)) {
            log.warn("Arena fight 보류 — 백스톱 누수 마커 감지");
            return null;
        }
        return new FightDecision(side, content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) : content);
    }

    private static String normalizeSide(String s) {
        if (s == null) return null;
        return switch (s.strip().toUpperCase(Locale.ROOT)) {
            case "PRO" -> "PRO";
            case "CON" -> "CON";
            default -> null;
        };
    }

    private String buildUserPrompt(Topic topic, List<FightPost> existing) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 모드\narena-fight\n");
        sb.append("## 오늘의 토론 주제\n");
        sb.append("제목: ").append(safe(topic.title())).append("\n");
        sb.append("PRO(찬성): ").append(safe(topic.pros())).append("\n");
        sb.append("CON(반대): ").append(safe(topic.cons())).append("\n\n");
        if (existing != null && !existing.isEmpty()) {
            sb.append("## 이미 올라온 논거 (반박 참고)\n");
            for (FightPost p : existing) {
                if (p.isBlinded()) continue;
                sb.append("- [").append(safe(p.side())).append("] @").append(safe(p.nickname()))
                        .append(": ").append(safe(p.content())).append("\n");
            }
            sb.append("\n");
        }
        sb.append("## 지시\n네네로서 PRO/CON 중 한쪽을 골라 논거를 위 형식으로 작성하세요.\n");
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\n", " ").strip();
    }
}
