package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 댓글 토픽 게이트 (페르소나 비종속 일반 룰).
 *
 * 에무는 밝은 일상 화제에만 자연스럽게 끼어든다. 범죄·사고·사망 등 무거운 사안의 글에는
 * 시그니처 톤이 부적합하므로 아예 댓글을 달지 않는다. 결정적(deterministic) 마커 검사라
 * LLM 호출 없이 후보 단계에서 걸러져 비용도 절감된다.
 *
 * 오탐(밝은 글 과차단)보다 미탐(무거운 글에 댓글) 쪽 피해가 크므로 마커는 고정밀로 유지하되,
 * 과장 표현('배고파 죽겠다')·농담('식곤증 무서운')에 걸리지 않게 단독 모호 토큰('죽','사고')은 제외한다.
 */
@Component
public class CommentTopicGate {

    /**
     * 무거운 사안 마커. 고정밀 유지를 위해 모호 단독 토큰('죽','사고','전쟁','충격','무서')은 제외하고
     * 다자(多字) 명시 토큰만 둔다. 미탐(무거운 글에 댓글)보다 오탐(밝은 글 차단)이 덜 해롭다는 전제.
     */
    private static final Set<String> HEAVY_MARKERS = Set.of(
            // 범죄·폭력
            "범죄", "범행", "성범죄", "성폭력", "성추행", "강간", "몰카", "불법촬영", "촬영까지",
            "폭행", "학대", "납치", "협박", "마약", "음주운전", "횡령", "사기 혐의",
            // 수사·처벌
            "구속", "체포", "검거", "기소", "징역", "실형", "유죄", "가해자", "피해자",
            // 사망·참사
            "사망", "숨졌", "숨진", "사상자", "참사", "부고", "별세", "자살", "극단적 선택",
            // 재난·사고
            "교통사고", "재난", "화재", "지진", "붕괴", "침몰", "추락사",
            // 전쟁·테러
            "전쟁범죄", "테러", "학살", "폭격"
    );

    public boolean isBrightEnough(Post post) {
        if (post == null) return true;
        return !containsHeavyMarker(post.title()) && !containsHeavyMarker(post.content());
    }

    private static boolean containsHeavyMarker(String text) {
        if (text == null || text.isBlank()) return false;
        for (String marker : HEAVY_MARKERS) {
            if (text.contains(marker)) return true;
        }
        return false;
    }
}
