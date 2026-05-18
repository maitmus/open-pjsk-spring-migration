package com.maitmus.sekairouter.persona;

import java.util.Arrays;
import java.util.Optional;

/**
 * Persona 분류 — 발화 가능한 토픽 시드의 범위를 결정한다.
 *
 * - VIRTUAL_SINGER: 인간으로서의 사회 신분(학교/집/가족/아르바이트) 없음. 노래·세카이·마음 영역.
 *   예: 미쿠, 향후 추가될 린·렌·루카·메이코·카이토 등.
 * - HUMAN_SEKAI: 현실 세계의 인간 캐릭터. 학교·동아리·가족·아르바이트 등 일상 영역 사용 가능.
 *   예: 에무·네네·하루카·아이리·미노리·시즈쿠.
 */
public enum PersonaType {
    VIRTUAL_SINGER,
    HUMAN_SEKAI;

    public static Optional<PersonaType> fromString(String s) {
        if (s == null) return Optional.empty();
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(s.trim()))
                .findFirst();
    }
}
