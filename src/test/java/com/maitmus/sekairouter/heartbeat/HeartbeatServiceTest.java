package com.maitmus.sekairouter.heartbeat;

import com.maitmus.sekairouter.config.DiscordProperties;
import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.persona.PersonaType;
import com.maitmus.sekairouter.proxy.ProxySpeechService;
import com.maitmus.sekairouter.proxy.TypingIndicatorService;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.RandomCharacterSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class HeartbeatServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // Mocks
    private HeartbeatStateStore state;
    private EventsCalendar events;
    private HeartbeatPromptBuilder promptBuilder;
    private AnthropicClientWrapper anthropic;
    private RandomCharacterSelector randomSelector;
    private HeartbeatSeedPicker seedPicker;
    private ProxySpeechService proxy;
    private TypingIndicatorService typing;
    private ScheduledExecutorService scheduler;
    private DiscordProperties discordProperties;
    private PersonaRegistry personaRegistry;

    @BeforeEach
    void setUp() {
        state = mock(HeartbeatStateStore.class);
        events = mock(EventsCalendar.class);
        promptBuilder = mock(HeartbeatPromptBuilder.class);
        anthropic = mock(AnthropicClientWrapper.class);
        randomSelector = mock(RandomCharacterSelector.class);
        seedPicker = mock(HeartbeatSeedPicker.class);
        proxy = mock(ProxySpeechService.class);
        typing = mock(TypingIndicatorService.class);
        scheduler = mock(ScheduledExecutorService.class);
        discordProperties = mock(DiscordProperties.class);
        personaRegistry = mock(PersonaRegistry.class);

        // Sensible defaults
        when(discordProperties.sekaiChannelId()).thenReturn("ch-test");
        when(promptBuilder.build()).thenReturn(new com.maitmus.sekairouter.routing.PromptBlocks("shared", "suffix"));
        // Default: JSON envelope — utterance만 추출돼서 Discord로
        when(anthropic.generateUtterance(any(), anyString()))
                .thenReturn("{\"reasoning\":\"\",\"utterance\":\"안녕!\"}");
        when(randomSelector.pickOne(any())).thenReturn(CharacterId.EMU);
        when(state.lastSpeaker()).thenReturn(Optional.empty());
        when(state.recentUtterances()).thenReturn(List.of());
        when(seedPicker.pickTopic(any(PersonaType.class))).thenReturn("test-topic");
        when(seedPicker.pickDialoguePattern()).thenReturn("test-pattern");
        when(events.todayOverride()).thenReturn(Optional.empty());
        // Persona lookups — default 모든 캐릭터 HUMAN_SEKAI
        for (CharacterId id : CharacterId.values()) {
            when(personaRegistry.get(id))
                    .thenReturn(new Persona(id, id.name(), PersonaType.HUMAN_SEKAI, "content"));
        }
        // Stub schedule to prevent NPE on ScheduledFuture return value (even though we ignore it)
        doReturn(null).when(scheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Static helper: isQuietHours wraps across midnight
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void isQuietHours_wrapsAcrossMidnight_quietAtMidnight() {
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(23, 30), 21, 10)).isTrue();
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(0, 0), 21, 10)).isTrue();
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(9, 59), 21, 10)).isTrue();
    }

    @Test
    void isQuietHours_wrapsAcrossMidnight_activeInMiddleOfDay() {
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(10, 0), 21, 10)).isFalse();
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(15, 0), 21, 10)).isFalse();
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(20, 59), 21, 10)).isFalse();
    }

    @Test
    void isQuietHours_nonWrapping_range() {
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(9, 0), 9, 17)).isTrue();
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(16, 59), 9, 17)).isTrue();
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(8, 59), 9, 17)).isFalse();
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(17, 0), 9, 17)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. rerollThreshold
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void rerollThreshold_setsRandomValue0to29() {
        HeartbeatService service = buildService(enabledProps(0.0), clockAt(14, 0));

        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        service.rerollThreshold();

        verify(state).resetThresholdForHour(captor.capture());
        int n = captor.getValue();
        assertThat(n).isBetween(0, 29);
    }

    @Test
    void rerollThreshold_skipsWhenDisabled() {
        HeartbeatProperties props = new HeartbeatProperties(false, 21, 10, 0.5);
        HeartbeatService service = buildService(props, clockAt(14, 0));

        service.rerollThreshold();

        verifyNoInteractions(state);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3-5. heartbeatCheck skips
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void heartbeatCheck_skipsWhenDisabled() {
        HeartbeatProperties props = new HeartbeatProperties(false, 21, 10, 0.5);
        HeartbeatService service = buildService(props, clockAt(14, 30));

        service.heartbeatCheck();

        verifyNoInteractions(state, events, anthropic, proxy, typing);
    }

    @Test
    void heartbeatCheck_skipsInQuietHours() {
        HeartbeatService service = buildService(enabledProps(0.0), clockAt(23, 0));

        service.heartbeatCheck();

        verifyNoInteractions(state, events, anthropic, proxy, typing);
    }

    @Test
    void heartbeatCheck_skipsWhenAlreadyFired() {
        when(state.getThreshold()).thenReturn(999);
        HeartbeatService service = buildService(enabledProps(0.0), clockAt(14, 30));

        service.heartbeatCheck();

        verify(state).getThreshold();
        verify(state, never()).markFired();
        verifyNoInteractions(events, anthropic, proxy, typing);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. solo fire path — JSON envelope parsing
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void heartbeatCheck_fires_solo_parsesUtteranceFromJson() {
        when(state.getThreshold()).thenReturn(0);
        when(randomSelector.pickOne(any())).thenReturn(CharacterId.AIRI);
        when(anthropic.generateUtterance(any(), anyString()))
                .thenReturn("{\"reasoning\":\"날씨 토픽 그대로 사용\",\"utterance\":\"오늘 날씨 좋다~\"}");

        HeartbeatService service = buildService(enabledProps(0.0), clockAt(14, 10));
        service.heartbeatCheck();

        verify(anthropic, times(1)).generateUtterance(any(), anyString());
        verify(scheduler, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        verify(state).markFired();
        verify(state).recordLastSpeaker(CharacterId.AIRI);
        // utterance 필드만 기록 — reasoning은 새지 않음
        verify(state).recordUtterance(CharacterId.AIRI, "오늘 날씨 좋다~");
        verify(seedPicker).pickTopic(PersonaType.HUMAN_SEKAI);
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(anthropic).generateUtterance(any(), userPromptCaptor.capture());
        assertThat(userPromptCaptor.getValue()).contains("오늘의 토픽 시드");
        // 출력 스키마 안내가 user prompt에 들어가 있음
        assertThat(userPromptCaptor.getValue()).contains("출력 형식");
        assertThat(userPromptCaptor.getValue()).contains("\"utterance\"");
    }

    @Test
    void heartbeatCheck_solo_reasoningDoesNotLeakToDiscord() {
        // 2026-05-13 19:55 미쿠 reasoning leak 회귀 방지
        when(state.getThreshold()).thenReturn(0);
        when(randomSelector.pickOne(any())).thenReturn(CharacterId.AIRI);
        when(anthropic.generateUtterance(any(), anyString()))
                .thenReturn("{\"reasoning\":\"동아리 활동이 토픽인데 미쿠는 학교 동아리 소속이 없는 오리지널 미쿠야. 그러면 이걸 어떻게 자연스럽게 처리할까?\","
                        + "\"utterance\":\"오늘 멤버들이 활기차게 연습하는 모습 보면서 노래로 응원하고 싶어졌어♪\"}");

        HeartbeatService service = buildService(enabledProps(0.0), clockAt(14, 10));
        service.heartbeatCheck();

        // recordUtterance에 reasoning이 새면 안 됨
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(state).recordUtterance(eq(CharacterId.AIRI), captor.capture());
        String sent = captor.getValue();
        assertThat(sent).doesNotContain("동아리 활동이 토픽인데");
        assertThat(sent).doesNotContain("어떻게 자연스럽게 처리할까");
        assertThat(sent).isEqualTo("오늘 멤버들이 활기차게 연습하는 모습 보면서 노래로 응원하고 싶어졌어♪");
    }

    @Test
    void heartbeatCheck_solo_skipsSendOnParseFailure() {
        when(state.getThreshold()).thenReturn(0);
        when(randomSelector.pickOne(any())).thenReturn(CharacterId.AIRI);
        // raw 텍스트 (JSON 아님) — 파싱 실패
        when(anthropic.generateUtterance(any(), anyString()))
                .thenReturn("그냥 평문 발화 텍스트");

        HeartbeatService service = buildService(enabledProps(0.0), clockAt(14, 10));
        service.heartbeatCheck();

        // 파싱 실패 → 전송 스킵, recordUtterance/recordLastSpeaker 호출 안 됨
        verify(state, never()).recordUtterance(any(), anyString());
        verify(state, never()).recordLastSpeaker(any());
        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        // markFired는 여전히 호출 (slot 소진)
        verify(state).markFired();
    }

    @Test
    void heartbeatCheck_solo_virtualSinger_callsSeedPickerWithVsType() {
        // 미쿠 = VS인 경우 seedPicker가 VS 타입으로 호출되는지 검증
        when(state.getThreshold()).thenReturn(0);
        when(randomSelector.pickOne(any())).thenReturn(CharacterId.MIKU);
        when(personaRegistry.get(CharacterId.MIKU))
                .thenReturn(new Persona(CharacterId.MIKU, "MIKU", PersonaType.VIRTUAL_SINGER, "content"));

        HeartbeatService service = buildService(enabledProps(0.0), clockAt(14, 10));
        service.heartbeatCheck();

        verify(seedPicker).pickTopic(PersonaType.VIRTUAL_SINGER);
    }

    @Test
    void heartbeatCheck_solo_injectsRecentUtterancesBlock_whenBufferNonempty() {
        when(state.getThreshold()).thenReturn(0);
        when(randomSelector.pickOne(any())).thenReturn(CharacterId.AIRI);
        when(state.recentUtterances()).thenReturn(List.of(
                new HeartbeatStateStore.RecentUtterance(CharacterId.EMU, "원더호~이☆ 붕어빵 먹었어요!"),
                new HeartbeatStateStore.RecentUtterance(CharacterId.NENE, "...대전 게임 1등.")
        ));

        HeartbeatService service = buildService(enabledProps(0.0), clockAt(14, 10));
        service.heartbeatCheck();

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(anthropic).generateUtterance(any(), userPromptCaptor.capture());
        String prompt = userPromptCaptor.getValue();
        assertThat(prompt).contains("최근 발화 이력");
        assertThat(prompt).contains("원더호~이");
        assertThat(prompt).contains("대전 게임");
        assertThat(prompt).contains("회피");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. event path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void heartbeatCheck_fires_event() {
        when(state.getThreshold()).thenReturn(0);
        when(state.lastSpeaker()).thenReturn(Optional.empty());

        EventsCalendar.EventOverride birthday = new EventsCalendar.EventOverride(
                "에무 생일", List.of(CharacterId.EMU), EventsCalendar.EventKind.BIRTHDAY);
        when(events.todayOverride()).thenReturn(Optional.of(birthday));
        when(anthropic.generateUtterance(any(), anyString()))
                .thenReturn("{\"reasoning\":\"\",\"utterance\":\"생일 축하해~!\"}");

        HeartbeatService service = buildService(enabledProps(0.5), clockAt(14, 10));

        service.heartbeatCheck();

        verify(anthropic, times(1)).generateUtterance(any(), anyString());
        verify(scheduler, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        verify(state).markFired();
        verify(state).recordLastSpeaker(CharacterId.EMU);
        verify(state).recordUtterance(CharacterId.EMU, "생일 축하해~!");
        // Successful event fire records the per-character event count for today
        verify(state).recordEvent(CharacterId.EMU, java.time.LocalDate.of(2026, 5, 7));
    }

    @Test
    void heartbeatCheck_normalSolo_userPromptContainsTimeBlock() {
        when(state.getThreshold()).thenReturn(0);
        HeartbeatService service = buildService(enabledProps(0.0), clockAt(14, 10));
        service.heartbeatCheck();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(anthropic).generateUtterance(any(), captor.capture());
        String prompt = captor.getValue();
        assertThat(prompt).contains("## 현재 시각 (KST)");
        assertThat(prompt).contains("2026-05-07 (목) 14:10 (오후 수업)");
        assertThat(prompt).doesNotContain("## 오늘 날짜 (KST)");
    }

    @Test
    void heartbeatCheck_dialoguePair_bothPromptsContainTimeBlock() {
        when(state.getThreshold()).thenReturn(0);
        HeartbeatService service = buildService(enabledProps(1.0), clockAt(14, 10));
        service.heartbeatCheck();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(anthropic, times(2)).generateUtterance(any(), captor.capture());
        for (String prompt : captor.getAllValues()) {
            assertThat(prompt).contains("## 현재 시각 (KST)");
            assertThat(prompt).contains("2026-05-07 (목) 14:10 (오후 수업)");
            assertThat(prompt).doesNotContain("## 오늘 날짜 (KST)");
        }
    }

    @Test
    void heartbeatCheck_event_userPromptContainsTimeBlock() {
        when(state.getThreshold()).thenReturn(0);
        EventsCalendar.EventOverride birthday = new EventsCalendar.EventOverride(
                "에무 생일", List.of(CharacterId.EMU), EventsCalendar.EventKind.BIRTHDAY);
        when(events.todayOverride()).thenReturn(Optional.of(birthday));
        HeartbeatService service = buildService(enabledProps(0.0), clockAt(14, 10));
        service.heartbeatCheck();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(anthropic).generateUtterance(any(), captor.capture());
        String prompt = captor.getValue();
        assertThat(prompt).contains("## 현재 시각 (KST)");
        assertThat(prompt).contains("2026-05-07 (목) 14:10 (오후 수업)");
        assertThat(prompt).doesNotContain("## 오늘 날짜 (KST)");
    }

    @Test
    void heartbeatCheck_fallsToNormal_whenAllEligibleEventCharactersCapped() {
        // EMU-only event, EMU already at cap → event mode skipped, solo normal fires instead.
        when(state.getThreshold()).thenReturn(0);
        when(state.eventCount(eq(CharacterId.EMU), any(java.time.LocalDate.class))).thenReturn(1);

        EventsCalendar.EventOverride birthday = new EventsCalendar.EventOverride(
                "에무 생일", List.of(CharacterId.EMU), EventsCalendar.EventKind.BIRTHDAY);
        when(events.todayOverride()).thenReturn(Optional.of(birthday));

        HeartbeatService service = buildService(enabledProps(0.0), clockAt(14, 10));
        service.heartbeatCheck();

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(anthropic, times(1)).generateUtterance(any(), promptCaptor.capture());
        // The fall-through must take the normal solo path, not the event path
        assertThat(promptCaptor.getValue()).contains("자율 발화 (솔로)");
        assertThat(promptCaptor.getValue()).doesNotContain("자율 발화 (이벤트)");
        verify(state, never()).recordEvent(any(), any());
    }

    @Test
    void heartbeatCheck_picksUncappedCharacter_whenSomeCapped() {
        // Override pool [EMU, NENE], EMU capped, NENE free → NENE must speak.
        when(state.getThreshold()).thenReturn(0);
        when(state.eventCount(eq(CharacterId.EMU), any(java.time.LocalDate.class))).thenReturn(1);
        when(state.eventCount(eq(CharacterId.NENE), any(java.time.LocalDate.class))).thenReturn(0);

        EventsCalendar.EventOverride anniv = new EventsCalendar.EventOverride(
                "그룹 결성일", List.of(CharacterId.EMU, CharacterId.NENE), EventsCalendar.EventKind.ANNIVERSARY);
        when(events.todayOverride()).thenReturn(Optional.of(anniv));

        HeartbeatService service = buildService(enabledProps(0.0), clockAt(14, 10));
        service.heartbeatCheck();

        verify(anthropic, times(1)).generateUtterance(any(), anyString());
        // Event path took place — recordEvent was called for NENE (the only uncapped option), never EMU
        verify(state).recordEvent(CharacterId.NENE, java.time.LocalDate.of(2026, 5, 7));
        verify(state, never()).recordEvent(eq(CharacterId.EMU), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Clock clockAt(int hour, int minute) {
        java.time.LocalDateTime ldt = java.time.LocalDateTime.of(2026, 5, 7, hour, minute, 0);
        Instant instant = ldt.atZone(KST).toInstant();
        return Clock.fixed(instant, KST);
    }

    private HeartbeatProperties enabledProps(double dialogueProb) {
        return new HeartbeatProperties(true, 21, 10, dialogueProb);
    }

    private HeartbeatService buildService(HeartbeatProperties props, Clock clock) {
        DailyWeatherProperties dailyWeatherProps = new DailyWeatherProperties(false, "0 30 9 * * *", "부산 중앙동");
        return new HeartbeatService(
                props, dailyWeatherProps, state, events, promptBuilder, anthropic,
                randomSelector, seedPicker, proxy, typing, scheduler, discordProperties,
                personaRegistry, new TimeOfDayLabeler(), clock);
    }
}
