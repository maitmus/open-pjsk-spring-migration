package com.maitmus.sekairouter.discord;

import com.maitmus.sekairouter.config.DiscordProperties;
import com.maitmus.sekairouter.memory.ConversationMemory;
import com.maitmus.sekairouter.memory.ConversationTurn;
import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.proxy.LastSpeakerStore;
import com.maitmus.sekairouter.proxy.ProxySpeechService;
import com.maitmus.sekairouter.proxy.TypingIndicatorService;
import com.maitmus.sekairouter.routing.PersonaResponse;
import com.maitmus.sekairouter.routing.RandomCharacterSelector;
import com.maitmus.sekairouter.routing.RouterRequest;
import com.maitmus.sekairouter.routing.RouterService;
import com.maitmus.sekairouter.routing.RoutingDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class RouterEventListener extends ListenerAdapter {

    // 캐릭터 사이 buffer: 이전 send 완료 후 다음 typing 시작까지 대기
    private static final long INTER_MESSAGE_BUFFER_MS = 600;
    // 동적 typing duration: base + 글자당 가산, max로 cap
    private static final long TYPING_BASE_MS = 800;
    private static final long TYPING_PER_CHAR_MS = 80;
    private static final long TYPING_MAX_MS = 4000;

    private final DiscordProperties properties;
    private final RouterService routerService;
    private final ConversationMemory memory;
    private final LastSpeakerStore lastSpeaker;
    private final RandomCharacterSelector randomSelector;
    private final ProxySpeechService proxy;
    private final TypingIndicatorService typing;
    private final ScheduledExecutorService scheduler;

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!properties.sekaiChannelId().equals(event.getChannel().getId())) {
            return;  // Phase 1은 단일 채널만
        }
        if (event.getAuthor().isBot()) {
            return;  // 봇 메시지(자기 자신/캐릭터 봇) 무시
        }

        Message message = event.getMessage();
        String content = message.getContentDisplay();
        if (content.isBlank()) {
            return;  // 스티커 전용 등
        }

        String channelId = event.getChannel().getId();
        memory.append(channelId, new ConversationTurn("user", content, Instant.now().getEpochSecond()));

        CharacterId last = lastSpeaker.get(channelId).orElse(null);
        CharacterId suggested = randomSelector.pickOne(last);

        RouterRequest request = new RouterRequest(channelId, memory.getRecent(channelId), content, last);

        try {
            RoutingDecision decision = routerService.route(request, suggested);
            handleDecision(channelId, decision);
        } catch (Exception e) {
            log.error("Routing failed for message on {}: {}", channelId, e.getMessage(), e);
        }
    }

    private void handleDecision(String channelId, RoutingDecision decision) {
        log.info("Routing decision for {}: {} ({})", channelId, decision.getClass().getSimpleName(),
                reasoningOf(decision));

        List<PersonaResponse> responses = decision.responses();
        if (responses.isEmpty()) {
            return;
        }

        // typing duration이 메시지 길이에 비례. 캐릭터별 누적 타임라인:
        //   typing@cumulative → send@(cumulative + typingDuration) → 다음 캐릭터 typing@(send + buffer)
        long cumulative = 0;
        for (PersonaResponse r : responses) {
            long typingDuration = typingDurationFor(r.message());
            long typingAt = cumulative;
            long sendAt = cumulative + typingDuration;
            scheduler.schedule(
                    () -> typing.start(r.character(), channelId),
                    typingAt, TimeUnit.MILLISECONDS);
            scheduler.schedule(() -> {
                boolean ok = proxy.send(r.character(), channelId, r.message());
                if (ok) {
                    memory.append(channelId,
                            new ConversationTurn(r.character().name().toLowerCase(),
                                    r.message(), Instant.now().getEpochSecond()));
                    lastSpeaker.record(channelId, r.character());
                }
            }, sendAt, TimeUnit.MILLISECONDS);
            cumulative = sendAt + INTER_MESSAGE_BUFFER_MS;
        }
    }

    private static long typingDurationFor(String message) {
        long len = message == null ? 0 : message.length();
        return Math.min(TYPING_MAX_MS, TYPING_BASE_MS + len * TYPING_PER_CHAR_MS);
    }

    private String reasoningOf(RoutingDecision d) {
        return switch (d) {
            case RoutingDecision.Single s -> s.reasoning();
            case RoutingDecision.Multi m -> m.reasoning();
            case RoutingDecision.NoReply n -> n.reasoning();
        };
    }
}
