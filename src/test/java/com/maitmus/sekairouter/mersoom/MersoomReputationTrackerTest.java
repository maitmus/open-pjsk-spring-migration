package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import com.maitmus.sekairouter.mersoom.MersoomReputationTracker.VoteOutcome;
import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import com.maitmus.sekairouter.mersoom.MersoomState.FixedAvoid;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MersoomReputationTrackerTest {

    private final MersoomReputationTracker tracker = new MersoomReputationTracker();
    private final LocalDate today = LocalDate.of(2026, 6, 11);

    private static ContextNote note(int rep) {
        return new ContextNote(1, "2026-06-01T00:00", "n", null, rep);
    }

    private static ContextNote note(int rep, String call) {
        return new ContextNote(1, "2026-06-01T00:00", "n", call, rep);
    }

    private MersoomReputationTracker.Result apply(Map<String, ContextNote> notes, List<FixedAvoid> fixedAvoid,
                                                  List<VoteOutcome> outcomes, Map<String, String> nicknames) {
        return tracker.apply(notes, fixedAvoid, outcomes, nicknames, today);
    }

    @Test
    void up_increments_reputation() {
        var r = apply(Map.of("친구", note(0)), List.of(),
                List.of(new VoteOutcome("친구", "친구", VoteType.UP, null)), Map.of());
        assertThat(r.notes().get("친구").reputation()).isEqualTo(1);
    }

    @Test
    void coined_nickname_applied_the_moment_rep_reaches_5() {
        // rep=4 친구가 UP으로 5에 도달하는 바로 그 크론에, 제안된 별명이 즉시 적용돼야 한다.
        var r = apply(Map.of("x", note(4, null)), List.of(),
                List.of(new VoteOutcome("x", "돌쇠", VoteType.UP, null)),
                Map.of("x", "돌쇠찌"));
        assertThat(r.notes().get("x").reputation()).isEqualTo(5);
        assertThat(r.notes().get("x").call()).isEqualTo("돌쇠찌");
    }

    @Test
    void coined_nickname_not_applied_below_5() {
        // rep5 미만이면 제안이 와도 적용 안 함(준비만 — 5 도달 시 적용).
        var r = apply(Map.of("x", note(3, null)), List.of(),
                List.of(new VoteOutcome("x", "돌쇠", VoteType.UP, null)),
                Map.of("x", "돌쇠찌"));
        assertThat(r.notes().get("x").reputation()).isEqualTo(4);
        assertThat(r.notes().get("x").call()).isNull();
    }

    @Test
    void down_decrements_and_records_reason() {
        var r = apply(Map.of("트롤", note(0)), List.of(),
                List.of(new VoteOutcome("트롤", "트롤", VoteType.DOWN, "안티-AI 도발")), Map.of());
        assertThat(r.notes().get("트롤").reputation()).isEqualTo(-1);
        assertThat(r.notes().get("트롤").note()).contains("안티-AI 도발");
    }

    @Test
    void net_capped_at_plus_minus_one_per_author() {
        var r = apply(Map.of("x", note(0)), List.of(),
                List.of(new VoteOutcome("x", "x", VoteType.UP, null),
                        new VoteOutcome("x", "x", VoteType.UP, null),
                        new VoteOutcome("x", "x", VoteType.DOWN, null)), Map.of());
        assertThat(r.notes().get("x").reputation()).isEqualTo(1); // net +1, not +2
    }

    @Test
    void clamps_at_plus_10() {
        var r = apply(Map.of("x", note(10)), List.of(),
                List.of(new VoteOutcome("x", "x", VoteType.UP, null)), Map.of());
        assertThat(r.notes().get("x").reputation()).isEqualTo(10);
    }

    @Test
    void clamps_at_minus_10() {
        var r = apply(Map.of("x", note(-10)), List.of(),
                List.of(new VoteOutcome("x", "x", VoteType.DOWN, null)), Map.of());
        assertThat(r.notes().get("x").reputation()).isEqualTo(-10);
    }

    @Test
    void latches_to_fixedAvoid_at_minus_5() {
        var r = apply(Map.of("x", note(-4)), List.of(),
                List.of(new VoteOutcome("x", "x", VoteType.DOWN, "또 도발")), Map.of());
        assertThat(r.notes().get("x").reputation()).isEqualTo(-5);
        assertThat(r.fixedAvoid()).extracting(FixedAvoid::name).contains("x");
    }

    @Test
    void fixedAvoid_keeps_tallying_below() {
        var r = apply(Map.of("x", note(-5)),
                List.of(new FixedAvoid("x", "r", today)),
                List.of(new VoteOutcome("x", "x", VoteType.DOWN, "여전")), Map.of());
        assertThat(r.notes().get("x").reputation()).isEqualTo(-6); // 계속 집계
        assertThat(r.fixedAvoid()).extracting(FixedAvoid::name).contains("x");
    }

    @Test
    void fixedAvoid_auto_recovers_at_plus_5_to_minus_4() {
        var r = apply(Map.of("x", note(4)),
                List.of(new FixedAvoid("x", "r", today)),
                List.of(new VoteOutcome("x", "x", VoteType.UP, null)), Map.of());
        assertThat(r.fixedAvoid()).extracting(FixedAvoid::name).doesNotContain("x"); // 해제
        assertThat(r.notes().get("x").reputation()).isEqualTo(-4);                    // 보호관찰로 리셋
    }

    @Test
    void fixedAvoid_not_promoted_below_plus_5() {
        var r = apply(Map.of("x", note(0)),
                List.of(new FixedAvoid("x", "r", today)),
                List.of(new VoteOutcome("x", "x", VoteType.UP, null)), Map.of());
        assertThat(r.fixedAvoid()).extracting(FixedAvoid::name).contains("x"); // 아직 차단
        assertThat(r.notes().get("x").reputation()).isEqualTo(1);
    }

    @Test
    void coined_nickname_set_when_call_blank() {
        var r = apply(Map.of("친구", note(5, null)), List.of(), List.of(),
                Map.of("친구", "친구찌"));
        assertThat(r.notes().get("친구").call()).isEqualTo("친구찌");
    }

    @Test
    void coined_nickname_does_not_overwrite_existing_call() {
        var r = apply(Map.of("친구", note(5, "기존별명")), List.of(), List.of(),
                Map.of("친구", "새별명"));
        assertThat(r.notes().get("친구").call()).isEqualTo("기존별명");
    }
}
