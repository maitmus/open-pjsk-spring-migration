package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.OutputSanityGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 머슴 광고 배너 문구 생성 — 포인트로 등록하는 캐릭터 페르소나 한마디(≤50자).
 * 글/댓글과 달리 링크·서비스 없이, 캐릭터가 사람들에게 건네는 초대·자기소개 한 줄.
 * (예) 에무: "재밌는 글 보면 에무가 달려가는 거에요!! 같이 놀아요!! 원더호이~!!"
 *
 * 봉투 파싱 실패·shouldPost=false·빈 문구·백스톱 누수 시 {@code null}(등록 보류).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MersoomAdGenerator {

    private static final int MAX_AD = 50;

    private final AnthropicClientWrapper anthropic;
    private final MersoomPromptBuilder promptBuilder;
    private final OutputSanityGate outputSanityGate;

    /** @return 광고 문구, 또는 보류 시 {@code null}. */
    public String generate(CitizenProfile profile) {
        String raw = anthropic.completeJson(promptBuilder.build(profile), buildUserPrompt(profile));
        var parsed = MersoomEnvelopeParser.parse(raw);
        if (parsed.isEmpty()) {
            log.warn("[{}] Mersoom ad 보류 — 봉투 파싱 실패: {}", profile.key(),
                    raw == null ? "null" : raw.substring(0, Math.min(100, raw.length())));
            return null;
        }
        var env = parsed.get();
        if (env.reasoning() != null && !env.reasoning().isBlank()) {
            log.info("[{}] Mersoom ad reasoning (not posted): {}", profile.key(), env.reasoning());
        }
        if (!Boolean.TRUE.equals(env.shouldPost())) {
            log.info("[{}] Mersoom ad 보류 — shouldPost={}", profile.key(), env.shouldPost());
            return null;
        }
        String content = env.content() == null ? "" : env.content().strip();
        if (content.isBlank()) {
            log.info("[{}] Mersoom ad 보류 — content 비어있음", profile.key());
            return null;
        }
        if (!outputSanityGate.isClean(content)) {
            log.warn("[{}] Mersoom ad 보류 — 백스톱 누수 마커", profile.key());
            return null;
        }
        return content.length() > MAX_AD ? content.substring(0, MAX_AD) : content;
    }

    private String buildUserPrompt(CitizenProfile profile) {
        boolean nene = profile.persona() == CharacterId.NENE;
        StringBuilder sb = new StringBuilder();
        sb.append("## 모드\nad\n\n");
        sb.append("## 지시\n");
        sb.append("머슴 광고 배너에 띄울 **").append(profile.actorName()).append("의 페르소나 한마디**. ");
        sb.append("사람들에게 너를 알리는 짧은 초대·자기소개를 네 톤으로 한 줄.\n");
        sb.append("- **하드 최대 50자, 한 문장**(짧고 임팩트). 50자 절대 넘기지 말 것.\n");
        if (nene) {
            sb.append("- **네네 톤** — 차분한 직설 반말, 츳코미·츤데레 기질. 광고인데도 무심한 듯, 그래도 챙기는 한 줄. (예: \"재밌는 글 있으면 네네가 슬쩍 볼지도. ...뭐, 기대는 마.\")\n");
        } else {
            sb.append("- **에무 톤** — 밝고 천진, 원더호~이 시그니처. 신나는 초대 한 줄. (예: \"재밌는 글 보면 에무가 달려가는 거에요!! 같이 놀아요!! 원더호이~!!\")\n");
        }
        sb.append("- 링크·해시태그 ❌. 정치·사행성·유해 내용 ❌. 거절·메타·자기지칭(AI/어시스턴트/저) ❌.\n");
        sb.append("- 광고 슬롯은 인간 사용자에게 노출되니, 네 매력이 드러나는 자연스러운 한 줄로.\n");
        sb.append("\n## 출력 형식 (JSON 1개, 이 형식만)\n");
        sb.append("{\"reasoning\":\"<비공개 메모 / 또는 보류 사유>\", \"content\":\"<광고 문구 ≤50자>\", \"shouldPost\":true}\n");
        sb.append("- ⚠️ 문자열 값 안에 큰따옴표(\") 쓰지 말 것 — 인용은 작은따옴표(')나 「」.\n");
        return sb.toString();
    }
}
