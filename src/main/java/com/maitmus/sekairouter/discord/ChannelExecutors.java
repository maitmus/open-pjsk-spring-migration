package com.maitmus.sekairouter.discord;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 채널별 단일 스레드 직렬 실행기. 같은 채널의 작업(라우팅+발화)은 제출 순서대로 하나씩 실행되어,
 * 다음 메시지의 라우팅이 직전 발화 완료(memory/lastSpeaker 반영) 후에만 돌도록 보장한다.
 * 채널이 서로 다르면 독립 스레드라 병렬.
 */
@Slf4j
@Component
public class ChannelExecutors {

    private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();

    /** 해당 채널의 직렬 큐에 작업을 넣는다(논블로킹). */
    public void execute(String channelId, Runnable task) {
        executors.computeIfAbsent(channelId, this::newWorker).execute(task);
    }

    private ExecutorService newWorker(String channelId) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "channel-worker-" + channelId);
            t.setDaemon(true);
            return t;
        });
    }

    /** 진행 중 작업당 완료 대기 한도(초). compose stop_grace_period(30s)보다 짧게 둬 강제 kill 전에 끝나게 한다. */
    private static final long AWAIT_SECONDS = 20;

    /**
     * Graceful 종료 — 새 작업은 거부하되 진행 중인 발화/라우팅은 완료를 기다린다(인터럽트 X).
     * AWAIT_SECONDS 안에 안 끝나면 그제서야 인터럽트. SIGTERM 시 진행 중 발화·머슴 게시 유실 방지.
     */
    @PreDestroy
    public void shutdown() {
        executors.values().forEach(ExecutorService::shutdown);   // 새 작업 거부, 진행 중은 계속
        for (var ex : executors.values()) {
            try {
                if (!ex.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("channel worker가 {}s 내 미완료 — 강제 종료", AWAIT_SECONDS);
                    ex.shutdownNow();
                }
            } catch (InterruptedException e) {
                ex.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        executors.clear();
    }
}
