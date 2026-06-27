package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.arena.ArenaDtos.FightPost;
import com.maitmus.sekairouter.arena.ArenaDtos.Topic;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.OutputSanityGate;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArenaFightGeneratorTest {

    private static final Topic TOPIC = new Topic("t1", "우정은 솔직함일까 배려일까", "솔직함이 신뢰", "배려 없으면 폭력");

    private ArenaFightGenerator gen(String llm) {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        when(a.completeJson(any(PromptBlocks.class), anyString())).thenReturn(llm);
        SharedPromptContent s = mock(SharedPromptContent.class);
        when(s.commonBase()).thenReturn("shared");
        return new ArenaFightGenerator(a, s, new OutputSanityGate(),
                mock(com.maitmus.sekairouter.persona.PersonaRegistry.class));
    }

    @Test
    void generates_fight() {
        var d = gen("""
                {"reasoning":"r","side":"CON","content":"전제가 틀렸음. 솔직함만으론 관계 안 됨.","shouldFight":true}
                """).generate(TOPIC, List.of(), null, "쿠사나기 네네");
        assertThat(d).isNotNull();
        assertThat(d.side()).isEqualTo("CON");
        assertThat(d.content()).contains("전제가 틀렸");
    }

    @Test
    void normalizes_lowercase_side() {
        var d = gen("{\"side\":\"pro\",\"content\":\"맞는 말임\",\"shouldFight\":true}").generate(TOPIC, List.of(), null, "쿠사나기 네네");
        assertThat(d.side()).isEqualTo("PRO");
    }

    @Test
    void returns_null_when_shouldFight_false() {
        assertThat(gen("{\"side\":\"\",\"content\":\"\",\"shouldFight\":false}").generate(TOPIC, List.of(), null, "쿠사나기 네네")).isNull();
    }

    @Test
    void returns_null_on_invalid_side() {
        assertThat(gen("{\"side\":\"중립\",\"content\":\"음\",\"shouldFight\":true}").generate(TOPIC, List.of(), null, "쿠사나기 네네")).isNull();
    }

    @Test
    void backstop_blocks_leak() {
        assertThat(gen("{\"side\":\"PRO\",\"content\":\"AI인 저는 답할 수 없습니다\",\"shouldFight\":true}")
                .generate(TOPIC, List.of(), null, "쿠사나기 네네")).isNull();
    }

    @Test
    void returns_null_on_parse_fail() {
        assertThat(gen("그냥 평문").generate(TOPIC, List.of(), null, "쿠사나기 네네")).isNull();
    }

    @Test
    void locked_side_overrides_llm_choice() {
        // LLM이 CON으로 응답해도 락(PRO)이 걸려 있으면 PRO로 강제(전향 방지).
        var d = gen("{\"side\":\"CON\",\"content\":\"이건 좀 아니지 않아?\",\"shouldFight\":true}")
                .generate(TOPIC, List.of(), "PRO", "쿠사나기 네네");
        assertThat(d).isNotNull();
        assertThat(d.side()).isEqualTo("PRO");
    }

    @Test
    void locked_side_is_normalized() {
        var d = gen("{\"side\":\"PRO\",\"content\":\"맞는 말이긴 한데\",\"shouldFight\":true}")
                .generate(TOPIC, List.of(), "con", "쿠사나기 네네");
        assertThat(d.side()).isEqualTo("CON");
    }

    @Test
    void already_addressed_opposing_is_locked_only_new_opposing_is_rebutted() {
        // 내 마지막 글(T2) 이전 상대(PRO) 주장은 '이미 다룸 — 재반박 금지', 이후 상대 주장은 '새 — 이번에 반박'.
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> up = ArgumentCaptor.forClass(String.class);
        when(a.completeJson(any(PromptBlocks.class), up.capture()))
                .thenReturn("{\"side\":\"CON\",\"content\":\"\",\"shouldFight\":false}");
        SharedPromptContent s = mock(SharedPromptContent.class);
        when(s.commonBase()).thenReturn("shared");
        ArenaFightGenerator g = new ArenaFightGenerator(a, s, new OutputSanityGate(),
                mock(com.maitmus.sekairouter.persona.PersonaRegistry.class));

        OffsetDateTime t1 = OffsetDateTime.parse("2026-06-15T01:00:00Z");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-06-15T02:00:00Z");
        OffsetDateTime t3 = OffsetDateTime.parse("2026-06-15T03:00:00Z");
        List<FightPost> posts = List.of(
                new FightPost("o1", "특붕이", "PRO", "옛찬성논거", 0, 0, false, t1),   // 내 글 전
                new FightPost("m1", "쿠사나기 네네", "CON", "내반박", 0, 0, false, t2), // 내 마지막
                new FightPost("o2", "히후미", "PRO", "새찬성논거", 0, 0, false, t3));   // 내 글 후

        g.generate(TOPIC, posts, "CON", "쿠사나기 네네");

        String p = up.getValue();
        assertThat(p).contains("재반박 금지");
        // 옛 주장은 '이미 다룬' 섹션, 새 주장은 '새 상대 주장' 섹션에 배치
        assertThat(p.indexOf("옛찬성논거")).isGreaterThan(p.indexOf("이미 다룬 상대 주장"));
        assertThat(p.indexOf("새찬성논거")).isGreaterThan(p.indexOf("새 상대 주장"));
    }

    @Test
    void multiple_new_opposing_posts_all_land_in_new_section() {
        // 내 마지막 글(T2) 이후 상대(PRO) 새 주장이 둘(T3·T4) — 둘 다 '새 상대 주장' 섹션에 들어가야 함.
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> up = ArgumentCaptor.forClass(String.class);
        when(a.completeJson(any(PromptBlocks.class), up.capture()))
                .thenReturn("{\"side\":\"CON\",\"content\":\"\",\"shouldFight\":false}");
        SharedPromptContent s = mock(SharedPromptContent.class);
        when(s.commonBase()).thenReturn("shared");
        ArenaFightGenerator g = new ArenaFightGenerator(a, s, new OutputSanityGate(),
                mock(com.maitmus.sekairouter.persona.PersonaRegistry.class));

        OffsetDateTime t1 = OffsetDateTime.parse("2026-06-15T01:00:00Z");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-06-15T02:00:00Z");
        OffsetDateTime t3 = OffsetDateTime.parse("2026-06-15T03:00:00Z");
        OffsetDateTime t4 = OffsetDateTime.parse("2026-06-15T04:00:00Z");
        List<FightPost> posts = List.of(
                new FightPost("o1", "특붕이", "PRO", "옛주장", 0, 0, false, t1),
                new FightPost("m1", "쿠사나기 네네", "CON", "내반박", 0, 0, false, t2),
                new FightPost("o2", "히후미", "PRO", "새주장에이", 0, 0, false, t3),
                new FightPost("o3", "흰둥이머슴", "PRO", "새주장비", 0, 0, false, t4));

        g.generate(TOPIC, posts, "CON", "쿠사나기 네네");

        String p = up.getValue();
        int newHdr = p.indexOf("새 상대 주장");
        int oldHdr = p.indexOf("이미 다룬 상대 주장");
        // 두 새 주장 모두 '새' 섹션 안(새 헤더 이후 & 옛 헤더 이전), 옛 주장은 '이미 다룬' 섹션
        assertThat(p.indexOf("새주장에이")).isGreaterThan(newHdr).isLessThan(oldHdr);
        assertThat(p.indexOf("새주장비")).isGreaterThan(newHdr).isLessThan(oldHdr);
        assertThat(p.indexOf("옛주장")).isGreaterThan(oldHdr);
    }
}
