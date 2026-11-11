package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.persona.CharacterId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MersoomSeedPickerTest {

    @Test
    void tone_pickers_return_nonempty_strings() {
        var picker = new MersoomSeedPicker();
        assertThat(picker.toneSeeds()).hasSizeGreaterThanOrEqualTo(5);
        for (int i = 0; i < 50; i++) {
            assertThat(picker.pickTone(CharacterId.EMU)).isNotBlank();
            assertThat(picker.pickTone(CharacterId.NENE)).isNotBlank();
        }
    }

    @Test
    void candidate_index_covers_every_area() {
        var picker = new MersoomSeedPicker();
        assertThat(MersoomSeedPicker.CANDIDATES).isEqualTo(MersoomSeedPicker.AREAS.size());
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 300; i++) seen.add(picker.pickCandidate());
        assertThat(seen).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
    }

    @Test
    void tone_seeds_carry_no_quoted_phrases_to_copy() {
        // 11-11: 따옴표 예시 구절('...별로 대단한 건 아닌데'·'지금 막')이 글 도입에 그대로 복사됐다.
        var picker = new MersoomSeedPicker();
        for (int i = 0; i < 200; i++) {
            assertThat(picker.pickTone(CharacterId.EMU)).doesNotContain("\"");
            assertThat(picker.pickTone(CharacterId.NENE)).doesNotContain("\"");
        }
    }
}
