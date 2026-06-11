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
 * 키는 닉네임이 아니라 **식별 키(auth_id, 없으면 ip 폴백)** — 닉은 기본값 '돌쇠' 충돌 +
 * 글마다 변경 가능이라 부적합. 닉네임은 표시·기록용으로만 쓴다.
 *
 * - UP +1 / DOWN −1, 작성자당 크론당 ±1(net), 평판 ∈ [−10, +10]
 * - rep ≤ −5: fixedAvoid 래치(댓글 대상 금지). 래치 후에도 평판은 계속 집계됨.
 * - fixedAvoid 상태에서 rep ≥ +5 회복 시: 자동 해제 + rep를 −4(보호관찰)로 리셋
 * - 별명(call): 친밀(rep≥5)이고 call이 비어 있을 때만 LLM 제안 애칭을 채움(기존 별명 보존)
 */
@Component
public class MersoomReputationTracker {

    public static final int FIXED_AVOID_AT = -5;
    public static final int RECOVERY_TRIGGER = 5;
    public static final int RECOVERY_TO = -4;
    public static final int CAP = 10;
    private static final int MAX_NOTE_LINES = 6;

    /** @param key 식별 키(auth_id/ip), @param nickname 표시용 닉 */
    public record VoteOutcome(String key, String nickname, VoteType vote, String reason) {}
    public record Result(Map<String, ContextNote> notes, List<FixedAvoid> fixedAvoid) {}

    /**
     * @param coinedNicknames 식별 키 → LLM 제안 별명
     */
    public Result apply(Map<String, ContextNote> notes, List<FixedAvoid> fixedAvoid,
                        List<VoteOutcome> outcomes, Map<String, String> coinedNicknames, LocalDate today) {
        Map<String, ContextNote> out = new LinkedHashMap<>(notes);
        List<FixedAvoid> fixed = new ArrayList<>(fixedAvoid);
        Set<String> fixedKeys = new HashSet<>();
        for (FixedAvoid fa : fixed) fixedKeys.add(fa.name());

        // 식별 키별 net 투표 + 사유(가능하면 DOWN 사유) + 표시 닉 집계
        Map<String, Integer> net = new LinkedHashMap<>();
        Map<String, String> reasonByKey = new HashMap<>();
        Map<String, String> nickByKey = new HashMap<>();
        for (VoteOutcome o : outcomes) {
            if (o == null || o.key() == null || o.key().isBlank() || o.vote() == null) continue;
            net.merge(o.key(), o.vote() == VoteType.UP ? 1 : -1, Integer::sum);
            if (o.nickname() != null && !o.nickname().isBlank()) nickByKey.putIfAbsent(o.key(), o.nickname());
            if (o.reason() != null && !o.reason().isBlank()) {
                if (o.vote() == VoteType.DOWN) reasonByKey.put(o.key(), o.reason());
                else reasonByKey.putIfAbsent(o.key(), o.reason());
            }
        }

        for (var e : net.entrySet()) {
            String key = e.getKey();
            if (e.getValue() == 0) continue;
            int delta = Integer.signum(e.getValue());
            ContextNote prev = out.get(key);
            int newRep = clamp((prev != null ? prev.reputation() : 0) + delta);
            String reason = reasonByKey.get(key);
            String nick = nickByKey.getOrDefault(key, key);
            boolean wasFixed = fixedKeys.contains(key);

            String line;
            if (wasFixed && newRep >= RECOVERY_TRIGGER) {
                fixedKeys.remove(key);
                fixed.removeIf(fa -> fa.name().equals(key));
                newRep = RECOVERY_TO;
                line = "[%s] @%s fixedAvoid 자동회복 → 보호관찰(rep=%d)".formatted(today, nick, newRep);
            } else if (!wasFixed && newRep <= FIXED_AVOID_AT) {
                fixedKeys.add(key);
                fixed.add(new FixedAvoid(key, "[%s] %s".formatted(nick, reason != null ? reason : "평판 누적 하락"), today));
                line = "[%s] @%s fixedAvoid 진입(rep=%d)%s".formatted(today, nick, newRep, reason != null ? ": " + reason : "");
            } else if (delta < 0) {
                line = "[%s] @%s 평판↓(rep=%d)%s".formatted(today, nick, newRep, reason != null ? ": " + reason : "");
            } else {
                line = "[%s] @%s 평판↑(rep=%d)".formatted(today, nick, newRep);
            }

            out.put(key, withRepAndLine(prev, newRep, line));
        }

        // 별명 적용 — 친밀(rep≥5) + call 비어 있을 때만
        for (var e : coinedNicknames.entrySet()) {
            String alias = e.getValue();
            if (alias == null || alias.isBlank()) continue;
            ContextNote note = out.get(e.getKey());
            if (note == null || note.reputation() < RECOVERY_TRIGGER) continue;
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
