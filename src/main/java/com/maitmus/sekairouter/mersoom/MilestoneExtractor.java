package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.JsonExtractor;
import com.maitmus.sekairouter.routing.PromptBlocks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 방금 올린 글에서 '한 번만 일어날 수 있는 일'(올해 첫 입김·코타츠 첫 개시·첫눈 등)을 뽑는 소형 LLM 호출.
 * 지시는 영문 — {@link SharedEventExtractor}와 같은 이유(한국어 지시 시 Opus 5.5 refusal).
 */
@Slf4j
@Component
public class MilestoneExtractor {

    private static final ObjectMapper PARSER = new ObjectMapper();

    static final String SYSTEM = """
            You read a short fictional post written by a game character on a community board. Find things in it that can only happen ONCE for this character in a season or year, so later posts do not repeat them as if new. Reply with JSON only.

            What counts: the post says the character did or saw something for the first time this season/year, or started/brought out something seasonal (e.g. took out the kotatsu, first breath fog this year, first snow, first time wearing a winter coat this year, opened a new game on release day).
            What does not count: ordinary repeatable activities (playing a game, eating, practice, napping, walking), things that only happened to other people, plans for the future, memories of the past years. Do not invent anything not in the text.

            Rules:
            - Skip anything already in the existing list (same meaning, even if worded differently).
            - id: lowercase kebab-case (e.g. kotatsu-out). title: Korean, short noun phrase, max 20 chars (e.g. 코타츠 꺼냄, 올해 첫 입김).
            - date: yyyy-MM-dd when it happened (use today's date from the user message; yesterday if the post says yesterday). null means today.
            - Most posts contain nothing — then milestones is an empty array.

            Output exactly one JSON object:
            {"reasoning":"<short note>","milestones":[{"id":"...","title":"...","date":"yyyy-MM-dd or null"}]}
            Do not use double quotes inside string values.
            """;

    private final AnthropicClientWrapper anthropic;

    public MilestoneExtractor(AnthropicClientWrapper anthropic) {
        this.anthropic = anthropic;
    }

    public List<Milestone> extract(String owner, String actorName, LocalDate today, String sourceId, String text,
                                   List<Milestone> existing) {
        try {
            String raw = anthropic.completeJson(new PromptBlocks(List.of(new PromptBlocks.Block(SYSTEM, true))),
                    buildUserPrompt(actorName, today, text, existing));
            return parse(raw, owner, today, sourceId);
        } catch (Exception e) {
            log.warn("[{}] Milestone 추출 실패(게시 영향 없음): {}", owner, e.toString());
            return List.of();
        }
    }

    static String buildUserPrompt(String actorName, LocalDate today, String text, List<Milestone> existing) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 오늘 (KST)\n").append(today).append("\n\n");
        sb.append("## 작성자\n").append(actorName).append("\n\n");
        sb.append("## 이미 기록된 일\n");
        if (existing == null || existing.isEmpty()) sb.append("(없음)\n");
        else for (Milestone m : existing) sb.append("- ").append(m.date()).append(' ').append(m.id()).append(": ").append(m.title()).append('\n');
        sb.append("\n## 방금 올린 글\n").append(text == null ? "" : text.strip()).append("\n\n");
        sb.append("## 지시\n위 글에서 새로 기록할 '한 번만 일어나는 일'을 JSON 1개로 출력.\n");
        return sb.toString();
    }

    static List<Milestone> parse(String raw, String owner, LocalDate today, String sourceId) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JsonNode arr = PARSER.readTree(JsonExtractor.extract(raw)).path("milestones");
            if (!arr.isArray()) return List.of();
            List<Milestone> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String id = text(n, "id"), title = text(n, "title"), date = text(n, "date");
                if (id == null || title == null) continue;
                try {
                    date = date == null ? today.toString() : LocalDate.parse(date).toString();
                } catch (DateTimeParseException e) {
                    date = today.toString();
                }
                out.add(new Milestone(owner, id, title, date, sourceId));
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
}
