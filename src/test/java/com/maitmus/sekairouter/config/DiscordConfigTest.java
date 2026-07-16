package com.maitmus.sekairouter.config;

import com.maitmus.sekairouter.persona.CharacterId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordConfigTest {

    @Test
    void characterJdas_empty_when_disabled_without_touching_discord() throws Exception {
        // discord.enabled=false면 JDABuilder(→discord.com DNS·awaitReady)를 안 탄다.
        // 실 Discord 없이 이 테스트가 통과 = 초기화 스킵 증명(활성이면 JDABuilder가 실 토큰으로 실패/행).
        var props = new DiscordProperties("router-tok", "chan", Map.of(CharacterId.EMU, "tok"), false);
        var cfg = new DiscordConfig(props);
        assertThat(cfg.characterJdas()).isEmpty();
    }

    @Test
    void enabled_defaults_true_when_absent() {
        // 미지정(null)=활성 — 기존 동작 유지(플래그 안 주면 예전처럼 JDA 붙음).
        assertThat(new DiscordProperties("t", "c", Map.of(), null).enabled()).isTrue();
        assertThat(new DiscordProperties("t", "c", Map.of(), false).enabled()).isFalse();
    }
}
