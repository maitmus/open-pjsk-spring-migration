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
}
