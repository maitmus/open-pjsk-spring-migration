package com.maitmus.sekairouter.routing;

import com.maitmus.sekairouter.persona.CharacterId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RandomCharacterSelectorTest {

    @Test
    void selectsAnyCharacter_whenNoExclusion() {
        RandomCharacterSelector selector = new RandomCharacterSelector(new Random(42L));

        CharacterId picked = selector.pickOne(null);

        assertThat(picked).isIn((Object[]) CharacterId.values());
    }

    @Test
    void excludesLastSpeaker() {
        RandomCharacterSelector selector = new RandomCharacterSelector(new Random(42L));

        Set<CharacterId> picks = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            picks.add(selector.pickOne(CharacterId.EMU));
        }

        assertThat(picks).doesNotContain(CharacterId.EMU);
        assertThat(picks).hasSize(6);  // 7 - 1
    }

    @Test
    void shuffleAll_returnsAllSeven() {
        RandomCharacterSelector selector = new RandomCharacterSelector(new Random(42L));

        var shuffled = selector.shuffleAll();

        assertThat(shuffled).hasSize(7).containsAll(java.util.Arrays.asList(CharacterId.values()));
    }
}
