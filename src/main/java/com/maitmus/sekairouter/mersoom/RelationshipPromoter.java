package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import com.maitmus.sekairouter.mersoom.MersoomState.FixedFriend;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;

/**
 * 자동 격상 평가 (RULES P0.8).
 *
 * - friends → fixed_friends: resetCount ≥ 2 + resetAt 최근 3일 이내
 * - reserved_nicknames 거부
 * - fixed_*는 자동 강등 X (수동 영역)
 */
@Slf4j
public class RelationshipPromoter {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final Clock clock;

    public RelationshipPromoter(Clock clock) {
        this.clock = clock;
    }

    public MersoomState evaluate(MersoomState state) {
        var newFriends = new ArrayList<>(state.friends());
        var newFixedFriends = new ArrayList<>(state.fixedFriends());
        LocalDate today = LocalDate.now(clock.withZone(KST));

        for (String name : new ArrayList<>(state.friends())) {
            if (state.reservedNicknames().contains(name)) continue;
            if (newFixedFriends.stream().anyMatch(f -> f.name().equals(name))) continue;
            ContextNote note = state.contextNotes().get(name);
            if (note == null) continue;
            if (note.resetCount() < 2) continue;
            if (!isRecent(note.resetAt(), today, 3)) continue;

            newFixedFriends.add(new FixedFriend(name,
                    "context_notes %d턴 연속 + 최근 교류 자동 격상".formatted(note.resetCount()),
                    today));
            newFriends.remove(name);
            log.info("Mersoom auto-promote: {} → fixed_friends (resetCount={})", name, note.resetCount());
        }

        return new MersoomState(
                state.lastPostIds(),
                state.lastCommentIds(),
                newFriends,
                state.avoid(),
                newFixedFriends,
                state.fixedAvoid(),
                state.contextNotes(),
                state.contextNotesMaxTtl(),
                state.reservedNicknames(),
                state.summary(),
                state.summaryPrev(),
                state.pendingReports(),
                state.votedPostIds()
        );
    }

    private static boolean isRecent(String resetAtStr, LocalDate today, int withinDays) {
        if (resetAtStr == null || resetAtStr.isBlank()) return false;
        try {
            LocalDateTime resetAt = LocalDateTime.parse(resetAtStr, TS_FORMAT);
            long days = ChronoUnit.DAYS.between(resetAt.toLocalDate(), today);
            return days >= 0 && days <= withinDays;
        } catch (Exception e) {
            return false;
        }
    }
}
