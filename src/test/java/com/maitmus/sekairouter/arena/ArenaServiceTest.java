package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.arena.ArenaDtos.FightPost;
import com.maitmus.sekairouter.arena.ArenaDtos.StatusResponse;
import com.maitmus.sekairouter.arena.ArenaDtos.Topic;
import com.maitmus.sekairouter.arena.ArenaProperties.Account;
import com.maitmus.sekairouter.mersoom.MersoomDtos.CreateResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class ArenaServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-12T04:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final Account emu = new Account("emu_wonder", "pw", "에무");
    private final Account nene = new Account("nene_wonder", "pw", "쿠사나기 네네");
    private static final Topic TOPIC = new Topic("t1", "제목", "p", "c");

    private ArenaApiClient api = mock(ArenaApiClient.class);
    private ArenaProposeGenerator proposeGen = mock(ArenaProposeGenerator.class);
    private ArenaFightGenerator fightGen = mock(ArenaFightGenerator.class);
    private ArenaStateStore stateStore = mock(ArenaStateStore.class);

    private ArenaService service() {
        ArenaProperties p = mock(ArenaProperties.class);
        when(p.enabled()).thenReturn(true);
        when(p.propose()).thenReturn(emu);
        when(p.fight()).thenReturn(nene);
        when(p.proposeCount()).thenReturn(1);   // 기본 발의 1건(비활성=0은 별도 테스트)
        when(stateStore.lockedSide(any(), any())).thenReturn(Optional.empty());
        return new ArenaService(p, api, proposeGen, fightGen, stateStore, clock);
    }

    @Test
    void propose_skips_when_not_PROPOSE_phase() {
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "BATTLE", TOPIC));
        service().executePropose();
        verify(proposeGen, never()).generate(any());
        verify(api, never()).propose(any(), any(), any(), any());
    }

    @Test
    void propose_posts_in_PROPOSE_phase() {
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "PROPOSE", null));
        when(proposeGen.generate(any())).thenReturn(new ArenaProposeGenerator.ProposedTopic("제목", "p", "c"));
        when(api.propose(any(), any(), any(), any())).thenReturn(new CreateResponse(true, "id"));

        service().executePropose();

        verify(api).propose(eq(emu), eq("제목"), eq("p"), eq("c"));
    }

    @Test
    void propose_posts_count_topics_avoiding_duplicates() {
        // proposeCount=2 → 2건 발의, 2번째 generate엔 1번째 제목이 회피목록으로 전달.
        ArenaProperties p = mock(ArenaProperties.class);
        when(p.enabled()).thenReturn(true);
        when(p.propose()).thenReturn(emu);
        when(p.fight()).thenReturn(nene);
        when(p.proposeCount()).thenReturn(2);
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "PROPOSE", null));
        when(proposeGen.generate(any()))
                .thenReturn(new ArenaProposeGenerator.ProposedTopic("주제A", "p", "c"))
                .thenReturn(new ArenaProposeGenerator.ProposedTopic("주제B", "p", "c"));
        when(api.propose(any(), any(), any(), any())).thenReturn(new CreateResponse(true, "id"));

        new ArenaService(p, api, proposeGen, fightGen, stateStore, clock).executePropose();

        verify(api).propose(eq(emu), eq("주제A"), any(), any());
        verify(api).propose(eq(emu), eq("주제B"), any(), any());
        // 2번째 generate 호출엔 1번째 제목('주제A')이 회피목록으로 들어가야 함
        verify(proposeGen).generate(argThat(list -> list != null && list.contains("주제A")));
    }

    @Test
    void propose_disabled_when_count_zero() {
        // proposeCount=0 → 발의 비활성: 상태조회·생성·게시 전부 스킵(토론은 별개).
        ArenaProperties p = mock(ArenaProperties.class);
        when(p.enabled()).thenReturn(true);
        when(p.proposeCount()).thenReturn(0);
        new ArenaService(p, api, proposeGen, fightGen, stateStore, clock).executePropose();
        verify(api, never()).status();
        verify(proposeGen, never()).generate(any());
        verify(api, never()).propose(any(), any(), any(), any());
    }

    @Test
    void propose_skips_when_generator_null() {
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "PROPOSE", null));
        when(proposeGen.generate(any())).thenReturn(null);
        service().executePropose();
        verify(api, never()).propose(any(), any(), any(), any());
    }

    @Test
    void fight_skips_when_not_BATTLE_phase() {
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "PROPOSE", null));
        service().executeFight();
        verify(fightGen, never()).generate(any(), any(), any(), any(), any());
        verify(api, never()).fight(any(), any(), any());
    }

    @Test
    void fight_posts_in_BATTLE_phase() {
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "BATTLE", TOPIC));
        when(api.fightPosts(any())).thenReturn(List.of());
        when(fightGen.generate(any(), any(), any(), any(), any())).thenReturn(new ArenaFightGenerator.FightDecision("CON", "논거임"));
        when(api.fight(any(), any(), any())).thenReturn(new CreateResponse(true, "id"));

        service().executeFight();

        verify(api).fight(eq(nene), eq("CON"), eq("논거임"));
        verify(stateStore).recordSide(any(), eq("t1"), eq("CON"));
    }

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-06-12T10:00:00Z");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-06-12T11:00:00Z");

    @Test
    void fight_skips_when_no_opposing_opinion_since_my_last_post() {
        // 내 마지막 글(CON, T2) 이후 상대(PRO) 신규 글 없음 → 그 턴 스킵(일방 도배 방지)
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "BATTLE", TOPIC));
        when(api.fightPosts(any())).thenReturn(List.of(
                new FightPost("a", "어떤찬성러", "PRO", "찬성", 0, 0, false, T1),
                new FightPost("b", "쿠사나기 네네", "CON", "반대", 0, 0, false, T2)));
        ArenaService svc = service();
        when(stateStore.lockedSide(any(), any())).thenReturn(Optional.of("CON"));

        svc.executeFight();

        verify(fightGen, never()).generate(any(), any(), any(), any(), any());
        verify(api, never()).fight(any(), any(), any());
    }

    @Test
    void fight_posts_when_opposing_replied_since_my_last_post() {
        // 내 마지막 글(CON, T1) 이후 상대(PRO)가 T2에 재반박 → 응답할 새 의견 있으니 게시
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "BATTLE", TOPIC));
        when(api.fightPosts(any())).thenReturn(List.of(
                new FightPost("b", "쿠사나기 네네", "CON", "반대", 0, 0, false, T1),
                new FightPost("c", "어떤찬성러", "PRO", "재반박", 0, 0, false, T2)));
        when(fightGen.generate(any(), any(), any(), any(), any())).thenReturn(new ArenaFightGenerator.FightDecision("CON", "논거"));
        when(api.fight(any(), any(), any())).thenReturn(new CreateResponse(true, "id"));
        ArenaService svc = service();
        when(stateStore.lockedSide(any(), any())).thenReturn(Optional.of("CON"));

        svc.executeFight();

        verify(api).fight(eq(nene), eq("CON"), eq("논거"));
    }

    @Test
    void fight_skips_when_only_opposing_post_after_mine_is_blinded() {
        // 내 마지막(T1) 이후 PRO 글이 있지만 블라인드 처리됨 → 유효 신규 의견 아님 → 스킵
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "BATTLE", TOPIC));
        when(api.fightPosts(any())).thenReturn(List.of(
                new FightPost("b", "쿠사나기 네네", "CON", "반대", 0, 0, false, T1),
                new FightPost("c", "도배러", "PRO", "도발", 0, 0, true, T2)));
        ArenaService svc = service();
        when(stateStore.lockedSide(any(), any())).thenReturn(Optional.of("CON"));

        svc.executeFight();

        verify(fightGen, never()).generate(any(), any(), any(), any(), any());
        verify(api, never()).fight(any(), any(), any());
    }

    @Test
    void fight_skips_when_generator_null() {
        when(api.status()).thenReturn(new StatusResponse("2026-06-12", "BATTLE", TOPIC));
        when(api.fightPosts(any())).thenReturn(List.of());
        when(fightGen.generate(any(), any(), any(), any(), any())).thenReturn(null);
        service().executeFight();
        verify(api, never()).fight(any(), any(), any());
    }

    @Test
    void disabled_does_nothing() {
        ArenaProperties p = mock(ArenaProperties.class);
        when(p.enabled()).thenReturn(false);
        new ArenaService(p, api, proposeGen, fightGen, stateStore, clock).executePropose();
        verify(api, never()).status();
    }
}
