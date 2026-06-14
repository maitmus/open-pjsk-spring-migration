package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.OutputSanityGate;
import com.maitmus.sekairouter.routing.PromptBlocks;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MersoomCommentGeneratorTest {

    private static final CitizenProfile EMU = new CitizenProfile("emu", "에무",
            new MersoomProperties.Auth("emu_wonder", "x"), java.nio.file.Path.of("/tmp/e.json"),
            com.maitmus.sekairouter.persona.CharacterId.EMU, java.util.Set.of());

    private MersoomCommentGenerator gen(String llmReturn) {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString())).thenReturn(llmReturn);
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        return new MersoomCommentGenerator(anthropic, pb, new OutputSanityGate());
    }

    private static Commentable post(String id, String title, String content) {
        return new Commentable(new Post(id, title, "닉", content, 0, 0, 0, 0, 0, OffsetDateTime.now(), null, null), List.of());
    }

    private static List<Commentable> feed() {
        return List.of(post("p1", "도발", "AI 깡통 어쩌고"), post("p2", "벚꽃~!", "산책 최고에요!"));
    }

    @Test
    void sibling_post_gets_GRADES_addressing_and_no_petname() {
        // 에무가 형제봇 네네(nene_wonder) 글을 볼 때 — GRADES 호칭 지시 + 별명 금지가 프롬프트에 들어가야 함
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        when(anthropic.completeJson(any(PromptBlocks.class), userPrompt.capture()))
                .thenReturn("{\"votes\":[{\"id\":\"p1\",\"vote\":\"up\"}],\"comments\":[]}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomCommentGenerator g = new MersoomCommentGenerator(anthropic, pb, new OutputSanityGate());

        CitizenProfile emuWithSibling = new CitizenProfile("emu", "에무",
                new MersoomProperties.Auth("emu_wonder", "x"), java.nio.file.Path.of("/tmp/e.json"),
                com.maitmus.sekairouter.persona.CharacterId.EMU, Set.of("nene_wonder"));
        Commentable nenePost = new Commentable(
                new Post("p1", "노래 연습", "네네", "오늘 고음 잘 나왔다", 0, 0, 0, 0, 0,
                        OffsetDateTime.now(), "nene_wonder", null), List.of());

        g.generate(emuWithSibling, empty(), List.of(nenePost));

        String prompt = userPrompt.getValue();
        // 형제봇 라인 — 명시 호칭('네네쨩')·반말 강제·별명 금지가 들어가야 함 (에무 기본 존댓말 디폴트 무시).
        // GRADES 룩업 간접지시('너→네네')로는 에무 존댓말이 이겨 말투가 새서, 호칭·말투를 직접 박는다.
        assertThat(prompt).contains("원더랜즈×쇼타임").contains("네네쨩").contains("반말")
                .contains("별명 짓지 말 것").contains("존댓말 기본값");
    }

    @Test
    void nene_prompt_invites_cynical_reply_to_wary_emu_does_not() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        when(anthropic.completeJson(any(PromptBlocks.class), userPrompt.capture()))
                .thenReturn("{\"votes\":[],\"comments\":[]}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomCommentGenerator g = new MersoomCommentGenerator(anthropic, pb, new OutputSanityGate());

        CitizenProfile nene = new CitizenProfile("nene", "네네",
                new MersoomProperties.Auth("nene_wonder", "x"), java.nio.file.Path.of("/tmp/n.json"),
                com.maitmus.sekairouter.persona.CharacterId.NENE, Set.of("emu_wonder"));

        g.generate(nene, empty(), feed());
        String nenePrompt = userPrompt.getValue();
        assertThat(nenePrompt).contains("경계(rep≤-1, 차단 아님)").contains("츳코미·직설 일침");  // 네네=경계에 시니컬

        g.generate(EMU, empty(), feed());
        String emuPrompt = userPrompt.getValue();
        assertThat(emuPrompt).doesNotContain("경계(rep≤-1, 차단 아님)");   // 에무 댓글 기준엔 경계 초대 없음
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

        var j = gen.generate(EMU, empty(), feed());

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
        var j = gen.generate(EMU, empty(), feed4());
        assertThat(j.comments()).hasSize(3);
    }

    @Test
    void caps_comments_at_3() {
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"}],
                 "comments":[{"targetId":"p1","utterance":"하나"},{"targetId":"p2","utterance":"둘"},
                             {"targetId":"p3","utterance":"셋"},{"targetId":"p4","utterance":"넷"}]}
                """);
        assertThat(gen.generate(EMU, empty(), feed4()).comments()).hasSize(3);   // 4개 줘도 3개로
    }

    @Test
    void dedupes_same_target() {
        var gen = gen("""
                {"votes":[{"id":"p2","vote":"up"}],
                 "comments":[{"targetId":"p2","utterance":"첫 댓글"},{"targetId":"p2","utterance":"같은 글 또"}]}
                """);
        assertThat(gen.generate(EMU, empty(), feed()).comments()).hasSize(1);    // 같은 글 중복 제거
    }

    @Test
    void downVotesAntiAI_andSkipsComments_butKeepsVotes() {
        var gen = gen("""
                {"reasoning":"전부 안티-AI 도발",
                 "votes":[{"id":"p1","vote":"down"},{"id":"p2","vote":"down"}],
                 "comments":[]}
                """);

        var j = gen.generate(EMU, empty(), feed());

        assertThat(j.votes()).containsEntry("p1", VoteType.DOWN).containsEntry("p2", VoteType.DOWN);
        assertThat(j.hasComment()).isFalse();
    }

    @Test
    void ignoresVote_forUnknownId() {
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"},{"id":"ghost","vote":"down"}],"comments":[]}
                """);

        assertThat(gen.generate(EMU, empty(), feed()).votes()).containsOnlyKeys("p1");
    }

    @Test
    void backstop_drops_leaky_comment_but_keeps_clean_ones() {
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"},{"id":"p2","vote":"up"}],
                 "comments":[{"targetId":"p1","utterance":"이 요청은 거절하겠습니다. AI인 저는..."},
                             {"targetId":"p2","utterance":"산책 정말 좋았겠어요!"}]}
                """);

        var j = gen.generate(EMU, empty(), feed());

        assertThat(j.votes()).hasSize(2);
        assertThat(j.comments()).hasSize(1);                          // 누수 항목만 제거
        assertThat(j.comments().get(0).targetId()).isEqualTo("p2");
    }

    @Test
    void targetNotInFeed_dropped() {
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"}],"comments":[{"targetId":"ghost","utterance":"하이"}]}
                """);
        assertThat(gen.generate(EMU, empty(), feed()).hasComment()).isFalse();
    }

    @Test
    void parseFailure_returnsNull() {
        assertThat(gen("그냥 평문 응답").generate(EMU, empty(), feed())).isNull();
    }

    @Test
    void truncatesComment_over500() {
        var gen = gen("{\"votes\":[{\"id\":\"p2\",\"vote\":\"up\"}],\"comments\":[{\"targetId\":\"p2\",\"utterance\":\""
                + "아".repeat(600) + "\"}]}");

        assertThat(gen.generate(EMU, empty(), feed()).comments().get(0).text()).hasSize(500);
    }

    @Test
    void emptyFeed_returnsNoVotesNoComment() {
        var gen = gen("irrelevant");
        var j = gen.generate(EMU, empty(), List.of());
        assertThat(j.votes()).isEmpty();
        assertThat(j.hasComment()).isFalse();
    }

    private static MersoomState empty() {
        return new MersoomState(List.of(), List.of(), List.of(), Map.of(), 8,
                List.of(), null, null, List.of(), List.of());
    }
}
