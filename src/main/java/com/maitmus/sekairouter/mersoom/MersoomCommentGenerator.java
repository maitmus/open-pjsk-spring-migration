package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.OutputSanityGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 머슴 댓글 크론의 통합 판단기. commentable 피드 전체를 LLM에 주고 한 번에:
 *   1) 글마다 up/down 투표
 *   2) 댓글 대상 1개 선택 + 본문 + 게시여부(shouldPost)
 * 를 받아 {@link FeedJudgment}로 반환한다. 투표와 댓글이 같은 판단을 공유하므로, 안티-AI 도발글을
 * 추천하면서 동시에 댓글은 거부하던 모순이 사라진다.
 *
 * 메타·거절 사고는 reasoning에 격리(발행 안 됨), shouldPost 보수 평가 + 발행 직전 백스톱 적용.
 * 파싱 실패 시 {@code null} 반환 → 호출측이 휴리스틱 투표로 폴백하고 댓글은 스킵.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MersoomCommentGenerator {

    private static final int MAX_CONTENT = 500;
    private static final int MAX_COMMENTS = 3;   // 한 크론 댓글 상한 (머슴 권장 2~3, 한도 30분 20개)

    private final AnthropicClientWrapper anthropic;
    private final MersoomPromptBuilder promptBuilder;
    private final OutputSanityGate outputSanityGate;

    /**
     * 피드 판정 결과.
     * @param votes           글 id → up/down (LLM이 판단한 것만; 나머지는 호출측 휴리스틱 폴백)
     * @param voteReasons     글 id → 투표 사유 (평판 트래커가 contextNote에 기록)
     * @param commentTargetId 댓글 달 글 id, 또는 댓글 보류 시 {@code null}
     * @param commentText     게시할 댓글 본문, 또는 보류 시 {@code null}
     * @param coinedNicknames 닉네임 → LLM이 제안한 별명(친밀 친구 대상)
     */
    public record CommentItem(String targetId, String text) {}

    public record FeedJudgment(Map<String, VoteType> votes, Map<String, String> voteReasons,
                               List<CommentItem> comments, Map<String, String> coinedNicknames) {
        public boolean hasComment() {
            return comments != null && !comments.isEmpty();
        }
    }

    /** @return 판정 결과, 또는 파싱 실패 시 {@code null}. */
    public FeedJudgment generate(CitizenProfile profile, MersoomState state, List<Commentable> commentable) {
        if (commentable.isEmpty()) return new FeedJudgment(Map.of(), Map.of(), List.of(), Map.of());

        String userPrompt = buildUserPrompt(profile, state, commentable);
        String raw = anthropic.completeJson(promptBuilder.build(profile), userPrompt);

        var parsed = MersoomFeedJudgmentParser.parse(raw);
        if (parsed.isEmpty()) {
            log.warn("Mersoom feed judgment 파싱 실패 — 휴리스틱 폴백: {}",
                    raw == null ? "null" : raw.substring(0, Math.min(120, raw.length())));
            return null;
        }
        var j = parsed.get();
        if (j.reasoning() != null && !j.reasoning().isBlank()) {
            log.info("Mersoom feed reasoning (not posted): {}", j.reasoning());
        }

        Set<String> feedIds = commentable.stream().map(c -> c.post().id()).collect(Collectors.toSet());
        Set<String> feedNicks = commentable.stream().map(c -> c.post().nickname()).collect(Collectors.toSet());

        // 1) 투표 맵 + 사유 — 피드에 실재하는 글만, up/down 만 인정
        Map<String, VoteType> votes = new LinkedHashMap<>();
        Map<String, String> voteReasons = new LinkedHashMap<>();
        for (var v : j.votes()) {
            if (!feedIds.contains(v.id())) continue;
            VoteType vt = toVoteType(v.vote());
            if (vt == null) continue;
            votes.put(v.id(), vt);
            if (v.reason() != null && !v.reason().isBlank()) voteReasons.put(v.id(), v.reason().strip());
        }

        // 2) 별명 제안 — 피드 작성자에 한해
        Map<String, String> coinedNicknames = new LinkedHashMap<>();
        for (var np : j.nicknames()) {
            if (feedNicks.contains(np.name())) coinedNicknames.put(np.name(), np.alias());
        }

        // 3) 댓글 — 최대 MAX_COMMENTS개. 각 글마다 유효성·중복글·백스톱 검사.
        List<CommentItem> comments = new ArrayList<>();
        Set<String> usedTargets = new HashSet<>();
        for (var c : j.comments()) {
            if (comments.size() >= MAX_COMMENTS) break;
            String targetId = c.targetId() == null ? "" : c.targetId().strip();
            String text = c.utterance() == null ? "" : c.utterance().strip();
            if (targetId.isBlank() || !feedIds.contains(targetId)) {
                log.info("Mersoom comment 항목 보류 — targetId 무효: '{}'", targetId);
                continue;
            }
            if (!usedTargets.add(targetId)) continue;   // 같은 글 중복 제거
            if (text.isBlank()) continue;
            if (!outputSanityGate.isClean(text)) {
                log.warn("Mersoom comment 항목 보류 — 백스톱 누수 마커 감지: {}",
                        text.substring(0, Math.min(60, text.length())));
                continue;
            }
            comments.add(new CommentItem(targetId, text.length() > MAX_CONTENT ? text.substring(0, MAX_CONTENT) : text));
        }
        return new FeedJudgment(votes, voteReasons, comments, coinedNicknames);
    }

    private static VoteType toVoteType(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "up" -> VoteType.UP;
            case "down" -> VoteType.DOWN;
            default -> null;
        };
    }

    private String buildUserPrompt(CitizenProfile profile, MersoomState state, List<Commentable> commentable) {
        Set<String> fixedNames = state.fixedAvoid().stream().map(fa -> fa.name()).collect(Collectors.toSet());
        String actor = profile.actorName();
        boolean nene = profile.persona() == com.maitmus.sekairouter.persona.CharacterId.NENE;

        StringBuilder sb = new StringBuilder();
        sb.append("## 모드\ncomment\n\n");

        sb.append("## 피드 (이 글들 전부에 투표 + 이 중 1개에 댓글)\n");
        sb.append("각 줄 [관계]는 그 작성자에 대한 ").append(actor).append("의 누적 평판이다. rep는 호출마다 ±1로 쌓인다.\n");
        for (Commentable c : commentable) {
            var p = c.post();
            sb.append("- id=").append(p.id())
                    .append(" @").append(safe(p.nickname()))
                    .append(": \"").append(safe(p.title())).append("\"\n");
            sb.append("  본문: ").append(safe(p.content())).append("\n");
            if (!c.comments().isEmpty()) {
                sb.append("  기존 댓글: ");
                sb.append(c.comments().stream()
                        .map(cm -> "@" + safe(cm.nickname()) + " " + safe(cm.content()))
                        .collect(Collectors.joining(" / ")));
                sb.append("\n");
            }
            sb.append("  ").append(relationshipLine(state, p, fixedNames.contains(p.identityKey()))).append("\n");
        }

        sb.append("\n## 투표 기준 (votes — 위 모든 id에 up/down + 짧은 reason)\n");
        sb.append("- up: 공감·지지할 밝은 일상/창작/근황 글\n");
        sb.append("- down: 규칙 위반·스팸·도배, 안티-AI 도발/조롱(봇·AI 비하), 공격적·악의적 글\n");
        sb.append("- **⛔차단(fixedAvoid) 작성자도 투표는 한다. 과거 이력·차단 신분과 무관하게 오직 이번 글 내용만으로 판단** — 정상·따뜻·우호적·성찰적 글이면 **반드시 up**(차단된 작성자가 회복할 유일한 길). **과거에 도발했다는 이유로 지금 멀쩡한 글에 down 주지 말 것** — 그건 신분 차별이지 내용 판단이 아니다. 진짜 갱생하는 작성자를 받아줘야 평판이 의미 있다.\n");
        sb.append("- 평판을 참고하되 맹종하지 말 것: 친한 친구라도 이번 글이 나쁘면 down, 경계·차단 대상이라도 이번 글이 좋으면 up.\n");

        sb.append("\n## 댓글 기준 (comments — 최대 3개)\n");
        sb.append("- ").append(actor).append("가 자연스럽게 한 마디 할 **밝은 글을 최대 3개까지** comments에 담는다(각 targetId+utterance). **친밀(★)·우호 친구 글 우선.**\n");
        sb.append("- **서로 다른 글에**(한 글에 중복 X). **⛔차단(fixedAvoid) 작성자는 절대 고르지 않는다**(투표만).\n");
        if (nene) {
            sb.append("- 각 2~3문장, **하드 최소 90자 (목표 100~200자)**. **네네 톤 — 차분한 직설 반말, 소극적 독설가(츳코미). 무뚝뚝하되 챙기는 마음은 직설 아래 깔 것. 존댓말 어미(~예요/~네요) 금지.** 원글 정서에 공감하되 과장 없이.\n");
        } else {
            sb.append("- 각 2~3문장, **하드 최소 90자 (목표 100~200자)**. 에무 톤. 원글 정서에 공감 우선.\n");
        }
        sb.append("- 별명이 있는 친구(별명=...)에게 댓글 달 땐 **그 별명으로 부른다**.\n");
        sb.append("- **억지로 3개 채우지 말 것** — 진짜 한 마디 하고 싶은 밝은 글만. 없으면 빈 배열(0개도 정상). **투표는 그래도 모두 채운다.**\n");

        sb.append("\n## 별명(nicknames)\n");
        if (nene) {
            sb.append("- 친밀이거나 **곧 친밀이 될(rep≥4) '별명 미정'인 친구**가 있으면, 그 **닉네임을 기반으로 네네다운 애칭(무심한 듯 챙기는, 과하게 귀엽지 않게)**을 지어 nicknames에 넣는다. rep4는 미리 준비해두는 것 — 다음에 5가 되는 순간 바로 그 별명으로 부른다.\n");
        } else {
            sb.append("- 친밀이거나 **곧 친밀이 될(rep≥4) '별명 미정'인 친구**가 있으면, 그 **닉네임을 기반으로 에무다운 다정한 애칭**을 지어 nicknames에 넣는다(예: 오호돌쇠→오호찌). rep4는 미리 준비해두는 것 — 다음에 5가 되는 순간 바로 그 별명으로 부른다.\n");
        }
        sb.append("- **'별명 미정'인 친구가 여러 명이면 이번에 모두 짓는다(한 명만 하고 미루지 말 것).** 특히 **이미 rep≥5인데 별명이 없는 친구는 예외 없이 이번 크론에 반드시 부여** — 미루면 계속 누락된다.\n");
        sb.append("- 이미 별명이 있으면 다시 안 만든다. 해당 친구가 없으면 빈 배열.\n");

        sb.append("\n## 절대 금지 (댓글 본문)\n");
        sb.append("- 원글 디테일 나열, 억지 분량 채우기, 시그니처 남발\n");
        sb.append("- **거절·메타·자기지칭(AI/어시스턴트/저)·규칙 설명을 utterance에 쓰지 말 것.** 그런 판단은 reasoning으로.\n");

        sb.append("\n## 출력 형식 (JSON 1개, 이 형식만)\n");
        sb.append("{\"reasoning\":\"<판단 근거 — 비공개, 발행 안 됨>\", ");
        sb.append("\"votes\":[{\"id\":\"<글id>\",\"vote\":\"up|down\",\"reason\":\"<짧은 사유>\"}, ...], ");
        sb.append("\"comments\":[{\"targetId\":\"<댓글 달 글 id>\",\"utterance\":\"<댓글 본문>\"}, ...], ");
        sb.append("\"nicknames\":[{\"name\":\"<친구 닉>\",\"alias\":\"<지은 별명>\"}]}\n");
        sb.append("- votes에는 위 피드의 모든 id를 포함한다. comments는 0~3개(없으면 []). nicknames는 해당 없으면 [].\n");
        sb.append("- ⚠️ **JSON 안전**: 문자열 값 안에 큰따옴표(\") 절대 쓰지 말 것 — 인용은 작은따옴표(') 나 「」 사용. reasoning은 2~3문장으로 짧게(JSON 깨짐 방지).\n");
        return sb.toString();
    }

    /** 작성자 평판/티어/별명을 한 줄로 — LLM이 기억 기반으로 판단하도록 주입. 키는 식별키, 표시는 닉. */
    private static String relationshipLine(MersoomState state, com.maitmus.sekairouter.mersoom.MersoomDtos.Post post, boolean blocked) {
        ContextNote note = state.contextNotes().get(post.identityKey());
        int rep = note != null ? note.reputation() : 0;
        String call = note != null ? note.call() : null;
        StringBuilder s = new StringBuilder("[관계] rep=").append(rep);
        if (blocked) {
            // 차단(fixedAvoid) 작성자엔 과거 도발 note를 주입하지 않는다 — LLM이 이력에 앵커돼
            // 현재 멀쩡한 글까지 down 주는 오작동 방지. 오직 이번 글 내용으로만 판단하게.
            return s.append(" ⛔차단(댓글 금지, 투표만) — 이번 글 내용만으로: 정상·우호적이면 up(회복 유일 경로)").toString();
        }
        if (rep >= 5) s.append(" ★친밀");
        else if (rep >= 1) s.append(" 우호");
        else if (rep <= -1) s.append(" ⚠경계");
        if (call != null && !call.isBlank()) s.append(" 별명='").append(call).append("'");
        else if (rep >= 4) s.append(" (별명 미정)");  // rep4(임박)부터 노출 → 5 되는 크론에 별명이 준비돼 즉시 적용
        if (note != null && note.note() != null && !note.note().isBlank()) {
            s.append(" | ").append(safe(note.note()).replace("\n", " "));
        }
        return s.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\n", " ").strip();
    }
}
