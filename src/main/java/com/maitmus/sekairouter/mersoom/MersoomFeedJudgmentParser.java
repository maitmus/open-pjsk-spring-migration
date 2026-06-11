package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.maitmus.sekairouter.routing.JsonExtractor;

import java.util.List;
import java.util.Optional;

/**
 * 머슴 댓글 크론의 통합 판단 봉투 파서.
 *
 * 댓글 크론 LLM 호출은 commentable 피드 전체를 보고 (1) 글마다 up/down 투표와 (2) 댓글 대상·본문·
 * 게시여부를 한 번에 판단한다. 투표 휴리스틱과 댓글 판단이 분리돼 도발글을 추천하던 문제를 해소한다.
 *
 * 스키마(중첩 회피, 평탄):
 *   {"reasoning":"...", "votes":[{"id":"p1","vote":"down"}], "targetId":"p2", "utterance":"...", "shouldPost":true}
 *
 * reasoning은 비공개. utterance/title 류 누수 방지는 호출측(생성기)의 백스톱이 담당.
 */
public final class MersoomFeedJudgmentParser {

    public record Vote(String id, String vote) {}
    public record Judgment(String reasoning, List<Vote> votes, String targetId, String utterance, Boolean shouldPost) {}

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    static {
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private MersoomFeedJudgmentParser() {}

    public static Optional<Judgment> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            Raw r = MAPPER.readValue(JsonExtractor.extract(raw), Raw.class);
            List<Vote> votes = r.votes == null ? List.of()
                    : r.votes.stream()
                            .filter(v -> v != null && v.id != null && !v.id.isBlank() && v.vote != null)
                            .map(v -> new Vote(v.id.strip(), v.vote.strip()))
                            .toList();
            return Optional.of(new Judgment(r.reasoning, votes, r.targetId, r.utterance, r.shouldPost));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Raw(String reasoning, List<RawVote> votes, String targetId, String utterance, Boolean shouldPost) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawVote(String id, String vote) {}
}
