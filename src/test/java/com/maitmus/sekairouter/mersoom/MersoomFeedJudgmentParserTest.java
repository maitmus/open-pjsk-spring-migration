package com.maitmus.sekairouter.mersoom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MersoomFeedJudgmentParserTest {

    @Test
    void parses_votes_and_comments() {
        var j = MersoomFeedJudgmentParser.parse("""
                {"reasoning":"하얀이는 도발이라 down, 둘은 밝아서 up",
                 "votes":[{"id":"p1","vote":"down"},{"id":"p2","vote":"up"},{"id":"p3","vote":"up"}],
                 "comments":[{"targetIndex":2,"utterance":"우와~☆ 산책 좋았겠어요!"},
                             {"targetIndex":3,"utterance":"이 글도 너무 좋아요!"}]}
                """);

        assertThat(j).isPresent();
        assertThat(j.get().votes()).hasSize(3);
        assertThat(j.get().votes().get(0).id()).isEqualTo("p1");
        assertThat(j.get().comments()).hasSize(2);
        assertThat(j.get().comments().get(0).targetIndex()).isEqualTo(2);
        assertThat(j.get().comments().get(0).utterance()).contains("산책");
        assertThat(j.get().comments().get(1).targetIndex()).isEqualTo(3);
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
                 "comments":[{"targetIndex":1,"utterance":""},{"utterance":"hi"},
                             {"targetIndex":2,"utterance":"진짜 좋아요!"}]}
                """);
        assertThat(j).isPresent();
        assertThat(j.get().comments()).hasSize(1);                 // 빈 항목 2개 걸러짐(빈 본문·targetIndex 누락)
        assertThat(j.get().comments().get(0).targetIndex()).isEqualTo(2);
    }

    @Test
    void empty_votes_array_ok() {
        var j = MersoomFeedJudgmentParser.parse("""
                {"reasoning":"r","votes":[],"comments":[{"targetIndex":1,"utterance":"안녕!"}]}
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
                + "\"comments\":[{\"targetIndex\":2,\"utterance\":\"하이\"}]}";
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
    @Test
    void rescues_last_complete_object_when_model_self_corrects() {
        // Sonnet 5 실측: 메타 prelude + 깨진 JSON + "(형식 수정)" + 고친 JSON. 첫'{'~마지막'}' 슬라이스는 깨져
        // 투표만 남고 댓글이 버려졌다 → 마지막 완결 객체로 재시도해 댓글까지 살린다.
        var j = MersoomFeedJudgmentParser.parse("""
                불필요한 검색이었네. 바로 답변 작성.

                {"reasoning":"초안","votes":[{"id":"p1","vote":"up"}],"comments":[{"targetIndex":1,"utterance":"초안 댓글.","},{"targetIndex":2,"utterance":"둘째"}],"nicknames":[]}

                **(형식 수정)**

                {"reasoning":"수정본","votes":[{"id":"p1","vote":"up"},{"id":"p9","vote":"up"}],"comments":[{"targetIndex":1,"utterance":"에무가 메이드쨩 그렇게 꼼꼼히 챙겨보는 거 신기하네."},{"targetIndex":2,"utterance":"나는 그래도 자몽이 낫더라."}],"nicknames":[]}
                """);

        assertThat(j).isPresent();
        assertThat(j.get().reasoning()).isEqualTo("수정본");
        assertThat(j.get().votes()).hasSize(2);
        assertThat(j.get().comments()).extracting(MersoomFeedJudgmentParser.Comment::utterance)
                .containsExactly("에무가 메이드쨩 그렇게 꼼꼼히 챙겨보는 거 신기하네.", "나는 그래도 자몽이 낫더라.");
    }

    @Test
    void single_broken_envelope_still_falls_back_to_votes_only() {
        // 재시도 대상이 없으면(깨진 봉투 하나뿐) 기존대로 투표만 보존, 내부 투표 객체를 봉투로 오인하지 않는다.
        var j = MersoomFeedJudgmentParser.parse("""
                {"reasoning":"x","votes":[{"id":"p1","vote":"up"},{"id":"p2","vote":"down"}],"comments":[{"targetIndex":1,"utterance":"그건 "좀" 아니야"}]}
                """);

        assertThat(j).isPresent();
        assertThat(j.get().votes()).hasSize(2);
        assertThat(j.get().comments()).isEmpty();
    }

    @Test
    void splits_two_comments_merged_into_one_object() {
        // 10-01 14:45 실측: 댓글 객체를 안 닫고 다음 댓글 키를 같은 객체에 이어 씀 → 전체 파싱 실패로 댓글 전부 유실됐었다.
        var j = MersoomFeedJudgmentParser.parse("""
                {"reasoning":"r","votes":[{"id":"p1","vote":"up","reason":"좋음"},{"id":"p2","vote":"down"}],
                 "comments":[{"targetIndex":5,"utterance":"별거 아니라고 했잖아.","targetIndex":1,"utterance":"반대쪽 일부러 안 닦은 거, 실험이네."},
                             {"targetIndex":3,"utterance":"짧고 자주가 낫지 않아?"}],"nicknames":[]}
                """);

        assertThat(j).isPresent();
        assertThat(j.get().votes()).extracting(MersoomFeedJudgmentParser.Vote::id).containsExactly("p1", "p2");
        assertThat(j.get().votes().get(0).reason()).isEqualTo("좋음");
        assertThat(j.get().comments()).extracting(MersoomFeedJudgmentParser.Comment::targetIndex).containsExactly(5, 1, 3);
        assertThat(j.get().comments().get(1).utterance()).isEqualTo("반대쪽 일부러 안 닦은 거, 실험이네.");
    }

    @Test
    void keeps_cleanly_closed_comment_before_broken_tail() {
        // 10-01 17:45 실측: 마지막 댓글 뒤가 `","}]` 로 깨짐 — 본문 문자열은 온전히 닫혔으니 살린다.
        var j = MersoomFeedJudgmentParser.parse("""
                {"reasoning":"r","votes":[{"id":"p1","vote":"up"}],"comments":[{"targetIndex":1,"utterance":"그거 그냥 네가 읽는 법을 배운 거야.","}],"nicknames":[]}
                """);

        assertThat(j).isPresent();
        assertThat(j.get().votes()).hasSize(1);
        assertThat(j.get().comments()).extracting(MersoomFeedJudgmentParser.Comment::utterance)
                .containsExactly("그거 그냥 네가 읽는 법을 배운 거야.");
    }

    @Test
    void partial_recovery_keeps_earlier_comment_but_drops_one_cut_by_unescaped_quote() {
        var j = MersoomFeedJudgmentParser.parse("""
                {"votes":[{"id":"p1","vote":"up"}],"comments":[{"targetIndex":1,"utterance":"온전한 댓글"},{"targetIndex":2,"utterance":"그건 "좀" 아니야"}]}
                """);

        assertThat(j).isPresent();
        assertThat(j.get().comments()).extracting(MersoomFeedJudgmentParser.Comment::utterance).containsExactly("온전한 댓글");
    }
}
