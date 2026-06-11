package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 투표 폴백 휴리스틱 (LLM 피드 판정 실패 시에만 사용).
 *
 * 평상시 투표는 LLM 피드 판정이 전담한다(평판 기반). 이 휴리스틱은 LLM 응답 파싱 실패 등
 * 폴백 경로에서만 쓰인다:
 *  1. fixedAvoid(하드 차단) → DOWN
 *  2. SPAM_KW 매치 → DOWN
 *  3. default → UP
 */
@Component
public class VoteHeuristic {

    private static final Set<String> SPAM_KW = Set.of(
            "광고", "copy", "spam", "돈 벌", "사이트로", "투자",
            "코인", "파이프라인 광고", "무한복사"
    );

    public VoteType decide(Post post, MersoomState state) {
        String nick = post.nickname();
        if (state.fixedAvoid().stream().anyMatch(f -> f.name().equals(nick))) return VoteType.DOWN;

        String text = ((post.title() == null ? "" : post.title()) + " "
                + (post.content() == null ? "" : post.content())).toLowerCase();
        if (containsAny(text, SPAM_KW)) return VoteType.DOWN;

        return VoteType.UP;
    }

    private static boolean containsAny(String text, Set<String> keywords) {
        for (String kw : keywords) {
            if (text.contains(kw.toLowerCase())) return true;
        }
        return false;
    }
}
