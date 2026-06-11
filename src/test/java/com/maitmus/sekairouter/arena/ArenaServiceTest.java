package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.arena.ArenaDtos.StatusResponse;
import com.maitmus.sekairouter.arena.ArenaDtos.Topic;
import com.maitmus.sekairouter.arena.ArenaProperties.Account;
import com.maitmus.sekairouter.mersoom.MersoomDtos.CreateResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArenaServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-12T04:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final Account emu = new Account("emu_wonder", "pw", "에무");
    private final Account nene = new Account("nene_wonder", "pw", "쿠사나기 네네");
    private static final Topic TOPIC = new Topic("t1", "제목", "p", "c");

    private ArenaApiClient api = mock(ArenaApiClient.class);
    private ArenaProposeGenerator proposeGen = mock(ArenaProposeGenerator.class);
    private ArenaFightGenerator fightGen = mock(ArenaFightGenerator.class);

    private ArenaService service() {
        ArenaProperties p = mock(ArenaProperties.class);
        when(p.enabled()).thenReturn(true);
        when(p.propose()).thenReturn(emu);
        when(p.fight()).thenReturn(nene);
        return new ArenaService(p, api, proposeGen, fightGen, clock);
    }

    @Test
    void propose_skips_when_not_PROPOSE_phase() {
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "BATTLE", TOPIC));
        service().executePropose();
        verify(proposeGen, never()).generate();
        verify(api, never()).propose(any(), any(), any(), any());
    }

    @Test
    void propose_posts_in_PROPOSE_phase() {
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "PROPOSE", null));
        when(proposeGen.generate()).thenReturn(new ArenaProposeGenerator.ProposedTopic("제목", "p", "c"));
        when(api.propose(any(), any(), any(), any())).thenReturn(new CreateResponse(true, "id"));

        service().executePropose();

        verify(api).propose(eq(emu), eq("제목"), eq("p"), eq("c"));
    }

    @Test
    void propose_skips_when_generator_null() {
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "PROPOSE", null));
        when(proposeGen.generate()).thenReturn(null);
        service().executePropose();
        verify(api, never()).propose(any(), any(), any(), any());
    }

    @Test
    void fight_skips_when_not_BATTLE_phase() {
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "PROPOSE", null));
        service().executeFight();
        verify(fightGen, never()).generate(any(), any());
        verify(api, never()).fight(any(), any(), any());
    }

    @Test
    void fight_posts_in_BATTLE_phase() {
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "BATTLE", TOPIC));
        when(api.fightPosts(any())).thenReturn(List.of());
        when(fightGen.generate(any(), any())).thenReturn(new ArenaFightGenerator.FightDecision("CON", "논거임"));
        when(api.fight(any(), any(), any())).thenReturn(new CreateResponse(true, "id"));

        service().executeFight();

        verify(api).fight(eq(nene), eq("CON"), eq("논거임"));
    }

    @Test
    void fight_skips_when_generator_null() {
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "BATTLE", TOPIC));
        when(api.fightPosts(any())).thenReturn(List.of());
        when(fightGen.generate(any(), any())).thenReturn(null);
        service().executeFight();
        verify(api, never()).fight(any(), any(), any());
    }

    @Test
    void disabled_does_nothing() {
        ArenaProperties p = mock(ArenaProperties.class);
        when(p.enabled()).thenReturn(false);
        new ArenaService(p, api, proposeGen, fightGen, clock).executePropose();
        verify(api, never()).status();
    }
}
