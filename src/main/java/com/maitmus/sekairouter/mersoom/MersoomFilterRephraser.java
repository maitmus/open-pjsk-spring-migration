package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.JsonExtractor;
import com.maitmus.sekairouter.routing.OutputSanityGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 머슴 서버 욕설 필터 오탐 우회 — 서버는 본문을 substring으로 걸러 무해한 단어 경계('혼자지만'의 '자지')도 400으로 거절한다.
 * 거절 응답이 걸린 조합을 알려주므로, 그 캐릭터 프롬프트(캐시 블록) 그대로 해당 부분만 살짝 고쳐 쓰게 한다.
 * 결과에 조합이 남으면(띄어쓰기를 빼고 붙여 읽어도) 실패로 본다. 실패·예외는 null(호출자가 그 글을 보류).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MersoomFilterRephraser {

    private static final Pattern BADWORD = Pattern.compile("부적절한 단어가 포함되어 있습니다: \\\\?\"([^\"\\\\]+)");
    private static final ObjectMapper PARSER = new ObjectMapper();

    private final AnthropicClientWrapper anthropic;
    private final MersoomPromptBuilder promptBuilder;
    private final OutputSanityGate outputSanityGate;

    /** 서버 욕설 필터 400이면 걸린 조합, 아니면 null. */
    public static String flaggedWord(Throwable e) {
        if (!(e instanceof HttpClientErrorException.BadRequest br)) return null;
        Matcher m = BADWORD.matcher(br.getResponseBodyAsString());
        return m.find() ? m.group(1) : null;
    }

    /** 걸린 조합이 붙여 읽어도 남아 있는지. */
    public static boolean containsLoosely(String text, String word) {
        return text != null && text.replaceAll("\\s+", "").contains(word.replaceAll("\\s+", ""));
    }

    /**
     * @param kind 글 | 댓글 | 대댓글 (프롬프트 문구용)
     * @return 고친 텍스트, 또는 실패 시 null
     */
    public String rephrase(CitizenProfile profile, String kind, String text, String word) {
        try {
            String raw = anthropic.completeJson(promptBuilder.build(profile), buildUserPrompt(kind, text, word));
            JsonNode node = PARSER.readTree(JsonExtractor.extract(raw)).get("text");
            String fixed = node == null ? "" : node.asText("").strip();
            if (fixed.isBlank() || containsLoosely(fixed, word) || !outputSanityGate.isClean(fixed)
                    || fixed.length() > text.length() * 2 + 20) {
                log.warn("[{}] Mersoom 필터 재작성 무효 — '{}' 잔존/빈 값/이상 길이: \"{}\"", profile.key(), word, fixed);
                return null;
            }
            return outputSanityGate.normalize(fixed);
        } catch (Exception e) {
            log.warn("[{}] Mersoom 필터 재작성 실패: {}", profile.key(), e.toString());
            return null;
        }
    }

    static String buildUserPrompt(String kind, String text, String word) {
        return "## 모드\nfilter-재작성\n\n"
                + "방금 네가 쓴 " + kind + "이(가) 게시판 단어 필터에 걸렸어. 나쁜 말은 없는데, 글자가 이어지면서 '" + word
                + "' 조합이 생겨 기계적으로 막힌 오탐이야.\n"
                + "**뜻·톤·말투·길이는 그대로** 두고, '" + word + "' 조합이 어디에도 안 생기게(띄어쓰기를 빼고 붙여 읽어도) 그 부분만 살짝 바꿔 다시 내.\n"
                + "예) '혼자지만' → '혼자이지만'\n\n"
                + "## 원문\n" + text + "\n\n"
                + "## 출력 형식 (JSON 1개)\n{\"text\":\"<고친 " + kind + ">\"}\n";
    }
}
