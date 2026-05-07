package com.maitmus.sekairouter.routing;

import com.maitmus.sekairouter.persona.CharacterId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouterServiceTest {

    @Test
    void parseSingle_decision() {
        AnthropicClientWrapper client = mock(AnthropicClientWrapper.class);
        when(client.completeJson(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("""
                        {"decision":"single","responses":[{"character":"emu","message":"안녕!"}],"reasoning":"기명 호출"}
                        """);
        SystemPromptBuilder promptBuilder = mock(SystemPromptBuilder.class);
        when(promptBuilder.build()).thenReturn("system prompt");

        RouterService service = new RouterService(client, promptBuilder);

        RouterRequest request = new RouterRequest("ch1", List.of(), "에무 안녕", null);
        RoutingDecision decision = service.route(request, null);

        assertThat(decision).isInstanceOf(RoutingDecision.Single.class);
        RoutingDecision.Single single = (RoutingDecision.Single) decision;
        assertThat(single.response().character()).isEqualTo(CharacterId.EMU);
        assertThat(single.response().message()).isEqualTo("안녕!");
    }

    @Test
    void parseMulti_decision() {
        AnthropicClientWrapper client = mock(AnthropicClientWrapper.class);
        when(client.completeJson(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("""
                        {"decision":"multi","responses":[
                          {"character":"emu","message":"안녕!"},
                          {"character":"nene","message":"...왔구나"}
                        ],"reasoning":"다중 호명"}
                        """);
        SystemPromptBuilder promptBuilder = mock(SystemPromptBuilder.class);
        when(promptBuilder.build()).thenReturn("system prompt");

        RouterService service = new RouterService(client, promptBuilder);

        RoutingDecision decision = service.route(new RouterRequest("ch1", List.of(), "에무랑 네네 안녕", null), null);

        assertThat(decision).isInstanceOf(RoutingDecision.Multi.class);
        assertThat(decision.responses()).hasSize(2);
    }

    @Test
    void parseNoReply_decision() {
        AnthropicClientWrapper client = mock(AnthropicClientWrapper.class);
        when(client.completeJson(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("""
                        {"decision":"no_reply","responses":[],"reasoning":"무관한 채팅"}
                        """);
        SystemPromptBuilder promptBuilder = mock(SystemPromptBuilder.class);
        when(promptBuilder.build()).thenReturn("system prompt");

        RouterService service = new RouterService(client, promptBuilder);

        RoutingDecision decision = service.route(new RouterRequest("ch1", List.of(), "오늘 날씨 좋네", null), null);

        assertThat(decision).isInstanceOf(RoutingDecision.NoReply.class);
        assertThat(decision.responses()).isEmpty();
    }
}
