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
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class RouterEventListener extends ListenerAdapter {

    // 동적 typing duration: 본인 메시지 길이 기반
    private static final long TYPING_BASE_MS = 800;
    private static final long TYPING_PER_CHAR_MS = 80;
    private static final long TYPING_MAX_MS = 4000;
    // 동적 read buffer: 이전 캐릭터 메시지 길이 기반 (사용자가 읽는 시간)
    private static final long READ_BASE_MS = 500;
    private static final long READ_PER_CHAR_MS = 40;
    private static final long READ_MAX_MS = 2500;

    private final DiscordProperties properties;
    private final RouterService routerService;
    private final ConversationMemory memory;
    private final LastSpeakerStore lastSpeaker;
    private final RandomCharacterSelector randomSelector;
    private final ProxySpeechService proxy;
    private final TypingIndicatorService typing;
    private final ScheduledExecutorService scheduler;
    private final ChannelExecutors channelExecutors;
    private final Map<CharacterId, JDA> characterJdas;

    /** botUserId → CharacterId. JDA awaitReady() 후 selfUser ID 안정적으로 알 수 있어 lazy 빌드. */
    private volatile Map<String, CharacterId> botUserIdToCharacter;

    private Map<String, CharacterId> botUserIdMap() {
        Map<String, CharacterId> snapshot = botUserIdToCharacter;
        if (snapshot != null) return snapshot;
        synchronized (this) {
            if (botUserIdToCharacter != null) return botUserIdToCharacter;
            Map<String, CharacterId> built = new HashMap<>();
            characterJdas.forEach((id, jda) -> built.put(jda.getSelfUser().getId(), id));
            botUserIdToCharacter = Map.copyOf(built);
            return botUserIdToCharacter;
        }
    }

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
        log.info("Received on {} from {} ({}): {}",
                channelId,
                event.getAuthor().getName(),
                event.getAuthor().getId(),
                content);
        // 수신 즉시 user 턴 적재(순서 보장). 라우팅·발화는 채널 직렬 워커로 넘겨 ReadThread를 즉시 해방.
        memory.append(channelId, new ConversationTurn("user", content, Instant.now().getEpochSecond()));
        channelExecutors.execute(channelId, () -> processMessage(channelId, message, content));
    }

    /**
     * 채널 직렬 워커에서 실행 — 직전 발화가 send 완료되어 memory/lastSpeaker에 반영된 뒤 라우팅한다.
     * 그래서 연속 메시지가 빠르게 들어와도 다음 라우팅이 직전 응답을 컨텍스트로 본다(겹침·중복화자 방지).
     */
    private void processMessage(String channelId, Message message, String content) {
        try {
            CharacterId last = lastSpeaker.get(channelId).orElse(null);
            CharacterId forced = detectForcedCharacter(message);
            CharacterId suggested = forced == null ? randomSelector.pickOne(last) : null;

            if (forced != null) {
                log.info("Reply detected — forcing character {} for channel {}", forced, channelId);
            }

            RouterRequest request = new RouterRequest(
                    channelId, memory.getRecent(channelId), content, last, forced);

            RoutingDecision decision = routerService.route(request, suggested);
            handleDecision(channelId, decision).join();  // 발화 전부 send 완료까지 블록 → 다음 메시지 직렬화
        } catch (Exception e) {
            log.error("Routing failed for message on {}: {}", channelId, e.getMessage(), e);
        }
    }

    /**
     * Discord reply 감지. 답장 대상 메시지의 작성자가 캐릭터 봇이면 해당 CharacterId 반환.
     * - 답장 아니면 null
     * - 답장 대상이 사용자/외부 봇이면 null (기존 routing 로직 유지)
     * - referencedMessage가 cache miss면 null (best effort, fetch는 latency 우려로 안 함)
     */
    private CharacterId detectForcedCharacter(Message message) {
        Message ref = message.getReferencedMessage();
        if (ref == null) return null;
        return botUserIdMap().get(ref.getAuthor().getId());
    }

    /** @return 모든 발화가 send 완료되면 끝나는 Future. 워커가 join()해서 다음 메시지를 직렬화한다. */
    private CompletableFuture<Void> handleDecision(String channelId, RoutingDecision decision) {
        log.info("Routing decision for {}: {} ({})", channelId, decision.getClass().getSimpleName(),
                reasoningOf(decision));

        List<PersonaResponse> responses = decision.responses();
        if (responses.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // 캐릭터별 누적 타임라인:
        //   typing@cumulative → send@(cumulative + typingDuration)
        //   → 다음 캐릭터 typing@(send + readBuffer of 이전 메시지)
        // readBuffer는 사용자가 이전 캐릭터 응답을 읽는 시간(이전 메시지 길이 비례).
        long cumulative = 0;
        List<CompletableFuture<Void>> sends = new ArrayList<>();
        for (PersonaResponse r : responses) {
            long typingDuration = typingDurationFor(r.message());
            long typingAt = cumulative;
            long sendAt = cumulative + typingDuration;
            scheduler.schedule(
                    () -> typing.start(r.character(), channelId),
                    typingAt, TimeUnit.MILLISECONDS);
            CompletableFuture<Void> sent = new CompletableFuture<>();
            sends.add(sent);
            scheduler.schedule(() -> {
                try {
                    boolean ok = proxy.send(r.character(), channelId, r.message());
                    if (ok) {
                        memory.append(channelId,
                                new ConversationTurn(r.character().name().toLowerCase(),
                                        r.message(), Instant.now().getEpochSecond()));
                        lastSpeaker.record(channelId, r.character());
                    }
                } catch (Exception e) {
                    log.error("Send failed for {} on {}: {}", r.character(), channelId, e.getMessage(), e);
                } finally {
                    sent.complete(null);
                }
            }, sendAt, TimeUnit.MILLISECONDS);
            cumulative = sendAt + readBufferFor(r.message());
        }
        return CompletableFuture.allOf(sends.toArray(new CompletableFuture[0]));
    }

    private static long typingDurationFor(String message) {
        long len = message == null ? 0 : message.length();
        return Math.min(TYPING_MAX_MS, TYPING_BASE_MS + len * TYPING_PER_CHAR_MS);
    }

    private static long readBufferFor(String previousMessage) {
        long len = previousMessage == null ? 0 : previousMessage.length();
        return Math.min(READ_MAX_MS, READ_BASE_MS + len * READ_PER_CHAR_MS);
    }

    private String reasoningOf(RoutingDecision d) {
        return switch (d) {
            case RoutingDecision.Single s -> s.reasoning();
            case RoutingDecision.Multi m -> m.reasoning();
            case RoutingDecision.NoReply n -> n.reasoning();
        };
    }
}
