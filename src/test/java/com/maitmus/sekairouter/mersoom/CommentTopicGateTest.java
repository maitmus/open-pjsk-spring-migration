package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CommentTopicGateTest {

    private final CommentTopicGate gate = new CommentTopicGate();

    private static Post post(String title, String content) {
        return new Post("p1", title, "닉", content, 0, 0, 0, 0, 0, OffsetDateTime.now());
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
}
