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
     * 인물 참조 별칭 인덱스 — 원글이 인물을 *성(姓)*으로 불러도(예 '호시노 씨'=이치카) 스캔이 잡게 한다.
     * 별칭 = {이름 키} ∪ {두 페르소나 호칭 값에서 뽑은 이름/성 토큰}. 성은 이미 (주로 네네) 호칭 값에 들어있어
     * 별도 성 매핑 없이 자동 도출된다(GRADES 바뀌면 호칭 값 따라 자동 갱신). 형제 성(히노모리=시호·시즈쿠,
     * 시노노메=아키토·에나)처럼 한 토큰이 둘 이상 가리키면 모호해서 **유일 별칭만 채택**. 1글자 토큰도 제외(오탐).
     */
    private static final Map<String, String> ALIAS_TO_NAME = new LinkedHashMap<>();        // 별칭 토큰 → 이름 키
    private static final Map<String, java.util.Set<String>> NAME_TO_ALIASES = new LinkedHashMap<>(); // 이름 키 → 별칭 집합
    static {
        Map<String, java.util.Set<String>> cand = new LinkedHashMap<>();   // 토큰 → 가리키는 이름들(모호성 판정용)
        for (var e : BOOK.entrySet()) {
            java.util.Set<String> toks = new java.util.LinkedHashSet<>();
            toks.add(e.getKey());
            for (Address a : e.getValue().values()) {
                String t = leadingNameToken(a.call());
                if (t.length() >= 2) toks.add(t);
            }
            for (String t : toks) cand.computeIfAbsent(t, k -> new java.util.LinkedHashSet<>()).add(e.getKey());
        }
        for (String name : BOOK.keySet()) {   // 이름 키는 항상 자기 인물로 확정
            ALIAS_TO_NAME.put(name, name);
            NAME_TO_ALIASES.put(name, new java.util.LinkedHashSet<>(java.util.List.of(name)));
        }
        for (var e : cand.entrySet()) {        // 나머지(성 등)는 유일하게 한 인물만 가리킬 때만 채택
            if (ALIAS_TO_NAME.containsKey(e.getKey()) || e.getValue().size() != 1) continue;
            String name = e.getValue().iterator().next();
            ALIAS_TO_NAME.put(e.getKey(), name);
            NAME_TO_ALIASES.get(name).add(e.getKey());
        }
    }

    /** 호칭에서 이름/성 토큰 추출 — 첫 어절 + 접미(쨩/군/씨) 제거. '호시노 씨'→호시노, '아오야기군'→아오야기, '히노모리 선배'→히노모리. */
    private static String leadingNameToken(String call) {
        String first = call.split("\\s+")[0];
        for (String suf : new String[]{"쨩", "군", "씨"})
            if (first.length() > suf.length() && first.endsWith(suf)) return first.substring(0, first.length() - suf.length());
        return first;
    }

    /**
     * 원글 본문에서 PJSK 인물 이름을 스캔해, 발화 페르소나(speaker)의 호칭 힌트를 만든다.
     * 없으면 빈 문자열. relationshipLine에서 그 글 relationship 뒤에 붙인다.
     */
    public static String hintFor(CharacterId speaker, String body) {
        return build(speaker, body, "원글이");   // 댓글: 원글 본문 스캔
    }

    /** 글(post) 생성용 — 시드 텍스트를 스캔(원본이 시드라 문구만 다름). */
    public static String hintForSeed(CharacterId speaker, String seed) {
        return build(speaker, seed, "시드가");
    }

    /**
     * 생성된 글(자유 생성)에서 화자 고유 호칭이 아닌 *맨이름*으로 샌 PJSK 인물을 찾는다.
     * 시드-스캔(hintForSeed)은 시드에 있던 이름만 커버하나, 모델이 무대 글에서 유닛 동료를 자발적으로
     * 꺼내면(예: '루이') 힌트가 안 붙어 맨이름으로 샌다 → 생성 후 이 스캔으로 잡아 교정 콜을 트리거한다.
     *
     * <p>탐지: 화자 호칭 표기('루이군'·'히노모리 선배')를 먼저 지운 뒤에도 맨이름('루이'·'시즈쿠')이
     * 남으면 맨이름 누수. 화자가 맨이름 그대로 부르는 인물(유닛-내 네네→루이 등)은 제외.
     *
     * @return {맨이름 → 이 화자의 호칭} (교정 대상, 순서보존). 없으면 빈 맵.
     */
    public static Map<String, String> findBareLeaks(CharacterId speaker, String text) {
        Map<String, String> leaks = new LinkedHashMap<>();
        if (text == null || (speaker != CharacterId.EMU && speaker != CharacterId.NENE)) return leaks;
        for (var e : BOOK.entrySet()) {
            String name = e.getKey();
            Address a = e.getValue().get(speaker);
            if (a == null || a.call().equals(name)) continue;   // 맨이름 그대로 부름 → 누수 개념 없음
            String stripped = text.replace(a.call(), "");        // 화자 호칭 표기를 먼저 지움
            for (String alias : NAME_TO_ALIASES.get(name))       // 이름·성 등 별칭 중 하나라도 남으면 = 그 표기로 샜다
                if (stripped.contains(alias)) { leaks.put(alias, a.call()); break; }   // 키=샌 표기(호시노 등) → 교정콜서 정확 치환
        }
        return leaks;
    }

    private static String build(CharacterId speaker, String text, String src) {
        if (text == null || (speaker != CharacterId.EMU && speaker != CharacterId.NENE)) return "";
        Map<String, String> nameToAlias = new LinkedHashMap<>();   // 이름 → 본문서 매칭된 표기(성 포함), 인물당 1개
        for (var e : ALIAS_TO_NAME.entrySet())
            if (text.contains(e.getKey())) nameToAlias.putIfAbsent(e.getValue(), e.getKey());
        StringBuilder found = new StringBuilder();
        for (var e : nameToAlias.entrySet()) {
            String name = e.getKey(), alias = e.getValue();
            Address a = BOOK.get(name).get(speaker);
            if (a == null) continue;
            if (found.length() > 0) found.append(" / ");
            String shown = alias.equals(name) ? "'" + name + "'" : "'" + alias + "'(=" + name + ")";   // 성으로 불렸으면 명시
            found.append(shown).append("→**'").append(a.call()).append("'")
                 .append(a.jondaemal() ? "(존댓말)" : "").append("**");
        }
        if (found.length() == 0) return "";
        return " ⚠️ **이 글에 나온 PJSK 인물은 네가 이렇게 부른다: " + found
             + " — " + src + " 부른 호칭(맨이름·성 등)을 그대로 따라 쓰지 말고 이 호칭으로.**";
    }
}
