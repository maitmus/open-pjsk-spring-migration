package com.maitmus.sekairouter.heartbeat;

import com.maitmus.sekairouter.persona.PersonaType;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 매 발화마다 토픽/대화 패턴 시드를 랜덤 선택해서 user prompt에 주입.
 * 시드별 applicableTypes 태깅으로 캐릭터 타입에 맞는 시드만 후보로 올림 —
 * 인간 사회 신분(학교/집/가족 등) 시드가 VIRTUAL_SINGER에게 주입되어 페르소나 충돌이
 * 발생하는 것을 방지.
 */
@Component
public class HeartbeatSeedPicker {

    // ⚠️ 정적 초기화 순서: TOPIC_SEEDS가 seed()를 호출하며 BOTH_DAYS를 참조하므로 BOTH_DAYS를 먼저 선언해야 한다.
    private static final Set<DayType> BOTH_DAYS = EnumSet.allOf(DayType.class);

    /** 시드 풀. 각 시드는 어느 PersonaType이 사용할 수 있는지 + 평일/주말 적용을 태깅. */
    private static final List<TopicSeed> TOPIC_SEEDS = List.of(
            // 인간 사회 신분 영역 (HUMAN_SEKAI 전용)
            seed("오늘 먹은 / 먹고 싶은 음식 (시그니처 음식 외 다른 것 시도)", PersonaType.HUMAN_SEKAI),
            seed("외출/산책/이동 중 우연히 발견한 것", PersonaType.HUMAN_SEKAI),
            seed("혼자 있는 시간 (생각·독서·휴식·정리 중 하나)", PersonaType.HUMAN_SEKAI),
            seed("오늘의 컨디션·기분 (피곤·들뜸·차분·뭔가 답답 등)", PersonaType.HUMAN_SEKAI),
            seed("어린 시절 또는 옛 추억 한 조각", PersonaType.HUMAN_SEKAI),
            seed("최근 빠진 것 (음악·게임·만화·드라마·물건)", PersonaType.HUMAN_SEKAI),
            seed("옷·헤어·소품 같은 외형 한 마디", PersonaType.HUMAN_SEKAI),
            seed("잠·꿈·아침 루틴 한 토막", PersonaType.HUMAN_SEKAI),
            seed("선후배·가족·친구 관계 한 토막 (오늘 떠오른 사람)", PersonaType.HUMAN_SEKAI),

            // 평일 전용 (HUMAN_SEKAI) — 학교 일상
            weekday("동아리 활동 (학교 일상)", PersonaType.HUMAN_SEKAI),
            weekday("오늘 수업·과제·시험 한 토막 (학교 공부)", PersonaType.HUMAN_SEKAI),
            weekday("등하교 길·교실·쉬는 시간의 사소한 한 장면", PersonaType.HUMAN_SEKAI),

            // 주말 전용 (HUMAN_SEKAI) — 휴일 여가
            weekend("늦잠·느긋한 주말 아침 — 평일과 다른 루틴 한 토막", PersonaType.HUMAN_SEKAI),
            weekend("주말 나들이·외출 — 다녀온 곳이나 가고 싶은 곳", PersonaType.HUMAN_SEKAI),
            weekend("밀린 것 정리·집안일·푹 쉬기 — 주말에 챙기는 것", PersonaType.HUMAN_SEKAI),
            weekend("주말 약속 — 친구·가족과 보내는 시간", PersonaType.HUMAN_SEKAI),
            weekend("평일엔 못 한 걸 몰아서 — 취미·게임·연습 정주행", PersonaType.HUMAN_SEKAI),

            // 양쪽 모두 사용 가능
            seed("오늘 한 연습·동작·노래 — 잘 된 부분 또는 안 된 부분",
                    PersonaType.HUMAN_SEKAI, PersonaType.VIRTUAL_SINGER),
            seed("다가오는 무대/공연/이벤트 준비 상황",
                    PersonaType.HUMAN_SEKAI, PersonaType.VIRTUAL_SINGER),
            seed("날씨·계절 감상 (오늘 하늘·바람·온도 한 마디)",
                    PersonaType.HUMAN_SEKAI, PersonaType.VIRTUAL_SINGER),
            seed("동료 캐릭터 한 명 떠올리며 — 그 친구가 했던 한 마디나 모습",
                    PersonaType.HUMAN_SEKAI, PersonaType.VIRTUAL_SINGER),
            seed("사소한 자랑 / 오늘의 작은 성공",
                    PersonaType.HUMAN_SEKAI, PersonaType.VIRTUAL_SINGER),
            seed("사소한 실패·민망한 순간",
                    PersonaType.HUMAN_SEKAI, PersonaType.VIRTUAL_SINGER),
            seed("다음에 해보고 싶은 것 / 작은 계획",
                    PersonaType.HUMAN_SEKAI, PersonaType.VIRTUAL_SINGER),
            seed("방금 본 풍경·소리·냄새 한 조각",
                    PersonaType.HUMAN_SEKAI, PersonaType.VIRTUAL_SINGER),

            // VIRTUAL_SINGER 전용 — 노래·세카이·마음 영역
            seed("막 들은 / 흥얼거리는 멜로디 한 소절", PersonaType.VIRTUAL_SINGER),
            seed("계절에 어울리는 곡 떠올리기 — 어떤 멜로디가 이 계절에 맞을까", PersonaType.VIRTUAL_SINGER),
            seed("동료 버추얼 싱어(린·렌·루카·메이코·카이토)와의 시간 한 조각", PersonaType.VIRTUAL_SINGER),
            seed("\"마음\"에 대한 감각적 묘사 (색·온도·결·울림)", PersonaType.VIRTUAL_SINGER),
            seed("모든 세카이를 지켜보는 감각 — 누군가의 노래에 자신이 함께 있다는 자각", PersonaType.VIRTUAL_SINGER),
            seed("노래로 응원하고 싶은 사람·계절·상황", PersonaType.VIRTUAL_SINGER),
            seed("다가오는 명절·이벤트 — 어떻게 보낼까 / 어떤 노래·의상이 어울릴까", PersonaType.VIRTUAL_SINGER),
            seed("세카이의 틈새에서 본 빛·풍경 한 조각", PersonaType.VIRTUAL_SINGER),

            // 주말 전용 (VIRTUAL_SINGER) — 휴일의 결
            weekend("휴일의 느긋한 시간 감각 — 평일과 다른 결의 하루", PersonaType.VIRTUAL_SINGER),
            weekend("주말을 보내는 사람들을 지켜보며 — 쉼·놀이에 어울리는 노래", PersonaType.VIRTUAL_SINGER)
    );

