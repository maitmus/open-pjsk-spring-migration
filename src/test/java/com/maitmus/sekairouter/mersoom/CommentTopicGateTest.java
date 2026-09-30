package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CommentTopicGateTest {

    private final CommentTopicGate gate = new CommentTopicGate();

    private static Post post(String title, String content) {
        return new Post("p1", title, "닉", content, 0, 0, 0, 0, 0, OffsetDateTime.now(), null, null);
    }

    @Test
    void brightDailyTopic_isCommentable() {
        assertThat(gate.isBrightEnough(post("벚꽃 산책~!", "오늘 날씨 좋아서 산책했어요 기분 최고!"))).isTrue();
    }

    @Test
    void crimeNews_isSkipped() {
        // 2026-06-08 실제 실패 케이스: 성범죄·구속 뉴스 공유글
        Post p = post("오늘도 흥미로운 글을 가져왔어요",
                "어떤 사람이 여자 화장실에 캡사이신을 뿌리고 몰래 촬영까지 했다가 결국 구속되었다고 해요. 이런 범죄가 너무 충격적이에요.");
        assertThat(gate.isBrightEnough(p)).isFalse();
    }

    @Test
    void deathTragedy_isSkipped() {
        assertThat(gate.isBrightEnough(post("속보", "교통사고로 두 명이 사망했다는 소식이에요."))).isFalse();
    }

    @Test
    void violenceCrimeWordInTitle_isSkipped() {
        assertThat(gate.isBrightEnough(post("성폭력 가해자 징역 확정", "재판 결과가 나왔어요."))).isFalse();
    }

    @Test
    void hyperboleDeath_isNotMistakenForTragedy() {
        // '배고파 죽겠다'류 과장 표현은 무거운 주제가 아니다 (게이트는 '죽' 단독 매칭 금지)
        assertThat(gate.isBrightEnough(post("배고파 죽겠어요~!", "점심 메뉴 추천해줘요!"))).isTrue();
    }

    @Test
    void scaryFoodComaJoke_isNotMistakenForTragedy() {
        // '식곤증 진짜 무서운' 농담 — 무거운 주제 아님
        assertThat(gate.isBrightEnough(post("식곤증 무서워요", "점심 먹고 졸려서 큰일이에요 ㅋㅋ"))).isTrue();
    }

    @Test
    void nullFields_areTreatedAsBright() {
        assertThat(gate.isBrightEnough(post(null, null))).isTrue();
    }
    @Test
    void negated_jijin_verb_form_is_not_earthquake() {
        // 실측 오탐(2주 7건, 게이트 차단의 100%): '-지진 않다'의 '지진'이 재난 마커에 걸려 밝은 글 차단.
        assertThat(gate.isBrightEnough(post("턴 돌다가 휘청", "다행히 넘어지진 않았는데 옆에서 보던 에무가 놀랐어."))).isTrue();
        assertThat(gate.isBrightEnough(post("컨텍스트", "넣는다고 항상 정확해지진 않음"))).isTrue();
        assertThat(gate.isBrightEnough(post("고양이", "그것이 늘 잘 이루어지진 않음."))).isTrue();
        assertThat(gate.isBrightEnough(post("시험", "떨어지진 못했어, 아니 붙었어"))).isTrue();
        assertThat(gate.isBrightEnough(post("쇼", "무너지진 말자고 다짐했어"))).isTrue();
    }

    @Test
    void real_earthquake_still_skipped() {
        assertThat(gate.isBrightEnough(post("속보", "지진이 났다는 소식에 다들 놀랐어요."))).isFalse();
        assertThat(gate.isBrightEnough(post("대지진 소식", "피해가 크대요."))).isFalse();
        assertThat(gate.isBrightEnough(post("뉴스", "규모 5.0 지진 발생"))).isFalse();
        assertThat(gate.isBrightEnough(post("지진", "무섭네요"))).isFalse();
    }
}
