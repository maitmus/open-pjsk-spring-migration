package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.persona.CharacterId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PjskAddressBookTest {

    @Test
    void emu_uses_first_name_suffix_and_jondaemal_for_seniors() {
        String h = PjskAddressBook.hintFor(CharacterId.EMU, "루이한테 얘기했더니 어깨 힘 빼라더라");
        assertThat(h).contains("루이군").doesNotContain("존댓말");   // 루이=루이군(반말)
        assertThat(PjskAddressBook.hintFor(CharacterId.EMU, "토우야랑 붙었어")).contains("토우야군");
        assertThat(PjskAddressBook.hintFor(CharacterId.EMU, "이치카 신곡 좋더라")).contains("이치카쨩");
        assertThat(PjskAddressBook.hintFor(CharacterId.EMU, "시즈쿠랑 얘기했어")).contains("히노모리 선배").contains("존댓말");
    }

    @Test
    void nene_uses_surname_form() {
        assertThat(PjskAddressBook.hintFor(CharacterId.NENE, "토우야랑 붙었어")).contains("아오야기군");
        assertThat(PjskAddressBook.hintFor(CharacterId.NENE, "이치카 신곡 좋더라")).contains("호시노 씨");
        assertThat(PjskAddressBook.hintFor(CharacterId.NENE, "루이한테 물어봤어")).contains("루이");  // 유닛-내 맨이름
    }

    @Test
    void no_hint_when_no_pjsk_name() {
        assertThat(PjskAddressBook.hintFor(CharacterId.EMU, "오늘 라벤더 차 마셨어 향 좋더라")).isEmpty();
    }

    @Test
    void findBareLeaks_detects_bare_name_only_when_persona_has_distinct_call() {
        // 에무는 루이를 '루이군'으로 부름 → 맨이름 '루이'는 누수
        assertThat(PjskAddressBook.findBareLeaks(CharacterId.EMU, "오늘 루이랑 연습했어요"))
                .containsEntry("루이", "루이군");
        // 호칭이 이미 있으면 누수 아님
        assertThat(PjskAddressBook.findBareLeaks(CharacterId.EMU, "오늘 루이군이랑 연습했어요")).isEmpty();
        // 호칭+맨이름 섞이면 맨이름만 누수로
        assertThat(PjskAddressBook.findBareLeaks(CharacterId.EMU, "루이군이랑 하다가 루이가 도와줬어"))
                .containsEntry("루이", "루이군");
        // 네네는 루이를 맨이름으로 부름(유닛-내) → 누수 아님
        assertThat(PjskAddressBook.findBareLeaks(CharacterId.NENE, "루이랑 연습")).isEmpty();
        // 성-기반 호칭(에무→시즈쿠='히노모리 선배'): 맨이름 '시즈쿠'는 누수
        assertThat(PjskAddressBook.findBareLeaks(CharacterId.EMU, "시즈쿠랑 얘기했어요")).containsEntry("시즈쿠", "히노모리 선배");
        assertThat(PjskAddressBook.findBareLeaks(CharacterId.EMU, "히노모리 선배랑 얘기했어요")).isEmpty();
        // PJSK 인물 없음
        assertThat(PjskAddressBook.findBareLeaks(CharacterId.EMU, "라벤더 차 마셨어요")).isEmpty();
    }

    @Test
    void seed_variant_scans_seed_and_uses_seed_wording() {
        // 글 시드에 PJSK 인물이 맨이름으로 나오면 페르소나 호칭으로 교정하는 힌트
        String h = PjskAddressBook.hintForSeed(CharacterId.EMU, "또 토우야한테 졌다");
        assertThat(h).contains("토우야군").contains("시드가").doesNotContain("원글이");
        assertThat(PjskAddressBook.hintForSeed(CharacterId.NENE, "루이랑 무대 준비")).contains("루이");
        assertThat(PjskAddressBook.hintForSeed(CharacterId.EMU, "라벤더 차 향 좋더라")).isEmpty();
    }
}
