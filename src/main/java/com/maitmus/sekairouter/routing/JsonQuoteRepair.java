package com.maitmus.sekairouter.routing;

/**
 * LLM이 JSON 문자열 값 안에 큰따옴표를 이스케이프 없이 넣어
 * ({@code "content": "에무가 "네네쨩 괜찮아?" 물어봤어"}) 문자열이 조기 종료돼
 * 그 뒤가 잘려나가는 문제를 보정한다.
 *
 * <p>전략: 문자열 값 내부의 {@code "} 중 *구조 문자(, } ] : 또는 입력 끝)*가 뒤따르지 않는 것은
 * 내부 따옴표로 보고 {@code \"}로 escape 한다. 이미 {@code \"}로 이스케이프된 건 그대로 둔다 →
 * 정상 JSON엔 영향이 없다(멱등). 구조 문자 lookahead 휴리스틱이라 완벽하진 않지만(예: 인용 끝이
 * 곧장 쉼표로 이어지는 희귀 케이스), 봉투 형태(reasoning/title/content/utterance/shouldPost)에서
 * 실제로 문제되는 자유 텍스트 필드의 비이스케이프 따옴표를 안정적으로 살린다.
 */
public final class JsonQuoteRepair {

    private JsonQuoteRepair() {}

    public static String escapeInnerQuotes(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length() + 16);
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!inString) {
                out.append(c);
                if (c == '"') inString = true;
                continue;
            }
            if (c == '\\') {                       // 이미 이스케이프된 쌍 — 그대로 복사
                out.append(c);
                if (i + 1 < s.length()) out.append(s.charAt(++i));
                continue;
            }
            if (c == '"') {
                int j = i + 1;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                char next = j < s.length() ? s.charAt(j) : '\0';
                if (next == ',' || next == '}' || next == ']' || next == ':' || next == '\0') {
                    out.append(c);                 // 진짜 종료 따옴표
                    inString = false;
                } else {
                    out.append("\\\"");            // 내부 따옴표 → escape
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
