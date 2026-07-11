package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.persona.CharacterId;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 머슴 글 생성 시 토픽·톤 시드를 랜덤 선택해 user prompt에 주입.
 * 시스템 프롬프트의 자유 선택에 맡기면 LLM이 직전 글 토픽(특히 동아리)을 자기 강화로
 * 반복 픽하는 경향이 있어, 매 글마다 독립 시드를 박아서 분포를 평준화한다.
 *
 * 페르소나별 풀: EMU(기본)와 NENE. 각 풀 전체가 해당 페르소나에서 도출 가능한 각도여야 한다.
 */
@Component
public class MersoomSeedPicker {

    /**
     * 토픽 시드 풀. 동아리는 의도적으로 1슬롯만 (기댓값 ~5%).
     * 풀 전체가 EMU 페르소나에서 도출 가능한 각도여야 한다.
     */
    private static final List<String> TOPIC_SEEDS = List.of(
            "오늘 막 끝낸 / 시작한 연습 (안무·점프·착지·아크로바틱 부분) — 잘 됐거나 안 된 부분",
            "동아리 한 토막 (리듬체조부·수영부·댄스부·핸드볼부 중 하나) — 오늘 활동·동료 한 마디",
            "붕어빵 / 슈크림 / 단 것 — 신메뉴 발견·시식·줄·맛 비교",
            "새로 마주친 음식 (시그니처 외) — 편의점·길거리·식당 한 컷",
            "아침 루틴 한 토막 — 잠 깬 순간·등교 길·교복 입는 손",
            "옷·소품·헤어 한 마디 — 오늘 입은 것·발견한 것·바꾸고 싶은 것",
            "머슴 친구 한 마디 — 오호·냥냥이·다른 친구의 글·말에서 떠오른 생각",
            "동물원·테마파크 한 조각 — 본 동물·놀이기구·캐릭터",
            "다가오는 무대·공연·원더쇼 — 준비 상황·기대감 한 호흡",
            "오늘 사소한 자랑 — 잘 된 동작·시간 안배·작은 성공",
            "오늘 사소한 실패·민망 — 안 된 동작·헛수고",
            "어렸을 때 / 옛 추억 한 조각 — 첫 무대·처음 ○○",
            "신체 감각 한 마디 — 점프 후·스트레칭·피로 회복·2층 낙하해도 멀쩡 시그니처",
            "날씨·하늘·바람 — 오늘의 공기 한 마디",
            "우연한 발견 — 길에서 본 것·줍줍·우연한 마주침",
            "음악·노래 흥얼거림 — 머리에 맴도는 멜로디 한 소절",
            "다음 계획·작은 약속 — 다음에 해보고 싶은 것·어디 갈 약속",
            "방금 본 풍경·소리·냄새 한 조각",
            "친구·동료 한 사람 떠올림 — 그 친구의 한 마디·모습",
            "갑자기 든 작은 깨달음 — 오늘 든 사소한 생각"
    );

    /**
     * 톤·도입 패턴 풀. 매 글의 시작 결을 비끄러뜨려 자기 강화 회피.
     */
    private static final List<String> TONE_SEEDS = List.of(
            "즉시감: \"지금 막 ~\" / \"방금 ~\" 으로 도입",
            "발견감: \"오늘 ~ 발견했어요!\" 새로 마주친 것 공유",
            "자랑·기쁨감: 오늘 잘 된 작은 일 한 가지를 자랑하듯",
            "약속·기대감: 다음에 할 일·다가올 일 기대감으로 마무리",
            "고민감: 사소한 고민·결정 한 마디 풀어내기",
            "회상감: 어제·옛날 일 한 조각 떠올리기"
    );

