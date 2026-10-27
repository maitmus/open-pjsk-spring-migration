package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * context_notes 용량 관리·이벤트 append.
 *
 * TTL(시간 기반 망각)은 폐지됨 — 머슴은 소규모 재방문 커뮤니티라 관계는 지속 기억하는 게 맞다.
 * 대신 용량 상한(capByReputation)으로 bound: 초과 시 휴면·약한 관계부터, 그다음 |reputation| 낮고 오래된 노트부터 제거.
 * 친밀·트롤(|reputation| ≥ 5)은 휴면이어도 보존된다.
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
     * 0순위 휴면(마지막 활동 {@value #DORMANT_DAYS}일+)·약한 관계(|reputation| < {@value #STRONG_REP}), 오래된 것 먼저.
     * 그다음 |reputation| 오름차순, 마지막 활동 오래된 것 먼저.
     * 친밀·트롤(|reputation| ≥ 5)은 휴면이어도 보존. 휴면 우선이 없으면 6~7월 이후 안 보인 +2 계정들이
     * 자리를 차지해 지금 활발한 +1 신규가 매 크론 밀려난다(10-27 라이브: 재팔e 16회 축출).
     */
    private static final int DEFAULT_CAPACITY = 100;
    private static final int DORMANT_DAYS = 30;
    private static final int STRONG_REP = 5;
    private static final Pattern NOTE_DATE = Pattern.compile("\\[(\\d{4}-\\d{2}-\\d{2})]");

    public Map<String, ContextNote> capByReputation(Map<String, ContextNote> current, int capacity) {
        int cap = capacity > 0 ? capacity : DEFAULT_CAPACITY;
        if (current.size() <= cap) return current;

        String dormantBefore = LocalDate.now(clock.withZone(KST)).minusDays(DORMANT_DAYS).toString();
        List<Map.Entry<String, ContextNote>> entries = new ArrayList<>(current.entrySet());
        entries.sort(Comparator
                .comparingInt((Map.Entry<String, ContextNote> e) -> {
                    boolean dormant = lastSeen(e.getValue()).compareTo(dormantBefore) < 0;
                    return dormant && Math.abs(e.getValue().reputation()) < STRONG_REP ? 0 : 1;
                })
                .thenComparingInt(e -> Math.abs(e.getValue().reputation()))
                .thenComparing(e -> lastSeen(e.getValue())));

        int removeCount = current.size() - cap;
        Map<String, ContextNote> next = new LinkedHashMap<>(current);
        for (int i = 0; i < removeCount; i++) {
            String key = entries.get(i).getKey();
            next.remove(key);
            log.debug("ContextNote evicted (capacity): {} (rep={})", key, entries.get(i).getValue().reputation());
        }
        return next;
    }

    /** 마지막 활동일(yyyy-MM-dd) — resetAt(댓글)과 노트 줄 [날짜](평판 판정 포함) 중 늦은 쪽, 없으면 "". */
    static String lastSeen(ContextNote n) {
        String best = n.resetAt() == null ? "" : n.resetAt().substring(0, Math.min(10, n.resetAt().length()));
        if (n.note() != null) {
            Matcher m = NOTE_DATE.matcher(n.note());
            while (m.find()) if (m.group(1).compareTo(best) > 0) best = m.group(1);
        }
        return best;
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
