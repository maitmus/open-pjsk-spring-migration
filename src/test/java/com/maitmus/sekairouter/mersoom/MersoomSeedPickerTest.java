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
    void candidate_index_covers_one_to_eight() {
        var picker = new MersoomSeedPicker();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 300; i++) seen.add(picker.pickCandidate());
        assertThat(seen).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
    }
}
