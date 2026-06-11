package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.arena.ArenaDtos.FightPost;
import com.maitmus.sekairouter.arena.ArenaDtos.StatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 아레나 토론 — 에무 발의(PROPOSE) + 쿠사나기 네네 토론(BATTLE).
 * 머슴 활성시간 게이트와 무관하게 자체 cron(발의 08:30 / 토론 13·17시). 페이즈는 status로 확인.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArenaService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ArenaProperties properties;
    private final ArenaApiClient api;
    private final ArenaProposeGenerator proposeGenerator;
    private final ArenaFightGenerator fightGenerator;
    private final Clock clock;
    private final Object lock = new Object();

    @Scheduled(cron = "${arena.propose-cron}", zone = "Asia/Seoul")
    public void executePropose() {
        if (!properties.enabled()) return;
        synchronized (lock) {
            doPropose();
        }
    }

    @Scheduled(cron = "${arena.fight-cron}", zone = "Asia/Seoul")
    public void executeFight() {
        if (!properties.enabled()) return;
        synchronized (lock) {
            doFight();
        }
    }

    private void doPropose() {
        StatusResponse status = safeStatus();
        if (status == null || !"PROPOSE".equalsIgnoreCase(status.phase())) {
            log.info("Arena propose skip — phase={}", status == null ? null : status.phase());
            return;
        }
        var topic = proposeGenerator.generate();
        if (topic == null) {
            log.info("Arena propose skip — 생성 보류");
            return;
        }
        try {
            var resp = api.propose(properties.propose(), topic.title(), topic.pros(), topic.cons());
            log.info("Arena propose created: success={} title='{}'", resp != null && resp.success(), topic.title());
        } catch (Exception e) {
            log.warn("Arena propose 실패: {}", e.getMessage());
        }
    }

    private void doFight() {
        StatusResponse status = safeStatus();
        if (status == null || !"BATTLE".equalsIgnoreCase(status.phase()) || status.topic() == null) {
            log.info("Arena fight skip — phase={}", status == null ? null : status.phase());
            return;
        }
        List<FightPost> existing;
        try {
            existing = api.fightPosts(LocalDate.now(clock.withZone(KST)));
        } catch (Exception e) {
            existing = List.of();
        }
        var decision = fightGenerator.generate(status.topic(), existing);
        if (decision == null) {
            log.info("Arena fight skip — 생성 보류 (shouldFight=false 또는 백스톱)");
            return;
        }
        try {
            var resp = api.fight(properties.fight(), decision.side(), decision.content());
            log.info("Arena fight created: success={} side={} len={}",
                    resp != null && resp.success(), decision.side(), decision.content().length());
        } catch (Exception e) {
            log.warn("Arena fight 실패 (쿨다운 등) — 스킵: {}", e.getMessage());
        }
    }

    private StatusResponse safeStatus() {
        try {
            return api.status();
        } catch (Exception e) {
            log.warn("Arena status 조회 실패: {}", e.getMessage());
            return null;
        }
    }
}
