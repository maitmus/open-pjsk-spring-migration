package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * context_notes 용량 관리·이벤트 append.
 *
 * TTL(시간 기반 망각)은 폐지됨 — 머슴은 소규모 재방문 커뮤니티라 관계는 지속 기억하는 게 맞다.
 * 대신 용량 상한(capByReputation)으로 bound: 초과 시 |reputation| 낮고 오래된 중립 노트부터 제거.
 * 평판(친구·트롤)은 |reputation|이 커서 자연 보존되고, 사실상 한 번 스친 중립(rep 0)만 정리된다.
 */
@Slf4j
public class ContextNoteManager {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final Clock clock;
    private final int maxBytesPerFriend;

    public ContextNoteManager(Clock clock, int maxBytesPerFriend) {
        this.clock = clock;
        this.maxBytesPerFriend = maxBytesPerFriend;
    }

    /**
     * 용량 상한 적용. size ≤ capacity면 그대로. 초과 시 중요도 낮은 것부터 제거 —
     * 1순위 |reputation| 오름차순(평판 약한 것 먼저), 2순위 resetAt 오래된 것 먼저.
     * 평판 있는 관계(친구·트롤)는 |reputation|이 커서 보존됨.
     */
    private static final int DEFAULT_CAPACITY = 100;

    public Map<String, ContextNote> capByReputation(Map<String, ContextNote> current, int capacity) {
        int cap = capacity > 0 ? capacity : DEFAULT_CAPACITY;
        if (current.size() <= cap) return current;

        List<Map.Entry<String, ContextNote>> entries = new ArrayList<>(current.entrySet());
        // 제거 우선순위: |reputation| asc, 그다음 resetAt asc(오래된 것 먼저)
        entries.sort(Comparator
                .comparingInt((Map.Entry<String, ContextNote> e) -> Math.abs(e.getValue().reputation()))
                .thenComparing(e -> e.getValue().resetAt() == null ? "" : e.getValue().resetAt()));

        int removeCount = current.size() - cap;
        Map<String, ContextNote> next = new LinkedHashMap<>(current);
        for (int i = 0; i < removeCount; i++) {
            String key = entries.get(i).getKey();
            next.remove(key);
            log.debug("ContextNote evicted (capacity): {} (rep={})", key, entries.get(i).getValue().reputation());
        }
        return next;
    }

    public ContextNote upsertAfterInteraction(ContextNote prev, String newEvent, String call) {
        String resetAt = LocalDateTime.now(clock.withZone(KST)).format(TS_FORMAT);
        if (prev == null) {
            String truncated = truncateNote(newEvent + "\n");
            return new ContextNote(1, resetAt, truncated, call, 0);
        }
        String mergedNote = (prev.note() == null ? "" : prev.note());
        if (!mergedNote.isEmpty() && !mergedNote.endsWith("\n")) mergedNote += "\n";
        mergedNote += newEvent + "\n";
        String truncated = truncateNote(mergedNote);
        return new ContextNote(prev.resetCount() + 1, resetAt, truncated,
                call != null ? call : prev.call(), prev.reputation());
    }

    /** 줄 단위 FIFO truncate — 첫 줄부터 제거하며 maxBytes 이하 유지. */
    public String truncateNote(String note) {
        if (note == null) return "";
        byte[] bytes = note.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytesPerFriend) return note;

        String[] lines = note.split("\n", -1);
        int start = 0;
        while (start < lines.length) {
            String candidate = String.join("\n", java.util.Arrays.copyOfRange(lines, start, lines.length));
            if (candidate.getBytes(StandardCharsets.UTF_8).length <= maxBytesPerFriend) {
                return candidate;
            }
            start++;
        }
        return "";
    }
}
