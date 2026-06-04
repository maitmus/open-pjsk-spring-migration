package com.maitmus.sekairouter.routing;

import com.maitmus.sekairouter.routing.UtteranceEnvelopeParser.Envelope;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class UtteranceEnvelopeParserTest {

    @Test
    void parsesPlainObject() {
        String raw = "{\"reasoning\":\"메타 사고\",\"utterance\":\"안녕\"}";

        Optional<Envelope> env = UtteranceEnvelopeParser.parse(raw);

        assertThat(env).isPresent();
        assertThat(env.get().reasoning()).isEqualTo("메타 사고");
        assertThat(env.get().utterance()).isEqualTo("안녕");
    }

    @Test
    void stripsCodeFence() {
        String raw = "```json\n{\"reasoning\":\"x\",\"utterance\":\"펜스 안\"}\n```";

        Optional<Envelope> env = UtteranceEnvelopeParser.parse(raw);

        assertThat(env).isPresent();
        assertThat(env.get().utterance()).isEqualTo("펜스 안");
    }

    @Test
    void toleratesLiteralNewlinesInsideStringValue() {
        // 모델이 reasoning 안에 raw 개행을 넣은 경우 (표준 JSON 위반이지만 흔함)
        String raw = "{\"reasoning\":\"첫 줄\n\n둘째 단락\",\"utterance\":\"발화\"}";

        Optional<Envelope> env = UtteranceEnvelopeParser.parse(raw);

        assertThat(env).isPresent();
        assertThat(env.get().utterance()).isEqualTo("발화");
    }

    @Test
    void mergesFieldsWhenModelSplitsIntoTwoObjects() {
        // 2026-06-01 09:30 실제 실패 케이스 재현:
        // reasoning(개행 포함)과 utterance가 별도 top-level 객체로 쪼개짐
        String raw = "```json\n"
                + "{\n  \"reasoning\": \"기상 분석\n\n페르소나 검토\"\n}\n"
                + ",\n"
                + "{\n  \"utterance\": \"오늘 공기가 무거워\"\n}\n"
                + "```";

        Optional<Envelope> env = UtteranceEnvelopeParser.parse(raw);

        assertThat(env).isPresent();
        assertThat(env.get().utterance()).isEqualTo("오늘 공기가 무거워");
        assertThat(env.get().reasoning()).contains("기상 분석");
    }

    @Test
    void handlesPreludeTextBeforeObject() {
        String raw = "확인했어요. {\"utterance\":\"본론\"}";

        Optional<Envelope> env = UtteranceEnvelopeParser.parse(raw);

        assertThat(env).isPresent();
        assertThat(env.get().utterance()).isEqualTo("본론");
    }

    @Test
    void emptyWhenUtteranceBlank() {
        String raw = "{\"reasoning\":\"메타만 있고\",\"utterance\":\"  \"}";

        assertThat(UtteranceEnvelopeParser.parse(raw)).isEmpty();
    }

    @Test
    void emptyWhenUtteranceMissing() {
        String raw = "{\"reasoning\":\"utterance 누락\"}";

        assertThat(UtteranceEnvelopeParser.parse(raw)).isEmpty();
    }

    @Test
    void emptyOnGarbage() {
        assertThat(UtteranceEnvelopeParser.parse("완전 깨진 텍스트, JSON 아님")).isEmpty();
    }

    @Test
    void emptyOnNull() {
        assertThat(UtteranceEnvelopeParser.parse(null)).isEmpty();
    }
}
