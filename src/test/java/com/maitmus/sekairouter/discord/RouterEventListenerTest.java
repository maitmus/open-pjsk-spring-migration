package com.maitmus.sekairouter.discord;

import com.maitmus.sekairouter.config.DiscordProperties;
import com.maitmus.sekairouter.memory.ConversationMemory;
import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.proxy.LastSpeakerStore;
import com.maitmus.sekairouter.proxy.ProxySpeechService;
import com.maitmus.sekairouter.proxy.TypingIndicatorService;
import com.maitmus.sekairouter.routing.PersonaResponse;
import com.maitmus.sekairouter.routing.RandomCharacterSelector;
import com.maitmus.sekairouter.routing.RouterRequest;
import com.maitmus.sekairouter.routing.RouterService;
import com.maitmus.sekairouter.routing.RoutingDecision;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouterEventListenerTest {

    private static final String CHANNEL = "chan-1";

    private DiscordProperties properties;
    private RouterService routerService;
    private ConversationMemory memory;
    private LastSpeakerStore lastSpeaker;
    private RandomCharacterSelector randomSelector;
    private ProxySpeechService proxy;
    private TypingIndicatorService typing;
    private ScheduledExecutorService scheduler;
    private ChannelExecutors channelExecutors;
    private RouterEventListener listener;

    @BeforeEach
    void setup() {
        properties = mock(DiscordProperties.class);
        when(properties.sekaiChannelId()).thenReturn(CHANNEL);
        routerService = mock(RouterService.class);
        memory = new ConversationMemory(10);          // 실제 객체로 상태 추적
        lastSpeaker = new LastSpeakerStore();
        randomSelector = mock(RandomCharacterSelector.class);
        proxy = mock(ProxySpeechService.class);
        when(proxy.send(any(), anyString(), anyString())).thenReturn(true);
        typing = mock(TypingIndicatorService.class);

        // scheduler: 예약을 즉시 실행해 발화를 동기 완료시킨다(결정적 테스트).
        scheduler = mock(ScheduledExecutorService.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(scheduler).schedule(any(Runnable.class), anyLong(), any());

        // 채널 워커: caller-runs(동기). 직렬성 자체는 단일 스레드 구현이 보장하므로 테스트는 동기로 대체.
        channelExecutors = mock(ChannelExecutors.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(channelExecutors).execute(anyString(), any(Runnable.class));

        listener = new RouterEventListener(properties, routerService, memory, lastSpeaker,
                randomSelector, proxy, typing, scheduler, channelExecutors, Map.of());
    }

    private MessageReceivedEvent event(String content) {
        MessageReceivedEvent e = mock(MessageReceivedEvent.class);
        MessageChannelUnion ch = mock(MessageChannelUnion.class);
        when(ch.getId()).thenReturn(CHANNEL);
        when(e.getChannel()).thenReturn(ch);
        User author = mock(User.class);
        when(author.isBot()).thenReturn(false);
        when(author.getName()).thenReturn("maitmus");
        when(author.getId()).thenReturn("uid");
        when(e.getAuthor()).thenReturn(author);
        Message m = mock(Message.class);
        when(m.getContentDisplay()).thenReturn(content);
        when(m.getReferencedMessage()).thenReturn(null);
        when(e.getMessage()).thenReturn(m);
        return e;
    }

    private RoutingDecision single(CharacterId c, String msg) {
        return new RoutingDecision.Single(new PersonaResponse(c, msg), "r");
    }

    @Test
    void second_routing_sees_first_characters_utterance() {
        // 첫 메시지 → airi 응답, 둘째 메시지 → haruka 응답
        when(routerService.route(any(), any()))
                .thenReturn(single(CharacterId.AIRI, "여기 있는데? 뭐 해?"))
                .thenReturn(single(CharacterId.HARUKA, "여기 있어요."));

        listener.onMessageReceived(event("아이리 있어?"));
        listener.onMessageReceived(event("하루카 있어?"));

        ArgumentCaptor<RouterRequest> cap = ArgumentCaptor.forClass(RouterRequest.class);
        verify(routerService, times(2)).route(cap.capture(), any());

        RouterRequest second = cap.getAllValues().get(1);
        // 직렬화 핵심: 둘째 라우팅이 직전 airi 발화를 컨텍스트로 본다.
        assertThat(second.recentTurns())
                .anyMatch(t -> t.speaker().equals("airi") && t.content().contains("여기 있는데"));
    }

    @Test
    void character_utterance_and_lastSpeaker_recorded_after_send() {
        when(routerService.route(any(), any()))
                .thenReturn(single(CharacterId.AIRI, "여기 있는데? 뭐 해?"));

        listener.onMessageReceived(event("아이리 있어?"));

        verify(proxy).send(eq(CharacterId.AIRI), anyString(), anyString());
        assertThat(lastSpeaker.get(CHANNEL)).contains(CharacterId.AIRI);
        assertThat(memory.getRecent(CHANNEL))
                .anyMatch(t -> t.speaker().equals("airi"));
    }
}
