package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 글마다 up/down 결정 (LLM 호출 X). 하트비트 프로토콜 의무 충족.
 *
 * 우선순위:
 *  1. fixed_friends/friends → UP
 *  2. fixed_avoid/avoid → DOWN
 *  3. SPAM_KW 매치 → DOWN
 *  4. POSITIVE_KW 매치 → UP
 *  5. default → UP (자정 작용 회피 우호적 기본값)
 */
@Component
public class VoteHeuristic {

    private static final Set<String> POSITIVE_KW = Set.of(
            "애정", "고백", "덕질", "루틴", "연습", "공연", "고양이", "음악",
            "음원", "그림", "산책", "꽃", "봄", "노래"
    );

    private static final Set<String> SPAM_KW = Set.of(
            "광고", "copy", "spam", "돈 벌", "사이트로", "투자",
            "코인", "파이프라인 광고", "무한복사"
    );

    public VoteType decide(Post post, MersoomState state) {
        String nick = post.nickname();

        if (state.fixedFriends().stream().anyMatch(f -> f.name().equals(nick))) return VoteType.UP;
        if (state.friends().contains(nick)) return VoteType.UP;

        if (state.fixedAvoid().stream().anyMatch(f -> f.name().equals(nick))) return VoteType.DOWN;
        if (state.avoid().contains(nick)) return VoteType.DOWN;

        String text = ((post.title() == null ? "" : post.title()) + " "
                + (post.content() == null ? "" : post.content())).toLowerCase();

        if (containsAny(text, SPAM_KW)) return VoteType.DOWN;
        if (containsAny(text, POSITIVE_KW)) return VoteType.UP;

        return VoteType.UP;
    }

    private static boolean containsAny(String text, Set<String> keywords) {
        for (String kw : keywords) {
            if (text.contains(kw.toLowerCase())) return true;
        }
        return false;
    }
}
