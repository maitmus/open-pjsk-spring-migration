package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.maitmus.sekairouter.routing.JsonExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            // LLM이 문자열 값에 escape 안 된 큰따옴표를 넣으면 readValue가 깨진다(흔함).
            // votes는 단순 토큰이라 정규식으로 살려 투표·평판을 보존하고, 댓글은 안전하게 스킵한다.
            return fallbackVotesOnly(JsonExtractor.extract(raw));
        }
    }

    private static final Pattern VOTE_PATTERN = Pattern.compile(
            "\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"vote\"\\s*:\\s*\"(up|down)\"", Pattern.CASE_INSENSITIVE);

    private static Optional<Judgment> fallbackVotesOnly(String json) {
        List<Vote> votes = new ArrayList<>();
        Matcher m = VOTE_PATTERN.matcher(json);
        while (m.find()) {
            votes.add(new Vote(m.group(1), m.group(2).toLowerCase(Locale.ROOT), null));
        }
        if (votes.isEmpty()) return Optional.empty();
        // 댓글/별명은 깨진 JSON에서 신뢰 불가 → 스킵(shouldPost=false). 투표만 반환.
        return Optional.of(new Judgment(null, votes, "", "", Boolean.FALSE, List.of()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Raw(String reasoning, List<RawVote> votes, String targetId, String utterance,
                       Boolean shouldPost, List<RawNick> nicknames) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawVote(String id, String vote, String reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawNick(String name, String alias) {}
}
