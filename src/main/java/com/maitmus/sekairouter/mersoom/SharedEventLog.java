package com.maitmus.sekairouter.mersoom;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 공유 이벤트 목록의 순수 로직 — 수명 정리(prune)와 추출 결과 적용(apply, 검증 포함).
 * 파일 I/O는 {@link SharedEventStore}, LLM 추출은 {@link SharedEventExtractor}.
 */
final class SharedEventLog {

    static final int MAX_EVENTS = 8;
    static final int MAX_FACTS = 5;
    static final int MAX_FACT_LEN = 80;
    static final int MAX_TITLE = 30;
    static final int MAX_SOURCES = 5;
    private static final Pattern ID = Pattern.compile("[a-z0-9-]{2,40}");
    private static final Set<String> STATUSES = Set.of("upcoming", "done", "cancelled");

    /** 추출기가 돌려준 연산 하나. add는 title·facts·date, update는 addFacts·status·date를 쓴다. */
    record Op(String op, String id, String title, String date, List<String> facts, List<String> addFacts, String status) {}

    record ApplyResult(List<SharedEvent> events, List<SharedEvent> added, List<SharedEvent> updated) {}

    private SharedEventLog() {}

    /**
     * 수명 정리 — 날짜 있는 일은 날짜+3일까지(지난 일 후속 대화), 끝남·취소는 갱신+3일, 날짜 미정은 갱신+7일.
     * 활성은 최대 {@value #MAX_EVENTS}개, 넘치면 오래 갱신 안 된 것부터.
     */
    static List<SharedEvent> prune(List<SharedEvent> events, LocalDate today) {
        List<SharedEvent> alive = new ArrayList<>();
        for (SharedEvent e : events) {
            if (e == null || e.id() == null) continue;
            LocalDate date = parse(e.date());
            LocalDate updated = parse(e.updatedAt() == null ? null : e.updatedAt().substring(0, Math.min(10, e.updatedAt().length())));
            boolean closed = "done".equals(e.status()) || "cancelled".equals(e.status());
            if (date != null && today.isAfter(date.plusDays(3))) continue;
            if (closed && updated != null && today.isAfter(updated.plusDays(3))) continue;
            if (date == null && !closed && updated != null && today.isAfter(updated.plusDays(7))) continue;
            alive.add(e);
        }
        if (alive.size() <= MAX_EVENTS) return alive;
        // 다가오는 날짜 있는 일정(쇼·약속일)이 먼저 — 날짜 없는 작은 약속이 더 최근이어도 일정을 밀어내지 않게
        List<SharedEvent> byRecent = new ArrayList<>(alive);
        byRecent.sort(Comparator.comparing((SharedEvent e) -> !upcomingScheduled(e, today))
                .thenComparing(Comparator.comparing((SharedEvent e) -> e.updatedAt() == null ? "" : e.updatedAt()).reversed()));
        Set<SharedEvent> keep = new LinkedHashSet<>(byRecent.subList(0, MAX_EVENTS));
        return alive.stream().filter(keep::contains).toList();
    }

    private static boolean upcomingScheduled(SharedEvent e, LocalDate today) {
        LocalDate d = parse(e.date());
        return "upcoming".equals(e.status()) && d != null && !d.isBefore(today);
    }

    static ApplyResult apply(List<SharedEvent> current, List<Op> ops, LocalDate today,
                             String actor, String sourceId, String nowTs) {
        List<SharedEvent> events = new ArrayList<>(current);
        List<SharedEvent> added = new ArrayList<>();
        List<SharedEvent> updated = new ArrayList<>();
        for (Op op : ops == null ? List.<Op>of() : ops) {
            if (op == null || op.op() == null || op.id() == null) continue;
            String id = op.id().strip();
            if ("add".equals(op.op())) {
                String title = clean(op.title(), MAX_TITLE);
                int idx = indexOf(events, id, title);
                if (idx >= 0) {
                    SharedEvent merged = merge(events.get(idx), op.facts(), null, op.date(), today, sourceId, nowTs);
                    events.set(idx, merged);
                    updated.add(merged);
                    continue;
                }
                if (!ID.matcher(id).matches() || title == null) continue;
                SharedEvent created = new SharedEvent(id, title, validDate(op.date(), today), "upcoming",
                        mergeFacts(List.of(), op.facts()), actor, nowTs, sourceId == null ? List.of() : List.of(sourceId));
                events.add(created);
                added.add(created);
            } else if ("update".equals(op.op())) {
                int idx = indexOf(events, id, null);
                if (idx < 0) continue;
                SharedEvent merged = merge(events.get(idx), op.addFacts(), op.status(), op.date(), today, sourceId, nowTs);
                events.set(idx, merged);
                updated.add(merged);
            }
        }
        return new ApplyResult(events, added, updated);
    }

    private static int indexOf(List<SharedEvent> events, String id, String title) {
        for (int i = 0; i < events.size(); i++) {
            SharedEvent e = events.get(i);
            if (e.id().equals(id)) return i;
            if (title != null && e.title() != null && e.title().strip().equalsIgnoreCase(title)) return i;
        }
        return -1;
    }

    private static SharedEvent merge(SharedEvent e, List<String> newFacts, String status, String date,
                                     LocalDate today, String sourceId, String nowTs) {
        String st = status != null && STATUSES.contains(status) ? status : e.status();
        String d = validDate(date, today);
        List<String> sources = new ArrayList<>(e.sources() == null ? List.of() : e.sources());
        if (sourceId != null && !sources.contains(sourceId)) sources.add(sourceId);
        while (sources.size() > MAX_SOURCES) sources.remove(0);
        return new SharedEvent(e.id(), e.title(), d != null ? d : e.date(), st,
                mergeFacts(e.facts() == null ? List.of() : e.facts(), newFacts), e.createdBy(), nowTs, sources);
    }

    /** 중복 없이 뒤에 덧붙이고, 넘치면 오래된 줄부터 FIFO. */
    private static List<String> mergeFacts(List<String> existing, List<String> add) {
        List<String> facts = new ArrayList<>(existing);
        for (String f : add == null ? List.<String>of() : add) {
            String c = clean(f, MAX_FACT_LEN);
            if (c != null && !facts.contains(c)) facts.add(c);
        }
        while (facts.size() > MAX_FACTS) facts.remove(0);
        return facts;
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip().replace("\n", " ");
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** today-7 ≤ date ≤ today+60인 yyyy-MM-dd만, 아니면 null. */
    static String validDate(String s, LocalDate today) {
        LocalDate d = parse(s);
        if (d == null || d.isBefore(today.minusDays(7)) || d.isAfter(today.plusDays(60))) return null;
        return d.toString();
    }

    private static LocalDate parse(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) return null;
        try {
            return LocalDate.parse(s.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
