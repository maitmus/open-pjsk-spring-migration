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
    void seed_variant_scans_seed_and_uses_seed_wording() {
        // 글 시드에 PJSK 인물이 맨이름으로 나오면 페르소나 호칭으로 교정하는 힌트
        String h = PjskAddressBook.hintForSeed(CharacterId.EMU, "또 토우야한테 졌다");
        assertThat(h).contains("토우야군").contains("시드가").doesNotContain("원글이");
        assertThat(PjskAddressBook.hintForSeed(CharacterId.NENE, "루이랑 무대 준비")).contains("루이");
        assertThat(PjskAddressBook.hintForSeed(CharacterId.EMU, "라벤더 차 향 좋더라")).isEmpty();
    }
}
