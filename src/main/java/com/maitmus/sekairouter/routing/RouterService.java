package com.maitmus.sekairouter.routing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maitmus.sekairouter.persona.CharacterId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouterService {

    private final AnthropicClientWrapper anthropic;
    private final SystemPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RoutingDecision route(RouterRequest request, CharacterId suggestedCharacter) {
        PromptBlocks systemPrompt = promptBuilder.build();
        String userPrompt = buildUserPrompt(request, suggestedCharacter);

        String json = anthropic.completeJson(systemPrompt, userPrompt);
        return parse(json);
    }

    private String buildUserPrompt(RouterRequest request, CharacterId suggested) {
        StringBuilder sb = new StringBuilder();
        // 오늘 날짜(KST) — events.json의 생일/기념일과 매칭하여 캐릭터가 자연스럽게 언급
        sb.append("오늘 날짜 (KST): ")
                .append(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))
                .append("\n\n");
        sb.append("## 채널 최근 발화\n");
        if (request.recentTurns().isEmpty()) {
            sb.append("(없음 — 새 대화)\n");
        } else {
            request.recentTurns().forEach(t ->
                    sb.append(t.speaker()).append(": ").append(t.content()).append("\n"));
        }
        if (request.lastSpeaker() != null) {
            sb.append("\n직전 응답자: ").append(request.lastSpeaker().name().toLowerCase()).append("\n");
        }
        if (request.forceCharacter() != null) {
            sb.append("\n## 강제 응답자 (Discord reply 감지)\n");
            sb.append("사용자가 **").append(request.forceCharacter().name().toLowerCase())
                    .append("**의 메시지에 답장(reply)했습니다. ");
            sb.append("반드시 `single` 결정 + character=\"").append(request.forceCharacter().name().toLowerCase())
                    .append("\" 만 응답합니다. ");
            sb.append("`multi`/`no_reply` 금지. 다른 캐릭터 응답 금지.\n");
        } else if (suggested != null) {
            sb.append("\nsuggestedCharacter: ").append(suggested.name().toLowerCase()).append("\n");
        }
        sb.append("\n## 새 메시지\n").append(request.newMessage()).append("\n\n## 판단 요청\n위 라우팅 규칙대로 출력 JSON 스키마 형식으로만 응답하세요.");
        return sb.toString();
    }

    private RoutingDecision parse(String json) {
        try {
            String cleaned = JsonExtractor.extract(json);
            RawDecision raw = objectMapper.readValue(cleaned, RawDecision.class);
            return switch (raw.decision()) {
                case "single" -> {
                    if (raw.responses().size() != 1) {
                        throw new IllegalArgumentException("single must have 1 response, got " + raw.responses().size());
                    }
                    yield new RoutingDecision.Single(raw.responses().get(0).toModel(), raw.reasoning());
                }
                case "multi" -> {
                    if (raw.responses().size() < 2) {
                        throw new IllegalArgumentException("multi must have 2+ responses");
                    }
                    yield new RoutingDecision.Multi(
                            raw.responses().stream().map(RawResponse::toModel).toList(),
                            raw.reasoning());
                }
                case "no_reply" -> new RoutingDecision.NoReply(raw.reasoning());
                default -> throw new IllegalArgumentException("Unknown decision: " + raw.decision());
            };
        } catch (Exception e) {
            log.error("Failed to parse routing decision JSON: {}", json, e);
            return new RoutingDecision.NoReply("parse error: " + e.getMessage());
        }
    }

    private record RawDecision(String decision, List<RawResponse> responses, String reasoning) {}

    private record RawResponse(@JsonProperty("character") String character,
                               @JsonProperty("message") String message) {
        PersonaResponse toModel() {
            CharacterId id = CharacterId.fromString(character)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown character: " + character));
            return new PersonaResponse(id, message);
        }
    }
}
