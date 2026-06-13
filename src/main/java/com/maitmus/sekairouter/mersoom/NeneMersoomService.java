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
 * 머슴 네네 시민 스케줄러 — 에무와 동급의 글/댓글 사회 시민(별도 계정·state·네네 페르소나).
 * cron은 에무와 엇갈리게(:45 댓글, 11:00/18:00 글). enabled=mersoom.nene.enabled(기본 false).
 * 흐름은 {@link MersoomCitizenEngine}을 네네 프로필로 호출.
 */
@Slf4j
@Service
public class NeneMersoomService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 머슴 피드 닉네임은 짧게 "네네" (에무가 "에무" 쓰는 것과 동일 결). 아레나는 "쿠사나기 네네"(풀네임) 별도.
    private static final String NICKNAME = "네네";

    private final MersoomProperties properties;
    private final MersoomCitizenEngine engine;
    private final Clock clock;
    private final Object lock = new Object();

    public NeneMersoomService(MersoomProperties properties, MersoomCitizenEngine engine, Clock clock) {
        this.properties = properties;
        this.engine = engine;
        this.clock = clock;
    }

    private boolean neneEnabled() {
        return properties.nene() != null && properties.nene().enabled()
                && properties.nene().auth() != null
                && properties.nene().auth().authId() != null && !properties.nene().auth().authId().isBlank()
                && properties.nene().stateFile() != null && !properties.nene().stateFile().isBlank();
    }

    /** 네네 프로필 — 네네 계정·state·페르소나, 형제 봇=에무 auth_id DOWN 무마. */
    private CitizenProfile profile() {
        Set<String> sibling = (properties.auth() != null && properties.auth().authId() != null
                && !properties.auth().authId().isBlank())
                ? Set.of(properties.auth().authId())
                : Set.of();
        return new CitizenProfile("nene", NICKNAME, properties.nene().auth(),
                Paths.get(properties.nene().stateFile()), CharacterId.NENE, sibling);
    }

    @Scheduled(cron = "${mersoom.nene.post-cron:0 0 11,18 * * *}", zone = "Asia/Seoul")
    public void executePost() {
        if (!neneEnabled()) return;
        if (!isActiveHour()) {
            log.warn("Nene mersoom post triggered outside active hours, skip");
            return;
        }
        synchronized (lock) {
            engine.runPost(profile());
        }
    }

    @Scheduled(cron = "${mersoom.nene.comment-cron:0 45 9-19 * * *}", zone = "Asia/Seoul")
    public void executeComment() {
        if (!neneEnabled()) return;
        if (!isActiveHour()) {
            log.warn("Nene mersoom comment triggered outside active hours, skip");
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
