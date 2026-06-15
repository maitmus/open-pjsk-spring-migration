package com.maitmus.sekairouter.heartbeat;

import com.maitmus.sekairouter.persona.PersonaType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatSeedPickerTest {

    private final HeartbeatSeedPicker picker = new HeartbeatSeedPicker();

    @Test
    void topicPools_nonEmpty_forBothTypes_andBothDayKinds() {
        for (boolean weekend : new boolean[]{false, true}) {
            for (PersonaType type : PersonaType.values()) {
                assertThat(picker.topicSeedsFor(type, weekend))
                        .isNotEmpty()
                        .allSatisfy(s -> assertThat(s).isNotBlank());
            }
        }
    }

    @Test
    void dialoguePatternPool_nonEmptyAndAllNonBlank() {
        var patterns = picker.dialoguePatterns();
        assertThat(patterns).isNotEmpty();
        assertThat(patterns).allSatisfy(p -> assertThat(p).isNotBlank());
    }

    @Test
    void pickTopic_returnsItemFromTypePool() {
        for (boolean weekend : new boolean[]{false, true}) {
            for (PersonaType type : PersonaType.values()) {
                var pool = Set.copyOf(picker.topicSeedsFor(type, weekend));
                for (int i = 0; i < 50; i++) {
                    assertThat(pool).contains(picker.pickTopic(type, weekend));
                }
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
        for (boolean weekend : new boolean[]{false, true}) {
            for (PersonaType type : PersonaType.values()) {
                Set<String> observed = new HashSet<>();
                for (int i = 0; i < 200; i++) {
                    observed.add(picker.pickTopic(type, weekend));
                }
                // Sanity: at least half the pool observed across 200 picks
                assertThat(observed.size()).isGreaterThanOrEqualTo(picker.topicSeedsFor(type, weekend).size() / 2);
            }
        }
    }

    @Test
    void virtualSinger_pool_excludesIncompatibleHumanOnlySeeds() {
        // VS 풀에는 인간 사회 신분 영역 시드가 절대 들어가면 안 됨 (reasoning leak 재발 방지) — 평일·주말 모두
        for (boolean weekend : new boolean[]{false, true}) {
            var vsPool = Set.copyOf(picker.topicSeedsFor(PersonaType.VIRTUAL_SINGER, weekend));
            assertThat(vsPool)
                    .noneMatch(s -> s.contains("동아리 활동"))
                    .noneMatch(s -> s.contains("어린 시절"))
                    .noneMatch(s -> s.contains("잠·꿈"))
                    .noneMatch(s -> s.contains("선후배·가족"))
                    .noneMatch(s -> s.contains("옷·헤어·소품"));
        }
    }

    @Test
    void weekday_and_weekend_topicPools_differ_forHumanSekai() {
        var weekday = Set.copyOf(picker.topicSeedsFor(PersonaType.HUMAN_SEKAI, false));
        var weekend = Set.copyOf(picker.topicSeedsFor(PersonaType.HUMAN_SEKAI, true));

        // 학교 일상 시드(동아리)는 평일에만, 주말엔 없음
        assertThat(weekday).anyMatch(s -> s.contains("동아리 활동"));
        assertThat(weekend).noneMatch(s -> s.contains("동아리 활동"));

        // 주말 여가 시드(나들이/늦잠)는 주말에만, 평일엔 없음
        assertThat(weekend).anyMatch(s -> s.contains("나들이") || s.contains("늦잠"));
        assertThat(weekday).noneMatch(s -> s.contains("나들이") || s.contains("늦잠"));

        // 공통 시드(어린 시절·음식)는 양쪽 모두에
        assertThat(weekday).anyMatch(s -> s.contains("어린 시절"));
        assertThat(weekend).anyMatch(s -> s.contains("어린 시절"));
    }
}
