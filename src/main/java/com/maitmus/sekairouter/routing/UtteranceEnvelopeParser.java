package com.maitmus.sekairouter.routing;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * LLM 응답에서 reasoning/utterance 필드를 견고하게 추출한다.
 *
 * 모델이 흔히 저지르는 깨진 출력도 살려낸다:
 *   - 문자열 값 안의 literal 개행 (표준 JSON 위반) — ALLOW_UNESCAPED_CONTROL_CHARS
 *   - {"reasoning":...},{"utterance":...} 처럼 객체가 둘로 쪼개진 경우
 *     — 배열로 감싸 토큰 스캔하며 필드 병합 (2026-06-01 09:30 날씨캐스트 실패 케이스)
 *   - 코드 펜스 / prelude 텍스트 — JsonExtractor 선처리
 *
 * 전략: JsonExtractor로 펜스/prelude를 벗긴 뒤 '[' ... ']'로 감싸 0개 이상의 객체를
 * 단일 JSON 배열로 만들고, 토큰 스트림을 훑어 "reasoning"/"utterance" 필드 값을 모은다.
 * 객체가 하나든 둘이든 동일하게 동작한다.
 */
public final class UtteranceEnvelopeParser {

    public record Envelope(String reasoning, String utterance) {}

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private UtteranceEnvelopeParser() {}

    public static Optional<Envelope> parse(String raw) {
        if (raw == null) return Optional.empty();

        String wrapped = "[" + JsonExtractor.extract(raw) + "]";
        String reasoning = null;
        String utterance = null;

        try (JsonParser p = MAPPER.createParser(wrapped)) {
            while (p.nextToken() != null) {
                if (p.currentToken() != JsonToken.FIELD_NAME) continue;
                String field = p.currentName();
                if ("utterance".equals(field) && p.nextToken() == JsonToken.VALUE_STRING) {
                    utterance = p.getValueAsString();
                } else if ("reasoning".equals(field) && p.nextToken() == JsonToken.VALUE_STRING) {
                    reasoning = p.getValueAsString();
                }
            }
        } catch (Exception e) {
            // 파싱 중간에 깨져도, 그 전까지 모은 utterance가 있으면 살려 쓴다.
        }

        if (utterance == null || utterance.isBlank()) return Optional.empty();
        return Optional.of(new Envelope(reasoning, utterance.strip()));
    }
}
