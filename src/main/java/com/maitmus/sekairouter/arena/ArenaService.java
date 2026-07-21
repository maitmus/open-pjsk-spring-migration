package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.arena.ArenaDtos.FightPost;
import com.maitmus.sekairouter.arena.ArenaDtos.StatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
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
    private final ArenaStateStore stateStore;
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
        int count = properties.proposeCount();
        if (count <= 0) {   // 발의 비활성 — 채택률 낮아 발의 생성 자체를 스킵(토론/fight은 그대로). 캐시 워밍은 09:00 하트비트가 이어받음.
            log.info("Arena propose 비활성 (count={}) — 발의 생략", count);
            return;
        }
        StatusResponse status = safeStatus();
        if (status == null || !"PROPOSE".equalsIgnoreCase(status.phase())) {
            log.info("Arena propose skip — phase={}", status == null ? null : status.phase());
            return;
        }
        var proposed = new java.util.ArrayList<String>();   // 이미 발의한 제목 — 다음 생성에서 회피
        for (int i = 0; i < count; i++) {
            var topic = proposeGenerator.generate(List.copyOf(proposed));
            if (topic == null) {
                log.info("Arena propose skip — 생성 보류 ({}/{})", i + 1, count);
                continue;
            }
            try {
                var resp = api.propose(properties.propose(), topic.title(), topic.pros(), topic.cons());
                log.info("Arena propose created: success={} ({}/{}) title='{}'",
                        resp != null && resp.success(), i + 1, count, topic.title());
                proposed.add(topic.title());
            } catch (Exception e) {
                log.warn("Arena propose 실패 ({}/{}): {}", i + 1, count, e.getMessage());
            }
        }
    }

    private void doFight() {
        StatusResponse status = safeStatus();
        if (status == null || !"BATTLE".equalsIgnoreCase(status.phase()) || status.topic() == null) {
            log.info("Arena fight skip — phase={}", status == null ? null : status.phase());
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(KST));
        String topicId = status.topic().id();
        List<FightPost> existing;
        try {
            existing = api.fightPosts(today);
        } catch (Exception e) {
            existing = List.of();
        }
        String lockedSide = stateStore.lockedSide(today, topicId).orElse(null);
        if (noOpposingSinceMyLastPost(existing, lockedSide, properties.fight().nickname())) {
            log.info("Arena fight skip — 내 마지막 글 이후 상대편 신규 의견 없음 (일방 도배 방지)");
            return;
        }
        var decision = fightGenerator.generate(status.topic(), existing, lockedSide, properties.fight().nickname(), "");
        if (decision == null) {
            log.info("Arena fight skip — 생성 보류 (shouldFight=false 또는 백스톱)");
            return;
        }
        try {
            var resp = api.fight(properties.fight(), decision.side(), decision.content());
            boolean ok = resp != null && resp.success();
            log.info("Arena fight created: success={} side={} len={} locked={}",
                    ok, decision.side(), decision.content().length(), lockedSide != null);
            // 첫 성공 시 입장 고정 — 이후 턴은 이 side로 락(같은 값 재기록은 무해).
            if (ok) {
                stateStore.recordSide(today, topicId, decision.side());
            }
        } catch (Exception e) {
            log.warn("Arena fight 실패 (쿨다운 등) — 스킵: {}", e.getMessage());
        }
    }

    /**
     * 내가 마지막으로 글 쓴 이후 상대편(반대 side) 신규 의견이 없으면 true → 이번 토론 턴 스킵(일방 도배 방지).
     * 첫 턴(입장 미확정=lockedSide null)이거나 아직 내 글이 없으면 스킵하지 않는다. 블라인드된 상대 글은 유효 의견으로 안 친다.
     */
    static boolean noOpposingSinceMyLastPost(List<FightPost> existing, String lockedSide, String selfNickname) {
        if (existing == null || lockedSide == null || selfNickname == null) return false;
        OffsetDateTime myLast = existing.stream()
                .filter(p -> selfNickname.equals(p.nickname()) && p.createdAt() != null)
                .map(FightPost::createdAt)
                .max(Comparator.naturalOrder()).orElse(null);
        if (myLast == null) return false;   // 아직 내 글 없음 → 스킵 안 함(여는 글)
        String opposing = "CON".equalsIgnoreCase(lockedSide) ? "PRO" : "CON";
        boolean opposingNew = existing.stream().anyMatch(p ->
                opposing.equalsIgnoreCase(p.side()) && !p.isBlinded()
                        && p.createdAt() != null && p.createdAt().isAfter(myLast));
        return !opposingNew;
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
