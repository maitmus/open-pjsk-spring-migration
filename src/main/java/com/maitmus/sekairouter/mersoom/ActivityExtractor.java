package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.JsonExtractor;
import com.maitmus.sekairouter.routing.PromptBlocks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 게시 직후 글·댓글(크론 한 번분 묶음)에서 에무·네네가 '이미 한 일'을 한 줄씩 뽑는다 — {@link Activity}.
 * 생성 프롬프트와 분리된 별도 콜이라 본문 품질에 영향이 없다. 실패는 빈 목록(게시 흐름 무영향).
 */
@Slf4j
@Component
public class ActivityExtractor {

    private static final String[] DOW = {"월", "화", "수", "목", "금", "토", "일"};
    private static final ObjectMapper PARSER = new ObjectMapper();
    static final Set<String> PARTS = Set.of("아침", "오전", "점심", "오후", "하교", "저녁", "밤");
    static final int MAX_TEXT = 60;

    // 영문 지시 — SharedEventExtractor와 같은 이유(한국어 지시문은 Opus 5.5 refusal이 잦았다).
    static final String SYSTEM = """
            You read short fictional posts and comments written by two game characters, Emu and Nene (friends in the same theater troupe, Wonderlands×Showtime — not siblings or family), on a community board. Keep a log of what Emu and Nene say they actually did, so later posts and comments by either of them stay consistent with it. Reply with JSON only.

            What counts: concrete things Emu or Nene already did or are doing right now, as stated in the new text — what they ate or drank (and where), where they went or are, what they did (play, practice, club, chores, shopping, watching something), who they were with or met, what happened to them.
            Whose acts: the writer's own, and the other character's (Emu or Nene) when the text states them as fact (Nene writes that Emu rolled on the grass → actor 에무). Something the two did together → actor 에무·네네.
            What does not count: plans, promises or anything in the future; feelings, opinions, wishes, questions, advice; habits (always/usually) and memories of past years or childhood; acts of anyone other than Emu and Nene (they may appear only as companions, e.g. 시라이시 씨랑 도시락); reading or writing on the board itself. Family words (언니·오빠·엄마 and the like) mean the writer's own family, never the other character. In a comment or reply, the context is only for understanding — record what the new text itself states, not the context. Do not invent anything not in the text.

            Rules:
            - Keep only facts someone might bring up again later: meals and snacks (what and where), places gone, the main thing done and how it turned out, people met or spent time with. Skip momentary states and small gestures (smelling, waiting, sitting, feeling sleepy, looking at something, being in class right now), and details of the same act — one line per act. Usually 0–2 lines per text, rarely more.
            - Skip anything already in the existing log (same meaning, even if worded differently).
            - date: yyyy-MM-dd when it happened — today from the user message unless the text says otherwise (yesterday, Friday...).
            - part: one of 아침, 오전, 점심, 오후, 하교, 저녁, 밤 — or null if unclear.
            - text: Korean, one short concrete line, max 50 chars: what + where/with whom (e.g. 도서실 카운터 뒤에서 편의점 소시지빵 먹음, 잔디밭 낮잠 대회, 수영부에서 플립턴 성공).
            - item: the number of the text it came from.
            - Many texts contain nothing new — then activities is an empty array.

            Output exactly one JSON object:
            {"reasoning":"<short note>","activities":[{"item":1,"actor":"에무|네네|에무·네네","date":"yyyy-MM-dd","part":"...","text":"..."}]}
            Do not use double quotes inside string values.
            """;

    /**
     * @param kind     글 | 댓글 | 대댓글
     * @param sourceId 근거 글 id
     * @param context  댓글이면 원글, 대댓글이면 스레드(글이면 빈 문자열)
     * @param text     방금 게시한 내용(글이면 제목+본문)
     */
    public record Item(String kind, String sourceId, String context, String text) {}

    private final AnthropicClientWrapper anthropic;

    public ActivityExtractor(AnthropicClientWrapper anthropic) {
        this.anthropic = anthropic;
    }

