package com.maitmus.sekairouter.heartbeat;

import com.maitmus.sekairouter.persona.CharacterId;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory state for the heartbeat scheduler.
 * Thread-safe via volatile fields and synchronized methods.
 */
@Component
public class HeartbeatStateStore {

    /** Sentinel value meaning this hour's heartbeat has already fired. */
    private static final int FIRED = 999;

    /** Ring buffer size for recent utterances (used as anti-repetition context in user prompts). */
    private static final int RECENT_BUFFER_SIZE = 12;

    /**
     * Random minute threshold (0–59) for when the heartbeat fires this hour.
     * -1 = uninitialized (no threshold set yet).
     * 999 = already fired this hour.
     */
    private volatile int threshold = -1;

    /** The most recent character that spoke in a heartbeat, for exclusion in the next pick. */
    private volatile CharacterId lastSpeaker;

    /** Recent utterances ring buffer (oldest → newest). Capped at RECENT_BUFFER_SIZE. */
    private final Deque<RecentUtterance> recentUtterances = new ArrayDeque<>();

    /** Per-character counter of event-mode utterances fired on {@link #eventCountsDate}. */
    private final Map<CharacterId, Integer> eventCountsByCharacter = new EnumMap<>(CharacterId.class);

    /** Date the {@link #eventCountsByCharacter} map applies to. Mismatch → reset on next access. */
    private LocalDate eventCountsDate;

    public void setThreshold(int n) {
        this.threshold = n;
    }

    public int getThreshold() {
        return threshold;
    }

    /** Marks this hour's heartbeat as fired (threshold = 999). */
    public synchronized void markFired() {
        this.threshold = FIRED;
    }

    /**
     * Resets the threshold for the new hour.
     * Called at the top of each hour by the scheduler.
     */
    public synchronized void resetThresholdForHour(int n) {
        this.threshold = n;
    }

    public void recordLastSpeaker(CharacterId id) {
        this.lastSpeaker = id;
    }

    public Optional<CharacterId> lastSpeaker() {
        return Optional.ofNullable(lastSpeaker);
    }

    /** Append an utterance to the ring buffer. Evicts oldest when over capacity. */
    public synchronized void recordUtterance(CharacterId speaker, String text) {
        recentUtterances.addLast(new RecentUtterance(speaker, text));
        while (recentUtterances.size() > RECENT_BUFFER_SIZE) {
            recentUtterances.removeFirst();
        }
    }

    /** Snapshot of recent utterances, oldest → newest. */
    public synchronized List<RecentUtterance> recentUtterances() {
        return new ArrayList<>(recentUtterances);
    }

    public record RecentUtterance(CharacterId speaker, String text) {}

    /** Per-character event-utterance count for {@code today}. Resets if today differs from the stored date. */
    public synchronized int eventCount(CharacterId character, LocalDate today) {
        rolloverIfStale(today);
        return eventCountsByCharacter.getOrDefault(character, 0);
    }

    /** Increments {@code character}'s event count for {@code today}, resetting on date change. */
    public synchronized void recordEvent(CharacterId character, LocalDate today) {
        rolloverIfStale(today);
        eventCountsByCharacter.merge(character, 1, Integer::sum);
    }

    private void rolloverIfStale(LocalDate today) {
        if (eventCountsDate == null || !eventCountsDate.equals(today)) {
            eventCountsByCharacter.clear();
            eventCountsDate = today;
        }
    }
}
