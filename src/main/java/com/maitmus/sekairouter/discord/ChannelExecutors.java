package com.maitmus.sekairouter.discord;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    @PreDestroy
    public void shutdown() {
        executors.values().forEach(ExecutorService::shutdownNow);
        executors.clear();
    }
}
