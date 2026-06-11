package com.maitmus.sekairouter.arena;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.maitmus.sekairouter.routing.JsonExtractor;

import java.util.Optional;

/**
 * 아레나 LLM 봉투 파서. 발의·토론 두 모드의 필드를 한 봉투로 관용 파싱:
 *   발의: {reasoning, title, pros, cons}
 *   토론: {reasoning, side, content, shouldFight}
 * reasoning은 비공개. 발행 누수 방지는 호출측(생성기) 백스톱이 담당.
 */
public final class ArenaEnvelopeParser {

    public record Envelope(String reasoning, String title, String pros, String cons,
                           String side, String content, Boolean shouldFight) {}

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    static {
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private ArenaEnvelopeParser() {}

    public static Optional<Envelope> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            Raw r = MAPPER.readValue(JsonExtractor.extract(raw), Raw.class);
            if (r == null) return Optional.empty();
            if (r.reasoning == null && r.title == null && r.pros == null && r.cons == null
                    && r.side == null && r.content == null && r.shouldFight == null) {
                return Optional.empty();
            }
            return Optional.of(new Envelope(r.reasoning, r.title, r.pros, r.cons, r.side, r.content, r.shouldFight));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Raw(String reasoning, String title, String pros, String cons,
                       String side, String content, Boolean shouldFight) {}
}
