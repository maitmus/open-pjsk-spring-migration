package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.maitmus.sekairouter.routing.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 머슴 댓글 크론의 통합 판단 봉투 파서.
 *
 * 댓글 크론 LLM 호출은 피드 전체를 보고 (1) 글마다 up/down 투표(+사유) (2) 댓글 대상·본문(최대 3개)
 * (3) 친밀 친구 별명 제안 을 한 번에 판단한다. 투표·댓글·관계가 같은 판단을 공유한다.
 *
 * 스키마(중첩 회피, 평탄):
 *   {"reasoning":"...",
 *    "votes":[{"id":"p1","vote":"down","reason":"안티-AI 도발"}],
 *    "comments":[{"targetIndex":2,"utterance":"..."}],   // 최대 3개, 없으면 []. targetIndex=피드 [N] 번호
 *    "nicknames":[{"name":"오호돌쇠","alias":"오호찌"}]}
 *
 * reasoning은 비공개. 발행 누수 방지는 호출측(생성기) 백스톱이 담당.
 */
public final class MersoomFeedJudgmentParser {

    private static final Logger log = LoggerFactory.getLogger(MersoomFeedJudgmentParser.class);

    public record Vote(String id, String vote, String reason) {}
    public record Comment(Integer targetIndex, String utterance) {}
    public record NickProposal(String name, String alias) {}
    public record Judgment(String reasoning, List<Vote> votes, List<Comment> comments, List<NickProposal> nicknames) {}

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
            return Optional.of(toJudgment(MAPPER.readValue(JsonExtractor.extract(raw), Raw.class)));
        } catch (Exception e) {
            // 모델이 깨진 JSON 뒤에 고친 JSON을 다시 내는 경우(자기수정) — 뒤쪽 '{'부터 첫 값을 파싱해
            // 마지막 완결 봉투를 채택. 봉투 필드(votes/comments)가 없는 객체(투표·댓글 항목)는 제외.
            for (int i = raw.lastIndexOf('{'); i >= 0; i = raw.lastIndexOf('{', i - 1)) {
                try {
                    Raw r = MAPPER.readValue(raw.substring(i), Raw.class);
                    if (r.votes != null || r.comments != null) return Optional.of(toJudgment(r));
                } catch (Exception ignored) {
                    // 다음 후보
                }
            }
            String json = JsonExtractor.extract(raw);
            // Sonnet 5 실측(09-25~ 하루 2~5건): 댓글 객체를 안 닫고 다음 댓글을 같은 객체에 이어 쓰거나
            // ({"targetIndex":5,"utterance":"..","targetIndex":1,"utterance":".."}) 마지막 댓글 뒤를 깨뜨림(..","}]).
            // 스트리밍으로 깨진 지점까지 읽어, 따옴표가 깔끔히 닫힌 (targetIndex, utterance) 쌍만 댓글로 살린다.
            Optional<Judgment> partial = lenientPartial(json);
            if (partial.isPresent()) {
                log.warn("Mersoom feed judgment 깨진 JSON — 부분 복구: votes={} comments={}",
                        partial.get().votes().size(), partial.get().comments().size());
                return partial;
            }
            // LLM이 문자열 값에 escape 안 된 큰따옴표를 넣으면 readValue가 깨진다(흔함).
            // votes는 단순 토큰이라 정규식으로 살려 투표·평판을 보존하고, 댓글은 안전하게 스킵한다.
            Optional<Judgment> votesOnly = fallbackVotesOnly(json);
            votesOnly.ifPresent(j -> log.warn("Mersoom feed judgment 깨진 JSON — 투표만 폴백, 댓글 버림: votes={} raw={}",
                    j.votes().size(), json.substring(0, Math.min(200, json.length()))));
            return votesOnly;
        }
    }

    /**
     * 깨진 봉투를 앞에서부터 스트리밍으로 읽어 깨진 지점 전까지의 투표·댓글을 수집한다. 댓글이 하나도 안 살면 empty
     * (→ 투표만 폴백). 댓글은 utterance 문자열이 닫힌 직후 다음 문자가 ',' 또는 '}'일 때만 채택 — escape 안 된
     * 따옴표로 본문이 중간에 잘린 경우("그건 "좀"..")를 잘린 댓글로 게시하지 않기 위해.
     */
    private static Optional<Judgment> lenientPartial(String json) {
        String reasoning = null;
        List<Vote> votes = new ArrayList<>();
        List<Comment> comments = new ArrayList<>();
        try (JsonParser p = MAPPER.createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return Optional.empty();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                JsonToken t = p.nextToken();
                if ("reasoning".equals(field) && t == JsonToken.VALUE_STRING) reasoning = p.getText();
                else if ("votes".equals(field) && t == JsonToken.START_ARRAY) readVotes(p, votes);
                else if ("comments".equals(field) && t == JsonToken.START_ARRAY) readComments(p, json, comments);
                else p.skipChildren();
            }
        } catch (Exception ignored) {
            // 깨진 지점 — 그 전까지 수집한 것만 쓴다
        }
        if (comments.isEmpty()) return Optional.empty();
        if (votes.isEmpty()) votes = fallbackVotesOnly(json).map(Judgment::votes).orElse(List.of());
        return Optional.of(new Judgment(reasoning, votes, comments, List.of()));
    }

    private static void readVotes(JsonParser p, List<Vote> out) throws Exception {
        String id = null, vote = null, reason = null;
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
            if (t == JsonToken.START_OBJECT) { id = vote = reason = null; }
            else if (t == JsonToken.FIELD_NAME) {
                String f = p.currentName();
                p.nextToken();
                String v = p.currentToken() == JsonToken.VALUE_STRING ? p.getText() : null;
                if ("id".equals(f)) id = v; else if ("vote".equals(f)) vote = v; else if ("reason".equals(f)) reason = v;
                else p.skipChildren();
            } else if (t == JsonToken.END_OBJECT && id != null && !id.isBlank() && vote != null) {
                out.add(new Vote(id.strip(), vote.strip(), reason));
            }
        }
    }

    private static void readComments(JsonParser p, String json, List<Comment> out) throws Exception {
        Integer idx = null;
        String utt = null;
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
            if (t == JsonToken.START_OBJECT || t == JsonToken.END_OBJECT) { idx = null; utt = null; continue; }
            if (t != JsonToken.FIELD_NAME) continue;
            String f = p.currentName();
            JsonToken v = p.nextToken();
            if ("targetIndex".equals(f) && v == JsonToken.VALUE_NUMBER_INT) idx = p.getIntValue();
            else if ("utterance".equals(f) && v == JsonToken.VALUE_STRING) {
                String text = p.getText();   // 토큰 완결(닫는 따옴표까지 소비) 후 위치 확인
                utt = cleanlyTerminated(json, (int) p.currentLocation().getCharOffset()) ? text : null;
            } else p.skipChildren();
            if (idx != null && utt != null) {   // 쌍 완성 즉시 채택 — 같은 객체에 이어 쓴 다음 쌍도 별개 댓글로
                if (!utt.isBlank()) out.add(new Comment(idx, utt.strip()));
                idx = null;
                utt = null;
            }
        }
    }

    private static boolean cleanlyTerminated(String json, int offset) {
        for (int i = offset; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isWhitespace(c)) continue;
            return c == ',' || c == '}';
        }
        return false;
    }

    private static Judgment toJudgment(Raw r) {
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
        List<Comment> comments = r.comments == null ? List.of()
                : r.comments.stream()
                        .filter(c -> c != null && c.targetIndex != null
                                && c.utterance != null && !c.utterance.isBlank())
                        .map(c -> new Comment(c.targetIndex, c.utterance.strip()))
                        .toList();
        return new Judgment(r.reasoning, votes, comments, nicknames);
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
        // 댓글/별명은 깨진 JSON에서 신뢰 불가 → 스킵(빈 댓글). 투표만 반환.
        return Optional.of(new Judgment(null, votes, List.of(), List.of()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Raw(String reasoning, List<RawVote> votes, List<RawComment> comments, List<RawNick> nicknames) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawVote(String id, String vote, String reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawComment(Integer targetIndex, String utterance) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawNick(String name, String alias) {}
}
