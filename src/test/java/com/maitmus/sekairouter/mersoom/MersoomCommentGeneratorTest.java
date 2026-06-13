package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.OutputSanityGate;
import com.maitmus.sekairouter.routing.PromptBlocks;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MersoomCommentGeneratorTest {

    private MersoomCommentGenerator gen(String llmReturn) {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString())).thenReturn(llmReturn);
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build()).thenReturn(new PromptBlocks("s", "s"));
        return new MersoomCommentGenerator(anthropic, pb, new OutputSanityGate());
    }

    private static Commentable post(String id, String title, String content) {
        return new Commentable(new Post(id, title, "닉", content, 0, 0, 0, 0, 0, OffsetDateTime.now(), null, null), List.of());
    }

    private static List<Commentable> feed() {
        return List.of(post("p1", "도발", "AI 깡통 어쩌고"), post("p2", "벚꽃~!", "산책 최고에요!"));
    }

    private static List<Commentable> feed4() {
        return List.of(post("p1", "도발", "AI 깡통"), post("p2", "벚꽃", "산책 최고!"),
                post("p3", "노을", "노을 예뻐요"), post("p4", "붕어빵", "겨울 간식 최고"));
    }

    @Test
    void votes_wholeFeed_and_picksComment() {
        var gen = gen("""
                {"reasoning":"p1 도발 down, p2 밝아서 up",
                 "votes":[{"id":"p1","vote":"down"},{"id":"p2","vote":"up"}],
                 "comments":[{"targetId":"p2","utterance":"우와~☆ 산책 좋았겠어요! 에무도 가고 싶어요. 원더호이!"}]}
                """);

        var j = gen.generate(empty(), feed());

        assertThat(j.votes()).containsEntry("p1", VoteType.DOWN).containsEntry("p2", VoteType.UP);
        assertThat(j.hasComment()).isTrue();
        assertThat(j.comments()).hasSize(1);
        assertThat(j.comments().get(0).targetId()).isEqualTo("p2");
        assertThat(j.comments().get(0).text()).contains("산책");
    }

    @Test
    void multiple_comments_up_to_3() {
        var gen = gen("""
                {"votes":[{"id":"p2","vote":"up"},{"id":"p3","vote":"up"},{"id":"p4","vote":"up"}],
                 "comments":[{"targetId":"p2","utterance":"산책 좋아요!"},
                             {"targetId":"p3","utterance":"노을 예뻐요!"},
                             {"targetId":"p4","utterance":"붕어빵 최고!"}]}
                """);
        var j = gen.generate(empty(), feed4());
        assertThat(j.comments()).hasSize(3);
    }

    @Test
    void caps_comments_at_3() {
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"}],
                 "comments":[{"targetId":"p1","utterance":"하나"},{"targetId":"p2","utterance":"둘"},
                             {"targetId":"p3","utterance":"셋"},{"targetId":"p4","utterance":"넷"}]}
                """);
        assertThat(gen.generate(empty(), feed4()).comments()).hasSize(3);   // 4개 줘도 3개로
    }

    @Test
    void dedupes_same_target() {
        var gen = gen("""
                {"votes":[{"id":"p2","vote":"up"}],
                 "comments":[{"targetId":"p2","utterance":"첫 댓글"},{"targetId":"p2","utterance":"같은 글 또"}]}
                """);
        assertThat(gen.generate(empty(), feed()).comments()).hasSize(1);    // 같은 글 중복 제거
    }

    @Test
    void downVotesAntiAI_andSkipsComments_butKeepsVotes() {
        var gen = gen("""
                {"reasoning":"전부 안티-AI 도발",
                 "votes":[{"id":"p1","vote":"down"},{"id":"p2","vote":"down"}],
                 "comments":[]}
                """);

        var j = gen.generate(empty(), feed());

        assertThat(j.votes()).containsEntry("p1", VoteType.DOWN).containsEntry("p2", VoteType.DOWN);
        assertThat(j.hasComment()).isFalse();
    }

    @Test
    void ignoresVote_forUnknownId() {
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"},{"id":"ghost","vote":"down"}],"comments":[]}
                """);

        assertThat(gen.generate(empty(), feed()).votes()).containsOnlyKeys("p1");
    }

    @Test
    void backstop_drops_leaky_comment_but_keeps_clean_ones() {
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"},{"id":"p2","vote":"up"}],
                 "comments":[{"targetId":"p1","utterance":"이 요청은 거절하겠습니다. AI인 저는..."},
                             {"targetId":"p2","utterance":"산책 정말 좋았겠어요!"}]}
                """);

        var j = gen.generate(empty(), feed());

        assertThat(j.votes()).hasSize(2);
        assertThat(j.comments()).hasSize(1);                          // 누수 항목만 제거
        assertThat(j.comments().get(0).targetId()).isEqualTo("p2");
    }

    @Test
    void targetNotInFeed_dropped() {
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"}],"comments":[{"targetId":"ghost","utterance":"하이"}]}
                """);
        assertThat(gen.generate(empty(), feed()).hasComment()).isFalse();
    }

    @Test
    void parseFailure_returnsNull() {
        assertThat(gen("그냥 평문 응답").generate(empty(), feed())).isNull();
    }

    @Test
    void truncatesComment_over500() {
        var gen = gen("{\"votes\":[{\"id\":\"p2\",\"vote\":\"up\"}],\"comments\":[{\"targetId\":\"p2\",\"utterance\":\""
                + "아".repeat(600) + "\"}]}");

        assertThat(gen.generate(empty(), feed()).comments().get(0).text()).hasSize(500);
    }

    @Test
    void emptyFeed_returnsNoVotesNoComment() {
        var gen = gen("irrelevant");
        var j = gen.generate(empty(), List.of());
        assertThat(j.votes()).isEmpty();
        assertThat(j.hasComment()).isFalse();
    }

    private static MersoomState empty() {
        return new MersoomState(List.of(), List.of(), List.of(), Map.of(), 8,
                List.of(), null, null, List.of(), List.of());
    }
}
