package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.persona.CharacterId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 머슴 글 생성 시 무작위로 정하는 것 — 소재 후보 번호와 톤·도입 패턴.
 * 소재 자체는 모델이 매번 후보 8개를 떠올린다(2026-11-09~). 예전 고정 토픽 시드 풀은 페르소나 설정(연습·원더쇼·노래·게임)으로
 * 글을 쏠리게 했고, 고르기까지 모델에 맡기면 가장 무난한 후보로 수렴해서 번호만 코드가 정한다.
 */
@Component
public class MersoomSeedPicker {

    static final int CANDIDATES = 8;

    /**
     * 톤·도입 패턴 풀. 매 글의 시작 결을 비끄러뜨려 자기 강화 회피.
     */
    private static final List<String> TONE_SEEDS = List.of(
            "즉시감: \"지금 막 ~\" / \"방금 ~\" 으로 도입",
            "공유감: 새로 알게 된 걸 친구한테 바로 알려주듯",
            "자랑·기쁨감: 오늘 잘 된 작은 일 한 가지를 자랑하듯",
            "약속·기대감: 다음에 할 일·다가올 일 기대감으로 마무리",
            "고민감: 사소한 고민·결정 한 마디 풀어내기",
            "회상감: 어제·옛날 일 한 조각 떠올리기"
    );

    /** 네네 톤·도입 패턴 풀 — 무심·직설·츤데레 결. */
    private static final List<String> NENE_TONE_SEEDS = List.of(
            "무심감: \"...별로 대단한 건 아닌데\" 식으로 슬쩍 도입",
            "직설감: 핵심부터 툭 던지고 뒤에 부연",
            "츳코미감: 누군가의 과한 행동에 차분한 일침",
            "자신감: 잘 아는 화제면 단정적으로",
            "회피·완곡감: \"...뭐\", \"...그렇긴 해\" 로 여운 남기기",
            "회상감: 어제·옛날 일 한 조각 담담히"
    );

    public String pickTone() {
        return pickTone(CharacterId.EMU);
    }

    public String pickTone(CharacterId persona) {
        List<String> pool = persona == CharacterId.NENE ? NENE_TONE_SEEDS : TONE_SEEDS;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /** 글 소재 후보(모델이 reasoning에 {@value #CANDIDATES}개 나열) 중 쓸 번호(1-based). */
    public int pickCandidate() {
        return ThreadLocalRandom.current().nextInt(1, CANDIDATES + 1);
    }

    public List<String> toneSeeds() {
        return TONE_SEEDS;
    }
}
