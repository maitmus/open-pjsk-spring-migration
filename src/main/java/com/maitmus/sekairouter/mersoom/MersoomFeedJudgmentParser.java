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
 * 댓글 크론 LLM 호출은 피드 전체를 보고 (1) 글마다 up/down 투표(+사유) (2) 댓글 대상·본문·게시여부
 * (3) 친밀 친구 별명 제안 을 한 번에 판단한다. 투표·댓글·관계가 같은 판단을 공유한다.
 *
 * 스키마(중첩 회피, 평탄):
 *   {"reasoning":"...",
 *    "votes":[{"id":"p1","vote":"down","reason":"안티-AI 도발"}],
 *    "targetId":"p2", "utterance":"...", "shouldPost":true,
 *    "nicknames":[{"name":"오호돌쇠","alias":"오호찌"}]}
 *
 * reasoning은 비공개. 발행 누수 방지는 호출측(생성기) 백스톱이 담당.
 */
public final class MersoomFeedJudgmentParser {

    public record Vote(String id, String vote, String reason) {}
    public record NickProposal(String name, String alias) {}
    public record Judgment(String reasoning, List<Vote> votes, String targetId, String utterance,
                           Boolean shouldPost, List<NickProposal> nicknames) {}

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
                            .map(v -> new Vote(v.id.strip(), v.vote.strip(), v.reason))
                            .toList();
            List<NickProposal> nicknames = r.nicknames == null ? List.of()
                    : r.nicknames.stream()
                            .filter(n -> n != null && n.name != null && !n.name.isBlank()
                                    && n.alias != null && !n.alias.isBlank())
                            .map(n -> new NickProposal(n.name.strip(), n.alias.strip()))
                            .toList();
            return Optional.of(new Judgment(r.reasoning, votes, r.targetId, r.utterance, r.shouldPost, nicknames));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Raw(String reasoning, List<RawVote> votes, String targetId, String utterance,
                       Boolean shouldPost, List<RawNick> nicknames) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawVote(String id, String vote, String reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawNick(String name, String alias) {}
}
