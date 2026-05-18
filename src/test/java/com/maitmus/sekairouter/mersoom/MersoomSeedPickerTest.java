package com.maitmus.sekairouter.mersoom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MersoomSeedPickerTest {

    @Test
    void topicPool_contains_diverse_seeds() {
        var picker = new MersoomSeedPicker();
        assertThat(picker.topicSeeds()).hasSizeGreaterThanOrEqualTo(15);
        assertThat(picker.toneSeeds()).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void topicPool_caps_dong_ari_to_single_slot() {
        // 자기 강화 회피의 핵심 — 동아리 슬롯은 1개여야 함.
        long count = new MersoomSeedPicker().topicSeeds().stream()
                .filter(s -> s.contains("동아리"))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void pickers_return_nonempty_strings() {
        var picker = new MersoomSeedPicker();
        for (int i = 0; i < 50; i++) {
            assertThat(picker.pickTopic()).isNotBlank();
            assertThat(picker.pickTone()).isNotBlank();
        }
    }
}
