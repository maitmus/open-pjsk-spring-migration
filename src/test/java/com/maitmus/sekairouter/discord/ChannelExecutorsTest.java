package com.maitmus.sekairouter.discord;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelExecutorsTest {

    @Test
    void shutdown_waits_for_inflight_task() throws InterruptedException {
        // graceful: 진행 중 작업은 인터럽트하지 않고 완료를 기다린다.
        ChannelExecutors ce = new ChannelExecutors();
        AtomicBoolean done = new AtomicBoolean(false);
        ce.execute("c1", () -> {
            try {
                Thread.sleep(150);  // 셧다운 시점에 '진행 중'인 작업
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;             // 인터럽트되면 done 안 찍힘 → shutdownNow였다면 실패
            }
            done.set(true);
        });
        Thread.sleep(20);           // 작업이 시작되도록 보장
        ce.shutdown();              // awaitTermination이 완료까지 대기해야 함
        assertThat(done.get()).isTrue();
    }

    @Test
    void serializes_tasks_on_same_channel() throws InterruptedException {
        ChannelExecutors ce = new ChannelExecutors();
        StringBuilder order = new StringBuilder();
        ce.execute("c1", () -> { try { Thread.sleep(40); } catch (InterruptedException ignored) {} order.append("A"); });
        ce.execute("c1", () -> order.append("B"));
        ce.shutdown();              // 둘 다 완료 대기
        assertThat(order.toString()).isEqualTo("AB");  // 직렬 — A 먼저(느려도), B 나중
    }
}
