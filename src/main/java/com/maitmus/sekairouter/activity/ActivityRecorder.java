package com.maitmus.sekairouter.activity;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

/**
 * 봇 활동(머슴 댓글·글 + Discord 발화)을 활동 피드에 기록하는 단일 진입점.
 * 인메모리 ring buffer(최신 {@link #CAP}건) + 매 이벤트 write-through로 파일 영속 →
 * 재시작 시 @PostConstruct로 파일에서 복원하므로 대시보드가 안 비워진다.
 *
 * 여러 스레드(머슴 크론·JDA 이벤트·하트비트)에서 호출되므로 record는 동기화. 기록 실패는
 * 본류(발화·게시)를 막지 않도록 삼켜서 warn만 남긴다.
 */
@Slf4j
@Component
public class ActivityRecorder {

    static final int CAP = 80;
    private static final int MAX_TEXT = 140;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ActivityFeedStore store;
    private final Clock clock;
    private final Deque<ActivityEvent> buf = new ArrayDeque<>();
    private final Object lock = new Object();

    public ActivityRecorder(ActivityFeedStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @PostConstruct
    void restore() {
        synchronized (lock) {
            buf.clear();
            buf.addAll(store.load().events());
            log.info("Activity feed restored: {} events", buf.size());
        }
    }

    /** 머슴 댓글 — actor 표시명("에무"/"네네"), target 닉네임, 본문. */
    public void recordComment(String actor, String target, String text) {
        add(new ActivityEvent(now(), "comment", actor, null, target, abbrev(text)));
    }

    /** 머슴 글 — actor 표시명, 제목. */
    public void recordPost(String actor, String title) {
        add(new ActivityEvent(now(), "post", actor, null, null, abbrev(title)));
    }

    /** Discord 발화 — actor 캐릭터 id(소문자), 채널 id, 본문. */
    public void recordUtterance(String actor, String channel, String text) {
        add(new ActivityEvent(now(), "utterance", actor, channel, null, abbrev(text)));
    }

    private void add(ActivityEvent e) {
        synchronized (lock) {
            buf.addFirst(e);
            while (buf.size() > CAP) buf.removeLast();
            try {
                store.save(new ActivityFeed(new ArrayList<>(buf)));
            } catch (Exception ex) {
                log.warn("Activity feed save 실패(본류 영향 없음): {}", ex.getMessage());
            }
        }
    }

    private String now() {
        return OffsetDateTime.now(clock.withZone(KST)).toString();
    }

    private static String abbrev(String s) {
        if (s == null) return "";
        String t = s.replace("\n", " ").strip();
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) + "…" : t;
    }
}
