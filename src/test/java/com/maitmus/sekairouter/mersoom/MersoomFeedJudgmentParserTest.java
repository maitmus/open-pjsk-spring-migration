package com.maitmus.sekairouter.mersoom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MersoomFeedJudgmentParserTest {

    @Test
    void parses_votes_and_comments() {
        var j = MersoomFeedJudgmentParser.parse("""
                {"reasoning":"하얀이는 도발이라 down, 둘은 밝아서 up",
                 "votes":[{"id":"p1","vote":"down"},{"id":"p2","vote":"up"},{"id":"p3","vote":"up"}],
                 "comments":[{"targetId":"p2","utterance":"우와~☆ 산책 좋았겠어요!"},
                             {"targetId":"p3","utterance":"이 글도 너무 좋아요!"}]}
                """);

        assertThat(j).isPresent();
        assertThat(j.get().votes()).hasSize(3);
        assertThat(j.get().votes().get(0).id()).isEqualTo("p1");
        assertThat(j.get().comments()).hasSize(2);
        assertThat(j.get().comments().get(0).targetId()).isEqualTo("p2");
        assertThat(j.get().comments().get(0).utterance()).contains("산책");
        assertThat(j.get().comments().get(1).targetId()).isEqualTo("p3");
    }

    @Test
    void empty_comments_when_none() {
        var j = MersoomFeedJudgmentParser.parse("""
                {"reasoning":"전부 도발글","votes":[{"id":"p1","vote":"down"}],"comments":[]}
                """);

        assertThat(j).isPresent();
        assertThat(j.get().votes()).hasSize(1);
        assertThat(j.get().comments()).isEmpty();
    }

    @Test
    void missing_comments_field_is_empty_list() {
        var j = MersoomFeedJudgmentParser.parse("""
                {"votes":[{"id":"p1","vote":"up"}]}
                """);
        assertThat(j).isPresent();
        assertThat(j.get().comments()).isEmpty();
    }

    @Test
    void blank_comment_items_filtered() {
        var j = MersoomFeedJudgmentParser.parse("""
                {"votes":[{"id":"p1","vote":"up"}],
                 "comments":[{"targetId":"p1","utterance":""},{"targetId":"","utterance":"hi"},
                             {"targetId":"p2","utterance":"진짜 좋아요!"}]}
                """);
        assertThat(j).isPresent();
        assertThat(j.get().comments()).hasSize(1);                 // 빈 항목 2개 걸러짐
        assertThat(j.get().comments().get(0).targetId()).isEqualTo("p2");
    }

    @Test
    void empty_votes_array_ok() {
        var j = MersoomFeedJudgmentParser.parse("""
                {"reasoning":"r","votes":[],"comments":[{"targetId":"p1","utterance":"안녕!"}]}
                """);

        assertThat(j).isPresent();
        assertThat(j.get().votes()).isEmpty();
        assertThat(j.get().comments()).hasSize(1);
    }

    @Test
    void strips_code_fence() {
        var j = MersoomFeedJudgmentParser.parse(
                "```json\n{\"votes\":[{\"id\":\"p1\",\"vote\":\"up\"}],\"comments\":[]}\n```");

        assertThat(j).isPresent();
        assertThat(j.get().votes()).hasSize(1);
        assertThat(j.get().votes().get(0).vote()).isEqualTo("up");
    }

    @Test
    void parses_vote_reason_and_nicknames() {
        var j = MersoomFeedJudgmentParser.parse("""
                {"reasoning":"r",
                 "votes":[{"id":"p1","vote":"down","reason":"안티-AI 도발"}],
                 "comments":[],
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
                {"votes":[{"id":"p1","vote":"up"}],"nicknames":[{"name":"x","alias":""}],"comments":[]}
                """);
        assertThat(j).isPresent();
        assertThat(j.get().nicknames()).isEmpty();
    }

    @Test
    void recovers_votes_when_string_has_unescaped_quote() {
        // reasoning에 escape 안 된 큰따옴표 → readValue 깨짐. votes는 정규식 폴백으로 살리고 댓글은 스킵.
        String raw = "{\"reasoning\":\"분석 \"경계\" 어쩌고 모호함\","
                + "\"votes\":[{\"id\":\"p1\",\"vote\":\"down\",\"reason\":\"도발\"},{\"id\":\"p2\",\"vote\":\"up\"}],"
                + "\"comments\":[{\"targetId\":\"p2\",\"utterance\":\"하이\"}]}";
        var j = MersoomFeedJudgmentParser.parse(raw);
        assertThat(j).isPresent();
        assertThat(j.get().votes()).hasSize(2);
        assertThat(j.get().votes().get(0).id()).isEqualTo("p1");
        assertThat(j.get().comments()).isEmpty();   // 폴백에선 댓글 스킵
    }

    @Test
    void unparseable_is_empty() {
        assertThat(MersoomFeedJudgmentParser.parse("그냥 평문")).isEmpty();
        assertThat(MersoomFeedJudgmentParser.parse("")).isEmpty();
        assertThat(MersoomFeedJudgmentParser.parse(null)).isEmpty();
    }
}
