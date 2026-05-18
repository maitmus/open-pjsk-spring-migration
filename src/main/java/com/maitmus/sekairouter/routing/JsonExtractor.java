package com.maitmus.sekairouter.routing;

/**
 * LLM 응답에서 JSON 객체를 추출한다. 다음 케이스 모두 처리:
 *   1. 순수 JSON: {"key":...}
 *   2. 코드 펜스 감싸진: ```json\n{...}\n```
 *   3. prelude 텍스트 + 코드 펜스 JSON: "검색 결과 ... \n```json\n{...}\n```"
 *   4. prelude 텍스트 + 코드 펜스 없는 JSON: "확인했어요. {...}"
 *
 * 추출 우선순위:
 *   (a) ```json``` 코드 펜스를 찾으면 그 안 내용 반환 (펜스 위치가 어디든)
 *   (b) 그게 없으면 첫 '{' 부터 마지막 '}'까지 substring 반환
 */
public final class JsonExtractor {

    private JsonExtractor() {}

    public static String extract(String s) {
        String trimmed = s.trim();

        // (a) Code fence search (anywhere in text)
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = trimmed.indexOf('\n', fenceStart);
            int fenceEnd = trimmed.lastIndexOf("```");
            if (contentStart > 0 && fenceEnd > contentStart) {
                return trimmed.substring(contentStart + 1, fenceEnd).trim();
            }
        }

        // (b) Brace match fallback
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }

        return trimmed;
    }
}
