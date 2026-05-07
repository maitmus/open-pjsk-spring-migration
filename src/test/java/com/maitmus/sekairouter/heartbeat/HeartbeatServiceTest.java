package com.maitmus.sekairouter.heartbeat;

import com.maitmus.sekairouter.config.DiscordProperties;
import com.maitmus.sekairouter.persona.CharacterId;
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
    private ProxySpeechService proxy;
    private TypingIndicatorService typing;
    private ScheduledExecutorService scheduler;
    private DiscordProperties discordProperties;

    @BeforeEach
    void setUp() {
        state = mock(HeartbeatStateStore.class);
        events = mock(EventsCalendar.class);
        promptBuilder = mock(HeartbeatPromptBuilder.class);
        anthropic = mock(AnthropicClientWrapper.class);
        randomSelector = mock(RandomCharacterSelector.class);
        proxy = mock(ProxySpeechService.class);
        typing = mock(TypingIndicatorService.class);
        scheduler = mock(ScheduledExecutorService.class);
        discordProperties = mock(DiscordProperties.class);

        // Sensible defaults
        when(discordProperties.sekaiChannelId()).thenReturn("ch-test");
        when(promptBuilder.build()).thenReturn("system prompt");
        when(anthropic.generateUtterance(anyString(), anyString())).thenReturn("안녕!");
        when(randomSelector.pickOne(any())).thenReturn(CharacterId.EMU);
        when(state.lastSpeaker()).thenReturn(Optional.empty());
        when(events.todayOverride()).thenReturn(Optional.empty());
        // Stub schedule to prevent NPE on ScheduledFuture return value (even though we ignore it)
        doReturn(null).when(scheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Static helper: isQuietHours wraps across midnight
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void isQuietHours_wrapsAcrossMidnight_quietAtMidnight() {
        // start=21, end=10: 21:00~09:59 quiet
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(23, 30), 21, 10)).isTrue();
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(0, 0), 21, 10)).isTrue();
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(9, 59), 21, 10)).isTrue();
    }

    @Test
    void isQuietHours_wrapsAcrossMidnight_activeInMiddleOfDay() {
        // 10:00~20:59 should NOT be quiet
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(10, 0), 21, 10)).isFalse();
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(15, 0), 21, 10)).isFalse();
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(20, 59), 21, 10)).isFalse();
    }

    @Test
    void isQuietHours_nonWrapping_range() {
        // start=9, end=17: 9:00~16:59 quiet
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(9, 0), 9, 17)).isTrue();
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(16, 59), 9, 17)).isTrue();
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(8, 59), 9, 17)).isFalse();
        assertThat(HeartbeatService.isQuietHours(LocalTime.of(17, 0), 9, 17)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. rerollThreshold sets a value 0~29 (30-min slot)
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
    // 3. heartbeatCheck skips when disabled
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void heartbeatCheck_skipsWhenDisabled() {
        HeartbeatProperties props = new HeartbeatProperties(false, 21, 10, 0.5);
        HeartbeatService service = buildService(props, clockAt(14, 30));

        service.heartbeatCheck();

        verifyNoInteractions(state, events, anthropic, proxy, typing);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. heartbeatCheck skips during quiet hours
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void heartbeatCheck_skipsInQuietHours() {
        HeartbeatService service = buildService(enabledProps(0.0), clockAt(23, 0));

        service.heartbeatCheck();

        // Quiet hours means no threshold check, no firing
        verifyNoInteractions(state, events, anthropic, proxy, typing);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. heartbeatCheck skips when threshold is FIRED_THRESHOLD (999)
    // ─────────────────────────────────────────────────────────────────────────

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
    // 6. heartbeatCheck fires solo path (dialogueProbability=0.0)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void heartbeatCheck_fires_solo() {
        when(state.getThreshold()).thenReturn(0);
        when(state.lastSpeaker()).thenReturn(Optional.empty());
        when(randomSelector.pickOne(null)).thenReturn(CharacterId.AIRI);
        when(events.todayOverride()).thenReturn(Optional.empty());
        when(anthropic.generateUtterance(anyString(), anyString())).thenReturn("오늘 날씨 좋다~");

        // dialogueProbability=0.0 → always solo path
        HeartbeatService service = buildService(enabledProps(0.0), clockAt(14, 10));

        service.heartbeatCheck();

        // Anthropic called exactly once for solo utterance
        verify(anthropic, times(1)).generateUtterance(anyString(), anyString());

        // scheduleProxySend: 2 scheduler.schedule calls (typing + send)
        verify(scheduler, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

        // markFired called
        verify(state).markFired();

        // lastSpeaker recorded
        verify(state).recordLastSpeaker(CharacterId.AIRI);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. heartbeatCheck fires event path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void heartbeatCheck_fires_event() {
        when(state.getThreshold()).thenReturn(0);
        when(state.lastSpeaker()).thenReturn(Optional.empty());

        EventsCalendar.EventOverride birthday = new EventsCalendar.EventOverride(
                "에무 생일", List.of(CharacterId.EMU), EventsCalendar.EventKind.BIRTHDAY);
        when(events.todayOverride()).thenReturn(Optional.of(birthday));
        when(anthropic.generateUtterance(anyString(), anyString())).thenReturn("생일 축하해~!");

        HeartbeatService service = buildService(enabledProps(0.5), clockAt(14, 10));

        service.heartbeatCheck();

        // Anthropic called once for event utterance
        verify(anthropic, times(1)).generateUtterance(anyString(), anyString());

        // 2 schedule calls for typing + send
        verify(scheduler, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

        // markFired called
        verify(state).markFired();

        // lastSpeaker recorded as EMU (only character in birthday.characters())
        verify(state).recordLastSpeaker(CharacterId.EMU);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Clock fixed at given hour:minute in KST. */
    private Clock clockAt(int hour, int minute) {
        // Build an Instant that corresponds to the given KST time on an arbitrary date
        java.time.LocalDateTime ldt = java.time.LocalDateTime.of(2026, 5, 7, hour, minute, 0);
        Instant instant = ldt.atZone(KST).toInstant();
        return Clock.fixed(instant, KST);
    }

    /** Properties with enabled=true, quiet 21~10, given dialogue probability. */
    private HeartbeatProperties enabledProps(double dialogueProb) {
        return new HeartbeatProperties(true, 21, 10, dialogueProb);
    }

    private HeartbeatService buildService(HeartbeatProperties props, Clock clock) {
        return new HeartbeatService(
                props, state, events, promptBuilder, anthropic,
                randomSelector, proxy, typing, scheduler, discordProperties, clock);
    }
}
