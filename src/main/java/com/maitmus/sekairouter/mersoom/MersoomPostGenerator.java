package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.OutputSanityGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * 머슴 글 생성. LLM 호출 → {@code {reasoning, title, content, shouldPost}} 봉투 파싱.
 *
 * 댓글 생성기와 동일 규약: 메타·게시 보류 사유는 reasoning에 격리, 게시 여부는 shouldPost로 결정.
 * 게시 보류 시 {@code null} 반환. shouldPost는 '명시적 false'만 보류(누락=게시), 빈 title/content·백스톱은 차단.
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
    private final OutputSanityGate outputSanityGate;

    /** @return 게시할 글, 또는 게시 보류 시 {@code null}. */
    public GeneratedPost generate(CitizenProfile profile, MersoomState state, CollectedFeed feed, LocalDate today) {
        String userPrompt = buildUserPrompt(profile, state, feed, today);
        var blocks = promptBuilder.build(profile);   // 교정 콜에서 재사용(같은 시스템 프리픽스 → 캐시 히트)
        String raw = anthropic.completeJson(blocks, userPrompt);

        var parsed = MersoomEnvelopeParser.parse(raw);
        if (parsed.isEmpty()) {
            log.warn("Mersoom post 보류 — 봉투 파싱 실패: {}",
                    raw == null ? "null" : raw.substring(0, Math.min(120, raw.length())));
            return null;
        }
        var env = parsed.get();
        if (env.reasoning() != null && !env.reasoning().isBlank()) {
            log.info("Mersoom post reasoning (not posted): {}", env.reasoning());
        }
        // shouldPost는 '명시적 false'일 때만 보류 — 모델이 필드를 깜빡 누락(null)하면 좋은 글이 버려지므로
        // 누락은 게시로 본다(부적합은 아래 title/content·백스톱 검증이 차단).
        if (Boolean.FALSE.equals(env.shouldPost())) {
            log.info("Mersoom post 보류 — shouldPost=false (모델이 게시 거부)");
            return null;
        }
        String title = env.title() == null ? "" : env.title().strip();
        String content = env.content() == null ? "" : env.content().strip();
        if (title.isBlank() || content.isBlank()) {
            log.info("Mersoom post 보류 — title/content 비어있음");
            return null;
        }
        if (!outputSanityGate.isClean(title) || !outputSanityGate.isClean(content)) {
            log.warn("Mersoom post 보류 — 백스톱 누수 마커 감지");
            return null;
        }

        if (title.length() > MAX_TITLE) title = title.substring(0, MAX_TITLE);
        if (content.length() > MAX_CONTENT) content = content.substring(0, MAX_CONTENT);

        // 글 호칭 검증 게이트 — 모델이 자발적으로 꺼낸 PJSK 인물을 맨이름으로 불렀으면(시드-스캔 사각지대)
        // 같은 시스템 프롬프트로 1회 교정한다(캐시 히트라 비용 미미). 교정 실패 시 원문 유지.
        var leaks = PjskAddressBook.findBareLeaks(profile.persona(), title + "\n" + content);
        if (!leaks.isEmpty()) {
            var corrected = correctAddress(blocks, title, content, leaks);
            if (corrected != null) {
                log.info("Mersoom post 호칭 교정 적용: {} (맨이름→호칭)", leaks.keySet());
                title = corrected.title();
                content = corrected.content();
            } else {
                log.warn("Mersoom post 호칭 교정 실패({}) — 원문 유지", leaks.keySet());
            }
        }

        // 결정론적 오타 정규화(에뮤→에무 등) — 발행 직전 마지막
        title = outputSanityGate.normalize(title);
        content = outputSanityGate.normalize(content);
        return new GeneratedPost(title, content);
    }

    /** 맨이름 누수를 화자 호칭으로 고치는 1회 교정 콜(내용·톤은 유지). 실패 시 null. */
    private GeneratedPost correctAddress(com.maitmus.sekairouter.routing.PromptBlocks blocks,
                                         String title, String content, Map<String, String> leaks) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 모드\npost-호칭교정\n\n");
        sb.append("방금 네가 쓴 글이야. **내용·톤·길이·문장 구조는 그대로 두고**, 아래 PJSK 인물 호칭만 고쳐서 다시 내라")
          .append("(조사·어미도 새 호칭에 맞춰 맞춤법대로 자연스럽게):\n");
        for (var e : leaks.entrySet()) {
            sb.append("- '").append(e.getKey()).append("' → **'").append(e.getValue()).append("'**\n");
        }
        sb.append("\n[제목] ").append(title).append("\n[본문] ").append(content).append("\n\n");
        sb.append("## 출력 형식 (JSON 1개)\n{\"title\":\"<교정된 제목>\", \"content\":\"<교정된 본문>\"}\n");

        var parsed = MersoomEnvelopeParser.parse(anthropic.completeJson(blocks, sb.toString()));
        if (parsed.isEmpty()) return null;
        String ct = parsed.get().title() == null ? "" : parsed.get().title().strip();
        String cc = parsed.get().content() == null ? "" : parsed.get().content().strip();
        if (ct.isBlank() || cc.isBlank()) return null;
        if (!outputSanityGate.isClean(ct) || !outputSanityGate.isClean(cc)) return null;
        if (ct.length() > MAX_TITLE) ct = ct.substring(0, MAX_TITLE);
        if (cc.length() > MAX_CONTENT) cc = cc.substring(0, MAX_CONTENT);
        return new GeneratedPost(ct, cc);
    }

    private String buildUserPrompt(CitizenProfile profile, MersoomState state, CollectedFeed feed, LocalDate today) {
        var persona = profile.persona();
        StringBuilder sb = new StringBuilder();
        sb.append("## 모드\npost\n\n");
        sb.append("## 오늘 날짜 (KST)\n").append(today).append("\n\n");

        sb.append("## 오늘 글 시드 (이 각도로 작성)\n");
        String seedTopic = seedPicker.pickTopic(persona);
        String seedTone = seedPicker.pickTone(persona);
        sb.append("- 토픽: ").append(seedTopic).append("\n");
        sb.append("- 톤·도입 패턴: ").append(seedTone).append("\n");
        // 시드가 PJSK 인물을 맨이름으로 언급하면, 발화 페르소나 고유 호칭으로 부르도록 코드 힌트 주입
        // (댓글의 PjskAddressBook.hintFor와 동일 원리 — 큰 GRADES 표 대신 point-of-use).
        String pjskHint = PjskAddressBook.hintForSeed(persona, seedTopic + " " + seedTone);
        if (!pjskHint.isEmpty()) sb.append(pjskHint).append("\n");
        sb.append("\n");

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
                sb.append("- ").append(e.getKey()).append(" (rep=").append(n.reputation()).append(")");
                if (n.call() != null) sb.append(" call=\"").append(n.call()).append("\"");
                sb.append("\n  ").append(n.note().replace("\n", "\n  ")).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## 지시\n");
        sb.append("새 글 1개 작성.\n");
        if (persona == com.maitmus.sekairouter.persona.CharacterId.NENE) {
            sb.append("- title: **하드 6~15자 (16자 이상 ❌)**, 짧고 담담하게. 발랄한 느낌표 남발 ❌ — 평서·명사형·\"...\" 여운 종결 OK (예: \"오늘 연습 끝\", \"자몽 젤리 발견\", \"또 토우야한테 졌다...\"). 두 토픽 합쳐서 길어지면 한 토픽만 남기고 다른 건 본문으로.\n");
            sb.append("- content: **3~5문장, 하드 최소 180자 (목표 200~350자)**. 차분한 직설 반말. 무심한 듯 한 마디로 시작해 디테일을 풀되 감정을 과장하지 말 것. **발화처럼 읽는 사람한테 말 걸듯 — 짧고 구체적으로 끝나면 그걸로 OK, 분량 채우려 사색·잠언으로 늘리지 말 것.** 노래·게임 화제면 평소보다 자신감 있게 풀어도 됨.\n");
            sb.append("- ⚠️ **구체 일상 디테일로 채워라(디스코드 발화처럼 손에 잡히게) — 추상 사색·잠언으로 분량 늘리지 말 것.** 무대·리듬·박자·'무음과 강음'·'반복하면 몸이 기억'·'완벽함을 기다리면' 류 추상 메타포로 매번 환원 금지(자기복제). 무대 얘기면 어떤 곡·어느 동작 같은 구체 사건으로. 분량은 사색이 아니라 **오늘 실제 겪은 디테일**로 채운다(짧게 끝나면 OK).\n");
            sb.append("자수 점검:\n");
            sb.append("  1) title이 16자 이상이면 짧게 다시 쓰기\n");
            sb.append("  2) content가 180자 미만이면 **위 토픽 시드 각도에서 구체 디테일 1문장**만 추가(추상 사색 말고 — 오늘 실제 겪은 거). 180자 넘으면 더 늘리지 말 것.\n");
            sb.append("짧은 한 줄로 끝내지 말 것 — 본인 경험·관찰·다음 계획 중 최소 하나는 풀어 넣기. **존댓말 어미(~예요/~네요) 쓰지 말 것 — 네네는 반말.**\n");
            sb.append("- ⚠️ **본문은 혼잣말 일기가 아니라 '머슴 사람들 보라고' 쓰는 거 — 읽는 사람한테 말 걸듯 ~어/~지/~거든/~네로 끝낸다.** '실패한다 / 먼저다 / 시간이 간다 / 기분이 든다' 같은 **평서 ~다 종결 일기체가 줄줄이 나오면 네네가 아니라 일기장이다.** 발행 전 문장 끝 다 점검해 '~다'(평서 단정: ~ㄴ다/된다/난다·~았다/었다·~겠다·~ㄴ 거다·형용사 ~다)면 해체로 고쳐('간다'→'가네', '든다'→'들어', '깨달았다'→'깨달았어'). (단 **구어 ~더라/~지/~거든/~잖아, 인용·연결 ~다고/~다는/~다(가)**는 OK. 제목은 평서·명사형 종결 OK — 본문 문장만 해당.)\n");
        } else {
            sb.append("- title: **하드 6~15자 (16자 이상 ❌)**, 짧고 발랄, `!!`/`~!`/`~~`/`☆` 종결, in-the-moment 느낌 (예: \"합숙 들어가요!! 잘 있어요~!!\", \"짐 싸기 시작했어요~!\"). 회상·관조·명사형/평서 마침표 종결 ❌. 두 토픽 합쳐서 길어지면 한 토픽만 남기고 다른 건 본문으로.\n");
            sb.append("- content: 4~6문장, **하드 최소 280자 (목표 300~500자)**. 본문도 in-the-moment 우선 (지금 막 / 방금 / ~하러 가는 길)\n");
            sb.append("- ⚠️ **구체 일상 디테일로 채워라 — 추상 사색·잠언으로 분량 늘리지 말 것. 무대·리듬·박자·'반복하면 몸이 기억한다'·'완벽함을 포기하는 순간'·'완벽보다 불완전이 진짜' 류 추상 메타포·교훈으로 매번 환원 금지(자기복제).** 무대·연습 얘기면 어떤 곡·어느 동작 같은 **구체 사건**으로. **매 글을 '완벽하지 않아도 괜찮다/틀려도 그게 진짜다' 식 같은 교훈으로 끝내지 말 것 — 글마다 다른 결.** 분량은 사색이 아니라 오늘 실제 겪은 디테일로.\n");
            sb.append("자수 점검:\n");
            sb.append("  1) title이 16자 이상이면 짧게 다시 쓰기\n");
            sb.append("  2) content가 280자 미만이면 **위 토픽 시드 각도에서** 디테일 1~2문장 추가해서 분량 채우기 (산만하지 않게 한 호흡씩, 다른 토픽 끌어오지 말 것)\n");
            sb.append("짧은 한 줄 인사로 끝내지 말 것 — 본인 경험·소소한 디테일·약속·다음 계획 중 최소 하나는 반드시 풀어 넣기.\n");
            sb.append("- ⚠️ **존댓말 일관: 발랄 종결 `!`·`~`·`☆`·`★`은 존댓말 어미 위에 붙인다(어미를 반말로 떨구는 게 아님). ❌'떠올렸어!'·'애먹었거든—' → ✅'떠올렸어요!'·'애먹었거든요—'. 특히 맨 끝 흥분 구호/감탄이 형태 불문 잘 샌다 — '최고야!'·'신난다!'·'기대된다!'·'좋아!'·'대박!'처럼(~야·~다·~어 다 반말, ★/!를 붙여도 ❌) 외치지 말 것 → ✅'최고예요!'·'신나요!'·'기대돼요!'·'좋아요!'·'대박이에요!'. 발행 전 문장 끝, 특히 맨 마지막 줄 점검.**\n");
        }
        sb.append("\n## 출력 형식 (JSON 1개, 이 형식만)\n");
        sb.append("{\"reasoning\":\"<글 각도 메모 / 또는 게시 보류 사유 — 비공개, 발행 안 됨>\", ");
        sb.append("\"title\":\"<제목>\", \"content\":\"<본문>\", \"shouldPost\":true}\n");
        sb.append("- 올릴 글이 준비되면 shouldPost:true, title/content 채우기.\n");
        sb.append("- 지금 적절히 올릴 글이 없거나 부적절한 상황이면 shouldPost:false 로 두고 사유는 reasoning에만 적는다.\n");
        sb.append("- **거절·메타·자기지칭(AI/어시스턴트/저)·규칙 설명을 title/content에 쓰지 말 것.** 그런 판단은 전부 reasoning으로.\n");
        sb.append("- ⚠️ **JSON 안전**: 문자열 값(title/content) 안에 큰따옴표(\") 절대 쓰지 말 것 — 대사·인용은 작은따옴표(') 나 「」 사용(예: 에무가 '괜찮아?' 물어봤어). 큰따옴표를 넣으면 본문이 중간에서 잘린다.\n");

        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\n", " ").strip();
    }

    public record GeneratedPost(String title, String content) {}
}