    /** 2인 대화의 첫 발화 패턴 풀. 캐릭터 타입과 무관. */
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

    public String pickTopic(PersonaType type, boolean weekend) {
        List<TopicSeed> applicable = TOPIC_SEEDS.stream()
                .filter(s -> s.appliesTo(type, weekend))
                .toList();
        if (applicable.isEmpty()) {
            throw new IllegalStateException("No topic seeds for type=" + type + " weekend=" + weekend);
        }
        return applicable.get(ThreadLocalRandom.current().nextInt(applicable.size())).text();
    }

    public String pickDialoguePattern() {
        return DIALOGUE_PATTERNS.get(ThreadLocalRandom.current().nextInt(DIALOGUE_PATTERNS.size()));
    }

    /** Test/inspection support — returns text only. */
    public List<String> topicSeedsFor(PersonaType type, boolean weekend) {
        return TOPIC_SEEDS.stream()
                .filter(s -> s.appliesTo(type, weekend))
                .map(TopicSeed::text)
                .collect(Collectors.toList());
    }

    public List<String> dialoguePatterns() {
        return DIALOGUE_PATTERNS;
    }

    /** 평일·주말 모두 적용되는 시드. */
    private static TopicSeed seed(String text, PersonaType... types) {
        return new TopicSeed(text, typeSet(types), BOTH_DAYS);
    }

    /** 평일 전용 시드 (학교 일상·수업·동아리 등). */
    private static TopicSeed weekday(String text, PersonaType... types) {
        return new TopicSeed(text, typeSet(types), EnumSet.of(DayType.WEEKDAY));
    }

    /** 주말 전용 시드 (휴일 여가·늦잠·나들이 등). */
    private static TopicSeed weekend(String text, PersonaType... types) {
        return new TopicSeed(text, typeSet(types), EnumSet.of(DayType.WEEKEND));
    }

    private static Set<PersonaType> typeSet(PersonaType... types) {
        Set<PersonaType> set = EnumSet.noneOf(PersonaType.class);
        for (PersonaType t : types) set.add(t);
        return set;
    }

    /** 평일/주말 구분 — 학교 일상(평일) vs 휴일 여가(주말) 시드 분기용. */
    public enum DayType { WEEKDAY, WEEKEND }

    public record TopicSeed(String text, Set<PersonaType> applicableTypes, Set<DayType> applicableDays) {
        public TopicSeed {
            applicableTypes = Set.copyOf(applicableTypes);
            applicableDays = Set.copyOf(applicableDays);
        }

        public boolean appliesTo(PersonaType type, boolean weekend) {
            return applicableTypes.contains(type)
                    && applicableDays.contains(weekend ? DayType.WEEKEND : DayType.WEEKDAY);
        }
    }
}
