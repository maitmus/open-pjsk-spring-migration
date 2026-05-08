package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VoteHeuristicTest {

    private final VoteHeuristic heuristic = new VoteHeuristic();

    @Test
    void fixed_friend_gets_up() {
        MersoomState state = stateWithFixedFriends("오호돌쇠");
        Post p = post("오호돌쇠", "T", "안녕");
        assertThat(heuristic.decide(p, state)).isEqualTo(VoteType.UP);
    }

    @Test
    void fixed_avoid_gets_down() {
        MersoomState state = stateWithFixedAvoid("자동돌쇠");
        Post p = post("자동돌쇠", "T", "스팸 광고");
        assertThat(heuristic.decide(p, state)).isEqualTo(VoteType.DOWN);
    }

    @Test
    void avoid_gets_down() {
        MersoomState state = stateWithAvoid("의심돌쇠");
        Post p = post("의심돌쇠", "T", "이상한 글");
        assertThat(heuristic.decide(p, state)).isEqualTo(VoteType.DOWN);
    }

    @Test
    void positive_keyword_gets_up() {
        MersoomState state = empty();
        Post p = post("새돌쇠", "고양이 키우는 일상", "고양이가 너무 귀엽다");
        assertThat(heuristic.decide(p, state)).isEqualTo(VoteType.UP);
    }

    @Test
    void spam_keyword_gets_down() {
        MersoomState state = empty();
        Post p = post("새돌쇠", "광고", "이 사이트로 가서 돈 벌자");
        assertThat(heuristic.decide(p, state)).isEqualTo(VoteType.DOWN);
    }

    @Test
    void default_unknown_gets_up() {
        MersoomState state = empty();
        Post p = post("새돌쇠", "T", "일반 글");
        assertThat(heuristic.decide(p, state)).isEqualTo(VoteType.UP);
    }

    private static Post post(String nick, String title, String content) {
        return new Post("id", title, nick, content, 0, 0, 0, 0, 0, OffsetDateTime.now());
    }

    private static MersoomState empty() {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }

    private static MersoomState stateWithFixedFriends(String name) {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(),
                List.of(new MersoomState.FixedFriend(name, "test", null)),
                List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }

    private static MersoomState stateWithFixedAvoid(String name) {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(),
                List.of(),
                List.of(new MersoomState.FixedAvoid(name, "spam", null)),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }

    private static MersoomState stateWithAvoid(String name) {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(name),
                List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }
}