    /** 네네 토픽 시드 풀 — 노래·게임·자몽·관찰형 츳코미 등 네네 페르소나 도출 각도. */
    private static final List<String> NENE_TOPIC_SEEDS = List.of(
            "방금 끝낸 / 시작한 노래 연습 — 가창 한 토막, 잘 된 부분·안 된 고음 (노래 화제엔 자신감)",
            "대전 게임 한 판 — 서바이벌 결과, 토우야와의 라이벌 구도 한 마디",
            "자몽 한 마디 — 그 쓴맛이 좋은 이유 / 또는 민트 거부(치약 맛) 한 컷",
            "본 영화·뮤지컬 감상 한 조각 — 인상 깊은 장면·곡",
            "기계 조작·네네 로봇 손질 한 토막",
            "원더쇼 연습 중 동료(에무·츠카사)의 과한 에너지에 차분한 츳코미",
            "사람 많은 곳을 피한 하루 / 낯가림 한 토막 — 담담하게",
            "옷·롱스커트·레이스 취향 한 마디 — 오늘 입은 것·발견한 것",
            "머슴 친구 글에서 떠오른 생각 — 무심한 듯 한 마디",
            "노래 가르치기 한 토막 (에무·이치카에게) — 가르치는 시선",
            "옛 무대·트라우마 극복 추억 한 조각 — 과장 없이 담담히",
            "오늘 사소한 자랑 — 잘 된 한 가지 (노래·게임이면 단정적으로)",
            "오늘 사소한 실패·민망 — 무심한 듯 인정",
            "날씨·바다·물 한 마디 — 인어공주 모티브, 잔잔한 관찰",
            "머리에 맴도는 멜로디 한 소절",
            "다음 계획·작은 약속 — 무심한 듯, 별 기대 안 하는 척",
            "방금 본 풍경·소리 한 조각 — 짧은 관찰",
            "갑자기 든 사소한 깨달음 — 직설적으로 한 줄"
    );

    /** 네네 톤·도입 패턴 풀 — 무심·직설·츤데레 결. */
    private static final List<String> NENE_TONE_SEEDS = List.of(
            "무심감: \"...별로 대단한 건 아닌데\" 식으로 슬쩍 도입",
            "직설감: 핵심부터 툭 던지고 뒤에 부연",
            "츳코미감: 누군가의 과한 행동에 차분한 일침",
            "자신감(노래·게임): 이 화제만은 단정적으로",
            "회피·완곡감: \"...뭐\", \"...그렇긴 해\" 로 여운 남기기",
            "회상감: 옛 무대·추억 한 조각 담담히"
    );

    public String pickTopic() {
        return pickTopic(CharacterId.EMU);
    }

    public String pickTone() {
        return pickTone(CharacterId.EMU);
    }

    /**
     * 최근 픽한 토픽을 이 개수만큼 배제(동적 시드) — 복원추출이라 최근 소재가 재선택돼 편중되던 문제 완화.
     * 풀보다 작아야 함(NENE 18·EMU 20). 6 ≈ 최근 ~1.5일(4글/일) 내 같은 토픽 재등장 방지.
     */
    private static final int RECENT_WINDOW = 6;
    private final Map<CharacterId, Deque<String>> recentTopics = new EnumMap<>(CharacterId.class);

    public String pickTopic(CharacterId persona) {
        List<String> pool = persona == CharacterId.NENE ? NENE_TOPIC_SEEDS : TOPIC_SEEDS;
        return pickAvoidingRecent(persona, pool);
    }

    /** 최근 배제하고 픽 → 기록. 후보 소진(윈도우≥풀) 시 전체 풀 폴백. */
    private synchronized String pickAvoidingRecent(CharacterId persona, List<String> pool) {
        Deque<String> recent = recentTopics.computeIfAbsent(persona, k -> new ArrayDeque<>());
        List<String> candidates = new ArrayList<>(pool);
        candidates.removeAll(recent);
        if (candidates.isEmpty()) candidates = pool;
        String pick = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        recent.addLast(pick);
        while (recent.size() > RECENT_WINDOW) recent.removeFirst();
        return pick;
    }

    public String pickTone(CharacterId persona) {
        List<String> pool = persona == CharacterId.NENE ? NENE_TONE_SEEDS : TONE_SEEDS;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    public List<String> topicSeeds() {
        return TOPIC_SEEDS;
    }

    public List<String> toneSeeds() {
        return TONE_SEEDS;
    }
}
