package com.maitmus.sekairouter.routing;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.maitmus.sekairouter.config.AnthropicProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicClientWrapper {

    private final AnthropicProperties properties;
    private AnthropicClient client;

    @PostConstruct
    void init() {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .build();
    }

    public String completeJson(String systemPrompt, String userPrompt) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(properties.model()))
                .maxTokens(properties.maxTokens())
                .system(systemPrompt)
                .addUserMessage(userPrompt)
                .build();

        Message response = client.messages().create(params);
        String text = response.content().stream()
                .filter(block -> block.text().isPresent())
                .map(block -> block.text().get().text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No text content in response"));
        log.debug("Anthropic response: {}", text);
        return text;
    }
}
