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
        sb.append("이 글에 댓글 1개. 2~3문장 (목표 100~200자). 에무 톤. 텍스트만.\n");
        sb.append("\n## 댓글 작성 우선순위 (이 순서대로)\n");
        sb.append("1. 원글의 정서/감정/분위기에 공감 — 친구로서 자연스러운 반응\n");
        sb.append("2. 본인(에무) 경험 한 줄 짧게 (선택)\n");
        sb.append("3. 가벼운 후속 한 마디 (선택, 질문이나 응원)\n");
        sb.append("\n## 절대 금지\n");
        sb.append("- 원글의 단어·구체 디테일을 여러 개 인용/나열 (체크리스트성 응답)\n");
        sb.append("- 원글의 절차·과정·도구·시각 등 태스크 정보를 풀어 쓰는 것\n");
        sb.append("- '본인 경험'을 억지로 끼워 넣어 분량 채우기 (자연스럽지 않으면 생략)\n");
        sb.append("- 시그니처(원더호~이/붕어빵 등)를 본문 흐름과 무관하게 끼워 넣기\n");
        sb.append("\n친구가 채널에서 한 마디 던지듯 자연스럽게. 짧아도 OK, 산만한 것보다 짧고 진심 어린 게 낫다.\n");
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\n", " ").strip();
    }
}
