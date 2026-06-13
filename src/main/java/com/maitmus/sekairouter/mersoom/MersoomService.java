package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.persona.CharacterId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * 머슴 에무 시민 스케줄러 — cron 트리거 + enabled/활성시간 게이트. 실제 흐름은 {@link MersoomCitizenEngine}.
 * 네네는 {@link NeneMersoomService}가 같은 엔진을 자기 프로필로 호출한다.
 */
@Slf4j
@Service
public class MersoomService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String NICKNAME = "에무";

    private final MersoomProperties properties;
    private final MersoomCitizenEngine engine;
    private final Clock clock;
    private final Object lock = new Object();

    public MersoomService(MersoomProperties properties, MersoomCitizenEngine engine, Clock clock) {
        this.properties = properties;
        this.engine = engine;
        this.clock = clock;
    }

    /** 에무 프로필 — 기본 계정·state, 형제 봇=네네 auth_id(있으면) DOWN 무마. */
    private CitizenProfile profile() {
        Set<String> sibling = (properties.nene() != null && properties.nene().auth() != null
                && properties.nene().auth().authId() != null && !properties.nene().auth().authId().isBlank())
                ? Set.of(properties.nene().auth().authId())
                : Set.of();
        return new CitizenProfile("emu", NICKNAME, properties.auth(),
                Paths.get(properties.stateFile()), CharacterId.EMU, sibling);
    }

    @Scheduled(cron = "${mersoom.post-cron}", zone = "Asia/Seoul")
    public void executePost() {
        if (!properties.enabled()) return;
        if (!isActiveHour()) {
            log.warn("Mersoom post triggered outside active hours, skip");
            return;
        }
        synchronized (lock) {
            engine.runPost(profile());
        }
    }

    @Scheduled(cron = "${mersoom.comment-cron}", zone = "Asia/Seoul")
    public void executeComment() {
        if (!properties.enabled()) return;
        if (!isActiveHour()) {
            log.warn("Mersoom comment triggered outside active hours, skip");
            return;
        }
        synchronized (lock) {
            engine.runComment(profile());
        }
    }

    private boolean isActiveHour() {
        int h = LocalTime.now(clock.withZone(KST)).getHour();
        return h >= 9 && h <= 19;
    }
}
