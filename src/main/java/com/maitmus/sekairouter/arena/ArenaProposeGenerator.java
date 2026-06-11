package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.OutputSanityGate;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 아레나 주제 발의 (에무). 공유 prefix(캐시 체인) + 발의 모드 suffix. {reasoning, title, pros, cons} 봉투.
 * 파싱 실패·빈 필드·백스톱 누수 시 {@code null}(발의 보류).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArenaProposeGenerator {

    private static final int MAX_TITLE = 100;
    private static final int MAX_SIDE = 500;

    private final AnthropicClientWrapper anthropic;
    private final SharedPromptContent shared;
    private final OutputSanityGate backstop;

    public record ProposedTopic(String title, String pros, String cons) {}

    private static final String SUFFIX = """

            ## 아레나 발의 모드 (에무)
            당신은 에무(오오토리 에무)로서 머슴 토론장에 **오늘의 토론 주제를 발의**합니다.
            - 밝지만 **논쟁거리가 되는 가치·관계·일상 윤리** 주제 (예: 우정에서 솔직함 vs 배려, 꿈 vs 현실, 노력 vs 재능).
            - 정치·혐오·자극적 시사 주제 금지. 따뜻하되 양쪽 입장이 팽팽한 것.
            - pros/cons 양측을 **균형 있게** 제시 (한쪽으로 기울지 않게).
            - reasoning은 비공개. title/pros/cons에 거절·메타·자기지칭 쓰지 말 것.

            ## 출력 (JSON 1개)
            {"reasoning":"<비공개>", "title":"<토론 제목, ~100자>", "pros":"<찬성 측 논거, ~500자>", "cons":"<반대 측 논거, ~500자>"}
            """;

    private static final String USER = "## 모드\narena-propose\n## 지시\n오늘의 토론 주제 1개를 위 형식으로 발의하세요.\n";

    /** @return 발의할 주제, 또는 보류 시 {@code null}. */
    public ProposedTopic generate() {
        String raw = anthropic.completeJson(new PromptBlocks(shared.build(), SUFFIX), USER);
        var parsed = ArenaEnvelopeParser.parse(raw);
        if (parsed.isEmpty()) {
            log.warn("Arena propose 보류 — 봉투 파싱 실패: {}",
                    raw == null ? "null" : raw.substring(0, Math.min(120, raw.length())));
            return null;
        }
        var e = parsed.get();
        if (e.reasoning() != null && !e.reasoning().isBlank()) {
            log.info("Arena propose reasoning (not posted): {}", e.reasoning());
        }
        String title = strip(e.title()), pros = strip(e.pros()), cons = strip(e.cons());
        if (title.isBlank() || pros.isBlank() || cons.isBlank()) {
            log.info("Arena propose 보류 — title/pros/cons 비어있음");
            return null;
        }
        if (!backstop.isClean(title) || !backstop.isClean(pros) || !backstop.isClean(cons)) {
            log.warn("Arena propose 보류 — 백스톱 누수 마커 감지");
            return null;
        }
        return new ProposedTopic(trunc(title, MAX_TITLE), trunc(pros, MAX_SIDE), trunc(cons, MAX_SIDE));
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }

    private static String trunc(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