    public List<Activity> extract(String owner, String actorName, String siblingName, LocalDate today, LocalTime now,
                                  List<Item> items, List<Activity> existing) {
        if (items == null || items.isEmpty()) return List.of();
        try {
            String raw = anthropic.completeJson(new PromptBlocks(List.of(new PromptBlocks.Block(SYSTEM, true))),
                    buildUserPrompt(actorName, siblingName, today, now, items, existing));
            return parse(raw, today, items);
        } catch (Exception e) {
            log.warn("[{}] Activity 추출 실패(게시 영향 없음): {}", owner, e.toString());
            return List.of();
        }
    }

    static String buildUserPrompt(String actorName, String siblingName, LocalDate today, LocalTime now,
                                  List<Item> items, List<Activity> existing) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 지금 (KST)\n").append(today).append('(').append(DOW[today.getDayOfWeek().getValue() - 1]).append(") ")
                .append(String.format("%02d:%02d", now.getHour(), now.getMinute())).append("\n\n");
        sb.append("## 작성자\n").append(actorName).append(" (같은 극단 친구: ").append(siblingName).append(")\n\n");
        sb.append("## 이미 기록된 일\n");
        List<Activity> recent = existing == null ? List.of()
                : existing.stream().filter(a -> !LocalDate.parse(a.date()).isBefore(today.minusDays(1))).toList();
        if (recent.isEmpty()) sb.append("(없음)\n");
        else for (Activity a : recent) {
            sb.append("- ").append(a.date()).append(' ').append(a.part() == null ? "" : a.part() + " ")
                    .append(a.actor()).append(": ").append(a.text()).append('\n');
        }
        sb.append('\n');
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            sb.append("## ").append(i + 1).append(". 방금 올린 ").append(it.kind()).append(" (작성: ").append(actorName).append(")\n");
            if (it.context() != null && !it.context().isBlank()) {
                String ctx = it.context().strip();
                sb.append("(맥락) ").append(ctx.length() > 600 ? ctx.substring(0, 600) + "…" : ctx).append('\n');
            }
            sb.append(it.text() == null ? "" : it.text().strip()).append("\n\n");
        }
        sb.append("## 지시\n위 글·댓글에서 새로 기록할 에무·네네의 한 일을 JSON 1개로 출력.\n");
        return sb.toString();
    }

    static List<Activity> parse(String raw, LocalDate today, List<Item> items) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JsonNode arr = PARSER.readTree(JsonExtractor.extract(raw)).path("activities");
            if (!arr.isArray()) return List.of();
            List<Activity> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String actor = normActor(text(n, "actor"));
                String t = text(n, "text");
                if (actor == null || t == null) continue;
                t = t.strip();
                if (t.length() > MAX_TEXT) t = t.substring(0, MAX_TEXT);
                String date = text(n, "date");
                try {
                    date = date == null ? today.toString() : LocalDate.parse(date).toString();
                } catch (DateTimeParseException e) {
                    date = today.toString();
                }
                if (LocalDate.parse(date).isAfter(today)) continue;   // 미래(계획)는 기록 X
                String part = text(n, "part");
                if (part != null && !PARTS.contains(part)) part = null;
                int idx = n.path("item").asInt(1) - 1;
                String src = (idx >= 0 && idx < items.size()) ? items.get(idx).sourceId() : items.get(0).sourceId();
                out.add(new Activity(date, part, actor, t, src));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 에무 | 네네 | 에무·네네 로 정규화 — 그 외(제3자)는 null. */
    static String normActor(String a) {
        if (a == null) return null;
        boolean emu = a.contains("에무") || a.toLowerCase().contains("emu");
        boolean nene = a.contains("네네") || a.toLowerCase().contains("nene");
        if (emu && nene) return "에무·네네";
        if (emu) return "에무";
        if (nene) return "네네";
        return null;
    }

    private static String text(JsonNode o, String f) {
        JsonNode n = o.get(f);
        if (n == null || n.isNull()) return null;
        String s = n.asText();
        return s.isBlank() || "null".equals(s) ? null : s;
    }
}
