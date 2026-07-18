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
}
