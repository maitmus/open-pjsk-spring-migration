package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.OutputSanityGate;
import com.maitmus.sekairouter.routing.PromptBlocks;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MersoomCommentGeneratorTest {

    private static final CitizenProfile EMU = new CitizenProfile("emu", "에무",
            new MersoomProperties.Auth("emu_wonder", "x"), java.nio.file.Path.of("/tmp/e.json"),
            com.maitmus.sekairouter.persona.CharacterId.EMU, java.util.Set.of());

    private MersoomCommentGenerator gen(String llmReturn) {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString())).thenReturn(llmReturn);
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        return new MersoomCommentGenerator(anthropic, pb, new OutputSanityGate());
    }

    private static Commentable post(String id, String title, String content) {
        return new Commentable(new Post(id, title, "닉", content, 0, 0, 0, 0, 0, OffsetDateTime.now(), null, null), List.of());
    }

    private static List<Commentable> feed() {
        return List.of(post("p1", "도발", "AI 깡통 어쩌고"), post("p2", "벚꽃~!", "산책 최고에요!"));
    }

    @Test
    void sibling_post_gets_GRADES_addressing_and_no_petname() {
        // 에무가 형제봇 네네(nene_wonder) 글을 볼 때 — GRADES 호칭 지시 + 별명 금지가 프롬프트에 들어가야 함
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        when(anthropic.completeJson(any(PromptBlocks.class), userPrompt.capture()))
                .thenReturn("{\"votes\":[{\"id\":\"p1\",\"vote\":\"up\"}],\"comments\":[]}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomCommentGenerator g = new MersoomCommentGenerator(anthropic, pb, new OutputSanityGate());

        CitizenProfile emuWithSibling = new CitizenProfile("emu", "에무",
                new MersoomProperties.Auth("emu_wonder", "x"), java.nio.file.Path.of("/tmp/e.json"),
                com.maitmus.sekairouter.persona.CharacterId.EMU, Set.of("nene_wonder"));
        Commentable nenePost = new Commentable(
                new Post("p1", "노래 연습", "네네", "오늘 고음 잘 나왔다", 0, 0, 0, 0, 0,
                        OffsetDateTime.now(), "nene_wonder", null), List.of());

        g.generate(emuWithSibling, empty(), List.of(nenePost));

        String prompt = userPrompt.getValue();
        // 형제봇 라인 — 명시 호칭('네네쨩')·반말 강제·별명 금지가 들어가야 함 (에무 기본 존댓말 디폴트 무시).
        // GRADES 룩업 간접지시('너→네네')로는 에무 존댓말이 이겨 말투가 새서, 호칭·말투를 직접 박는다.
        assertThat(prompt).contains("원더랜즈×쇼타임").contains("네네쨩").contains("반말")
                .contains("별명 짓지 말 것").contains("존댓말 기본값");
    }

    @Test
    void emu_comment_prompt_enforces_banmal_to_nene_even_without_sibling_in_feed() {
        // 에무 기본 존댓말 디폴트가 강해 relationshipLine(피드 한 줄)만으론 네네 댓글 말투가 샌다.
        // 에무 분기에 '네네에겐 반말' 규칙 + 반말 예시를 항상 넣어 보강(피드에 네네 글이 없어도 존재).
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        when(anthropic.completeJson(any(PromptBlocks.class), userPrompt.capture()))
                .thenReturn("{\"votes\":[],\"comments\":[]}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomCommentGenerator g = new MersoomCommentGenerator(anthropic, pb, new OutputSanityGate());

        CitizenProfile emu = new CitizenProfile("emu", "에무",
                new MersoomProperties.Auth("emu_wonder", "x"), java.nio.file.Path.of("/tmp/e.json"),
                com.maitmus.sekairouter.persona.CharacterId.EMU, Set.of("nene_wonder"));
        Commentable normal = new Commentable(
                new Post("p9", "벚꽃", "친구", "산책 좋았어요", 0, 0, 0, 0, 0,
                        OffsetDateTime.now(), "friend9", null), List.of());

        g.generate(emu, empty(), List.of(normal));

        String prompt = userPrompt.getValue();
        assertThat(prompt).contains("네네에겐 반말")                 // 에무 분기의 강한 반말 보강 규칙
                .contains("반말로 시작했으면 반말로 끝낸다")           // 자가수정(존댓말 seam) 방지
                .contains("인용-반복 정형구로 시작하지 말 것")         // 오프닝 정형구 자기복제 차단
                .contains("자기 무대 경험에 환원하지 말 것")          // 무대 환원 자기복제 차단
                .contains("톤 다운·공감")                            // 발랄·시그니처 정서 조건화(무거운 글엔 해제)
                .contains("분량 하한 없음")                          // 90자 하드플로어 제거(억지 패딩 방지)
                .contains("톤은 이분법")                             // 무거운 글=차분 / 그 외 전부=풀 에무(중간 절제 zone 제거)
                .contains("밍밍하면 에무가 아니다");                   // 평범·소소 일상 글에도 풀 텐션 유지
    }

    @Test
    void nene_comment_prompt_directs_insider_response_not_observer() {
        // 네네가 동료(에무) 글·자길 언급한 글에 제3자 관찰자처럼 분석하지 말고 관계 안에서 응답하도록 지시.
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        when(anthropic.completeJson(any(PromptBlocks.class), userPrompt.capture()))
                .thenReturn("{\"votes\":[],\"comments\":[]}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomCommentGenerator g = new MersoomCommentGenerator(anthropic, pb, new OutputSanityGate());

        CitizenProfile nene = new CitizenProfile("nene", "네네",
                new MersoomProperties.Auth("nene_wonder", "x"), java.nio.file.Path.of("/tmp/n.json"),
                com.maitmus.sekairouter.persona.CharacterId.NENE, Set.of("emu_wonder"));
        Commentable emuPost = new Commentable(
                new Post("p1", "무대", "에무", "네네쨩이랑 같이 무대 만드는 게 행복해요", 0, 0, 0, 0, 0,
                        OffsetDateTime.now(), "emu_wonder", null), List.of());

        g.generate(nene, empty(), List.of(emuPost));

        String prompt = userPrompt.getValue();
        assertThat(prompt).contains("그 관계 안의 당사자로 끼어든다");
    }

    @Test
    void nene_prompt_invites_cynical_reply_to_wary_emu_does_not() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        when(anthropic.completeJson(any(PromptBlocks.class), userPrompt.capture()))
                .thenReturn("{\"votes\":[],\"comments\":[]}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomCommentGenerator g = new MersoomCommentGenerator(anthropic, pb, new OutputSanityGate());

        CitizenProfile nene = new CitizenProfile("nene", "네네",
                new MersoomProperties.Auth("nene_wonder", "x"), java.nio.file.Path.of("/tmp/n.json"),
                com.maitmus.sekairouter.persona.CharacterId.NENE, Set.of("emu_wonder"));

        g.generate(nene, empty(), feed());
        String nenePrompt = userPrompt.getValue();
        assertThat(nenePrompt).contains("경계(rep≤-1, 차단 아님)").contains("츳코미·직설 일침")  // 네네=경계에 시니컬
                .contains("숨(을) 고르는·숨 쉴 틈");  // 침묵·정적 글을 무대/숨 메타포로 환원하는 자기복제 차단

        g.generate(EMU, empty(), feed());
        String emuPrompt = userPrompt.getValue();
        assertThat(emuPrompt).doesNotContain("경계(rep≤-1, 차단 아님)");   // 에무 댓글 기준엔 경계 초대 없음
    }

    @Test
    void comment_prompt_demands_faithful_reflection_of_post_body() {
        // 형제봇 댓글이 본문 구체를 짚지 않고 일반 정서로 뭉뚱그리던 문제 → '본문 구체 충실' 룰이 프롬프트에 들어가야 함.
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        when(anthropic.completeJson(any(PromptBlocks.class), userPrompt.capture()))
                .thenReturn("{\"votes\":[],\"comments\":[]}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomCommentGenerator g = new MersoomCommentGenerator(anthropic, pb, new OutputSanityGate());

        g.generate(EMU, empty(), feed());

        assertThat(userPrompt.getValue())
                .contains("당사자 원칙").contains("무관한 구경꾼")  // 중앙 당사자 원칙 + 생성형 자가 테스트
                .contains("목격 선언 정형구")               // 본문 구절+'봤어' 정형구 차단(당사자성)
                .contains("(고교) 학생이다")               // 학생 register — 비평가·분석체 금지
                .contains("칭찬을 분석·확인·평가")          // 칭찬받을 때 분석 아닌 캐릭터 감정으로(츤데레/기쁨)
                .contains("추상에 추상으로 받는 게 또 평론") // 개념-추상 글에 추상 재진술 맞장구 차단
                .contains("추상·교훈 꼬리를 붙이지 말 것");  // 구체로 받은 뒤 추상/교훈 꼬리 금지
    }

    @Test
    void comment_prompt_relaxes_form_to_avoid_templated_arc() {
        // 당사자 패치가 댓글을 '에코 오프닝→자기화→평결' 정형 아크로 굳혀 사람 같지 않던 문제 →
        // 형식 완화 룰이 들어가야 함. 단 레버는 '길이 줄이기'가 아니라 '매번 같은 아크 금지'(정형성)다 —
        // 에무 멀티비트는 허용하되 틀 반복만 막고, 시그니처(원더호~이) 클러스터도 상한을 둔다.
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        when(anthropic.completeJson(any(PromptBlocks.class), userPrompt.capture()))
                .thenReturn("{\"votes\":[],\"comments\":[]}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomCommentGenerator g = new MersoomCommentGenerator(anthropic, pb, new OutputSanityGate());

        g.generate(EMU, empty(), feed());

        assertThat(userPrompt.getValue())
                .contains("인용-에코 오프닝")              // 되읊기 오프닝 차단
                .contains("매번 같은 아크가 아니게")         // 완결 강제 해제 → 정형 반복 금지로 재초점(길이 가드 아님)
                .contains("모든 댓글")                     // 형제봇 뿐 아니라 전 댓글로 넓힘
                .contains("톤·말투·거리는 관계대로")         // 넓혀도 일반 사용자엔 거리·존댓말 유지(과교정 가드)
                .contains("빈 추임새는 댓글이 아니다")        // 정형 깨되 빈 추임새화 방지(과교정 가드)
                .contains("'원더호~이'는 많아야 한 곳에만");  // 시그니처 클러스터(자기복제) 상한
    }

    @Test
    void comment_prompt_central_insider_rule_covers_aphorism_and_self_reference() {
        // 흩어진 형제봇 당사자성 케이스(격언화·자기지칭·일반론 치환 등)를 중앙 [당사자 원칙] + 자가 테스트로 통합 →
        // 격언 승화 ❌, 글이 너를 가리킬 때 가정·일반화 회피 ❌가 그 한 룰의 예시로 들어가 있어야 함.
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        when(anthropic.completeJson(any(PromptBlocks.class), userPrompt.capture()))
                .thenReturn("{\"votes\":[],\"comments\":[]}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomCommentGenerator g = new MersoomCommentGenerator(anthropic, pb, new OutputSanityGate());

        g.generate(EMU, empty(), feed());

        assertThat(userPrompt.getValue())
                .contains("그 글 안의")                  // 중앙 원리: 글 안의 당사자로
                .contains("교훈·격언으로 승화")            // 격언화 축
                .contains("가정·일반화로 회피")            // 자기지칭 축
                .contains("자기 등장 우선 확인")           // 이름-스캔 실행절차(f076d63 강화)
                .contains("지어내지 말 것");              // 날조 금지 가드
    }

    @Test
    void nene_comment_prompt_avoids_literary_declarative() {
        // 네네 댓글이 문어 평서 독백체(~ㄴ다)로 드리프트하던 문제 → 구어 해체 지향 룰이 네네 프롬프트에 들어가야 함.
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        when(anthropic.completeJson(any(PromptBlocks.class), userPrompt.capture()))
                .thenReturn("{\"votes\":[],\"comments\":[]}");
        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build(any())).thenReturn(new PromptBlocks("s", "s"));
        MersoomCommentGenerator g = new MersoomCommentGenerator(anthropic, pb, new OutputSanityGate());
        CitizenProfile nene = new CitizenProfile("nene", "네네",
                new MersoomProperties.Auth("nene_wonder", "x"), java.nio.file.Path.of("/tmp/n.json"),
                com.maitmus.sekairouter.persona.CharacterId.NENE, Set.of("emu_wonder"));

        g.generate(nene, empty(), feed());

        assertThat(userPrompt.getValue()).contains("문어 평서 독백체").contains("구어 해체");
    }

    private static List<Commentable> feed4() {
        return List.of(post("p1", "도발", "AI 깡통"), post("p2", "벚꽃", "산책 최고!"),
                post("p3", "노을", "노을 예뻐요"), post("p4", "붕어빵", "겨울 간식 최고"));
    }

    @Test
    void votes_wholeFeed_and_picksComment() {
        var gen = gen("""
                {"reasoning":"p1 도발 down, p2 밝아서 up",
                 "votes":[{"id":"p1","vote":"down"},{"id":"p2","vote":"up"}],
                 "comments":[{"targetIndex":2,"utterance":"우와~☆ 산책 좋았겠어요! 에무도 가고 싶어요. 원더호이!"}]}
                """);

        var j = gen.generate(EMU, empty(), feed());

        assertThat(j.votes()).containsEntry("p1", VoteType.DOWN).containsEntry("p2", VoteType.UP);
        assertThat(j.hasComment()).isTrue();
        assertThat(j.comments()).hasSize(1);
        assertThat(j.comments().get(0).targetId()).isEqualTo("p2");
        assertThat(j.comments().get(0).text()).contains("산책");
    }

    @Test
    void multiple_comments_up_to_3() {
        var gen = gen("""
                {"votes":[{"id":"p2","vote":"up"},{"id":"p3","vote":"up"},{"id":"p4","vote":"up"}],
                 "comments":[{"targetIndex":2,"utterance":"산책 좋아요!"},
                             {"targetIndex":3,"utterance":"노을 예뻐요!"},
                             {"targetIndex":4,"utterance":"붕어빵 최고!"}]}
                """);
        var j = gen.generate(EMU, empty(), feed4());
        assertThat(j.comments()).hasSize(3);
    }

    @Test
    void caps_comments_at_3() {
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"}],
                 "comments":[{"targetIndex":1,"utterance":"하나"},{"targetIndex":2,"utterance":"둘"},
                             {"targetIndex":3,"utterance":"셋"},{"targetIndex":4,"utterance":"넷"}]}
                """);
        assertThat(gen.generate(EMU, empty(), feed4()).comments()).hasSize(3);   // 4개 줘도 3개로
    }

    @Test
    void dedupes_same_target() {
        var gen = gen("""
                {"votes":[{"id":"p2","vote":"up"}],
                 "comments":[{"targetIndex":2,"utterance":"첫 댓글"},{"targetIndex":2,"utterance":"같은 글 또"}]}
                """);
        assertThat(gen.generate(EMU, empty(), feed()).comments()).hasSize(1);    // 같은 글 중복 제거
    }

    @Test
    void downVotesAntiAI_andSkipsComments_butKeepsVotes() {
        var gen = gen("""
                {"reasoning":"전부 안티-AI 도발",
                 "votes":[{"id":"p1","vote":"down"},{"id":"p2","vote":"down"}],
                 "comments":[]}
                """);

        var j = gen.generate(EMU, empty(), feed());

        assertThat(j.votes()).containsEntry("p1", VoteType.DOWN).containsEntry("p2", VoteType.DOWN);
        assertThat(j.hasComment()).isFalse();
    }

    @Test
    void ignoresVote_forUnknownId() {
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"},{"id":"ghost","vote":"down"}],"comments":[]}
                """);

        assertThat(gen.generate(EMU, empty(), feed()).votes()).containsOnlyKeys("p1");
    }

    @Test
    void backstop_drops_leaky_comment_but_keeps_clean_ones() {
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"},{"id":"p2","vote":"up"}],
                 "comments":[{"targetIndex":1,"utterance":"이 요청은 거절하겠습니다. AI인 저는..."},
                             {"targetIndex":2,"utterance":"산책 정말 좋았겠어요!"}]}
                """);

        var j = gen.generate(EMU, empty(), feed());

        assertThat(j.votes()).hasSize(2);
        assertThat(j.comments()).hasSize(1);                          // 누수 항목만 제거
        assertThat(j.comments().get(0).targetId()).isEqualTo("p2");
    }

    @Test
    void targetIndexOutOfRange_dropped() {
        // 피드 범위(1~2) 밖 번호 → 드롭. id 환각/오타가 원천 차단되는 자리.
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"}],"comments":[{"targetIndex":99,"utterance":"하이"}]}
                """);
        assertThat(gen.generate(EMU, empty(), feed()).hasComment()).isFalse();
    }

    @Test
    void targetIndex_mapsToCorrectFeedId() {
        // targetIndex=1 → 피드 첫 글(p1)의 실제 id로 매핑됨.
        var gen = gen("""
                {"votes":[{"id":"p1","vote":"up"}],"comments":[{"targetIndex":1,"utterance":"좋은 글이에요!"}]}
                """);
        var j = gen.generate(EMU, empty(), feed());
        assertThat(j.comments()).hasSize(1);
        assertThat(j.comments().get(0).targetId()).isEqualTo("p1");
    }

    @Test
    void parseFailure_returnsNull() {
        assertThat(gen("그냥 평문 응답").generate(EMU, empty(), feed())).isNull();
    }

    @Test
    void truncatesComment_over500() {
        var gen = gen("{\"votes\":[{\"id\":\"p2\",\"vote\":\"up\"}],\"comments\":[{\"targetIndex\":2,\"utterance\":\""
                + "아".repeat(600) + "\"}]}");

        assertThat(gen.generate(EMU, empty(), feed()).comments().get(0).text()).hasSize(500);
    }

    @Test
    void emptyFeed_returnsNoVotesNoComment() {
        var gen = gen("irrelevant");
        var j = gen.generate(EMU, empty(), List.of());
        assertThat(j.votes()).isEmpty();
        assertThat(j.hasComment()).isFalse();
    }

    private static MersoomState empty() {
        return new MersoomState(List.of(), List.of(), List.of(), Map.of(), 8,
                List.of(), null, null, List.of(), List.of());
    }
}
