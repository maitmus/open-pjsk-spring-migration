package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.persona.CharacterId;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PJSK 인물 호칭 주소록 — 에무·네네가 각 PJSK 캐릭터를 부르는 호칭(GRADES.md 매트릭스 curate).
 *
 * <p>머슴 댓글에서 형제봇 등이 원글 본문에 PJSK 인물을 맨이름으로 언급하면, 발화 봇이 그걸 그대로
 * echo해 자기 고유 호칭(에무='루이군', 네네='아오야기군' 등)이 깨지는 문제를 막는다. GRADES를
 * 프롬프트에 통째로 넣어도 모델이 큰 표를 능동 조회하지 않아(루이 3/3이나 토우야 1/3·이치카 0/2)
 * 실효가 부분적이었다 → 탐지·룩업을 코드로 옮겨 그 글 옆에 point-of-use 힌트를 주입한다(자기지목 ① 패턴).
 *
 * <p>키 = 캐릭터의 흔히 불리는 *이름 토큰*(원글이 그 이름으로 언급 → 스캔 매칭). 값 = 발화 페르소나별 호칭.
 * GRADES 표 기준: 에무=이름+쨩/군(+선배/존댓말), 네네=성+씨/군(+존댓말 일부). 원더쇼 유닛-내(루이·츠카사)는
 * 에무=이름+군 / 네네=맨이름. 캐스트는 안정적이라 상수로 curate(GRADES 변경 시에만 수동 동기화).
 *
 * <p>1글자 이름(안·린·렌)은 오탐(부분일치) 위험이 커 스캔 대상에서 제외(머슴 댓글서 드묾).
 */
public final class PjskAddressBook {

    private PjskAddressBook() {}

    /** 한 호칭. jondaemal=true면 그 인물에겐 존댓말(~요/~세요). */
    public record Address(String call, boolean jondaemal) {}

    private static Map<CharacterId, Address> row(String emuCall, boolean emuJon, String neneCall, boolean neneJon) {
        Map<CharacterId, Address> m = new LinkedHashMap<>();
        m.put(CharacterId.EMU, new Address(emuCall, emuJon));
        m.put(CharacterId.NENE, new Address(neneCall, neneJon));
        return m;
    }

    /** 이름 토큰 → {에무 호칭, 네네 호칭}. (에무: 이름+쨩/군 / 네네: 성+씨/군, GRADES 기준) */
    private static final Map<String, Map<CharacterId, Address>> BOOK = new LinkedHashMap<>();
    static {
        BOOK.put("이치카", row("이치카쨩", false, "호시노 씨", false));
        BOOK.put("사키",   row("사키쨩", false, "텐마 씨", false));
        BOOK.put("호나미", row("호나미쨩", false, "모치즈키 씨", false));
        BOOK.put("시호",   row("시호쨩", false, "히노모리 씨", false));
        BOOK.put("미노리", row("미노리쨩", false, "하나사토 씨", false));
        BOOK.put("하루카", row("하루카쨩", false, "키리타니 씨", false));
        BOOK.put("아이리", row("아이리쨩 선배", false, "모모이 씨", false));
        BOOK.put("시즈쿠", row("히노모리 선배", true, "히노모리 씨", false));
        BOOK.put("코하네", row("코하네쨩", false, "아즈사와 씨", false));
        BOOK.put("아키토", row("아키토군", false, "시노노메군", false));
        BOOK.put("토우야", row("토우야군", false, "아오야기군", false));
        BOOK.put("카나데", row("카나데쨩", false, "요이사키 언니", true));
        BOOK.put("마후유", row("아사히나 선배", true, "아사히나 씨", false));
        BOOK.put("에나",   row("에나 씨", false, "시노노메 씨", false));
        BOOK.put("미즈키", row("미즈키쨩", false, "아키야마 씨", false));
        BOOK.put("미쿠",   row("미쿠쨩", false, "미쿠", false));
        BOOK.put("루카",   row("루카 언니", false, "루카 씨", false));
        BOOK.put("메이코", row("메이코 언니", false, "메이코 씨", false));
        BOOK.put("카이토", row("카이토 오빠", false, "카이토 씨", true));
        // 원더랜즈×쇼타임 유닛-내 (에무: 이름+군 / 네네: 맨이름)
        BOOK.put("츠카사", row("츠카사군", false, "츠카사", false));
        BOOK.put("루이",   row("루이군", false, "루이", false));
    }

    /**
     * 원글 본문에서 PJSK 인물 이름을 스캔해, 발화 페르소나(speaker)의 호칭 힌트를 만든다.
     * 없으면 빈 문자열. relationshipLine에서 그 글 relationship 뒤에 붙인다.
     */
    public static String hintFor(CharacterId speaker, String body) {
        if (body == null || (speaker != CharacterId.EMU && speaker != CharacterId.NENE)) return "";
        StringBuilder found = new StringBuilder();
        for (var e : BOOK.entrySet()) {
            String name = e.getKey();
            if (!body.contains(name)) continue;
            Address a = e.getValue().get(speaker);
            if (a == null) continue;
            if (found.length() > 0) found.append(" / ");
            found.append("'").append(name).append("'→**'").append(a.call()).append("'")
                 .append(a.jondaemal() ? "(존댓말)" : "").append("**");
        }
        if (found.length() == 0) return "";
        return " ⚠️ **이 글에 나온 PJSK 인물은 네가 이렇게 부른다: " + found
             + " — 원글이 부른 호칭(맨이름 등)을 그대로 따라 쓰지 말고 이 호칭으로.**";
    }
}
