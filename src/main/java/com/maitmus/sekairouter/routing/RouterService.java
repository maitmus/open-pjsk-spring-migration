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
        if (suggested != null) {
            sb.append("\nsuggestedCharacter: ").append(suggested.name().toLowerCase()).append("\n");
        }
        sb.append("\n## 새 메시지\n").append(request.newMessage()).append("\n\n## 판단 요청\n위 라우팅 규칙대로 출력 JSON 스키마 형식으로만 응답하세요.");
        return sb.toString();
    }

    private RoutingDecision parse(String json) {
        try {
            String cleaned = extractJson(json);
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

    /**
     * LLM 응답에서 JSON 객체를 추출한다. 다음 케이스 모두 처리:
     *   1. 순수 JSON: {"decision":...}
     *   2. 코드 펜스 감싸진: ```json\n{...}\n```
     *   3. web_search 사용 후 prelude 텍스트 + JSON: "검색 결과 ... \n```json\n{...}\n```"
     *   4. prelude 텍스트 + 코드 펜스 없는 JSON: "확인했어요. {...}"
     *
     * 추출 우선순위:
     *   (a) ```json``` 코드 펜스를 찾으면 그 안 내용 반환 (펜스 위치가 어디든)
     *   (b) 그게 없으면 첫 '{' 부터 마지막 '}'까지 substring 반환
     */
    private String extractJson(String s) {
        String trimmed = s.trim();

        // (a) Code fence search (anywhere in text)
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            // After fence opener, find next newline (skips optional language hint like "json")
            int contentStart = trimmed.indexOf('\n', fenceStart);
            int fenceEnd = trimmed.lastIndexOf("```");
            if (contentStart > 0 && fenceEnd > contentStart) {
                return trimmed.substring(contentStart + 1, fenceEnd).trim();
            }
        }

        // (b) Brace match fallback
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }

        return trimmed;
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
