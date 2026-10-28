package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.JsonExtractor;
import com.maitmus.sekairouter.routing.PromptBlocks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 게시 직후 글·형제 댓글에서 '에무·네네 둘 다 얽힌 일'을 추려 공유 이벤트 연산(add/update)으로 돌려준다.
 * 생성 프롬프트와 분리된 별도 콜이라 본문 품질에 영향이 없다. 실패는 빈 목록(게시 흐름 무영향).
 */
@Slf4j
@Component
public class SharedEventExtractor {

    private static final String[] DOW = {"월", "화", "수", "목", "금", "토", "일"};
    private static final ObjectMapper PARSER = new ObjectMapper();

    // 영문 지시 — 한국어 지시문 버전은 Opus 5.5가 stop_reason=refusal(출력 0)을 절반가량 냈다(10-27 소급 시험, 영문은 10/10 정상).
    static final String SYSTEM = """
            You read short fictional posts and comments written by two game characters, Emu and Nene (both members of the same theater troupe, Wonderlands×Showtime), on a community board. Your job is to keep a small list of things the two of them share — so later posts stay consistent. Reply with JSON only.

            What counts (only things involving BOTH Emu and Nene):
            - Troupe shows, rehearsals or schedules (e.g. a Halloween show)
            - Plans or promises the two made together (e.g. going to a crepe shop together)
            - Things both took part in (e.g. a movie they watched together)

            What does not count: things only one of them does (tests, club activities, personal purchases, family), general chit-chat or praise, things with other users. Do not invent anything not in the text.

            Rules:
            - If it is the same thing as an existing list item, use "update" (new facts in addFacts; status "done" when it is over, "cancelled" when called off; date when set or changed) instead of "add".
            - Convert relative dates (in four days, this Saturday, tomorrow) into absolute yyyy-MM-dd using today's date from the user message. Use null if there is no basis.
            - facts: only settled things worth remembering days later — schedule, decisions, roles/costumes, results (e.g. rehearsal done, costume chosen). One short line each in Korean (max 80 chars). Do not log conversation lines, reactions, teasing, reminders or small talk (e.g. "bring water", "she said she'd think about clapping") — a back-and-forth reply usually adds no fact. Do not repeat existing facts.
            - id: lowercase kebab-case (e.g. halloween-show). title: Korean, max 30 chars.
            - Most posts and comments change nothing — then ops is an empty array.

            Output exactly one JSON object:
            {"reasoning":"<short note>","ops":[{"op":"add","id":"...","title":"...","date":"yyyy-MM-dd or null","facts":["..."]},{"op":"update","id":"...","addFacts":["..."],"status":"done|cancelled|null","date":"yyyy-MM-dd or null"}]}
            Do not use double quotes inside string values.
            """;

    /**
     * @param kind    글 | 댓글 | 대댓글
     * @param context 댓글이면 원글, 대댓글이면 대화 스레드(없으면 빈 문자열)
     * @param text    방금 게시한 내용(글이면 제목+본문)
     */
    public record Input(LocalDate today, String actorName, String siblingName, String kind, String context, String text) {}

    private final AnthropicClientWrapper anthropic;
    private final ObjectMapper objectMapper;

    public SharedEventExtractor(AnthropicClientWrapper anthropic, ObjectMapper objectMapper) {
        this.anthropic = anthropic;
        this.objectMapper = objectMapper;
    }

    public List<SharedEventLog.Op> extract(Input in, List<SharedEvent> current) {
        try {
            String raw = anthropic.completeJson(new PromptBlocks(List.of(new PromptBlocks.Block(SYSTEM, true))),
                    buildUserPrompt(in, current));
            return parse(raw);
        } catch (Exception e) {
            log.warn("Shared event 추출 실패(게시 영향 없음): {}", e.toString());
            return List.of();
        }
    }

    String buildUserPrompt(Input in, List<SharedEvent> current) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("## 오늘 (KST)\n").append(in.today()).append('(').append(DOW[in.today().getDayOfWeek().getValue() - 1]).append(")\n\n");
        sb.append("## 작성자\n").append(in.actorName()).append(" (형제: ").append(in.siblingName()).append(")\n\n");
        sb.append("## 현재 공유 기억 목록\n");
        List<Map<String, Object>> brief = new ArrayList<>();
        for (SharedEvent e : current) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("title", e.title());
            m.put("date", e.date());
            m.put("status", e.status());
            m.put("facts", e.facts());
            brief.add(m);
        }
        sb.append(objectMapper.writeValueAsString(brief)).append("\n\n");
        if (in.context() != null && !in.context().isBlank()) {
            sb.append("## 맥락\n").append(in.context().strip()).append("\n\n");
        }
        sb.append("## 방금 올린 ").append(in.kind()).append(" (작성: ").append(in.actorName()).append(")\n")
                .append(in.text() == null ? "" : in.text().strip()).append("\n\n");
        sb.append("## 지시\n위 내용으로 공유 기억 목록의 변화를 판정해 JSON 1개로 출력.\n");
        return sb.toString();
    }

    static List<SharedEventLog.Op> parse(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JsonNode root = PARSER.readTree(JsonExtractor.extract(raw));
            JsonNode ops = root.path("ops");
            if (!ops.isArray()) return List.of();
            List<SharedEventLog.Op> out = new ArrayList<>();
            for (JsonNode o : ops) {
                out.add(new SharedEventLog.Op(text(o, "op"), text(o, "id"), text(o, "title"), text(o, "date"),
                        list(o, "facts"), list(o, "addFacts"), text(o, "status")));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String text(JsonNode o, String f) {
        JsonNode n = o.get(f);
        if (n == null || n.isNull()) return null;
        String s = n.asText();
        return s.isBlank() || "null".equals(s) ? null : s;
    }

    private static List<String> list(JsonNode o, String f) {
        JsonNode n = o.get(f);
        if (n == null || !n.isArray()) return null;
        List<String> out = new ArrayList<>();
        n.forEach(x -> out.add(x.asText()));
        return out;
    }
}
