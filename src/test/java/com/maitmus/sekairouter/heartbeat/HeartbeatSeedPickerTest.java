package com.maitmus.sekairouter.heartbeat;

import com.maitmus.sekairouter.persona.PersonaType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatSeedPickerTest {

    private final HeartbeatSeedPicker picker = new HeartbeatSeedPicker();

    @Test
    void topicPools_nonEmptyForBothTypes() {
        assertThat(picker.topicSeedsFor(PersonaType.HUMAN_SEKAI))
                .isNotEmpty()
                .allSatisfy(s -> assertThat(s).isNotBlank());
        assertThat(picker.topicSeedsFor(PersonaType.VIRTUAL_SINGER))
                .isNotEmpty()
                .allSatisfy(s -> assertThat(s).isNotBlank());
    }

    @Test
    void dialoguePatternPool_nonEmptyAndAllNonBlank() {
        var patterns = picker.dialoguePatterns();
        assertThat(patterns).isNotEmpty();
        assertThat(patterns).allSatisfy(p -> assertThat(p).isNotBlank());
    }

    @Test
    void pickTopic_returnsItemFromTypePool() {
        for (PersonaType type : PersonaType.values()) {
            var pool = Set.copyOf(picker.topicSeedsFor(type));
            for (int i = 0; i < 50; i++) {
                assertThat(pool).contains(picker.pickTopic(type));
            }
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
        for (PersonaType type : PersonaType.values()) {
            Set<String> observed = new HashSet<>();
            for (int i = 0; i < 200; i++) {
                observed.add(picker.pickTopic(type));
            }
            // Sanity: at least half the pool observed across 200 picks
            assertThat(observed.size()).isGreaterThanOrEqualTo(picker.topicSeedsFor(type).size() / 2);
        }
    }

    @Test
    void virtualSinger_pool_excludesIncompatibleHumanOnlySeeds() {
        // VS 풀에는 인간 사회 신분 영역 시드가 절대 들어가면 안 됨 (19:55 reasoning leak 재발 방지)
        var vsPool = Set.copyOf(picker.topicSeedsFor(PersonaType.VIRTUAL_SINGER));
        assertThat(vsPool)
                .noneMatch(s -> s.contains("동아리 활동"))
                .noneMatch(s -> s.contains("어린 시절"))
                .noneMatch(s -> s.contains("잠·꿈"))
                .noneMatch(s -> s.contains("선후배·가족"))
                .noneMatch(s -> s.contains("옷·헤어·소품"));
    }

    @Test
    void humanSekai_pool_includesDailyLifeSeeds() {
        // 인간 풀은 기존 일상 시드 그대로 사용 가능
        var humanPool = Set.copyOf(picker.topicSeedsFor(PersonaType.HUMAN_SEKAI));
        assertThat(humanPool)
                .anyMatch(s -> s.contains("동아리 활동"))
                .anyMatch(s -> s.contains("어린 시절"));
    }
}
