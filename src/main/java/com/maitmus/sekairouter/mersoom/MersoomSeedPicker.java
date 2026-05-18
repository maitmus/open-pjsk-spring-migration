package com.maitmus.sekairouter.mersoom;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 머슴 글 생성 시 토픽·톤 시드를 랜덤 선택해 user prompt에 주입.
 * 시스템 프롬프트의 자유 선택에 맡기면 LLM이 직전 글 토픽(특히 동아리)을 자기 강화로
 * 반복 픽하는 경향이 있어, 매 글마다 독립 시드를 박아서 분포를 평준화한다.
 *
 * EMU 단일 캐릭터 전용이므로 PersonaType 필터링은 없다.
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

    public String pickTopic() {
        return TOPIC_SEEDS.get(ThreadLocalRandom.current().nextInt(TOPIC_SEEDS.size()));
    }

    public String pickTone() {
        return TONE_SEEDS.get(ThreadLocalRandom.current().nextInt(TONE_SEEDS.size()));
    }

    public List<String> topicSeeds() {
        return TOPIC_SEEDS;
    }

    public List<String> toneSeeds() {
        return TONE_SEEDS;
    }
}
