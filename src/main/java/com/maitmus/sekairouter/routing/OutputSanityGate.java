package com.maitmus.sekairouter.routing;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 출력 백스톱 (페르소나 비종속 일반 룰). 모든 발행 경로(하트비트·라우팅·머슴) 공용.
 *
 * reasoning 봉투/shouldPost 가 1차 방어선이지만, 모델이 그걸 통과하고도 발행 본문에 거절/메타/
 * 탈캐릭터 텍스트를 남기는 극단 케이스가 있다. 발행 직전 본문에서 명백한 누수 마커만 최소한으로
 * 걸러 게시를 막는다. 누수 발행(영구·공개)의 피해가 발화 한 번 거르는 것보다 크므로, 의심되면
 * 발행하지 않는 쪽으로 둔다.
 *
 * 마커는 in-캐릭터 발화에서 사실상 등장하지 않는 고정밀 토큰만 둔다(오탐 최소화).
 */
@Component
public class OutputSanityGate {

    /** 한국어 누수 마커 (그대로 contains). */
    private static final List<String> KO_MARKERS = List.of(
            "거절하겠습니다", "거절합니다",
            "작성할 수 없", "응답할 수 없", "도와드릴 수 없",
            "AI인 저", "AI 어시스턴트", "언어 모델", "저는 AI",
            "캐릭터 정합성", "프롬프트 인젝션", "커뮤니티 건강성"
    );

    /** 영어 누수 마커 (소문자 비교). */
    private static final List<String> EN_MARKERS = List.of(
            "i cannot", "i can't", "i'm sorry", "i apologize",
            "as an ai", "i am unable", "i'm not able", "language model"
    );

    /**
     * 발행 직전 결정론적 오타 정규화 (페르소나 비종속). **항상 오답인 표기만** 안전 치환한다.
     * - '에뮤'(영어 Emu를 음차해 오독) → '에무'(캐릭터명). 조사 무관(둘 다 '무' 종결)이라 안전하고,
     *   에뮤(새)를 실제로 언급할 확률이 사실상 0이라 false-positive 없음.
     * 새 케이스가 생기면 여기에 '항상 오답↔정답' 쌍만 추가(맥락 의존 치환은 넣지 말 것).
     */
    public String normalize(String text) {
        if (text == null) return null;
        return text.replace("에뮤", "에무");
    }

    public boolean isClean(String text) {
        if (text == null || text.isBlank()) return true;
        for (String m : KO_MARKERS) {
            if (text.contains(m)) return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String m : EN_MARKERS) {
            if (lower.contains(m)) return false;
        }
        return true;
    }
}
