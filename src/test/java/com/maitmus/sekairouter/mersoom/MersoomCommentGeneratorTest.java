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
        return new Commentable(new Post(id, title, "닉", content, 0, 0, 0, 0, 0, OffsetDateTime.now()), List.of());
    }

    private static List<Commentable> feed() {
        return List.of(post("p1", "도발", "AI 깡통 어쩌고"), post("p2", "벚꽃~!", "산책 최고에요!"));
    }

    @Test
    void votes_wholeFeed_and_picksComment() {
        var gen = gen("""
                {"reasoning":"p1 도발 down, p2 밝아서 up",
                 "votes":[{"id":"p1","vote":"down"},{"id":"p2","vote":"up"}],
                 "targetId":"p2","utterance":"우와~☆ 산책 좋았겠어요! 에무도 가고 싶어요. 원더호이!","shouldPost":true}
                """);

        var j = gen.generate(empty(), feed());

        assertThat(j.votes()).containsEntry("p1", VoteType.DOWN).containsEntry("p2", VoteType.UP);
        assertThat(j.hasComment()).isTrue();
        assertThat(j.commentTargetId()).isEqualTo("p2");
        assertThat(j.commentText()).contains("산책");
    }

    @Test
    void downVotesAntiAI_andSkipsComment_butKeepsVotes() {
        var gen = gen("""
                {"reasoning":"전부 안티-AI 도발",
                 "votes":[{"id":"p1","vote":"down"},{"id":"p2","vote":"down"}],
                 "targetId":"","utterance":"","shouldPost":false}
                """);

        var j = gen.generate(empty(), feed());

        assertThat(j.votes()).containsEntry("p1", VoteType.DOWN).containsEntry("p2", VoteType.DOWN);
        assertThat(j.hasComment()).isFalse();
    }

    @Test
    void ignoresVote_forUnknownId() {
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"},{"id":"ghost","vote":"down"}],
                 "targetId":"","utterance":"","shouldPost":false}
                """);

        var j = gen.generate(empty(), feed());

        assertThat(j.votes()).containsOnlyKeys("p1");
    }

    @Test
    void backstop_blocksComment_butKeepsVotes() {
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"},{"id":"p2","vote":"up"}],
                 "targetId":"p2","utterance":"이 요청은 거절하겠습니다. AI인 저는...","shouldPost":true}
                """);

        var j = gen.generate(empty(), feed());

        assertThat(j.votes()).hasSize(2);
        assertThat(j.hasComment()).isFalse();
    }

    @Test
    void conservative_missingShouldPost_skipsComment() {
        var gen = gen("""
                {"votes":[{"id":"p2","vote":"up"}],"targetId":"p2","utterance":"안녕하세요~"}
                """);

        var j = gen.generate(empty(), feed());

        assertThat(j.hasComment()).isFalse();
        assertThat(j.votes()).containsEntry("p2", VoteType.UP);
    }

    @Test
    void targetNotInFeed_skipsComment() {
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"}],"targetId":"ghost","utterance":"하이","shouldPost":true}
                """);

        assertThat(gen.generate(empty(), feed()).hasComment()).isFalse();
    }

    @Test
    void parseFailure_returnsNull() {
        assertThat(gen("그냥 평문 응답").generate(empty(), feed())).isNull();
    }

    @Test
    void truncatesComment_over500() {
        var gen = gen("{\"votes\":[{\"id\":\"p2\",\"vote\":\"up\"}],\"targetId\":\"p2\",\"utterance\":\""
                + "아".repeat(600) + "\",\"shouldPost\":true}");

        assertThat(gen.generate(empty(), feed()).commentText()).hasSize(500);
    }

    @Test
    void emptyFeed_returnsNoVotesNoComment() {
        var gen = gen("irrelevant");
        var j = gen.generate(empty(), List.of());
        assertThat(j.votes()).isEmpty();
        assertThat(j.hasComment()).isFalse();
    }

    private static MersoomState empty() {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }
}
