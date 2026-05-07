package com.maitmus.sekairouter.proxy;

import com.maitmus.sekairouter.persona.CharacterId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LastSpeakerStoreTest {

    private final LastSpeakerStore store = new LastSpeakerStore();

    @Test
    void recordAndGet() {
        store.record("ch1", CharacterId.EMU);

        assertThat(store.get("ch1")).contains(CharacterId.EMU);
    }

    @Test
    void getEmpty_whenNotRecorded() {
        assertThat(store.get("ch1")).isEmpty();
    }

    @Test
    void perChannelIsolation() {
        store.record("ch1", CharacterId.EMU);
        store.record("ch2", CharacterId.NENE);

        assertThat(store.get("ch1")).contains(CharacterId.EMU);
        assertThat(store.get("ch2")).contains(CharacterId.NENE);
    }
}
