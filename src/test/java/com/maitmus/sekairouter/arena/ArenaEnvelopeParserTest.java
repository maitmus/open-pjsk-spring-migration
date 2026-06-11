package com.maitmus.sekairouter.arena;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArenaEnvelopeParserTest {

    @Test
    void parses_propose_envelope() {
        var e = ArenaEnvelopeParser.parse("""
                {"reasoning":"가치 토픽","title":"진짜 우정은 솔직함일까 배려일까",
                 "pros":"솔직함이 신뢰의 바탕","cons":"배려 없는 솔직함은 폭력"}
                """);
        assertThat(e).isPresent();
        assertThat(e.get().title()).contains("우정");
        assertThat(e.get().pros()).contains("신뢰");
        assertThat(e.get().cons()).contains("배려");
    }

    @Test
    void parses_fight_envelope() {
        var e = ArenaEnvelopeParser.parse("""
                {"reasoning":"논리상 CON","side":"CON","content":"전제가 틀렸음. 첫째...","shouldFight":true}
                """);
        assertThat(e).isPresent();
        assertThat(e.get().side()).isEqualTo("CON");
        assertThat(e.get().content()).contains("전제가 틀렸");
        assertThat(e.get().shouldFight()).isTrue();
    }

    @Test
    void parses_shouldFight_false() {
        var e = ArenaEnvelopeParser.parse("""
                {"reasoning":"네네가 다룰 토픽 아님","side":"","content":"","shouldFight":false}
                """);
        assertThat(e).isPresent();
        assertThat(e.get().shouldFight()).isFalse();
    }

    @Test
    void strips_code_fence() {
        var e = ArenaEnvelopeParser.parse("```json\n{\"side\":\"PRO\",\"content\":\"맞음\",\"shouldFight\":true}\n```");
        assertThat(e).isPresent();
        assertThat(e.get().side()).isEqualTo("PRO");
    }

    @Test
    void unparseable_is_empty() {
        assertThat(ArenaEnvelopeParser.parse("그냥 평문")).isEmpty();
        assertThat(ArenaEnvelopeParser.parse("")).isEmpty();
        assertThat(ArenaEnvelopeParser.parse(null)).isEmpty();
    }
}
