package com.maitmus.sekairouter.proxy;

import com.maitmus.sekairouter.persona.CharacterId;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class LastSpeakerStore {

    private final ConcurrentMap<String, CharacterId> store = new ConcurrentHashMap<>();

    public void record(String channelId, CharacterId speaker) {
        store.put(channelId, speaker);
    }

    public Optional<CharacterId> get(String channelId) {
        return Optional.ofNullable(store.get(channelId));
    }
}
