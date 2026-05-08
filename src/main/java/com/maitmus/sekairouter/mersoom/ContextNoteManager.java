package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * context_notes의 TTL 관리·자동 truncate·이벤트 append.
 *
 * - tickAndPrune: 매 호출 시작에 ttl -= 1, ttl < 0 항목 제거
 * - upsertAfterInteraction: 상호작용 후 ttl 리셋 + 이벤트 append + 1KB FIFO truncate
 *
 * Spring Bean 아님 — MersoomService에서 Clock + 사이즈로 직접 인스턴스화.
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

    public Map<String, ContextNote> tickAndPrune(Map<String, ContextNote> current) {
        Map<String, ContextNote> next = new LinkedHashMap<>();
        for (var e : current.entrySet()) {
            ContextNote n = e.getValue();
            int newTtl = n.ttl() - 1;
            if (newTtl < 0) {
                log.debug("ContextNote expired: {}", e.getKey());
                continue;
            }
            next.put(e.getKey(), new ContextNote(newTtl, n.resetCount(), n.resetAt(), n.note(), n.call()));
        }
        return next;
    }

    public ContextNote upsertAfterInteraction(ContextNote prev, String newEvent, String call, int defaultTtl) {
        String resetAt = LocalDateTime.now(clock.withZone(KST)).format(TS_FORMAT);
        if (prev == null) {
            String truncated = truncateNote(newEvent + "\n");
            return new ContextNote(defaultTtl, 1, resetAt, truncated, call);
        }
        String mergedNote = (prev.note() == null ? "" : prev.note());
        if (!mergedNote.isEmpty() && !mergedNote.endsWith("\n")) mergedNote += "\n";
        mergedNote += newEvent + "\n";
        String truncated = truncateNote(mergedNote);
        return new ContextNote(defaultTtl, prev.resetCount() + 1, resetAt, truncated,
                call != null ? call : prev.call());
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
