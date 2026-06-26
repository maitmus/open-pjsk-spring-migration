package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.maitmus.sekairouter.routing.JsonExtractor;
import com.maitmus.sekairouter.routing.JsonQuoteRepair;

import java.util.Optional;

/**
 * 머슴 LLM 응답 봉투 파서. 하트비트의 {@code UtteranceEnvelopeParser}와 동일한 견고화 전략을
 * 쓰되, 머슴 모드(댓글/글)에 맞춰 필드를 확장한다:
 *   - 댓글: {@code reasoning, utterance, shouldPost}
 *   - 글:   {@code reasoning, title, content, shouldPost}
 *
 * {@code reasoning}은 모델의 내부 판단(게시 보류 사유 등)을 담는 비공개 필드 — 발행되지 않는다.
 * {@code shouldPost}는 모델이 직접 게시 여부를 판단하는 불(bool)이다. 메타·거절 사고가
 * 본문(utterance/content)으로 새지 않도록, 모델은 부적절하다고 판단하면 shouldPost=false를 주고
 * 사유는 reasoning에 적는다.
 *
 * 견고화: 코드펜스/prelude 제거(JsonExtractor) → '[' ... ']'로 감싸 0개 이상 객체를 단일 배열로
 * 만든 뒤 토큰 스트림을 훑어 필드를 모은다. 문자열 값 안 literal 개행, 둘로 쪼개진 객체도 살려낸다.
 */
public final class MersoomEnvelopeParser {

    public record Envelope(String reasoning, String utterance, String title, String content, Boolean shouldPost) {}

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private MersoomEnvelopeParser() {}

    public static Optional<Envelope> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();

        String wrapped = "[" + JsonQuoteRepair.escapeInnerQuotes(JsonExtractor.extract(raw)) + "]";
        String reasoning = null;
        String utterance = null;
        String title = null;
        String content = null;
        Boolean shouldPost = null;

        try (JsonParser p = MAPPER.createParser(wrapped)) {
            while (p.nextToken() != null) {
                if (p.currentToken() != JsonToken.FIELD_NAME) continue;
                String field = p.currentName();
                switch (field) {
                    case "reasoning" -> { if (p.nextToken() == JsonToken.VALUE_STRING) reasoning = p.getValueAsString(); }
                    case "utterance" -> { if (p.nextToken() == JsonToken.VALUE_STRING) utterance = p.getValueAsString(); }
                    case "title" -> { if (p.nextToken() == JsonToken.VALUE_STRING) title = p.getValueAsString(); }
                    case "content" -> { if (p.nextToken() == JsonToken.VALUE_STRING) content = p.getValueAsString(); }
                    case "shouldPost" -> {
                        JsonToken t = p.nextToken();
                        if (t == JsonToken.VALUE_TRUE) shouldPost = Boolean.TRUE;
                        else if (t == JsonToken.VALUE_FALSE) shouldPost = Boolean.FALSE;
                    }
                    default -> { /* ignore unknown fields */ }
                }
            }
        } catch (Exception e) {
            // 중간에 깨져도 그 전까지 모은 필드를 살려 쓴다.
        }

        if (reasoning == null && utterance == null && title == null && content == null && shouldPost == null) {
            return Optional.empty();
        }
        return Optional.of(new Envelope(reasoning, utterance, title, content, shouldPost));
    }
}
