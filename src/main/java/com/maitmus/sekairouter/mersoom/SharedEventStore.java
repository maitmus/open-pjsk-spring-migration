package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * shared-events.json 저장소 — 에무·네네 두 시민이 한 파일을 공유한다(같은 JVM, synchronized로 load-modify-save).
 * 파일이 없거나 비었거나 깨졌으면 빈 목록으로 보고, 다음 저장에서 정상 파일로 덮는다.
 * 저장은 {@link MersoomStateStore}와 같은 atomic→direct write fallback(single-file bind mount).
 */
@Slf4j
@Component
public class SharedEventStore {

    private final ObjectMapper objectMapper;
    private final Path file;

    public SharedEventStore(ObjectMapper objectMapper,
                            @Value("${mersoom.shared-events-file:/app/shared-events.json}") String path) {
        this.objectMapper = objectMapper;
        this.file = Paths.get(path);
    }

    /** '한 번만 일어나는 일' 보존 기간 — 계절·연 단위 첫 경험(코타츠 개시·첫 입김)이 한 철 안에서 반복되지 않을 만큼. */
    static final int MILESTONE_KEEP_DAYS = 120;

    /** 수명 정리된 활성 이벤트. */
    public synchronized List<SharedEvent> active(LocalDate today) {
        return SharedEventLog.prune(load().events(), today);
    }

    /** 그 시민의 보존 기간 내 milestones(오래된 순). */
    public synchronized List<Milestone> milestones(String owner, LocalDate today) {
        return pruneMilestones(load().milestones(), today).stream().filter(m -> owner.equals(m.owner())).toList();
    }

    /** milestones 추가 — 같은 시민·같은 id가 이미 있으면 먼저 기록된 것을 유지한다. 추가분이 없으면 파일을 건드리지 않는다. */
    public synchronized List<Milestone> addMilestones(String owner, List<Milestone> add, LocalDate today) {
        if (add == null || add.isEmpty()) return List.of();
        SharedEvent.File f = load();
        List<Milestone> all = new ArrayList<>(pruneMilestones(f.milestones(), today));
        List<Milestone> added = new ArrayList<>();
        for (Milestone m : add) {
            if (m == null || m.id() == null) continue;
            boolean dup = all.stream().anyMatch(x -> owner.equals(x.owner()) && m.id().equals(x.id()));
            if (dup) continue;
            Milestone owned = new Milestone(owner, m.id(), m.title(), m.date(), m.sourceId());
            all.add(owned);
            added.add(owned);
        }
        if (!added.isEmpty()) save(new SharedEvent.File(SharedEventLog.prune(f.events(), today), all, f.activities()));
        return added;
    }

    /** '최근 한 일' 보존 기간 — 프롬프트엔 그저께까지만 쓰지만 자정 직후 크론도 사흘 전을 볼 수 있게 하루 여유. */
    static final int ACTIVITY_KEEP_DAYS = 3;
    static final int ACTIVITY_MAX = 160;

    /** 보존 기간 내 activities(기록 순). */
    public synchronized List<Activity> activities(LocalDate today) {
        return pruneActivities(load().activities(), today);
    }

    /** activities 추가 — 같은 날·같은 사람·같은 문구는 건너뛴다. 추가분이 없으면 파일을 건드리지 않는다. */
    public synchronized List<Activity> addActivities(List<Activity> add, LocalDate today) {
        if (add == null || add.isEmpty()) return List.of();
        SharedEvent.File f = load();
        List<Activity> all = new ArrayList<>(pruneActivities(f.activities(), today));
        List<Activity> added = new ArrayList<>();
        for (Activity a : add) {
            if (a == null || a.text() == null || a.text().isBlank() || a.actor() == null || a.date() == null) continue;
            boolean dup = all.stream().anyMatch(x -> a.date().equals(x.date()) && a.actor().equals(x.actor())
                    && norm(a.text()).equals(norm(x.text())));
            if (dup) continue;
            all.add(a);
            added.add(a);
        }
        if (added.isEmpty()) return added;
        if (all.size() > ACTIVITY_MAX) all = new ArrayList<>(all.subList(all.size() - ACTIVITY_MAX, all.size()));
        save(new SharedEvent.File(SharedEventLog.prune(f.events(), today), f.milestones(), all));
        return added;
    }

    private static String norm(String s) {
        return s.replaceAll("[\\s.,!?~…·]", "");
    }

    private static List<Activity> pruneActivities(List<Activity> as, LocalDate today) {
        if (as == null) return List.of();
        return as.stream().filter(a -> {
            try {
                return a.date() != null && !LocalDate.parse(a.date()).isBefore(today.minusDays(ACTIVITY_KEEP_DAYS));
            } catch (DateTimeParseException e) {
                return false;
            }
        }).toList();
    }

    private static List<Milestone> pruneMilestones(List<Milestone> ms, LocalDate today) {
        if (ms == null) return List.of();
        return ms.stream().filter(m -> {
            try {
                return m.date() != null && !LocalDate.parse(m.date()).isBefore(today.minusDays(MILESTONE_KEEP_DAYS));
            } catch (DateTimeParseException e) {
                return false;
            }
        }).toList();
    }

    /** 추출 연산 적용 후 저장. 연산이 없으면 파일을 건드리지 않는다. */
    public synchronized SharedEventLog.ApplyResult apply(List<SharedEventLog.Op> ops, LocalDate today,
                                                         String actor, String sourceId, String nowTs) {
        SharedEvent.File f = load();
        List<SharedEvent> current = SharedEventLog.prune(f.events(), today);
        if (ops == null || ops.isEmpty()) return new SharedEventLog.ApplyResult(current, List.of(), List.of());
        var result = SharedEventLog.apply(current, ops, today, actor, sourceId, nowTs);
        List<SharedEvent> pruned = SharedEventLog.prune(result.events(), today);
        save(new SharedEvent.File(pruned, f.milestones(), f.activities()));
        return new SharedEventLog.ApplyResult(pruned, result.added(), result.updated());
    }

    private SharedEvent.File load() {
        SharedEvent.File empty = new SharedEvent.File(List.of(), List.of(), List.of());
        if (!Files.isRegularFile(file)) return empty;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) return empty;
            var parsed = objectMapper.readValue(json, SharedEvent.File.class);
            if (parsed == null) return empty;
            return new SharedEvent.File(parsed.events() == null ? List.of() : parsed.events(),
                    parsed.milestones() == null ? List.of() : parsed.milestones(),
                    parsed.activities() == null ? List.of() : parsed.activities());
        } catch (IOException e) {
            log.warn("shared-events 파싱 실패 — 빈 목록으로 진행: {}", e.getMessage());
            return empty;
        }
    }

    private void save(SharedEvent.File content) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(content);
            try {
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicErr) {
                Files.deleteIfExists(tmp);   // Docker single-file bind-mount: ATOMIC_MOVE 불가
                Files.writeString(file, json, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("shared-events 저장 실패: {}", e.getMessage());
        }
    }
}
