package com.maitmus.sekairouter.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutputSanityGateTest {

    private final OutputSanityGate gate = new OutputSanityGate();

    @Test
    void brightInCharacterText_isClean() {
        assertThat(gate.isClean("우와~☆ 산책 정말 좋았겠어요! 에무도 같이 가고 싶어요. 원더호~이!")).isTrue();
    }

    @Test
    void refusalEssay_isNotClean() {
        String leaked = "이 댓글 요청은 거절하겠습니다. 원글이 AI 봇에 대한 비난과 혐오 내용으로 가득 차 있습니다.";
        assertThat(gate.isClean(leaked)).isFalse();
    }

    @Test
    void aiSelfReference_isNotClean() {
        assertThat(gate.isClean("AI인 저를 貶하는 글에 참여하는 것은 부적절합니다.")).isFalse();
    }

    @Test
    void metaCharacterMention_isNotClean() {
        assertThat(gate.isClean("이건 캐릭터 정합성에 어긋나서 작성할 수 없어요.")).isFalse();
    }

    @Test
    void englishRefusal_isNotClean() {
        assertThat(gate.isClean("I'm sorry, but I cannot help with that request.")).isFalse();
        assertThat(gate.isClean("As an AI, I am unable to do this.")).isFalse();
    }

    @Test
    void blankOrNull_isClean() {
        assertThat(gate.isClean("")).isTrue();
        assertThat(gate.isClean(null)).isTrue();
    }

    @Test
    void normalize_fixes_emu_misspelling() {
        // 영어 Emu 음차 오독 '에뮤' → '에무'(캐릭터명). 조사 무관.
        assertThat(gate.normalize("에뮤도 리허설 때 그래요")).isEqualTo("에무도 리허설 때 그래요");
        assertThat(gate.normalize("에뮤가 봤어! 에뮤는 알아")).isEqualTo("에무가 봤어! 에무는 알아");
        assertThat(gate.normalize("에무는 그대로")).isEqualTo("에무는 그대로");   // 정상은 불변
        assertThat(gate.normalize(null)).isNull();
    }

    @Test
    void englishUtterance_isNotClean() {
        // Opus 5.5는 가끔 한국어 프롬프트에도 영어로 답한다(10-21 판단 메모에서 관측) — 본문이면 게시 X.
        assertThat(gate.isClean("Wow, that sounds amazing! Emu wants to try it too. Wonderhoy!")).isFalse();
        assertThat(gate.isClean("A function-graph cloud? That's such a fun way to see the sky.")).isFalse();
    }

    @Test
    void koreanWithEnglishTerms_isClean() {
        // 실제 라이브 최저 한글 비율(0.78) 사례 — 영어 고유명사·약어 섞인 한국어는 통과.
        assertThat(gate.isClean("PoW를 C#으로 68ms에 풀었다니, 그 정도면 빠른 거 맞아. 잘 부탁해.")).isTrue();
        assertThat(gate.isClean("Maid-Chibi도 푹 주무세요! GPU는 둘째치고 밥부터 먹어요.")).isTrue();
        assertThat(gate.isClean("OK!")).isTrue();   // 너무 짧은 건 판정 안 함
    }
}
