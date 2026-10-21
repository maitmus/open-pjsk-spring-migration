package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Comment;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MersoomReplyThreadsTest {

    private static final String ME = "nene_wonder";
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-22T10:00:00+09:00");

    private static Comment c(String id, String parent, String authId, String nick, String text, int min) {
        return new Comment(id, "p1", parent, nick, text, 0, 0, T0.plusMinutes(min), authId, null, false);
    }

    @Test
    void reply_to_my_comment_is_candidate_with_thread() {
        var cs = List.of(c("a", null, ME, "네네", "세 번째 찻잔이면 잠 안 오지", 0),
                c("b", "a", "maid_chibi", "Maid-Chibi", "루이보스로 마셔볼게요!", 5));
        var out = MersoomReplyThreads.candidates("p1", "밤공기", cs, ME, Set.of());
        assertThat(out).hasSize(1);
        var r = out.get(0);
        assertThat(r.replyToId()).isEqualTo("b");
        assertThat(r.replyAuthorKey()).isEqualTo("maid_chibi");
        assertThat(r.thread()).extracting(MersoomReplyThreads.ThreadLine::mine).containsExactly(true, false);
        assertThat(r.thread().get(1).text()).isEqualTo("루이보스로 마셔볼게요!");
    }

    @Test
    void already_answered_reply_is_not_candidate() {
        var cs = List.of(c("a", null, ME, "네네", "x", 0), c("b", "a", "maid_chibi", "M", "y", 5),
                c("c", "b", ME, "네네", "z", 10));
        assertThat(MersoomReplyThreads.candidates("p1", "t", cs, ME, Set.of())).isEmpty();
    }

    @Test
    void depth4_reply_is_candidate_but_depth6_is_ignored() {
        var d4 = new ArrayList<>(List.of(c("a", null, ME, "네네", "1", 0), c("b", "a", "m", "M", "2", 1),
                c("c", "b", ME, "네네", "3", 2), c("d", "c", "m", "M", "4", 3)));
        assertThat(MersoomReplyThreads.candidates("p1", "t", d4, ME, Set.of()))
                .extracting(MersoomReplyThreads.ReplyCandidate::replyToId).containsExactly("d");
        d4.add(c("e", "d", ME, "네네", "5", 4));
        d4.add(c("f", "e", "m", "M", "6", 5));
        assertThat(MersoomReplyThreads.candidates("p1", "t", d4, ME, Set.of())).isEmpty();
    }

    @Test
    void reply_whose_parent_is_not_mine_is_ignored() {
        var cs = List.of(c("a", null, "other", "O", "x", 0), c("b", "a", "m", "M", "y", 5));
        assertThat(MersoomReplyThreads.candidates("p1", "t", cs, ME, Set.of())).isEmpty();
    }

    @Test
    void blocked_author_reply_is_ignored() {
        var cs = List.of(c("a", null, ME, "네네", "x", 0), c("b", "a", "w8agi", "하얀이", "y", 5));
        assertThat(MersoomReplyThreads.candidates("p1", "t", cs, ME, Set.of("w8agi"))).isEmpty();
    }

    @Test
    void sibling_reply_is_candidate() {
        var cs = List.of(c("a", null, ME, "네네", "x", 0), c("b", "a", "emu_wonder", "에무", "네네쨩~", 5));
        assertThat(MersoomReplyThreads.candidates("p1", "t", cs, ME, Set.of()))
                .extracting(MersoomReplyThreads.ReplyCandidate::replyAuthorNick).containsExactly("에무");
    }

    @Test
    void deleted_my_comment_drops_replies() {
        var mine = new Comment("a", "p1", null, "네네", "x", 0, 0, T0, ME, null, true);
        var cs = List.of(mine, c("b", "a", "m", "M", "y", 5));
        assertThat(MersoomReplyThreads.candidates("p1", "t", cs, ME, Set.of())).isEmpty();
    }

    @Test
    void depth_counts_root_as_one() {
        var a = c("a", null, ME, "n", "1", 0);
        var b = c("b", "a", "m", "M", "2", 1);
        var cc = c("c", "b", ME, "n", "3", 2);
        var byId = Map.of("a", a, "b", b, "c", cc);
        assertThat(MersoomReplyThreads.depth(a, byId)).isEqualTo(1);
        assertThat(MersoomReplyThreads.depth(cc, byId)).isEqualTo(3);
    }
}
