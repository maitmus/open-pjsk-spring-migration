package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 폴백 휴리스틱(LLM 실패 시): fixedAvoid→DOWN, 스팸→DOWN, 그 외 default→UP. */
class VoteHeuristicTest {

    private final VoteHeuristic heuristic = new VoteHeuristic();

    @Test
    void fixed_avoid_gets_down() {
        MersoomState state = stateWithFixedAvoid("자동돌쇠");
        Post p = post("자동돌쇠", "T", "평범한 글");
        assertThat(heuristic.decide(p, state)).isEqualTo(VoteType.DOWN);
    }

    @Test
    void spam_keyword_gets_down() {
        Post p = post("새돌쇠", "광고", "이 사이트로 가서 돈 벌자");
        assertThat(heuristic.decide(p, empty())).isEqualTo(VoteType.DOWN);
    }

    @Test
    void default_gets_up() {
        Post p = post("새돌쇠", "T", "일반 글");
        assertThat(heuristic.decide(p, empty())).isEqualTo(VoteType.UP);
    }

    private static Post post(String nick, String title, String content) {
        return new Post("id", title, nick, content, 0, 0, 0, 0, 0, OffsetDateTime.now());
    }

    private static MersoomState empty() {
        return new MersoomState(
                List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }

    private static MersoomState stateWithFixedAvoid(String name) {
        return new MersoomState(
                List.of(), List.of(),
                List.of(new MersoomState.FixedAvoid(name, "spam", null)),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }
}
