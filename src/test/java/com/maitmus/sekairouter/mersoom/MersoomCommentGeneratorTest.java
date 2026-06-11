package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
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

    private static Commentable bright() {
        Post p = new Post("p1", "T", "오호돌쇠", "벚꽃 산책 기분 좋다", 0, 0, 0, 0, 0, OffsetDateTime.now());
        return new Commentable(p, List.of());
    }

    @Test
    void returns_utterance_when_shouldPost_true() {
        var gen = gen("{\"reasoning\":\"밝은 글이라 공감\","
                + "\"utterance\":\"우와! 그거 정말 원더호이네요! 에무도 같이 해보고 싶어요.\",\"shouldPost\":true}");

        String result = gen.generate(empty(), bright());

        assertThat(result).contains("원더호이").contains("에무");
        assertThat(result.length()).isLessThanOrEqualTo(500);
    }

    @Test
    void returns_null_when_shouldPost_false() {
        // 모델이 부적절하다고 판단해 게시 보류 — 메타는 reasoning에, 본문 발행 없음
        var gen = gen("{\"reasoning\":\"안티-AI 도발 글이라 에무 톤으로 낄 수 없음\",\"utterance\":\"\",\"shouldPost\":false}");

        assertThat(gen.generate(empty(), bright())).isNull();
    }

    @Test
    void returns_null_when_shouldPost_missing() {
        // 보수 기본값: shouldPost 누락 시 게시하지 않음
        var gen = gen("{\"reasoning\":\"r\",\"utterance\":\"안녕하세요\"}");

        assertThat(gen.generate(empty(), bright())).isNull();
    }

    @Test
    void returns_null_when_backstop_marker_present() {
        // shouldPost=true 여도 본문에 누수 마커가 있으면 백스톱이 차단
        var gen = gen("{\"reasoning\":\"r\",\"utterance\":\"이 댓글 요청은 거절하겠습니다.\",\"shouldPost\":true}");

        assertThat(gen.generate(empty(), bright())).isNull();
    }

    @Test
    void returns_null_when_unparseable() {
        // 봉투가 아닌 생 텍스트(구 포맷) → 게시 보류
        var gen = gen("그냥 댓글 텍스트입니다");

        assertThat(gen.generate(empty(), bright())).isNull();
    }

    @Test
    void truncates_content_over_500_chars() {
        var gen = gen("{\"utterance\":\"" + "아".repeat(600) + "\",\"shouldPost\":true}");

        String result = gen.generate(empty(), bright());

        assertThat(result).hasSize(500);
    }

    private static MersoomState empty() {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }
}
