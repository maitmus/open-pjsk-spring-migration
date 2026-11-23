package com.maitmus.sekairouter.routing;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.WebSearchTool20250305;
import com.maitmus.sekairouter.config.AnthropicProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
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

    /** 거부 원문 덤프 위치 — /app/logs는 호스트 /home/maitmus/sekai-router-logs 볼륨. */
    private static final Path REFUSAL_DUMP_DIR = Path.of("/app/logs/refusals");

    private final AnthropicProperties properties;
    private AnthropicClient client;

    @PostConstruct
    void init() {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .build();
    }

    /**
     * JSON 응답 콜 — 도구 없음. 머슴·아레나·퍼즐은 검색이 필요 없고, 도구를 붙이면 모델에 따라
     * 불필요한 검색(비용·지연)과 검색 후 메타 문장(JSON 앞 prelude)을 유발한다.
     */
    public String completeJson(PromptBlocks prompt, String userPrompt) {
        return completeJson(prompt, userPrompt, false);
    }

    /** 웹 검색이 필요할 수 있는 JSON 콜(디스코드 라우팅). */
    public String completeJsonWithWebSearch(PromptBlocks prompt, String userPrompt) {
        return completeJson(prompt, userPrompt, true);
    }

    private String completeJson(PromptBlocks prompt, String userPrompt, boolean webSearch) {
        MessageCreateParams params = buildParams(properties.model(), properties.maxTokens(), properties.effort(), prompt, userPrompt, webSearch);
        // 거부(stop_reason=refusal)·빈 응답이면 USER 원문을 남기고 1회 재생성 — 11-21·22 네네 글이 refusal로 2회 누락,
        // 원문이 로그에 없어 원인 특정 불가였다.
        String[] stopReason = {null};
        String text = retryOnceIfEmpty(
                () -> {
                    Message response = client.messages().create(params);
                    stopReason[0] = response.stopReason().map(Object::toString).orElse(null);
                    return lastText(response);
                },
                () -> {
                    Path dumped = dumpRefusal(REFUSAL_DUMP_DIR, stopReason[0], userPrompt);
                    log.warn("Anthropic 응답 텍스트 없음(stop_reason={}) — 1회 재생성, USER 원문 {}", stopReason[0], dumped);
                },
                "No text content in response");
        log.debug("Anthropic response: {}", text);
        return text;
    }

    /** 응답 1건의 마지막 text 블록. refusal이면 부분 텍스트가 있어도 빈 값으로 본다. */
    private Optional<String> lastText(Message response) {
        log.debug("Anthropic stop_reason: {}", response.stopReason());
        // INFO — 모델 전환(Sonnet 5) 후 비용 추적용(콜당 1줄).
        log.info("Anthropic usage: model={} cache_creation={}, cache_read={}, input={}, output={}",
                properties.model(),
                response.usage().cacheCreationInputTokens().orElse(null),
                response.usage().cacheReadInputTokens().orElse(null),
                response.usage().inputTokens(),
                response.usage().outputTokens());

        if (response.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            return Optional.empty();
        }
        // web_search responses contain multiple text blocks (intent → search results → final answer).
        // The final text block holds the JSON routing decision; earlier blocks are search prelude.
        // Take the last text block.
        return response.content().stream()
                .filter(block -> block.text().isPresent())
                .map(block -> block.text().get().text())
                .reduce((first, second) -> second);
    }

    /**
     * 첫 응답이 비면 onFirstEmpty(원문 덤프·경고) 후 1회만 다시 부른다. 두 번 다 비면 예외.
     * package-private static — 테스트용.
     */
    static String retryOnceIfEmpty(Supplier<Optional<String>> call, Runnable onFirstEmpty, String errorMessage) {
        Optional<String> first = call.get();
        if (first.isPresent()) return first.get();
        onFirstEmpty.run();
        return call.get().orElseThrow(() -> new IllegalStateException(errorMessage));
    }

    /**
     * 거부된 USER 프롬프트를 로그 볼륨에 파일 1개로 남긴다(시스템 블록은 정적이라 제외). 실패해도 예외 없이 null.
     * package-private static — 테스트용.
     */
    static Path dumpRefusal(Path dir, String stopReason, String userPrompt) {
        try {
            Files.createDirectories(dir);
            String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".txt";
            return Files.writeString(dir.resolve(name), "stop_reason=" + stopReason + "\n\n" + userPrompt);
        } catch (IOException | RuntimeException e) {
            log.warn("거부 원문 덤프 실패: {}", e.toString());
            return null;
        }
    }

    /**
     * Generates a plain-text utterance for heartbeat autonomous speech.
     * Unlike {@link #completeJson}, this does not expect a JSON response — callers receive
     * the raw character utterance text.
     *
     * Both blocks use TTL_1H so heartbeat's 30-min cadence stays cache-warm and the shared
     * block keeps serving routing reads.
     */
    public String generateUtterance(PromptBlocks prompt, String userPrompt) {
        MessageCreateParams params = buildParams(properties.model(), properties.maxTokens(), properties.effort(), prompt, userPrompt, true);

        Message response = client.messages().create(params);
        log.debug("Anthropic stop_reason: {}", response.stopReason());
        // INFO — 모델 전환(Sonnet 5) 후 비용 추적용(콜당 1줄).
        log.info("Anthropic usage: model={} cache_creation={}, cache_read={}, input={}, output={}",
                properties.model(),
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

    /**
     * 요청 파라미터 조립. effort 미설정이면 thinking을 명시적으로 끈다 — Sonnet 5 등은 생략 시 adaptive
     * thinking이 켜져 비용이 는다. effort 설정 시 adaptive + 그 effort — Opus 5.5는 disabled를 400으로
     * 거부해 effort로만 사고량을 조절한다. 품질 검증(모의 비교)은 라이브와 같은 설정으로 한다.
     * package-private static — 테스트용.
     */
    static MessageCreateParams buildParams(String model, long maxTokens, String effort, PromptBlocks prompt,
                                           String userPrompt, boolean webSearch) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(Model.of(model))
                // web_search responses contain search results embedded in the reply;
                // recommended minimum is 5000 tokens. Current default (1000) may truncate.
                // Raise AnthropicProperties.maxTokens to ≥5000 in production config.
                .maxTokens(maxTokens)
                .systemOfTextBlockParams(buildSystemBlocks(prompt))
                .addUserMessage(userPrompt);
        if (effort == null || effort.isBlank()) {
            builder.thinking(ThinkingConfigDisabled.builder().build());
        } else {
            builder.thinking(ThinkingConfigAdaptive.builder().build())
                   .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build());
        }
        if (webSearch) {
            builder.addTool(WEB_SEARCH_TOOL)
                   .putAdditionalHeader("anthropic-beta", WEB_SEARCH_BETA_HEADER);
        }
        return builder.build();
    }

    /**
     * 각 Block을 TextBlockParam으로 매핑한다.
     * cache=true일 때만 cache_control(TTL_1H)을 적용하고, 빈 text 블록은 제외(API 400 회피).
     * package-private static — PromptBlocksTest에서 직접 호출.
     */
    static List<TextBlockParam> buildSystemBlocks(PromptBlocks prompt) {
        java.util.List<TextBlockParam> out = new java.util.ArrayList<>();
        for (PromptBlocks.Block b : prompt.blocks()) {
            if (b.text() == null || b.text().isEmpty()) continue;   // 빈 블록 제외(API 400 회피)
            out.add(buildBlock(b.text(), b.cache()));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("PromptBlocks produced no non-empty system blocks");
        }
        return List.copyOf(out);
    }

    private static TextBlockParam buildBlock(String text, boolean cache) {
        TextBlockParam.Builder b = TextBlockParam.builder().text(text);
        if (cache) {
            b.cacheControl(CacheControlEphemeral.builder()
                    .ttl(CacheControlEphemeral.Ttl.TTL_1H)
                    .build());
        }
        return b.build();
    }
}
