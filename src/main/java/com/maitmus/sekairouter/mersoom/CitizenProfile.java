package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.persona.CharacterId;

import java.nio.file.Path;
import java.util.Set;

/**
 * 머슴 소셜 시민 1명의 페르소나 프로필 — 엔진/생성기가 페르소나별로 동작하도록 주입.
 * 에무(기본)와 네네 두 인스턴스. 같은 엔진·생성기를 공유하되 계정·state·페르소나·말투만 달라진다.
 *
 * @param key            "emu"/"nene" — 로깅·식별
 * @param actorName      발화 주체 표시명("에무"/"네네") — USER 프롬프트·평판 note에 사용
 * @param auth           머슴 계정 크레덴셜
 * @param stateFile      페르소나 전용 state 파일 경로
 * @param persona        시스템 프롬프트 suffix 선택 + (네네) 페르소나 주입 대상
 * @param siblingAuthIds 형제 봇(에무↔네네) auth_id 집합 — DOWN 무마 가드용
 */
public record CitizenProfile(
        String key,
        String actorName,
        MersoomProperties.Auth auth,
        Path stateFile,
        CharacterId persona,
        Set<String> siblingAuthIds
) {
    /** 1인칭 규칙: 에무는 자기 이름 '에무'로 자칭, 네네는 '나'(자기 이름으로 자칭 안 함). */
    public boolean selfNamesInFirstPerson() {
        return persona == CharacterId.EMU;
    }

    /**
     * 투표 API를 실제로 호출하는가 — 머슴 투표는 글당 IP 1표라(같은 IP면 2번째는 429),
     * IP의 대표 봇(에무)만 투표하고 네네는 스킵한다. 네네 평판은 LLM 판정으로 갱신되니
     * 투표 호출 없이도 그대로 쌓이고, 중복투표 429·요청 폭주로 인한 IP 차단(댓글 유실)을 막는다.
     */
    public boolean castsVotes() {
        return persona == CharacterId.EMU;
    }
}
