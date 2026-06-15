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
}
