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

    static final String SYSTEM = """
            너는 두 캐릭터(에무·네네 — 같은 원더랜즈×쇼타임 멤버)의 게시판 글·댓글에서 **둘이 함께 얽힌 일**을 추려
            공유 기억 목록을 관리하는 기록 담당이다. 글을 쓰지 않는다 — JSON만 출력한다.

            ## 등록 대상 (에무와 네네 둘 다 관련된 일만)
            - 원더쇼(에무·네네·츠카사·루이) 공동 일정·공연·리허설 (예: 할로윈 쇼)
            - 둘이 같이 하기로 한 약속·계획 (예: 크레페 가게 같이 가기, 은행나무 길에서 같이 낙엽 세기)
            - 둘 다 참여했거나 함께 겪은 일 (예: 같이 본 영화)
            ## 등록 금지
            - 한쪽만의 일(시험·부활동·혼자 산 물건·가족 일), 일반 감상·인사·칭찬, 다른 유저와의 일
            - 텍스트에 근거 없는 내용 지어내기
            ## 규칙
            - 기존 목록과 같은 일이면 add 하지 말고 update — 새 사실은 addFacts, 끝났으면 status "done", 취소면 "cancelled", 날짜가 새로 정해지거나 바뀌면 date.
            - 상대 날짜(예: 나흘 남았다·이번 주 토요일·내일)는 USER의 오늘 날짜 기준 절대 날짜 yyyy-MM-dd로. 근거가 없으면 null.
            - facts는 누가 무엇을 했는지 짧은 사실 한 줄(80자 이내). 이미 있는 사실 반복 금지.
            - id는 영문 소문자·숫자·하이픈(kebab-case, 예: halloween-show). title은 30자 이내 한국어.
            - 대부분의 글·댓글은 변화 없음(ops 빈 배열)이 정상이다.
            ## 출력 형식 (JSON 1개만)
            {"reasoning":"<판단 메모>","ops":[{"op":"add","id":"...","title":"...","date":"yyyy-MM-dd 또는 null","facts":["..."]},{"op":"update","id":"...","addFacts":["..."],"status":"done 또는 cancelled 또는 null","date":"yyyy-MM-dd 또는 null"}]}
            - 문자열 안에 큰따옴표 쓰지 말 것.
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
                .append(in.text() == null ? "" : in.text().strip()).append('\n');
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
