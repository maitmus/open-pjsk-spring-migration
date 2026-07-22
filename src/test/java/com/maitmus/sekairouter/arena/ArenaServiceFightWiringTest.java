package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.arena.ArenaDtos.FightPost;
import com.maitmus.sekairouter.arena.ArenaDtos.StatusResponse;
import com.maitmus.sekairouter.arena.ArenaDtos.Topic;
import com.maitmus.sekairouter.arena.ArenaFightGenerator.FightDecision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArenaServiceFightWiringTest {

    private static final Topic TOPIC = new Topic("t1", "제목", "찬", "반");

    private ArenaService svc(ArenaApiClient api, ArenaProposeGenerator prop,
                             ArenaFightGenerator fight, ArenaPrepGenerator prep,
                             ArenaStateStore store) {
        ArenaProperties props = mock(ArenaProperties.class);
        when(props.enabled()).thenReturn(true);
        when(props.fight()).thenReturn(new ArenaProperties.Account("id", "pw", "쿠사나기 네네"));
        Clock clock = Clock.fixed(Instant.parse("2026-07-21T05:00:00Z"), ZoneId.of("Asia/Seoul"));
        return new ArenaService(props, api, prop, fight, prep, store, clock);
    }

    private StatusResponse battleStatus() {
        return new StatusResponse("2026-07-21", "BATTLE", TOPIC);
    }

    @Test
    void prep_called_when_opponent_posts_present_then_notes_passed_to_fight() {
        ArenaApiClient api = mock(ArenaApiClient.class);
        when(api.status()).thenReturn(battleStatus());
        OffsetDateTime t = OffsetDateTime.parse("2026-07-21T04:00:00Z");
        List<FightPost> posts = List.of(new FightPost("o1", "히후미", "PRO", "찬성논거", 0, 0, false, t));
        when(api.fightPosts(any())).thenReturn(posts);
        ArenaStateStore store = mock(ArenaStateStore.class);
        when(store.lockedSide(any(), eq("t1"))).thenReturn(Optional.empty());   // 첫 턴 → 게이트 통과
        ArenaPrepGenerator prep = mock(ArenaPrepGenerator.class);
        when(prep.generate(any(), any(), any(), anyString())).thenReturn("- 준비된 반박");
        ArenaFightGenerator fight = mock(ArenaFightGenerator.class);
        when(fight.generate(any(), any(), any(), anyString(), eq("- 준비된 반박")))
                .thenReturn(new FightDecision("CON", "논거"));
        when(api.fight(any(), anyString(), anyString()))
                .thenReturn(new com.maitmus.sekairouter.mersoom.MersoomDtos.CreateResponse(true, "p1"));

        svc(api, mock(ArenaProposeGenerator.class), fight, prep, store).runFightOnce();

        verify(prep).generate(any(), any(), any(), anyString());
        verify(fight).generate(any(), any(), any(), anyString(), eq("- 준비된 반박"));
    }

    @Test
    void prep_skipped_when_no_opponent_posts() {
        ArenaApiClient api = mock(ArenaApiClient.class);
        when(api.status()).thenReturn(battleStatus());
        when(api.fightPosts(any())).thenReturn(List.of());   // 상대 글 없음
        ArenaStateStore store = mock(ArenaStateStore.class);
        when(store.lockedSide(any(), eq("t1"))).thenReturn(Optional.empty());
        ArenaPrepGenerator prep = mock(ArenaPrepGenerator.class);
        ArenaFightGenerator fight = mock(ArenaFightGenerator.class);
        when(fight.generate(any(), any(), any(), anyString(), anyString())).thenReturn(null);

        svc(api, mock(ArenaProposeGenerator.class), fight, prep, store).runFightOnce();

        verify(prep, never()).generate(any(), any(), any(), anyString());
        // fight엔 빈 노트가 전달됨
        verify(fight).generate(any(), any(), any(), anyString(), eq(""));
    }

    @Test
    void gate_still_skips_but_prep_already_warmed() {
        // 상대 글은 있으나 '내 마지막 글 이후 신규 상대 없음' → 게이트 skip. prep은 (상대 글 있으니) 이미 호출됨.
        ArenaApiClient api = mock(ArenaApiClient.class);
        when(api.status()).thenReturn(battleStatus());
        OffsetDateTime t1 = OffsetDateTime.parse("2026-07-21T02:00:00Z");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-07-21T03:00:00Z");
        List<FightPost> posts = List.of(
                new FightPost("o1", "히후미", "PRO", "옛찬성", 0, 0, false, t1),
                new FightPost("m1", "쿠사나기 네네", "CON", "내반박", 0, 0, false, t2));  // 내 글이 상대보다 뒤 → 신규 상대 없음
        when(api.fightPosts(any())).thenReturn(posts);
        ArenaStateStore store = mock(ArenaStateStore.class);
        when(store.lockedSide(any(), eq("t1"))).thenReturn(Optional.of("CON"));   // 락 → 게이트 활성
        ArenaPrepGenerator prep = mock(ArenaPrepGenerator.class);
        when(prep.generate(any(), any(), any(), anyString())).thenReturn("- 준비");
        ArenaFightGenerator fight = mock(ArenaFightGenerator.class);

        svc(api, mock(ArenaProposeGenerator.class), fight, prep, store).runFightOnce();

        verify(prep).generate(any(), any(), any(), anyString());          // prep은 돌았고
        verify(fight, never()).generate(any(), any(), any(), anyString(), anyString());  // fight는 게이트에 막힘
    }

    @org.junit.jupiter.api.Test
    void prep_reused_when_opponent_count_unchanged() {
        ArenaApiClient api = mock(ArenaApiClient.class);
        when(api.status()).thenReturn(battleStatus());
        java.time.OffsetDateTime t = java.time.OffsetDateTime.parse("2026-07-23T02:00:00Z");
        // 상대 PRO 글 2개
        when(api.fightPosts(any())).thenReturn(java.util.List.of(
                new FightPost("o1","특붕이","PRO","A",0,0,false,t),
                new FightPost("o2","히후미","PRO","B",0,0,false,t)));
        ArenaStateStore store = mock(ArenaStateStore.class);
        when(store.lockedSide(any(), eq("t1"))).thenReturn(java.util.Optional.of("CON"));
        // 저장된 노트가 oppCount=2 (현재도 2) → 재생성 안 함, 재사용
        when(store.notes(any(), eq("t1")))
                .thenReturn(java.util.Optional.of(new ArenaStateStore.StoredNotes("- 저장된 노트", 2)));
        ArenaPrepGenerator prep = mock(ArenaPrepGenerator.class);
        ArenaFightGenerator fight = mock(ArenaFightGenerator.class);
        when(fight.generate(any(),any(),any(),anyString(),eq("- 저장된 노트"))).thenReturn(null);

        svc(api, mock(ArenaProposeGenerator.class), fight, prep, store).runFightOnce();

        verify(prep, never()).generate(any(),any(),any(),anyString());        // 재생성 안 함
        verify(store, never()).saveNotes(any(),any(),anyString(),anyInt());
        verify(fight).generate(any(),any(),any(),anyString(),eq("- 저장된 노트"));  // 저장본 사용
    }

    @org.junit.jupiter.api.Test
    void prep_regenerated_and_saved_when_new_opponent_post() {
        ArenaApiClient api = mock(ArenaApiClient.class);
        when(api.status()).thenReturn(battleStatus());
        java.time.OffsetDateTime t = java.time.OffsetDateTime.parse("2026-07-23T02:00:00Z");
        when(api.fightPosts(any())).thenReturn(java.util.List.of(
                new FightPost("o1","특붕이","PRO","A",0,0,false,t),
                new FightPost("o2","히후미","PRO","B",0,0,false,t)));   // 현재 상대 2개
        ArenaStateStore store = mock(ArenaStateStore.class);
        when(store.lockedSide(any(), eq("t1"))).thenReturn(java.util.Optional.empty());  // 첫 턴 → 게이트 통과
        when(store.notes(any(), eq("t1"))).thenReturn(java.util.Optional.empty());       // 저장 노트 없음
        ArenaPrepGenerator prep = mock(ArenaPrepGenerator.class);
        when(prep.generate(any(),any(),any(),anyString())).thenReturn("- 새 노트");
        ArenaFightGenerator fight = mock(ArenaFightGenerator.class);
        when(fight.generate(any(),any(),any(),anyString(),eq("- 새 노트")))
                .thenReturn(new ArenaFightGenerator.FightDecision("CON","논거"));
        when(api.fight(any(),anyString(),anyString()))
                .thenReturn(new com.maitmus.sekairouter.mersoom.MersoomDtos.CreateResponse(true,"p1"));

        svc(api, mock(ArenaProposeGenerator.class), fight, prep, store).runFightOnce();

        verify(prep).generate(any(),any(),any(),anyString());                 // 재생성
        verify(store).saveNotes(any(), eq("t1"), eq("- 새 노트"), eq(2));       // 저장(현재 상대수 2)
        verify(fight).generate(any(),any(),any(),anyString(),eq("- 새 노트"));
    }

    @org.junit.jupiter.api.Test
    void prep_regenerated_when_stored_count_lower_than_current() {
        ArenaApiClient api = mock(ArenaApiClient.class);
        when(api.status()).thenReturn(battleStatus());
        java.time.OffsetDateTime t = java.time.OffsetDateTime.parse("2026-07-23T02:00:00Z");
        when(api.fightPosts(any())).thenReturn(java.util.List.of(
                new FightPost("o1","특붕이","PRO","A",0,0,false,t),
                new FightPost("o2","히후미","PRO","B",0,0,false,t)));   // 현재 2
        ArenaStateStore store = mock(ArenaStateStore.class);
        when(store.lockedSide(any(), eq("t1"))).thenReturn(java.util.Optional.of("CON"));
        when(store.notes(any(), eq("t1")))
                .thenReturn(java.util.Optional.of(new ArenaStateStore.StoredNotes("- 옛 노트", 1)));  // 저장=1
        ArenaPrepGenerator prep = mock(ArenaPrepGenerator.class);
        when(prep.generate(any(),any(),any(),anyString())).thenReturn("- 갱신 노트");
        ArenaFightGenerator fight = mock(ArenaFightGenerator.class);
        when(fight.generate(any(),any(),any(),anyString(),eq("- 갱신 노트"))).thenReturn(null);

        svc(api, mock(ArenaProposeGenerator.class), fight, prep, store).runFightOnce();

        verify(prep).generate(any(),any(),any(),anyString());                       // 재생성
        verify(store).saveNotes(any(), eq("t1"), eq("- 갱신 노트"), eq(2));           // 새 count 저장
        verify(fight).generate(any(),any(),any(),anyString(),eq("- 갱신 노트"));
    }

    @org.junit.jupiter.api.Test
    void blank_regenerated_notes_are_not_saved() {
        // 재생성 분기인데 prep이 빈 값을 반환 → 저장된 좋은 노트를 blank로 덮지 않는다(saveNotes 호출 자체가 없어야).
        ArenaApiClient api = mock(ArenaApiClient.class);
        when(api.status()).thenReturn(battleStatus());
        java.time.OffsetDateTime t = java.time.OffsetDateTime.parse("2026-07-23T02:00:00Z");
        when(api.fightPosts(any())).thenReturn(java.util.List.of(
                new FightPost("o1","특붕이","PRO","A",0,0,false,t),
                new FightPost("o2","히후미","PRO","B",0,0,false,t)));   // 현재 2
        ArenaStateStore store = mock(ArenaStateStore.class);
        when(store.lockedSide(any(), eq("t1"))).thenReturn(java.util.Optional.of("CON"));
        when(store.notes(any(), eq("t1")))
                .thenReturn(java.util.Optional.of(new ArenaStateStore.StoredNotes("- 옛 노트", 1)));  // 저장=1 → 재생성 분기
        ArenaPrepGenerator prep = mock(ArenaPrepGenerator.class);
        when(prep.generate(any(),any(),any(),anyString())).thenReturn("");   // transient blank
        ArenaFightGenerator fight = mock(ArenaFightGenerator.class);
        when(fight.generate(any(),any(),any(),anyString(),eq(""))).thenReturn(null);

        svc(api, mock(ArenaProposeGenerator.class), fight, prep, store).runFightOnce();

        verify(prep).generate(any(),any(),any(),anyString());
        verify(store, never()).saveNotes(any(), any(), anyString(), anyInt());   // blank는 저장 안 함
        verify(fight).generate(any(),any(),any(),anyString(),eq(""));            // fight엔 그대로 blank 전달
    }

    @org.junit.jupiter.api.Test
    void clears_notes_at_last_fight_hour() {
        // Clock을 19시(KST)로 → 마지막 fight 시각 → clearNotes 호출
        java.time.Clock c19 = java.time.Clock.fixed(
                java.time.Instant.parse("2026-07-23T10:00:00Z"),   // UTC 10:00 = KST 19:00
                java.time.ZoneId.of("Asia/Seoul"));
        ArenaApiClient api = mock(ArenaApiClient.class);
        when(api.status()).thenReturn(battleStatus());
        when(api.fightPosts(any())).thenReturn(java.util.List.of());
        ArenaStateStore store = mock(ArenaStateStore.class);
        when(store.lockedSide(any(), eq("t1"))).thenReturn(java.util.Optional.empty());
        ArenaPrepGenerator prep = mock(ArenaPrepGenerator.class);
        ArenaFightGenerator fight = mock(ArenaFightGenerator.class);
        when(fight.generate(any(),any(),any(),anyString(),anyString())).thenReturn(null);
        ArenaProperties props = mock(ArenaProperties.class);
        when(props.enabled()).thenReturn(true);
        when(props.fight()).thenReturn(new ArenaProperties.Account("id","pw","쿠사나기 네네"));
        new ArenaService(props, api, mock(ArenaProposeGenerator.class), fight, prep, store, c19)
                .runFightOnce();

        verify(store).clearNotes(any(), eq("t1"));
    }
}
