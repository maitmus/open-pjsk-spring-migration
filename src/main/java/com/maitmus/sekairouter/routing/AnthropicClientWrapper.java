package com.maitmus.sekairouter.routing;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.WebSearchTool20250305;
import com.maitmus.sekairouter.config.AnthropicProperties;
import java.util.List;
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
    private static final ToolUnion WEB_SEARCH_TOOL = ToolUnion.ofWebSearchTool20250305(
            WebSearchTool20250305.builder().build()
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
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text(systemPrompt)
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()
                ))
                .addUserMessage(userPrompt)
                .addTool(WEB_SEARCH_TOOL)
                .putAdditionalHeader("anthropic-beta", WEB_SEARCH_BETA_HEADER)
                .build();

        Message response = client.messages().create(params);
        log.debug("Anthropic stop_reason: {}", response.stopReason());
        log.debug("Anthropic usage: cache_creation={}, cache_read={}, input={}, output={}",
                response.usage().cacheCreationInputTokens().orElse(null),
                response.usage().cacheReadInputTokens().orElse(null),
                response.usage().inputTokens(),
                response.usage().outputTokens());

        // web_search responses contain multiple text blocks (intent → search results → final answer).
        // The final text block holds the JSON routing decision; earlier blocks are search prelude.
        // Take the last text block.
        String text = response.content().stream()
                .filter(block -> block.text().isPresent())
                .map(block -> block.text().get().text())
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException("No text content in response"));
        log.debug("Anthropic response: {}", text);
        return text;
    }

    /**
     * Generates a plain-text utterance for heartbeat autonomous speech.
     * Unlike {@link #completeJson}, this does not expect a JSON response — callers receive
     * the raw character utterance text.
     *
     * Uses cache_control on the system prompt (heartbeat prompt is ~17K tokens that
     * benefits from caching across repeated calls within the same day).
     * web_search is included so the character can reference current events/weather if needed.
     */
    public String generateUtterance(String systemPrompt, String userPrompt) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(properties.model()))
                .maxTokens(properties.maxTokens())
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text(systemPrompt)
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()
                ))
                .addUserMessage(userPrompt)
                .addTool(WEB_SEARCH_TOOL)
                .putAdditionalHeader("anthropic-beta", WEB_SEARCH_BETA_HEADER)
                .build();

        Message response = client.messages().create(params);
        log.debug("Anthropic stop_reason: {}", response.stopReason());
        log.debug("Anthropic usage: cache_creation={}, cache_read={}, input={}, output={}",
                response.usage().cacheCreationInputTokens().orElse(null),
                response.usage().cacheReadInputTokens().orElse(null),
                response.usage().inputTokens(),
                response.usage().outputTokens());

        // Heartbeat may also use web_search (e.g. mention today's weather/season).
        // Take the last text block — same multi-block handling as completeJson.
        String text = response.content().stream()
                .filter(block -> block.text().isPresent())
                .map(block -> block.text().get().text())
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException("No text content in utterance response"));
        log.debug("Anthropic utterance: {}", text);
        return text;
    }
}
