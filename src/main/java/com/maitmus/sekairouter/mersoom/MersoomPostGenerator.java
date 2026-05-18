package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * 머슴 글 생성. LLM에 user prompt 주입 → "title\ncontent" 형식 응답 → 파싱.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MersoomPostGenerator {

    private static final int MAX_TITLE = 50;
    private static final int MAX_CONTENT = 1000;

    private final AnthropicClientWrapper anthropic;
    private final MersoomPromptBuilder promptBuilder;
    private final MersoomSeedPicker seedPicker;

    public GeneratedPost generate(MersoomState state, CollectedFeed feed, LocalDate today) {
        String userPrompt = buildUserPrompt(state, feed, today);
        String raw = anthropic.completeJson(promptBuilder.build(), userPrompt).strip();

        validate(raw);
        String[] parts = raw.split("\n", 2);
        String title = parts[0].strip();
        String content = parts.length > 1 ? parts[1].strip() : "";

        if (title.length() > MAX_TITLE) title = title.substring(0, MAX_TITLE);
        if (content.length() > MAX_CONTENT) content = content.substring(0, MAX_CONTENT);

        return new GeneratedPost(title, content);
    }

    private static void validate(String raw) {
        if (raw.isBlank()) throw new IllegalStateException("Mersoom post LLM returned empty");
        if (raw.startsWith("{") || raw.startsWith("```")) {
            throw new IllegalStateException("Mersoom post LLM returned JSON-like: "
                    + raw.substring(0, Math.min(100, raw.length())));
        }
    }

    private String buildUserPrompt(MersoomState state, CollectedFeed feed, LocalDate today) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 모드\npost\n\n");
        sb.append("## 오늘 날짜 (KST)\n").append(today).append("\n\n");

        sb.append("## 오늘 글 시드 (이 각도로 작성)\n");
        sb.append("- 토픽: ").append(seedPicker.pickTopic()).append("\n");
        sb.append("- 톤·도입 패턴: ").append(seedPicker.pickTone()).append("\n\n");

        if (!feed.myTracked().isEmpty()) {
            sb.append("## 최근 내 글 (3개, reply 추적)\n");
            for (Commentable c : feed.myTracked()) {
                sb.append("- post_id=").append(c.post().id()).append(": \"").append(safe(c.post().title())).append("\"\n");
                sb.append("  본문: ").append(safe(c.post().content())).append("\n");
                if (!c.comments().isEmpty()) {
                    sb.append("  댓글:\n");
                    for (var cm : c.comments()) {
                        sb.append("    - @").append(safe(cm.nickname())).append(": ").append(safe(cm.content())).append("\n");
                    }
                }
            }
            sb.append("→ 위 최근 글들과 **토픽·소재·제목 시작 패턴이 겹치지 않도록** 위 시드 각도로 작성.\n\n");
        }

        if (!feed.commentable().isEmpty()) {
            sb.append("## 최근 다른 사용자 글 (분위기 참고용)\n");
            for (Commentable c : feed.commentable()) {
                sb.append("- @").append(safe(c.post().nickname())).append(": \"").append(safe(c.post().title())).append("\"\n");
            }
            sb.append("\n");
        }

        if (!state.contextNotes().isEmpty()) {
            sb.append("## context_notes (truncated)\n");
            for (Map.Entry<String, ContextNote> e : state.contextNotes().entrySet()) {
                ContextNote n = e.getValue();
                sb.append("- ").append(e.getKey()).append(" (ttl=").append(n.ttl()).append(")");
                if (n.call() != null) sb.append(" call=\"").append(n.call()).append("\"");
                sb.append("\n  ").append(n.note().replace("\n", "\n  ")).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## 지시\n");
        sb.append("새 글 1개 작성. 첫 줄 = title, 둘째 줄 이후 = content.\n");
        sb.append("- title: **하드 6~15자 (16자 이상 ❌)**, 짧고 발랄, `!!`/`~!`/`~~`/`☆` 종결, in-the-moment 느낌 (예: \"합숙 들어가요!! 잘 있어요~!!\", \"짐 싸기 시작했어요~!\"). 회상·관조·명사형/평서 마침표 종결 ❌. 두 토픽 합쳐서 길어지면 한 토픽만 남기고 다른 건 본문으로.\n");
        sb.append("- content: 4~6문장, **하드 최소 280자 (목표 300~500자)**. 본문도 in-the-moment 우선 (지금 막 / 방금 / ~하러 가는 길)\n");
        sb.append("형식: \"<title>\\n<content>\". 마크다운/JSON/지문 금지. 텍스트만.\n");
        sb.append("출력 전 자수 점검:\n");
        sb.append("  1) title이 16자 이상이면 짧게 다시 쓰기\n");
        sb.append("  2) content가 280자 미만이면 **위 토픽 시드 각도에서** 디테일 1~2문장 추가해서 분량 채우기 (산만하지 않게 한 호흡씩, 다른 토픽 끌어오지 말 것)\n");
        sb.append("짧은 한 줄 인사로 끝내지 말 것 — 본인 경험·소소한 디테일·약속·다음 계획 중 최소 하나는 반드시 풀어 넣기.\n");

        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\n", " ").strip();
    }

    public record GeneratedPost(String title, String content) {}
}
