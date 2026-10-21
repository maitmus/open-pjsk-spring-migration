package com.maitmus.sekairouter.mersoom;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 댓글은 09–23시, 글은 09–19시(10-21 댓글만 저녁 연장 — 20시 이후 피드 글이 전량 미커버였음). */
class MersoomActiveHoursTest {

    private static Clock at(int hour) {
        return Clock.fixed(ZonedDateTime.of(2026, 10, 21, hour, 15, 0, 0, ZoneId.of("Asia/Seoul")).toInstant(),
                ZoneId.of("Asia/Seoul"));
    }

    private static MersoomProperties props() {
        MersoomProperties p = mock(MersoomProperties.class);
        when(p.enabled()).thenReturn(true);
        when(p.stateFile()).thenReturn("/tmp/e.json");
        var auth = new MersoomProperties.Auth("nene_wonder", "x");
        when(p.nene()).thenReturn(new MersoomProperties.Nene(true, "", "", "/tmp/n.json", auth));
        return p;
    }

    @Test
    void emu_comments_run_until_23_but_posts_stop_after_19() {
        for (int h : new int[]{9, 19, 20, 23}) {
            MersoomCitizenEngine engine = mock(MersoomCitizenEngine.class);
            var svc = new MersoomService(props(), engine, at(h));
            svc.executeComment();
            svc.executePost();
            verify(engine).runComment(any());
            verify(engine, times(h <= 19 ? 1 : 0)).runPost(any());
        }
    }

    @Test
    void nene_comments_run_until_23_but_posts_stop_after_19() {
        for (int h : new int[]{9, 19, 20, 23}) {
            MersoomCitizenEngine engine = mock(MersoomCitizenEngine.class);
            var svc = new NeneMersoomService(props(), engine, at(h));
            svc.executeComment();
            svc.executePost();
            verify(engine).runComment(any());
            verify(engine, times(h <= 19 ? 1 : 0)).runPost(any());
        }
    }

    @Test
    void nothing_runs_before_9() {
        MersoomCitizenEngine engine = mock(MersoomCitizenEngine.class);
        new MersoomService(props(), engine, at(8)).executeComment();
        new NeneMersoomService(props(), engine, at(8)).executeComment();
        verifyNoInteractions(engine);
    }
}
