package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 머슴 댓글 생성. target post 컨텍스트 + LLM 호출 → 500자 truncate + JSON-like reject.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MersoomCommentGenerator {

    private static final int MAX_CONTENT = 500;

    private final AnthropicClientWrapper anthropic;
    private final MersoomPromptBuilder promptBuilder;

    public String generate(MersoomState state, Commentable target) {
        String userPrompt = buildUserPrompt(state, target);
        String raw = anthropic.completeJson(promptBuilder.build(), userPrompt).strip();

        if (raw.isBlank()) throw new IllegalStateException("Mersoom comment LLM returned empty");
        if (raw.startsWith("{") || raw.startsWith("```")) {
            throw new IllegalStateException("Mersoom comment LLM returned JSON-like: "
                    + raw.substring(0, Math.min(100, raw.length())));
        }

        return raw.length() > MAX_CONTENT ? raw.substring(0, MAX_CONTENT) : raw;
    }

    private String buildUserPrompt(MersoomState state, Commentable target) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 모드\ncomment\n\n");
        sb.append("## 대상 글\n");
        sb.append("post_id: ").append(target.post().id()).append("\n");
        sb.append("@").append(safe(target.post().nickname())).append(": \"").append(safe(target.post().title())).append("\"\n");
        sb.append("본문: ").append(safe(target.post().content())).append("\n");

        if (!target.comments().isEmpty()) {
            sb.append("\n기존 댓글:\n");
            for (var c : target.comments()) {
                sb.append("- @").append(safe(c.nickname())).append(": ").append(safe(c.content())).append("\n");
            }
        }
        sb.append("\n");

        ContextNote relevant = state.contextNotes().get(target.post().nickname());
        if (relevant != null) {
            sb.append("## 작성자 context_notes\n");
            sb.append(relevant.note()).append("\n");
            if (relevant.call() != null) sb.append("호칭: ").append(relevant.call()).append("\n");
            sb.append("\n");
        }

        sb.append("## 지시\n");
        sb.append("이 글에 댓글 1개. 1~2문장 (500자 이내). 에무 톤. 텍스트만.\n");
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\n", " ").strip();
    }
}
