package com.maitmus.sekairouter.heartbeat;

import com.maitmus.sekairouter.config.DiscordProperties;
import com.maitmus.sekairouter.heartbeat.HeartbeatStateStore.RecentUtterance;
import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.proxy.ProxySpeechService;
import com.maitmus.sekairouter.proxy.TypingIndicatorService;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.RandomCharacterSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
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
    private final HeartbeatSeedPicker seedPicker;
    private final ProxySpeechService proxy;
    private final TypingIndicatorService typing;
    private final ScheduledExecutorService scheduler;
    private final DiscordProperties discordProperties;
    private final Clock clock;

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

        String speakerLower = speaker.name().toLowerCase();
        String userPrompt = "## 모드\n자율 발화 (일일 날씨 알림)"
                + "\n## 발화자\n" + speakerLower
                + "\n## 위치\n" + dailyWeatherProperties.location()
                + "\n## 오늘 날짜 (KST)\n" + LocalDate.now(clock)
                + "\n## 절차 (내부, 출력에 노출 금지)\n"
                + "1. web_search 도구로 '" + dailyWeatherProperties.location() + " 오늘 날씨' 조회\n"
                + "2. 검색 결과(기온·강수·체감 등)를 " + speakerLower + " 캐릭터의 1인칭 발화로 변환\n"
                + "## 출력 = 캐릭터의 입에서 나오는 발화 그 자체\n"
                + "- 출력 전체가 캐릭터가 채널에 직접 말하는 형태여야 함 (디스코드 채널에 그대로 노출됨)\n"
                + "- 자기 작업 설명·요약 절대 금지 (예: \"아이리답게 자연스럽게 발화 변환\", \"검색 결과 캐릭터 톤으로 변환 완료\", \"~답게 적용\" 모두 ❌)\n"
                + "- 검색 결과 인용 금지 (예: \"기온: 22°C\", \"~확인\", \"화씨 기준\" 같은 보고체 ❌)\n"
                + "- 메타 텍스트·지문·괄호 해설·도구 호출 의도 설명 금지\n"
                + "## 형식\n"
                + "- 3~5문장, 100~250자\n"
                + "- " + speakerLower + " 페르소나의 1인칭·시그니처 어법·어미 그대로 적용\n"
                + "- 날씨 정보(기온·체감·강수 등)는 캐릭터 톤으로 자연스럽게 녹여 넣되, 본인 일상·관심사도 한두 마디 곁들여 분량 충분히\n"
                + "## 좋은 예시 (shizuku인 경우)\n"
                + "어머나, 오늘 부산은 22도까지 올라간대. 아침엔 13도라 좀 쌀쌀했지만 오후엔 따뜻해질 거라네. 봄답게 햇살이 부드러워서 산책하기 딱 좋은 날이지~ 나도 두부피 사러 시장 한 바퀴 돌아볼까 싶어.\n"
                + "## 나쁜 예시 (절대 출력 금지)\n"
                + "❌ 5월 10일 부산 기온: 최고 22°C, 최저 13°C. 맑은 날씨 범위로 확인.\n"
                + "❌ 맑은 날씨에 체감은 살짝 쌀쌀한 편이네. 아이리답게 자연스럽게 발화 변환.\n"
                + "❌ 위 정보 바탕으로 캐릭터 톤으로 변환했어요."
                + recentUtterancesBlock();

        try {
            String message = anthropic.generateUtterance(systemPrompt, userPrompt);
            scheduleProxySend(speaker, channelId, message, 0);
            state.recordLastSpeaker(speaker);
            state.recordUtterance(speaker, message);
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
            String topicSeed = seedPicker.pickTopic();
            String userPrompt = "## 모드\n자율 발화 (솔로)\n## 발화자\n" + speaker.name().toLowerCase()
                    + "\n## 오늘 날짜 (KST)\n" + LocalDate.now(clock)
                    + "\n## 오늘의 토픽 시드 (이 각도에서 발화)\n" + topicSeed
                    + "\n## 지시\n" + speaker.name().toLowerCase()
                    + "이(가) 채널에 자기 일상/감상/취미/근황을 자연스럽게 한 마디 한다. **위 토픽 시드 각도를 살려** 1~3문장. 대사만 출력."
                    + recentUtterancesBlock();
            String message = anthropic.generateUtterance(systemPrompt, userPrompt);
            scheduleProxySend(speaker, channelId, message, 0);
            state.recordLastSpeaker(speaker);
            state.recordUtterance(speaker, message);
            return;
        }

        // 2-character dialogue
        CharacterId partner = randomSelector.pickOne(speaker);
        String topicSeed = seedPicker.pickTopic();
        String dialoguePattern = seedPicker.pickDialoguePattern();
        String firstUser = "## 모드\n자율 발화 (2인 대화 — 첫 발화)\n## 발화자\n" + speaker.name().toLowerCase()
                + "\n## 동료\n" + partner.name().toLowerCase()
                + "\n## 오늘 날짜 (KST)\n" + LocalDate.now(clock)
                + "\n## 오늘의 토픽 시드\n" + topicSeed
                + "\n## 첫 발화 패턴 시드 (이 패턴으로 문장 시작)\n" + dialoguePattern
                + "\n## 지시\n" + speaker.name().toLowerCase()
                + "이(가) " + partner.name().toLowerCase()
                + "에게 채널에서 가볍게 말을 건다. **위 토픽 시드와 패턴 시드를 반영**해 1~2문장. "
                + "GRADES.md 호칭/존댓말 매트릭스 준수. 대사만 출력."
                + recentUtterancesBlock();
        String firstLine = anthropic.generateUtterance(systemPrompt, firstUser);
        scheduleProxySend(speaker, channelId, firstLine, 0);
        state.recordUtterance(speaker, firstLine);

        String secondUser = "## 모드\n자율 발화 (2인 대화 — 응답)\n## 발화자\n" + partner.name().toLowerCase()
                + "\n## 직전 발화자\n" + speaker.name().toLowerCase()
                + "\n## 직전 대사\n" + firstLine
                + "\n## 오늘 날짜 (KST)\n" + LocalDate.now(clock)
                + "\n## 지시\n" + partner.name().toLowerCase()
                + "이(가) 위 대사에 자연스럽게 반응한다. GRADES.md 호칭/존댓말 매트릭스 준수. 1~2문장. 대사만 출력."
                + recentUtterancesBlock();
        String secondLine = anthropic.generateUtterance(systemPrompt, secondUser);
        // Schedule second send after first send completes
        long secondDelay = TYPING_BEFORE_SEND_MS + INTER_MESSAGE_BUFFER_MS + TYPING_BEFORE_SEND_MS;
        scheduleProxySend(partner, channelId, secondLine, secondDelay);
        state.recordLastSpeaker(partner);
        state.recordUtterance(partner, secondLine);
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
                + "\n## 지시\n오늘 이벤트와 연결되는 자연스러운 한 마디. 1~3문장. 대사만 출력."
                + recentUtterancesBlock();
        String message = anthropic.generateUtterance(systemPrompt, userPrompt);
        scheduleProxySend(speaker, channelId, message, 0);
        state.recordLastSpeaker(speaker);
        state.recordUtterance(speaker, message);
    }

    /**
     * 최근 발화 이력을 user prompt 말미에 붙이는 블록.
     * LLM이 직전 발화들과 토픽·소재·문장 시작 패턴이 겹치지 않도록 회피하게 한다.
     */
    private String recentUtterancesBlock() {
        List<RecentUtterance> list = state.recentUtterances();
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n## 최근 발화 이력 (반복 회피용 — 오래된 → 최근)\n");
        for (RecentUtterance u : list) {
            sb.append("- [").append(u.speaker().name().toLowerCase()).append("] ")
                    .append(safeOneLine(u.text())).append("\n");
        }
        sb.append("\n위 발화들과 **소재·토픽·문장 시작 패턴이 겹치지 않도록** 다른 각도로 발화. ")
                .append("특히 같은 캐릭터의 직전 발화에서 사용한 시그니처 소재(예: 붕어빵·조깅·대전 게임·화과자·이미지 트레이닝·산책·새 곡 등)는 의식적으로 회피하고 페르소나 안에서 다른 면을 보일 것.");
        return sb.toString();
    }

    private static String safeOneLine(String s) {
        return s == null ? "" : s.replace("\n", " ").strip();
    }

    private void scheduleProxySend(CharacterId character, String channelId, String message, long extraDelayMs) {
        scheduler.schedule((Runnable) () -> typing.start(character, channelId), extraDelayMs, TimeUnit.MILLISECONDS);
        scheduler.schedule(
                (Runnable) () -> proxy.send(character, channelId, message),
                extraDelayMs + TYPING_BEFORE_SEND_MS, TimeUnit.MILLISECONDS);
    }
}
