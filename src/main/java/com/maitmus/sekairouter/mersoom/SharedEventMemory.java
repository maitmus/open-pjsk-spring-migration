package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.persona.CharacterId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 엔진이 쓰는 공유 이벤트 파사드 — 생성 전 프롬프트 섹션 제공, 게시 직후 추출·저장(observe).
 * 어떤 실패도 글/댓글 흐름에 영향을 주지 않는다(섹션은 빈 문자열, observe는 WARN만).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SharedEventMemory {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final SharedEventStore store;
    private final SharedEventExtractor extractor;
    private final Clock clock;

    /** 형제 이름 — 에무 시민이면 네네, 네네 시민이면 에무. */
    static String siblingName(CitizenProfile profile) {
        return profile.persona() == CharacterId.NENE ? "에무" : "네네";
    }

    public String postSection(CitizenProfile profile) {
        try {
            LocalDate today = LocalDate.now(clock.withZone(KST));
            return SharedEventPrompt.postSection(store.active(today), today, siblingName(profile));
        } catch (Exception e) {
            log.warn("[{}] Shared event 섹션 생성 실패(생략): {}", profile.key(), e.toString());
            return "";
        }
    }

    public String commentSection(CitizenProfile profile) {
        try {
            LocalDate today = LocalDate.now(clock.withZone(KST));
            return SharedEventPrompt.commentSection(store.active(today), today, siblingName(profile));
        } catch (Exception e) {
            log.warn("[{}] Shared event 섹션 생성 실패(생략): {}", profile.key(), e.toString());
            return "";
        }
    }

    /**
     * 방금 게시한 글·형제 댓글·형제 대댓글에서 공유 이벤트를 추출해 저장한다.
     *
     * @param sourceId 근거 글 id(댓글·대댓글이면 달린 글 id)
     * @param kind     글 | 댓글 | 대댓글
     * @param context  댓글이면 원글, 대댓글이면 스레드(글이면 빈 문자열)
     */
    public void observe(CitizenProfile profile, String sourceId, String kind, String context, String text) {
        try {
            LocalDateTime now = LocalDateTime.now(clock.withZone(KST));
            LocalDate today = now.toLocalDate();
            List<SharedEvent> current = store.active(today);
            var ops = extractor.extract(new SharedEventExtractor.Input(today, profile.actorName(), siblingName(profile),
                    kind, context, text), current);
            if (ops.isEmpty()) return;
            var r = store.apply(ops, today, profile.key(), sourceId, now.format(TS));
            for (SharedEvent e : r.added()) {
                log.info("[{}] Shared event added: id={} title='{}' date={}", profile.key(), e.id(), e.title(), e.date());
            }
            for (SharedEvent e : r.updated()) {
                log.info("[{}] Shared event updated: id={} status={} date={} facts={}", profile.key(), e.id(),
                        e.status(), e.date(), e.facts() == null ? 0 : e.facts().size());
            }
        } catch (Exception e) {
            log.warn("[{}] Shared event 기록 실패(게시 영향 없음): {}", profile.key(), e.toString());
        }
    }
}
