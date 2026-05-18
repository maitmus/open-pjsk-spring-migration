package com.maitmus.sekairouter.heartbeat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maitmus.sekairouter.config.DiscordProperties;
import com.maitmus.sekairouter.heartbeat.HeartbeatStateStore.RecentUtterance;
import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.persona.PersonaType;
import com.maitmus.sekairouter.proxy.ProxySpeechService;
import com.maitmus.sekairouter.proxy.TypingIndicatorService;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.JsonExtractor;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.RandomCharacterSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final PersonaRegistry personaRegistry;
    private final TimeOfDayLabeler timeOfDayLabeler;
    private final Clock clock;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
                LocalDate today = LocalDate.now(clock);
                Optional<CharacterId> eventSpeaker = pickEventSpeaker(override.get(), today);
                if (eventSpeaker.isPresent()) {
                    executeEventHeartbeat(override.get(), eventSpeaker.get(), today);
                } else {
                    // Per-character cap exhausted across the eligible pool — give a normal
                    // heartbeat the slot instead of repeating the same event topic all day.
                    executeNormalHeartbeat();
                }
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
     * Picks an event-mode speaker honoring the per-character daily cap.
     * Empty override.characters() means "any character"; otherwise restricted to the listed pool.
     * Returns empty when every eligible character has already spoken about today's event.
     */
    private Optional<CharacterId> pickEventSpeaker(EventsCalendar.EventOverride override, LocalDate today) {
        List<CharacterId> pool = override.characters().isEmpty()
                ? List.of(CharacterId.values())
                : override.characters();
        List<CharacterId> uncapped = pool.stream()
                .filter(c -> state.eventCount(c, today) < 1)
                .toList();
        if (uncapped.isEmpty()) return Optional.empty();
        return Optional.of(uncapped.get(ThreadLocalRandom.current().nextInt(uncapped.size())));
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
                + "## 형식\n"
                + "- 3~5문장, 100~250자\n"
                + "- " + speakerLower + " 페르소나의 1인칭·시그니처 어법·어미 그대로 적용\n"
                + "- 날씨 정보(기온·체감·강수 등)는 캐릭터 톤으로 자연스럽게 녹여 넣되, 본인 일상·관심사도 한두 마디 곁들여 분량 충분히\n"
                + outputSchemaBlock()
                + "## 좋은 예시 utterance (shizuku인 경우)\n"
                + "어머나, 오늘 부산은 22도까지 올라간대. 아침엔 13도라 좀 쌀쌀했지만 오후엔 따뜻해질 거라네. 봄답게 햇살이 부드러워서 산책하기 딱 좋은 날이지~ 나도 두부피 사러 시장 한 바퀴 돌아볼까 싶어."
                + recentUtterancesBlock();

        String message = callUtterance(systemPrompt, userPrompt);
        if (message != null) {
            scheduleProxySend(speaker, channelId, message, 0);
            state.recordLastSpeaker(speaker);
            state.recordUtterance(speaker, message);
            log.info("Daily weather cast: speaker={}, location={}", speaker, dailyWeatherProperties.location());
        }
    }

    private void executeNormalHeartbeat() {
        boolean dialogue = ThreadLocalRandom.current().nextDouble() < properties.dialogueProbability();
        CharacterId speaker = randomSelector.pickOne(state.lastSpeaker().orElse(null));
        PersonaType speakerType = personaRegistry.get(speaker).type();

        String channelId = discordProperties.sekaiChannelId();
        PromptBlocks systemPrompt = promptBuilder.build();
        LocalDateTime now = LocalDateTime.now(clock);
        String timeBlock = timeOfDayLabeler.promptBlock(now);

        if (!dialogue) {
            String topicSeed = seedPicker.pickTopic(speakerType);
            String userPrompt = "## 모드\n자율 발화 (솔로)\n## 발화자\n" + speaker.name().toLowerCase()
                    + "\n" + timeBlock
                    + "\n## 오늘의 토픽 시드 (이 각도에서 발화)\n" + topicSeed
                    + "\n## 지시\n" + speaker.name().toLowerCase()
                    + "이(가) 채널에 자기 일상/감상/취미/근황을 자연스럽게 한 마디 한다. **위 토픽 시드 각도를 살려** 1~3문장."
                    + outputSchemaBlock()
                    + recentUtterancesBlock();
            String message = callUtterance(systemPrompt, userPrompt);
            if (message != null) {
                scheduleProxySend(speaker, channelId, message, 0);
                state.recordLastSpeaker(speaker);
                state.recordUtterance(speaker, message);
            }
            return;
        }

        // 2-character dialogue
        CharacterId partner = randomSelector.pickOne(speaker);
        String topicSeed = seedPicker.pickTopic(speakerType);
        String dialoguePattern = seedPicker.pickDialoguePattern();
        String firstUser = "## 모드\n자율 발화 (2인 대화 — 첫 발화)\n## 발화자\n" + speaker.name().toLowerCase()
                + "\n## 동료\n" + partner.name().toLowerCase()
                + "\n" + timeBlock
                + "\n## 오늘의 토픽 시드\n" + topicSeed
                + "\n## 첫 발화 패턴 시드 (이 패턴으로 문장 시작)\n" + dialoguePattern
                + "\n## 지시\n" + speaker.name().toLowerCase()
                + "이(가) " + partner.name().toLowerCase()
                + "에게 채널에서 가볍게 말을 건다. **위 토픽 시드와 패턴 시드를 반영**해 1~2문장. "
                + "GRADES.md 호칭/존댓말 매트릭스 준수."
                + outputSchemaBlock()
                + recentUtterancesBlock();
        String firstLine = callUtterance(systemPrompt, firstUser);
        if (firstLine == null) return;
        state.recordUtterance(speaker, firstLine);

        String secondUser = "## 모드\n자율 발화 (2인 대화 — 응답)\n## 발화자\n" + partner.name().toLowerCase()
                + "\n## 직전 발화자\n" + speaker.name().toLowerCase()
                + "\n## 직전 대사\n" + firstLine
                + "\n" + timeBlock
                + "\n## 지시\n" + partner.name().toLowerCase()
                + "이(가) 위 대사에 자연스럽게 반응한다. GRADES.md 호칭/존댓말 매트릭스 준수. 1~2문장."
                + outputSchemaBlock()
                + recentUtterancesBlock();
        String secondLine = callUtterance(systemPrompt, secondUser);
        if (secondLine == null) {
            // 두 번째 발화 생성 실패 — 첫 발화만 비-reply로 전송
            scheduleProxySend(speaker, channelId, firstLine, 0);
            return;
        }
        // 페어 모드: 첫 발화 send 완료 후, 그 메시지에 Discord reply로 두 번째 발화 체이닝
        schedulePairChainedSend(speaker, partner, channelId, firstLine, secondLine);
        state.recordLastSpeaker(partner);
        state.recordUtterance(partner, secondLine);
    }

    private void executeEventHeartbeat(EventsCalendar.EventOverride override, CharacterId speaker, LocalDate today) {
        String channelId = discordProperties.sekaiChannelId();
        PromptBlocks systemPrompt = promptBuilder.build();
        LocalDateTime now = LocalDateTime.now(clock);
        String userPrompt = "## 모드\n자율 발화 (이벤트)\n## 이벤트\n" + override.label() + " (" + override.kind() + ")"
                + "\n## 발화자\n" + speaker.name().toLowerCase()
                + "\n" + timeOfDayLabeler.promptBlock(now)
                + "\n## 지시\n오늘 이벤트와 연결되는 자연스러운 한 마디. 1~3문장."
                + outputSchemaBlock()
                + recentUtterancesBlock();
        String message = callUtterance(systemPrompt, userPrompt);
        if (message != null) {
            scheduleProxySend(speaker, channelId, message, 0);
            state.recordLastSpeaker(speaker);
            state.recordUtterance(speaker, message);
            state.recordEvent(speaker, today);
        }
    }

    /**
     * Anthropic 호출 → JSON 파싱 → utterance 필드 추출.
     * reasoning은 로그만, Discord에는 utterance만 전송 — LLM이 reasoning을 utterance에 섞어
     * 노출하는 사고(2026-05-13 19:55 미쿠 reasoning leak)를 구조적으로 차단.
     *
     * 파싱 실패 시 null 반환 → caller가 전송 스킵.
     */
    private String callUtterance(PromptBlocks systemPrompt, String userPrompt) {
        String raw = anthropic.generateUtterance(systemPrompt, userPrompt);
        try {
            String cleaned = JsonExtractor.extract(raw);
            UtteranceEnvelope env = objectMapper.readValue(cleaned, UtteranceEnvelope.class);
            if (env.utterance() == null || env.utterance().isBlank()) {
                log.error("Heartbeat utterance empty after parse — raw={}", raw);
                return null;
            }
            if (env.reasoning() != null && !env.reasoning().isBlank()) {
                log.info("Heartbeat reasoning (not sent): {}", env.reasoning());
            }
            return env.utterance().strip();
        } catch (Exception e) {
            log.error("Heartbeat utterance JSON parse failed — raw={}", raw, e);
            return null;
        }
    }

    /**
     * 모든 하트비트 호출에 공통으로 붙는 출력 스키마 안내.
     * reasoning과 utterance를 별도 필드로 분리해서 LLM이 메타 사고를 utterance로 흘리는 것을 방지.
     */
    private String outputSchemaBlock() {
        return "\n## 출력 형식 (정확히 이 JSON, 다른 텍스트 금지)\n"
                + "```json\n"
                + "{\n"
                + "  \"reasoning\": \"<토픽-페르소나 조정 등 메타 사고를 여기에. 비워도 됨>\",\n"
                + "  \"utterance\": \"<캐릭터가 채널에 발화하는 1인칭 텍스트만. 메타 설명·자기 분석·3인칭 자칭·\\\"미쿠는 ...\\\" 같은 토픽 처리 자문 금지>\"\n"
                + "}\n"
                + "```\n"
                + "- utterance 필드는 디스코드 채널에 그대로 노출되니, 캐릭터의 입에서 나오는 발화 그 자체여야 함.\n"
                + "- 토픽 시드가 페르소나와 안 맞으면 reasoning에 그 사고를 적고, utterance는 페르소나 권장 소재로 자연스럽게 변환해서 작성.\n";
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

    /**
     * 페어 모드 전용: 첫 발화를 보내고, 그 결과 메시지 ID를 receiver로 받아
     * 두 번째 발화를 Discord reply로 체이닝한다. 첫 send가 실패하면 두 번째는 발화되지 않는다.
     */
    private void schedulePairChainedSend(CharacterId speaker, CharacterId partner, String channelId,
                                         String firstLine, String secondLine) {
        scheduler.schedule((Runnable) () -> typing.start(speaker, channelId), 0, TimeUnit.MILLISECONDS);
        scheduler.schedule((Runnable) () -> {
            Optional<String> firstMsgId = proxy.sendWithReply(speaker, channelId, firstLine, null);
            if (firstMsgId.isEmpty()) {
                log.warn("Pair heartbeat: first send failed, skipping second send (speaker={}, partner={})",
                        speaker, partner);
                return;
            }
            String replyTo = firstMsgId.get();
            scheduler.schedule((Runnable) () -> typing.start(partner, channelId),
                    INTER_MESSAGE_BUFFER_MS, TimeUnit.MILLISECONDS);
            scheduler.schedule(
                    (Runnable) () -> proxy.sendWithReply(partner, channelId, secondLine, replyTo),
                    INTER_MESSAGE_BUFFER_MS + TYPING_BEFORE_SEND_MS, TimeUnit.MILLISECONDS);
        }, TYPING_BEFORE_SEND_MS, TimeUnit.MILLISECONDS);
    }

    /** 하트비트 LLM 응답의 JSON envelope. */
    private record UtteranceEnvelope(String reasoning, String utterance) {}
}
