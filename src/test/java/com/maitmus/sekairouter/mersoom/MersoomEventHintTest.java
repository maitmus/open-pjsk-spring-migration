package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.heartbeat.EventsCalendar;
import com.maitmus.sekairouter.persona.CharacterId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MersoomEventHintTest {

    private EventsCalendar cal(EventsCalendar.EventOverride ev) {
        var c = mock(EventsCalendar.class);
        when(c.todayOverride()).thenReturn(Optional.ofNullable(ev));
        return c;
    }

    @Test
    void birthday_self_gets_self_toned_hint() {
        var ev = new EventsCalendar.EventOverride("쿠사나기 네네 생일", List.of(CharacterId.NENE), EventsCalendar.EventKind.BIRTHDAY);
        assertThat(MersoomEventHint.todayLine(cal(ev), CharacterId.NENE))
                .contains("네 생일").contains("자축").doesNotContain("축하 한 마디");
    }

    @Test
    void birthday_other_gets_celebrate_hint() {
        var ev = new EventsCalendar.EventOverride("쿠사나기 네네 생일", List.of(CharacterId.NENE), EventsCalendar.EventKind.BIRTHDAY);
        assertThat(MersoomEventHint.todayLine(cal(ev), CharacterId.EMU))
                .contains("쿠사나기 네네 생일").contains("축하").doesNotContain("자축");   // 타인은 축하 톤(자축 아님)
    }

    @Test
    void no_event_returns_empty() {
        assertThat(MersoomEventHint.todayLine(cal(null), CharacterId.EMU)).isEmpty();
    }

    @Test
    void comment_line_limits_event_to_posts_that_mention_it() {
        var ev = new EventsCalendar.EventOverride("키리타니 하루카 생일", List.of(CharacterId.HARUKA), EventsCalendar.EventKind.BIRTHDAY);
        assertThat(MersoomEventHint.commentLine(cal(ev), CharacterId.EMU))
                .contains("키리타니 하루카 생일").contains("\"mentionsEvent\":true인 글").contains("표시 없는 글의 댓글엔 이벤트를 꺼내지 말 것");
        assertThat(MersoomEventHint.commentLine(cal(null), CharacterId.EMU)).isEmpty();
    }

    @Test
    void mentions_matches_name_tokens_not_generic_words() {
        var ev = new EventsCalendar.EventOverride("키리타니 하루카 생일", List.of(CharacterId.HARUKA), EventsCalendar.EventKind.BIRTHDAY);
        assertThat(MersoomEventHint.mentions(ev, "오늘 하루카쨩 생일이라 두근두근")).isTrue();
        assertThat(MersoomEventHint.mentions(ev, "키리타니 씨 축하해")).isTrue();
        assertThat(MersoomEventHint.mentions(ev, "내 생일은 아직 멀었어")).isFalse();   // '생일'만으론 X
        assertThat(MersoomEventHint.mentions(ev, null)).isFalse();
    }
}
