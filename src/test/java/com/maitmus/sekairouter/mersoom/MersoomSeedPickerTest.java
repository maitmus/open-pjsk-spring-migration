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

    @Test
    void pickTopic_avoids_recently_picked_within_window() {
        // 동적 시드: 최근 6개 윈도우 내에서 같은 토픽을 다시 뽑지 않는다(복원추출 편중 완화).
        // 연속 7개 픽에서, 각 픽은 직전 6개와 달라야 함 → 슬라이딩 윈도우로 검사.
        for (var persona : new com.maitmus.sekairouter.persona.CharacterId[]{
                com.maitmus.sekairouter.persona.CharacterId.EMU, com.maitmus.sekairouter.persona.CharacterId.NENE}) {
            var picker = new MersoomSeedPicker();   // 새 인스턴스(윈도우 초기화)
            java.util.List<String> seq = new java.util.ArrayList<>();
            for (int i = 0; i < 30; i++) seq.add(picker.pickTopic(persona));
            for (int i = 6; i < seq.size(); i++) {
                assertThat(seq.subList(i - 6, i))
                        .as("픽 %d은 직전 6개와 겹치면 안 됨", i)
                        .doesNotContain(seq.get(i));
            }
        }
    }
}
