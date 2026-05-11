package com.maitmus.sekairouter.heartbeat;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 매 발화마다 토픽/대화 패턴 시드를 랜덤 선택해서 user prompt에 주입.
 * 캐릭터별 시그니처 토픽(붕어빵·조깅·대전 게임 등)에 LLM이 anchor되어 반복하는 현상 완화.
 */
@Component
public class HeartbeatSeedPicker {

    /** 솔로/이벤트 외 일반 발화의 토픽 시드 풀. 캐릭터 페르소나 안에서 자연 변주 가능한 각도들. */
    private static final List<String> TOPIC_SEEDS = List.of(
            "오늘 먹은 / 먹고 싶은 음식 (시그니처 음식 외 다른 것 시도)",
            "오늘 한 연습·동작·노래 — 잘 된 부분 또는 안 된 부분",
            "다가오는 무대/공연/이벤트 준비 상황",
            "날씨·계절 감상 (오늘 하늘·바람·온도 한 마디)",
            "동료 캐릭터 한 명 떠올리며 — 그 친구가 했던 한 마디나 모습",
            "외출/산책/이동 중 우연히 발견한 것",
            "혼자 있는 시간 (생각·독서·휴식·정리 중 하나)",
            "사소한 자랑 / 오늘의 작은 성공",
            "사소한 실패·민망한 순간",
            "오늘의 컨디션·기분 (피곤·들뜸·차분·뭔가 답답 등)",
            "다음에 해보고 싶은 것 / 작은 계획",
            "어린 시절 또는 옛 추억 한 조각",
            "동아리 활동 (학교 일상)",
            "방금 본 풍경·소리·냄새 한 조각",
            "최근 빠진 것 (음악·게임·만화·드라마·물건)",
            "옷·헤어·소품 같은 외형 한 마디",
            "잠·꿈·아침 루틴 한 토막",
            "선후배·가족·친구 관계 한 토막 (오늘 떠오른 사람)"
    );

    /** 2인 대화의 첫 발화 패턴 풀. 의문형에 쏠리는 현상 해소. */
    private static final List<String> DIALOGUE_PATTERNS = List.of(
            "질문형: '요즘 ~ 어때?' 같은 안부/근황 질문",
            "공유형: '오늘 ~ 발견했어/봤어' 같은 사소한 발견 공유",
            "제안형: '~ 같이 ~할래?' / '~ 가지 않을래?' 가벼운 제안",
            "자랑형: '오늘 ~ 성공했어!' / '~ 됐어!' 작은 자랑",
            "부탁형: '~ 좀 도와줄 수 있어?' 가벼운 부탁",
            "혼잣말 던지기형: 동료가 옆에 있는 걸 의식하지 않고 혼자 중얼거리듯",
            "감탄/공감형: '~ 봤어?! 진짜 ~한 거 아니야?' 감탄 공유",
            "고민형: '요즘 ~ 어떻게 해야 할지 잘 모르겠어' 사소한 고민",
            "회상형: '예전에 ~ 했을 때 기억나?' 옛 일 한 마디",
            "보고형: '오늘 ~ 였어' 무덤덤한 사실 한 줄"
    );

    public String pickTopic() {
        return TOPIC_SEEDS.get(ThreadLocalRandom.current().nextInt(TOPIC_SEEDS.size()));
    }

    public String pickDialoguePattern() {
        return DIALOGUE_PATTERNS.get(ThreadLocalRandom.current().nextInt(DIALOGUE_PATTERNS.size()));
    }

    /** Test/inspection support. */
    public List<String> topicSeeds() {
        return TOPIC_SEEDS;
    }

    public List<String> dialoguePatterns() {
        return DIALOGUE_PATTERNS;
    }
}
