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

    private static final long INTER_MESSAGE_DELAY_MS = 1500;

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

        for (int i = 0; i < responses.size(); i++) {
            PersonaResponse r = responses.get(i);
            long delay = (long) i * INTER_MESSAGE_DELAY_MS;
            scheduler.schedule(() -> {
                typing.start(r.character(), channelId);
                boolean ok = proxy.send(r.character(), channelId, r.message());
                if (ok) {
                    memory.append(channelId,
                            new ConversationTurn(r.character().name().toLowerCase(),
                                    r.message(), Instant.now().getEpochSecond()));
                    lastSpeaker.record(channelId, r.character());
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    private String reasoningOf(RoutingDecision d) {
        return switch (d) {
            case RoutingDecision.Single s -> s.reasoning();
            case RoutingDecision.Multi m -> m.reasoning();
            case RoutingDecision.NoReply n -> n.reasoning();
        };
    }
}
