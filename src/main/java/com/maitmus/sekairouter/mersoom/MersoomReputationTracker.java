package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import com.maitmus.sekairouter.mersoom.MersoomState.FixedAvoid;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 양극 평판 트래커. LLM 피드 판정의 투표를 작성자별 signed 카운터로 누적한다.
 *
 * - UP +1 / DOWN −1, 작성자당 크론당 ±1(net), 평판 ∈ [−10, +10]
 * - rep ≤ −5: fixedAvoid 래치(댓글 대상 금지). 래치 후에도 평판은 계속 집계됨.
 * - fixedAvoid 상태에서 rep ≥ +5 회복 시: 자동 해제 + rep를 −4(보호관찰)로 리셋
 * - 별명(call)이 비어 있고 LLM이 애칭을 제안하면 채워 넣음(기존 별명은 보존)
 * - 사유는 contextNote.note에 누적 기록(최근 몇 줄만 유지)
 */
@Component
public class MersoomReputationTracker {

    public static final int FIXED_AVOID_AT = -5;
    public static final int RECOVERY_TRIGGER = 5;
    public static final int RECOVERY_TO = -4;
    public static final int CAP = 10;
    private static final int MAX_NOTE_LINES = 6;

    public record VoteOutcome(String nickname, VoteType vote, String reason) {}
    public record Result(Map<String, ContextNote> notes, List<FixedAvoid> fixedAvoid) {}

    public Result apply(Map<String, ContextNote> notes, List<FixedAvoid> fixedAvoid,
                        List<VoteOutcome> outcomes, Map<String, String> coinedNicknames, LocalDate today) {
        Map<String, ContextNote> out = new LinkedHashMap<>(notes);
        List<FixedAvoid> fixed = new ArrayList<>(fixedAvoid);
        Set<String> fixedNames = new HashSet<>();
        for (FixedAvoid fa : fixed) fixedNames.add(fa.name());

        // 작성자별 net 투표 + 사유(가능하면 DOWN 사유) 집계
        Map<String, Integer> net = new LinkedHashMap<>();
        Map<String, String> reasonByNick = new HashMap<>();
        for (VoteOutcome o : outcomes) {
            if (o == null || o.nickname() == null || o.nickname().isBlank() || o.vote() == null) continue;
            net.merge(o.nickname(), o.vote() == VoteType.UP ? 1 : -1, Integer::sum);
            if (o.reason() != null && !o.reason().isBlank()) {
                if (o.vote() == VoteType.DOWN) reasonByNick.put(o.nickname(), o.reason());
                else reasonByNick.putIfAbsent(o.nickname(), o.reason());
            }
        }

        for (var e : net.entrySet()) {
            String nick = e.getKey();
            if (e.getValue() == 0) continue;
            int delta = Integer.signum(e.getValue());
            ContextNote prev = out.get(nick);
            int oldRep = prev != null ? prev.reputation() : 0;
            int newRep = clamp(oldRep + delta);
            String reason = reasonByNick.get(nick);
            boolean wasFixed = fixedNames.contains(nick);

            String line;
            if (wasFixed && newRep >= RECOVERY_TRIGGER) {
                fixedNames.remove(nick);
                fixed.removeIf(fa -> fa.name().equals(nick));
                newRep = RECOVERY_TO;
                line = "[%s] fixedAvoid 자동회복 → 보호관찰(rep=%d)".formatted(today, newRep);
            } else if (!wasFixed && newRep <= FIXED_AVOID_AT) {
                fixedNames.add(nick);
                fixed.add(new FixedAvoid(nick, reason != null ? reason : "평판 누적 하락", today));
                line = "[%s] fixedAvoid 진입(rep=%d)%s".formatted(today, newRep, reason != null ? ": " + reason : "");
            } else if (delta < 0) {
                line = "[%s] 평판↓(rep=%d)%s".formatted(today, newRep, reason != null ? ": " + reason : "");
            } else {
                line = "[%s] 평판↑(rep=%d)".formatted(today, newRep);
            }

            out.put(nick, withRepAndLine(prev, newRep, line));
        }

        // 별명 적용 (call 비어 있을 때만)
        for (var e : coinedNicknames.entrySet()) {
            String alias = e.getValue();
            if (alias == null || alias.isBlank()) continue;
            ContextNote note = out.get(e.getKey());
            if (note == null) continue;
            if (note.call() == null || note.call().isBlank()) {
                out.put(e.getKey(), new ContextNote(note.resetCount(), note.resetAt(), note.note(), alias, note.reputation()));
            }
        }

        return new Result(out, fixed);
    }

    private static int clamp(int v) {
        return Math.max(-CAP, Math.min(CAP, v));
    }

    private static ContextNote withRepAndLine(ContextNote prev, int newRep, String line) {
        String prevNote = prev != null && prev.note() != null ? prev.note() : "";
        String merged = prevNote.isBlank() ? line : prevNote + (prevNote.endsWith("\n") ? "" : "\n") + line;
        merged = lastLines(merged, MAX_NOTE_LINES);
        int resetCount = prev != null ? prev.resetCount() : 0;
        String resetAt = prev != null ? prev.resetAt() : null;
        String call = prev != null ? prev.call() : null;
        return new ContextNote(resetCount, resetAt, merged, call, newRep);
    }

    private static String lastLines(String text, int n) {
        String[] lines = text.split("\n", -1);
        if (lines.length <= n) return text;
        return String.join("\n", Arrays.copyOfRange(lines, lines.length - n, lines.length));
    }
}
