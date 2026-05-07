package com.maitmus.sekairouter.routing;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;
import com.maitmus.sekairouter.config.AnthropicProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicClientWrapper {

    // web_search_20250305 is a server-managed tool: Anthropic executes the search
    // server-side and returns the final response with stop_reason=end_turn.
    // No client-side tool loop is needed. Requires anthropic-beta header.
    private static final String WEB_SEARCH_BETA_HEADER = "web-search-2025-03-05";
    private static final ToolUnion WEB_SEARCH_TOOL = ToolUnion.ofTool(
            Tool.builder()
                    .name("web_search")
                    // Override type via additionalProperties — SDK 0.8.0 has no
                    // dedicated WebSearchTool class; the standard Tool class serialises
                    // additionalProperties alongside named fields, so "type" wins.
                    .putAdditionalProperty("type", JsonValue.from("web_search_20250305"))
                    // inputSchema is unused for server-managed tools but the SDK's Tool
                    // class requires it to be non-null; supply a minimal empty schema.
                    .inputSchema(Tool.InputSchema.builder()
                            .type(JsonValue.from("object"))
                            .build())
                    .build()
    );

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
                // web_search responses contain search results embedded in the reply;
                // recommended minimum is 5000 tokens. Current default (1000) may truncate.
                // Raise AnthropicProperties.maxTokens to ≥5000 in production config.
                .maxTokens(properties.maxTokens())
                .system(systemPrompt)
                .addUserMessage(userPrompt)
                .addTool(WEB_SEARCH_TOOL)
                .putAdditionalHeader("anthropic-beta", WEB_SEARCH_BETA_HEADER)
                .build();

        Message response = client.messages().create(params);
        log.debug("Anthropic stop_reason: {}", response.stopReason());

        String text = response.content().stream()
                .filter(block -> block.text().isPresent())
                .map(block -> block.text().get().text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No text content in response"));
        log.debug("Anthropic response: {}", text);
        return text;
    }
}
