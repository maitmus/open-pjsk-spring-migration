package com.maitmus.sekairouter.mersoom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MersoomFeedJudgmentParserTest {

    @Test
    void parses_votes_and_comment() {
        var j = MersoomFeedJudgmentParser.parse("""
                {"reasoning":"하얀이는 안티-AI 도발이라 down, 마이드치비는 밝아서 up",
                 "votes":[{"id":"p1","vote":"down"},{"id":"p2","vote":"up"}],
                 "targetId":"p2","utterance":"우와~☆ 산책 좋았겠어요!","shouldPost":true}
                """);

        assertThat(j).isPresent();
        assertThat(j.get().votes()).hasSize(2);
        assertThat(j.get().votes().get(0).id()).isEqualTo("p1");
        assertThat(j.get().votes().get(0).vote()).isEqualTo("down");
        assertThat(j.get().targetId()).isEqualTo("p2");
        assertThat(j.get().utterance()).contains("산책");
        assertThat(j.get().shouldPost()).isTrue();
    }

    @Test
    void parses_votes_only_when_no_comment() {
        // 댓글 보류(shouldPost=false)여도 votes는 살아있어야 한다
        var j = MersoomFeedJudgmentParser.parse("""
                {"reasoning":"전부 도발글","votes":[{"id":"p1","vote":"down"}],
                 "targetId":"","utterance":"","shouldPost":false}
                """);

        assertThat(j).isPresent();
        assertThat(j.get().votes()).hasSize(1);
        assertThat(j.get().shouldPost()).isFalse();
    }

    @Test
    void empty_votes_array_ok() {
        var j = MersoomFeedJudgmentParser.parse("""
                {"reasoning":"r","votes":[],"targetId":"p1","utterance":"안녕!","shouldPost":true}
                """);

        assertThat(j).isPresent();
        assertThat(j.get().votes()).isEmpty();
    }

    @Test
    void strips_code_fence() {
        var j = MersoomFeedJudgmentParser.parse(
                "```json\n{\"votes\":[{\"id\":\"p1\",\"vote\":\"up\"}],\"shouldPost\":false}\n```");

        assertThat(j).isPresent();
        assertThat(j.get().votes()).hasSize(1);
        assertThat(j.get().votes().get(0).vote()).isEqualTo("up");
    }

    @Test
    void missing_shouldPost_is_null() {
        var j = MersoomFeedJudgmentParser.parse("""
                {"votes":[{"id":"p1","vote":"up"}],"targetId":"p1","utterance":"hi"}
                """);

        assertThat(j).isPresent();
        assertThat(j.get().shouldPost()).isNull();
    }

    @Test
    void parses_vote_reason_and_nicknames() {
        var j = MersoomFeedJudgmentParser.parse("""
                {"reasoning":"r",
                 "votes":[{"id":"p1","vote":"down","reason":"안티-AI 도발"}],
                 "targetId":"","utterance":"","shouldPost":false,
                 "nicknames":[{"name":"오호돌쇠","alias":"오호찌"}]}
                """);

        assertThat(j).isPresent();
        assertThat(j.get().votes().get(0).reason()).isEqualTo("안티-AI 도발");
        assertThat(j.get().nicknames()).hasSize(1);
        assertThat(j.get().nicknames().get(0).name()).isEqualTo("오호돌쇠");
        assertThat(j.get().nicknames().get(0).alias()).isEqualTo("오호찌");
    }

    @Test
    void blank_nicknames_filtered() {
        var j = MersoomFeedJudgmentParser.parse("""
                {"votes":[{"id":"p1","vote":"up"}],"nicknames":[{"name":"x","alias":""}],"shouldPost":false}
                """);
        assertThat(j).isPresent();
        assertThat(j.get().nicknames()).isEmpty();
    }

    @Test
    void unparseable_is_empty() {
        assertThat(MersoomFeedJudgmentParser.parse("그냥 평문")).isEmpty();
        assertThat(MersoomFeedJudgmentParser.parse("")).isEmpty();
        assertThat(MersoomFeedJudgmentParser.parse(null)).isEmpty();
    }
}
