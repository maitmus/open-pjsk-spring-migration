package com.maitmus.sekairouter.heartbeat;

import com.maitmus.sekairouter.persona.CharacterId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatStateStoreTest {

    private final HeartbeatStateStore store = new HeartbeatStateStore();

    @Test
    void defaultThreshold_isMinusOne() {
        assertThat(store.getThreshold()).isEqualTo(-1);
    }

    @Test
    void setAndGetThreshold() {
        store.setThreshold(42);
        assertThat(store.getThreshold()).isEqualTo(42);
    }

    @Test
    void markFired_setsThresholdTo999() {
        store.setThreshold(30);
        store.markFired();
        assertThat(store.getThreshold()).isEqualTo(999);
    }

    @Test
    void resetThresholdForHour_overwritesFiredState() {
        store.markFired();
        assertThat(store.getThreshold()).isEqualTo(999);

        store.resetThresholdForHour(15);
        assertThat(store.getThreshold()).isEqualTo(15);
    }

    @Test
    void lastSpeaker_emptyInitially() {
        assertThat(store.lastSpeaker()).isEmpty();
    }

    @Test
    void recordAndGetLastSpeaker() {
        store.recordLastSpeaker(CharacterId.EMU);
        assertThat(store.lastSpeaker()).contains(CharacterId.EMU);
    }

    @Test
    void lastSpeaker_overwrittenOnSecondRecord() {
        store.recordLastSpeaker(CharacterId.EMU);
        store.recordLastSpeaker(CharacterId.NENE);
        assertThat(store.lastSpeaker()).contains(CharacterId.NENE);
    }

    @Test
    void recentUtterances_emptyInitially() {
        assertThat(store.recentUtterances()).isEmpty();
    }

    @Test
    void recordUtterance_preservesInsertionOrderOldestFirst() {
        store.recordUtterance(CharacterId.EMU, "first");
        store.recordUtterance(CharacterId.NENE, "second");
        store.recordUtterance(CharacterId.AIRI, "third");

        var list = store.recentUtterances();
        assertThat(list).hasSize(3);
        assertThat(list.get(0).text()).isEqualTo("first");
        assertThat(list.get(0).speaker()).isEqualTo(CharacterId.EMU);
        assertThat(list.get(2).text()).isEqualTo("third");
        assertThat(list.get(2).speaker()).isEqualTo(CharacterId.AIRI);
    }

    @Test
    void recordUtterance_evictsOldestWhenOverCapacity() {
        // capacity = 12 — push 15 to overflow
        for (int i = 0; i < 15; i++) {
            store.recordUtterance(CharacterId.MIKU, "u" + i);
        }
        var list = store.recentUtterances();
        assertThat(list).hasSize(12);
        // oldest survivor is u3 (u0/u1/u2 evicted), newest is u14
        assertThat(list.get(0).text()).isEqualTo("u3");
        assertThat(list.get(11).text()).isEqualTo("u14");
    }

    @Test
    void recentUtterances_returnsSnapshot_mutationsDoNotAffectStore() {
        store.recordUtterance(CharacterId.EMU, "x");
        var snapshot = store.recentUtterances();
        assertThat(snapshot).hasSize(1);
        // snapshot is an independent List; clearing it shouldn't affect store
        snapshot.clear();
        assertThat(store.recentUtterances()).hasSize(1);
    }
}
