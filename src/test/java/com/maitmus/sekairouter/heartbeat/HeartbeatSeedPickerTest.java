package com.maitmus.sekairouter.heartbeat;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatSeedPickerTest {

    private final HeartbeatSeedPicker picker = new HeartbeatSeedPicker();

    @Test
    void topicPool_nonEmptyAndAllNonBlank() {
        var seeds = picker.topicSeeds();
        assertThat(seeds).isNotEmpty();
        assertThat(seeds).allSatisfy(s -> assertThat(s).isNotBlank());
    }

    @Test
    void dialoguePatternPool_nonEmptyAndAllNonBlank() {
        var patterns = picker.dialoguePatterns();
        assertThat(patterns).isNotEmpty();
        assertThat(patterns).allSatisfy(p -> assertThat(p).isNotBlank());
    }

    @Test
    void pickTopic_returnsItemFromPool() {
        var pool = Set.copyOf(picker.topicSeeds());
        for (int i = 0; i < 50; i++) {
            assertThat(pool).contains(picker.pickTopic());
        }
    }

    @Test
    void pickDialoguePattern_returnsItemFromPool() {
        var pool = Set.copyOf(picker.dialoguePatterns());
        for (int i = 0; i < 50; i++) {
            assertThat(pool).contains(picker.pickDialoguePattern());
        }
    }

    @Test
    void pickTopic_diverseAcrossManyCalls() {
        // Over 200 calls we should see at least half of the pool — basic randomness sanity
        Set<String> observed = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            observed.add(picker.pickTopic());
        }
        assertThat(observed.size()).isGreaterThanOrEqualTo(picker.topicSeeds().size() / 2);
    }
}
