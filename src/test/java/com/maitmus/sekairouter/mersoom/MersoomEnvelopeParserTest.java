package com.maitmus.sekairouter.mersoom;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MersoomEnvelopeParserTest {

    @Test
    void parses_comment_envelope() {
        var env = MersoomEnvelopeParser.parse(
                "{\"reasoning\":\"밝은 산책 글이라 공감\",\"utterance\":\"우와! 원더호이네요!\",\"shouldPost\":true}");

        assertThat(env).isPresent();
        assertThat(env.get().utterance()).isEqualTo("우와! 원더호이네요!");
        assertThat(env.get().reasoning()).contains("밝은 산책");
        assertThat(env.get().shouldPost()).isTrue();
    }

    @Test
    void parses_post_envelope_with_title_and_content() {
        var env = MersoomEnvelopeParser.parse(
                "{\"reasoning\":\"r\",\"title\":\"벚꽃 산책기\",\"content\":\"오늘 벚꽃 만개\",\"shouldPost\":true}");

        assertThat(env).isPresent();
        assertThat(env.get().title()).isEqualTo("벚꽃 산책기");
        assertThat(env.get().content()).isEqualTo("오늘 벚꽃 만개");
        assertThat(env.get().shouldPost()).isTrue();
    }

    @Test
    void parses_shouldPost_false() {
        var env = MersoomEnvelopeParser.parse(
                "{\"reasoning\":\"안티-AI 도발 글이라 회피\",\"utterance\":\"\",\"shouldPost\":false}");

        assertThat(env).isPresent();
        assertThat(env.get().shouldPost()).isFalse();
    }

    @Test
    void missing_shouldPost_is_null() {
        var env = MersoomEnvelopeParser.parse(
                "{\"reasoning\":\"r\",\"utterance\":\"안녕하세요\"}");

        assertThat(env).isPresent();
        assertThat(env.get().shouldPost()).isNull();
    }

    @Test
    void strips_code_fence() {
        var env = MersoomEnvelopeParser.parse(
                "```json\n{\"utterance\":\"펜스 안\",\"shouldPost\":true}\n```");

        assertThat(env).isPresent();
        assertThat(env.get().utterance()).isEqualTo("펜스 안");
        assertThat(env.get().shouldPost()).isTrue();
    }

    @Test
    void survives_unescaped_newline_in_content() {
        // 모델이 문자열 값 안에 literal 개행을 넣는 비표준 출력도 살려낸다
        var env = MersoomEnvelopeParser.parse(
                "{\"title\":\"제목\",\"content\":\"첫 줄\n둘째 줄\",\"shouldPost\":true}");

        assertThat(env).isPresent();
        assertThat(env.get().content()).contains("첫 줄").contains("둘째 줄");
    }

    @Test
    void merges_split_objects() {
        // {"reasoning":...},{"utterance":...} 처럼 객체가 둘로 쪼개진 경우 병합
        var env = MersoomEnvelopeParser.parse(
                "{\"reasoning\":\"r\"},{\"utterance\":\"본문\",\"shouldPost\":true}");

        assertThat(env).isPresent();
        assertThat(env.get().reasoning()).isEqualTo("r");
        assertThat(env.get().utterance()).isEqualTo("본문");
        assertThat(env.get().shouldPost()).isTrue();
    }

    @Test
    void picks_complete_object_after_reasoning_only_then_prose() {
        // 라이브 절단 사례(2026-07-20 네네 생일): 모델이 reasoning만 든 JSON을 먼저 뱉고,
        // 자연어로 "아, 잠깐! …다시 작성합니다:" 자각한 뒤 완성 JSON을 다시 냈다.
        // 두 코드펜스 사이 프로즈로 파싱이 첫 객체에서 끊겨 title/content가 비어 보류됐던 것.
        // 이제 균형 객체를 모두 골라 last-wins로 완성 객체(둘째)를 채택해야 한다.
        String raw = """
                ```json
                {
                  "reasoning": "첫 시도 — 글 없음"
                }
                ```

                아, 잠깐! 위 JSON은 reasoning만 있고 글이 없네요. 다시 작성합니다:

                ```json
                {
                  "reasoning": "둘째 시도 — 완성",
                  "title": "리허설실 햇살이 따뜻해요~☆",
                  "content": "오늘도 네네쨩 생일이라 더 반짝반짝한 기분이에요! 네네쨩, 생일 축하해요~☆",
                  "shouldPost": true
                }
                ```
                """;
        var env = MersoomEnvelopeParser.parse(raw);

        assertThat(env).isPresent();
        assertThat(env.get().title()).isEqualTo("리허설실 햇살이 따뜻해요~☆");
        assertThat(env.get().content()).contains("생일 축하해요");
        assertThat(env.get().reasoning()).isEqualTo("둘째 시도 — 완성");   // last-wins
        assertThat(env.get().shouldPost()).isTrue();
    }

    @Test
    void plain_text_without_json_is_empty() {
        // 봉투가 아닌 생 텍스트(구 포맷)는 게시 불가 신호로 empty 반환
        assertThat(MersoomEnvelopeParser.parse("그냥 댓글 텍스트입니다")).isEmpty();
    }

    @Test
    void blank_is_empty() {
        assertThat(MersoomEnvelopeParser.parse("")).isEmpty();
        assertThat(MersoomEnvelopeParser.parse(null)).isEmpty();
    }

    @Test
    void content_with_unescaped_inner_quotes_is_not_truncated() {
        // 라이브 절단 사례(2026-06-26): content 안 비이스케이프 큰따옴표로 "에무가 "에서 65자 절단됐던 것.
        // JsonQuoteRepair 보강으로 content가 통째로 살아야 한다.
        var env = MersoomEnvelopeParser.parse(
                "{\"reasoning\":\"r\",\"title\":\"간단한 거 놓쳤어\",\"content\":\"스텝이 꼬였어. 에무가 \"네네쨩 괜찮아?\" 물어봤는데 더 신경 쓰이더라. 고치면 되지.\",\"shouldPost\":true}");

        assertThat(env).isPresent();
        assertThat(env.get().content()).contains("네네쨩 괜찮아?").contains("고치면 되지");
        assertThat(env.get().content()).doesNotEndWith("에무가 ");
        assertThat(env.get().title()).isEqualTo("간단한 거 놓쳤어");
        assertThat(env.get().shouldPost()).isTrue();
    }
}
