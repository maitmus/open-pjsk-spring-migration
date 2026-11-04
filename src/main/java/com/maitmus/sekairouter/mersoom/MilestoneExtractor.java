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
 * 방금 올린 글에서 '다시 새로 일어나면 모순인 굵직한 일'(계절 물건 개시·올해 첫 경험·쇼 당일·받음/완성·첫 발견/만남)을 뽑는 소형 LLM 호출.
 * 지시는 영문 — {@link SharedEventExtractor}와 같은 이유(한국어 지시 시 Opus 5.5 refusal).
 */
@Slf4j
@Component
public class MilestoneExtractor {

    private static final ObjectMapper PARSER = new ObjectMapper();

    static final String SYSTEM = """
            You read a short fictional post written by a game character on a community board. Record the notable events in it that should not happen again as if new, so later posts stay consistent (a show day does not come twice, a place is discovered only once). Reply with JSON only.

            What counts:
            1) A seasonal item brought out or switched on for the season (e.g. took out the kotatsu, brought out a winter blanket, first time wearing a winter coat this season).
            2) A seasonal first experience framed as the first of this season/year (e.g. first breath fog this year, first snow).
            3) A one-time event that happened to the writer: a show, festival or event day, receiving something new (new choreography, a gift), finishing or completing something, a release day.
            4) A first discovery or first meeting: found a new place, shop, stall or alley; met a particular person or animal for the first time.
            What does not count: ordinary repeatable acts (eating or tasting a snack, buying something, decorating, wearing an accessory, playing a game, practice, napping, walking, chatting), feelings and opinions, things that happened only to other people, plans or upcoming events, memories of past years. Do not invent anything not in the text.

            Rules:
            - Skip anything already in the existing list (same meaning, even if worded differently). A later post about the same thing (seeing the cat again, after the show) is not new.
            - id: lowercase kebab-case (e.g. kotatsu-out, halloween-show-day). title: Korean, short noun phrase, max 20 chars (e.g. 코타츠 꺼냄, 할로윈 쇼 당일, 계단 골목 처음 발견).
            - date: yyyy-MM-dd when it happened (use today's date from the user message; yesterday if the post says yesterday). null means today.
            - Many posts contain nothing — then milestones is an empty array.

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
        sb.append("## 지시\n위 글에서 새로 기록할 굵직한 일을 JSON 1개로 출력.\n");
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
