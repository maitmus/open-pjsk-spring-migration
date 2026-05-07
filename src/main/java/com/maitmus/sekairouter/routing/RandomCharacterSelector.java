package com.maitmus.sekairouter.routing;

import com.maitmus.sekairouter.persona.CharacterId;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Component
public class RandomCharacterSelector {

    private final Random random;

    public RandomCharacterSelector() {
        this.random = null;  // Production: ThreadLocalRandom 사용
    }

    // 테스트용 — seed 고정 가능
    RandomCharacterSelector(Random random) {
        this.random = random;
    }

    public CharacterId pickOne(CharacterId exclude) {
        List<CharacterId> pool = Arrays.stream(CharacterId.values())
                .filter(c -> c != exclude)
                .collect(Collectors.toList());
        int idx = (random != null ? random.nextInt(pool.size())
                                  : ThreadLocalRandom.current().nextInt(pool.size()));
        return pool.get(idx);
    }

    public List<CharacterId> shuffleAll() {
        List<CharacterId> all = new java.util.ArrayList<>(Arrays.asList(CharacterId.values()));
        if (random != null) {
            Collections.shuffle(all, random);
        } else {
            Collections.shuffle(all, ThreadLocalRandom.current());
        }
        return all;
    }
}
