package com.maitmus.sekairouter.heartbeat;

import com.maitmus.sekairouter.config.DiscordProperties;
import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.proxy.ProxySpeechService;
import com.maitmus.sekairouter.proxy.TypingIndicatorService;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.RandomCharacterSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class HeartbeatService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int FIRED_THRESHOLD = 999;
    private static final long TYPING_BEFORE_SEND_MS = 1500;
    private static final long INTER_MESSAGE_BUFFER_MS = 1500;

    private final HeartbeatProperties properties;
    private final DailyWeatherProperties dailyWeatherProperties;
    private final HeartbeatStateStore state;
    private final EventsCalendar events;
    private final HeartbeatPromptBuilder promptBuilder;
    private final AnthropicClientWrapper anthropic;
    private final RandomCharacterSelector randomSelector;
    private final ProxySpeechService proxy;
    private final TypingIndicatorService typing;
    private final ScheduledExecutorService scheduler;
    private final DiscordProperties discordProperties;
    private final Clock clock;

    /**
     * On container startup: force-set threshold for the current 30-min slot so a heartbeat
     * fires within ~1 minute. Useful for verifying behavior after restart instead of
     * waiting up to 30 minutes for the next :00/:30 reroll.
     *
     * Skip if quiet hours or if we're already at slotMinute >= 28 (next reroll is sooner
     * than any threshold we could set).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initThresholdOnStartup() {
        if (!properties.enabled()) return;
        LocalTime now = LocalTime.now(clock);
        if (isQuietHours(now, properties.quietStartHour(), properties.quietEndHour())) {
            log.info("Heartbeat startup init: quiet hours ({}~{}) — skip",
                    properties.quietStartHour(), properties.quietEndHour());
            return;
        }
        int slotMinute = now.getMinute() % 30;
        if (slotMinute >= 28) {
            log.info("Heartbeat startup init: late in slot (slotMinute={}) — wait for next reroll", slotMinute);
            return;
        }
        // threshold = current slotMinute → next minute tick will satisfy slotMinute >= threshold and fire
        int threshold = slotMinute;
        state.resetThresholdForHour(threshold);
        int slotStart = now.getMinute() < 30 ? 0 : 30;
        log.info("Heartbeat startup init: threshold={} → next utterance at :{} (within ~1 min)",
                threshold, String.format("%02d", slotStart + threshold));
    }

    /**
     * Top of every 30-min slot (KST :00 / :30): pick new threshold N (0~29) for this slot.
     * 매 시간에 두 번 발화 (시간당 2 slot).
     */
    @Scheduled(cron = "0 0,30 * * * *", zone = "Asia/Seoul")
    public void rerollThreshold() {
        if (!properties.enabled()) return;
        int n = ThreadLocalRandom.current().nextInt(30);
        state.resetThresholdForHour(n);
        int currentMinute = LocalTime.now(clock).getMinute();
        int slotStart = currentMinute < 30 ? 0 : 30;
        log.info("Heartbeat threshold for this 30-min slot: {} → next utterance at :{}",
                n, String.format("%02d", slotStart + n));
    }

    /** Every minute: if (minute % 30) >= threshold and not yet fired this slot, fire. */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    public void heartbeatCheck() {
        if (!properties.enabled()) return;

        LocalTime now = LocalTime.now(clock);
        if (isQuietHours(now, properties.quietStartHour(), properties.quietEndHour())) return;

        int threshold = state.getThreshold();
        if (threshold < 0 || threshold == FIRED_THRESHOLD) return;

        int slotMinute = now.getMinute() % 30;
        if (slotMinute < threshold) return;

        // Fire
        try {
            Optional<EventsCalendar.EventOverride> override = events.todayOverride();
            if (override.isPresent()) {
                executeEventHeartbeat(override.get());
            } else {
                executeNormalHeartbeat();
            }
        } catch (Exception e) {
            log.error("Heartbeat fire failed", e);
        } finally {
            state.markFired();
        }
    }

    /**
     * Determines whether the current time falls within quiet hours.
     * Handles midnight-wrapping ranges (e.g. start=21, end=10 means 21:00–09:59 is quiet).
     * Package-private and static for testability without mocking Clock.
     */
    static boolean isQuietHours(LocalTime now, int start, int end) {
        int hour = now.getHour();
        // Wraps across midnight: e.g. 21~10 means hour >= 21 OR hour < 10
        if (start > end) {
            return hour >= start || hour < end;
        }
        return hour >= start && hour < end;
    }

    /**
     * Daily scheduled weather cast — fires at 09:30 KST by default so the resulting
     * cache stays alive (1h TTL) until the first morning heartbeat fires (latest 10:29
     * after the 10:00 reroll), letting morning calls hit warm cache across paths.
     *
     * Bypasses quiet-hours: this is an explicit scheduled event, not autonomous heartbeat.
     */
    @Scheduled(cron = "${daily-weather.cron}", zone = "Asia/Seoul")
    public void dailyWeatherCast() {
        if (!dailyWeatherProperties.enabled()) return;

        String channelId = discordProperties.sekaiChannelId();
        PromptBlocks systemPrompt = promptBuilder.build();
        CharacterId speaker = randomSelector.pickOne(state.lastSpeaker().orElse(null));

        String userPrompt = "## 모드\n자율 발화 (일일 날씨 알림)"
                + "\n## 발화자\n" + speaker.name().toLowerCase()
                + "\n## 위치\n" + dailyWeatherProperties.location()
                + "\n## 오늘 날짜 (KST)\n" + LocalDate.now(clock)
                + "\n## 지시\nweb_search로 오늘 " + dailyWeatherProperties.location()
                + " 날씨 조회 후 캐릭터 말투로 1~3문장 알림. 대사만 출력.";

        try {
            String message = anthropic.generateUtterance(systemPrompt, userPrompt);
            scheduleProxySend(speaker, channelId, message, 0);
            state.recordLastSpeaker(speaker);
            log.info("Daily weather cast: speaker={}, location={}", speaker, dailyWeatherProperties.location());
        } catch (Exception e) {
            log.error("Daily weather cast failed", e);
        }
    }

    private void executeNormalHeartbeat() {
        boolean dialogue = ThreadLocalRandom.current().nextDouble() < properties.dialogueProbability();
        CharacterId speaker = randomSelector.pickOne(state.lastSpeaker().orElse(null));

        String channelId = discordProperties.sekaiChannelId();
        PromptBlocks systemPrompt = promptBuilder.build();

        if (!dialogue) {
            String userPrompt = "## 모드\n자율 발화 (솔로)\n## 발화자\n" + speaker.name().toLowerCase()
                    + "\n## 오늘 날짜 (KST)\n" + LocalDate.now(clock)
                    + "\n## 지시\n" + speaker.name().toLowerCase()
                    + "이(가) 채널에 자기 일상/감상/취미/근황을 자연스럽게 한 마디 한다. 1~3문장. 대사만 출력.";
            String message = anthropic.generateUtterance(systemPrompt, userPrompt);
            scheduleProxySend(speaker, channelId, message, 0);
            state.recordLastSpeaker(speaker);
            return;
        }

        // 2-character dialogue
        CharacterId partner = randomSelector.pickOne(speaker);
        String firstUser = "## 모드\n자율 발화 (2인 대화 — 첫 발화)\n## 발화자\n" + speaker.name().toLowerCase()
                + "\n## 동료\n" + partner.name().toLowerCase()
                + "\n## 오늘 날짜 (KST)\n" + LocalDate.now(clock)
                + "\n## 지시\n" + speaker.name().toLowerCase()
                + "이(가) " + partner.name().toLowerCase()
                + "에게 채널에서 가볍게 말을 건다. GRADES.md 호칭/존댓말 매트릭스 준수. 1~2문장. 대사만 출력.";
        String firstLine = anthropic.generateUtterance(systemPrompt, firstUser);
        scheduleProxySend(speaker, channelId, firstLine, 0);

        String secondUser = "## 모드\n자율 발화 (2인 대화 — 응답)\n## 발화자\n" + partner.name().toLowerCase()
                + "\n## 직전 발화자\n" + speaker.name().toLowerCase()
                + "\n## 직전 대사\n" + firstLine
                + "\n## 오늘 날짜 (KST)\n" + LocalDate.now(clock)
                + "\n## 지시\n" + partner.name().toLowerCase()
                + "이(가) 위 대사에 자연스럽게 반응한다. GRADES.md 호칭/존댓말 매트릭스 준수. 1~2문장. 대사만 출력.";
        String secondLine = anthropic.generateUtterance(systemPrompt, secondUser);
        // Schedule second send after first send completes
        long secondDelay = TYPING_BEFORE_SEND_MS + INTER_MESSAGE_BUFFER_MS + TYPING_BEFORE_SEND_MS;
        scheduleProxySend(partner, channelId, secondLine, secondDelay);
        state.recordLastSpeaker(partner);
    }

    private void executeEventHeartbeat(EventsCalendar.EventOverride override) {
        String channelId = discordProperties.sekaiChannelId();
        PromptBlocks systemPrompt = promptBuilder.build();
        // Pick speaker — if event has characters listed, pick from them; else pick someone who'd plausibly mention it
        CharacterId speaker;
        if (!override.characters().isEmpty()) {
            int idx = ThreadLocalRandom.current().nextInt(override.characters().size());
            speaker = override.characters().get(idx);
        } else {
            speaker = randomSelector.pickOne(state.lastSpeaker().orElse(null));
        }
        String userPrompt = "## 모드\n자율 발화 (이벤트)\n## 이벤트\n" + override.label() + " (" + override.kind() + ")"
                + "\n## 발화자\n" + speaker.name().toLowerCase()
                + "\n## 오늘 날짜 (KST)\n" + LocalDate.now(clock)
                + "\n## 지시\n오늘 이벤트와 연결되는 자연스러운 한 마디. 1~3문장. 대사만 출력.";
        String message = anthropic.generateUtterance(systemPrompt, userPrompt);
        scheduleProxySend(speaker, channelId, message, 0);
        state.recordLastSpeaker(speaker);
    }

    private void scheduleProxySend(CharacterId character, String channelId, String message, long extraDelayMs) {
        scheduler.schedule((Runnable) () -> typing.start(character, channelId), extraDelayMs, TimeUnit.MILLISECONDS);
        scheduler.schedule(
                (Runnable) () -> proxy.send(character, channelId, message),
                extraDelayMs + TYPING_BEFORE_SEND_MS, TimeUnit.MILLISECONDS);
    }
}
