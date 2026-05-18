# 시간대 인지 발화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Heartbeat 발화에 학생 일과 기반 시간대 라벨(평일 6 / 주말 4)을 주입해 토픽 plausibility 향상.

**Architecture:** Stateless `TimeOfDayLabeler` 컴포넌트가 `LocalDateTime`을 받아 라벨 + 프롬프트 블록 문자열을 반환. `HeartbeatService`의 4개 발화 경로(normal solo / dialogue 첫 / dialogue 응답 / event)에서 기존 `## 오늘 날짜 (KST)` 블록을 `## 현재 시각 (KST) + label` 블록으로 교체. Daily weather cast는 변경 없음.

**Tech Stack:** Spring Boot 3.4.1 / Java 24 / JUnit 5 + Mockito + AssertJ / Gradle.

**Spec:** `docs/superpowers/specs/2026-05-18-time-of-day-utterance-design.md`

---

## File Structure

- Create: `src/main/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabeler.java`
  - Responsibility: 시각 → 라벨 매핑 + 프롬프트 블록 포맷팅. Stateless `@Component`.
- Create: `src/test/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabelerTest.java`
  - Responsibility: Labeler 단위 테스트 (경계, 요일, 포맷, fallback).
- Modify: `src/main/java/com/maitmus/sekairouter/heartbeat/HeartbeatService.java`
  - Add `TimeOfDayLabeler` field. 4개 발화 경로의 프롬프트 빌더에서 날짜 블록 교체. Weather cast 미변경.
- Modify: `src/test/java/com/maitmus/sekairouter/heartbeat/HeartbeatServiceTest.java`
  - `buildService` 헬퍼에 Labeler 주입. 4 경로 프롬프트 검증 추가.

---

## Task 1: TimeOfDayLabeler — 평일 라벨 매핑

**Files:**
- Create: `src/test/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabelerTest.java`
- Create: `src/main/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabeler.java`

- [ ] **Step 1: Write failing tests for weekday boundaries**

Create `src/test/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabelerTest.java`:

```java
package com.maitmus.sekairouter.heartbeat;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TimeOfDayLabelerTest {

    private final TimeOfDayLabeler labeler = new TimeOfDayLabeler();

    // 2026-05-13 = Wednesday (평일)
    private LocalDateTime weekday(int hour, int minute) {
        return LocalDateTime.of(2026, 5, 13, hour, minute, 0);
    }

    @Test
    void weekday_morningClass_10to11() {
        assertThat(labeler.label(weekday(10, 0))).isEqualTo("오전 수업");
        assertThat(labeler.label(weekday(11, 59))).isEqualTo("오전 수업");
    }

    @Test
    void weekday_lunch_12() {
        assertThat(labeler.label(weekday(12, 0))).isEqualTo("점심시간");
        assertThat(labeler.label(weekday(12, 59))).isEqualTo("점심시간");
    }

    @Test
    void weekday_afternoonClass_13to14() {
        assertThat(labeler.label(weekday(13, 0))).isEqualTo("오후 수업");
        assertThat(labeler.label(weekday(14, 59))).isEqualTo("오후 수업");
    }

    @Test
    void weekday_afterSchool_15to17() {
        assertThat(labeler.label(weekday(15, 0))).isEqualTo("방과 후");
        assertThat(labeler.label(weekday(17, 59))).isEqualTo("방과 후");
    }

    @Test
    void weekday_eveningHome_18to19() {
        assertThat(labeler.label(weekday(18, 0))).isEqualTo("귀가/저녁");
        assertThat(labeler.label(weekday(19, 59))).isEqualTo("귀가/저녁");
    }

    @Test
    void weekday_nightRest_20() {
        assertThat(labeler.label(weekday(20, 0))).isEqualTo("밤 휴식");
        assertThat(labeler.label(weekday(20, 59))).isEqualTo("밤 휴식");
    }
}
```

- [ ] **Step 2: Run test to verify compile fail**

Run: `cd /home/maitmus/projects/open-pjsk-spring-migration && ./gradlew test --tests TimeOfDayLabelerTest`
Expected: COMPILE FAILURE — `TimeOfDayLabeler` class not found.

- [ ] **Step 3: Implement TimeOfDayLabeler with weekday-only support**

Create `src/main/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabeler.java`:

```java
package com.maitmus.sekairouter.heartbeat;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Maps a KST LocalDateTime to a student-schedule-aware time-of-day label
 * used in heartbeat user prompts to keep topics plausible for the slot.
 */
@Component
public class TimeOfDayLabeler {

    public String label(LocalDateTime now) {
        int hour = now.getHour();
        if (hour < 12) return "오전 수업";
        if (hour < 13) return "점심시간";
        if (hour < 15) return "오후 수업";
        if (hour < 18) return "방과 후";
        if (hour < 20) return "귀가/저녁";
        return "밤 휴식";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/maitmus/projects/open-pjsk-spring-migration && ./gradlew test --tests TimeOfDayLabelerTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/maitmus/projects/open-pjsk-spring-migration
git add src/main/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabeler.java \
        src/test/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabelerTest.java
git commit -m "feat(heartbeat): TimeOfDayLabeler 평일 6구간 라벨 매핑

학생 일과(오전 수업/점심시간/오후 수업/방과 후/귀가·저녁/밤 휴식)
기반 시간대 라벨. 평일만 지원. 주말/fallback은 후속 커밋."
```

---

## Task 2: TimeOfDayLabeler — 주말 라벨 매핑

**Files:**
- Modify: `src/test/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabelerTest.java`
- Modify: `src/main/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabeler.java`

- [ ] **Step 1: Add failing tests for weekend boundaries**

Append to `TimeOfDayLabelerTest`:

```java
    // 2026-05-16 = Saturday, 2026-05-17 = Sunday
    private LocalDateTime saturday(int hour, int minute) {
        return LocalDateTime.of(2026, 5, 16, hour, minute, 0);
    }
    private LocalDateTime sunday(int hour, int minute) {
        return LocalDateTime.of(2026, 5, 17, hour, minute, 0);
    }

    @Test
    void weekend_morning_10to11_saturday() {
        assertThat(labeler.label(saturday(10, 0))).isEqualTo("주말 오전");
        assertThat(labeler.label(saturday(11, 59))).isEqualTo("주말 오전");
    }

    @Test
    void weekend_morning_10to11_sunday() {
        assertThat(labeler.label(sunday(10, 0))).isEqualTo("주말 오전");
        assertThat(labeler.label(sunday(11, 59))).isEqualTo("주말 오전");
    }

    @Test
    void weekend_lunch_12() {
        assertThat(labeler.label(saturday(12, 0))).isEqualTo("주말 점심");
        assertThat(labeler.label(sunday(12, 59))).isEqualTo("주말 점심");
    }

    @Test
    void weekend_afternoon_13to17() {
        assertThat(labeler.label(saturday(13, 0))).isEqualTo("주말 오후");
        assertThat(labeler.label(saturday(17, 59))).isEqualTo("주말 오후");
        assertThat(labeler.label(sunday(15, 30))).isEqualTo("주말 오후");
    }

    @Test
    void weekend_evening_18to20() {
        assertThat(labeler.label(saturday(18, 0))).isEqualTo("주말 저녁");
        assertThat(labeler.label(saturday(20, 59))).isEqualTo("주말 저녁");
        assertThat(labeler.label(sunday(19, 0))).isEqualTo("주말 저녁");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/maitmus/projects/open-pjsk-spring-migration && ./gradlew test --tests TimeOfDayLabelerTest`
Expected: FAIL — weekend hours currently match weekday labels.

- [ ] **Step 3: Add weekend branch**

Modify `TimeOfDayLabeler.java`:

```java
package com.maitmus.sekairouter.heartbeat;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

/**
 * Maps a KST LocalDateTime to a student-schedule-aware time-of-day label
 * used in heartbeat user prompts to keep topics plausible for the slot.
 */
@Component
public class TimeOfDayLabeler {

    public String label(LocalDateTime now) {
        int hour = now.getHour();
        DayOfWeek dow = now.getDayOfWeek();
        boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;

        if (weekend) {
            if (hour < 12) return "주말 오전";
            if (hour < 13) return "주말 점심";
            if (hour < 18) return "주말 오후";
            return "주말 저녁";
        }
        if (hour < 12) return "오전 수업";
        if (hour < 13) return "점심시간";
        if (hour < 15) return "오후 수업";
        if (hour < 18) return "방과 후";
        if (hour < 20) return "귀가/저녁";
        return "밤 휴식";
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/maitmus/projects/open-pjsk-spring-migration && ./gradlew test --tests TimeOfDayLabelerTest`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/maitmus/projects/open-pjsk-spring-migration
git add src/main/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabeler.java \
        src/test/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabelerTest.java
git commit -m "feat(heartbeat): TimeOfDayLabeler 주말 4구간 분기 추가

DayOfWeek 토/일 → 주말 라벨(주말 오전/점심/오후/저녁) 4구간 매핑.
주말은 학교 일과 의미 축소, 시간대 묶음만 사용."
```

---

## Task 3: TimeOfDayLabeler — 활성 시간 외 fallback

**Files:**
- Modify: `src/test/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabelerTest.java`
- Modify: `src/main/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabeler.java`

- [ ] **Step 1: Add failing tests for inactive hours**

Append to `TimeOfDayLabelerTest`:

```java
    @Test
    void weekday_beforeActive_09_59_returnsFallback() {
        assertThat(labeler.label(weekday(9, 59))).isEqualTo("활성 시간 외");
    }

    @Test
    void weekday_afterActive_21_00_returnsFallback() {
        assertThat(labeler.label(weekday(21, 0))).isEqualTo("활성 시간 외");
    }

    @Test
    void weekend_beforeActive_09_59_returnsFallback() {
        assertThat(labeler.label(saturday(9, 59))).isEqualTo("활성 시간 외");
        assertThat(labeler.label(sunday(0, 0))).isEqualTo("활성 시간 외");
    }

    @Test
    void weekend_afterActive_21_00_returnsFallback() {
        assertThat(labeler.label(saturday(21, 0))).isEqualTo("활성 시간 외");
        assertThat(labeler.label(sunday(23, 59))).isEqualTo("활성 시간 외");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/maitmus/projects/open-pjsk-spring-migration && ./gradlew test --tests TimeOfDayLabelerTest`
Expected: FAIL — currently 09:59 returns "오전 수업" / "주말 오전", 21:00 returns "밤 휴식" / "주말 저녁".

- [ ] **Step 3: Add fallback gate at top of label()**

Replace the body of `label()` in `TimeOfDayLabeler.java`:

```java
    public String label(LocalDateTime now) {
        int hour = now.getHour();
        if (hour < 10 || hour >= 21) return "활성 시간 외";
        DayOfWeek dow = now.getDayOfWeek();
        boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;

        if (weekend) {
            if (hour < 12) return "주말 오전";
            if (hour < 13) return "주말 점심";
            if (hour < 18) return "주말 오후";
            return "주말 저녁";
        }
        if (hour < 12) return "오전 수업";
        if (hour < 13) return "점심시간";
        if (hour < 15) return "오후 수업";
        if (hour < 18) return "방과 후";
        if (hour < 20) return "귀가/저녁";
        return "밤 휴식";
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/maitmus/projects/open-pjsk-spring-migration && ./gradlew test --tests TimeOfDayLabelerTest`
Expected: PASS (15 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/maitmus/projects/open-pjsk-spring-migration
git add src/main/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabeler.java \
        src/test/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabelerTest.java
git commit -m "feat(heartbeat): TimeOfDayLabeler 활성 시간 외 fallback

quiet hours(21:00~10:00) 호출 시 '활성 시간 외' 반환. 정상 경로에서는
도달 불가지만 NPE/오프-by-one 안전성 확보."
```

---

## Task 4: TimeOfDayLabeler — promptBlock() 포맷

**Files:**
- Modify: `src/test/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabelerTest.java`
- Modify: `src/main/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabeler.java`

- [ ] **Step 1: Add failing tests for promptBlock format**

Append to `TimeOfDayLabelerTest`:

```java
    @Test
    void promptBlock_weekday_formatsAsExpected() {
        // 2026-05-13 (수) 14:23 → 오후 수업
        LocalDateTime t = LocalDateTime.of(2026, 5, 13, 14, 23, 0);
        assertThat(labeler.promptBlock(t))
                .isEqualTo("## 현재 시각 (KST)\n2026-05-13 (수) 14:23 (오후 수업)");
    }

    @Test
    void promptBlock_saturday_morning() {
        LocalDateTime t = LocalDateTime.of(2026, 5, 16, 10, 5, 0);
        assertThat(labeler.promptBlock(t))
                .isEqualTo("## 현재 시각 (KST)\n2026-05-16 (토) 10:05 (주말 오전)");
    }

    @Test
    void promptBlock_sunday_evening() {
        LocalDateTime t = LocalDateTime.of(2026, 5, 17, 19, 0, 0);
        assertThat(labeler.promptBlock(t))
                .isEqualTo("## 현재 시각 (KST)\n2026-05-17 (일) 19:00 (주말 저녁)");
    }

    @Test
    void promptBlock_inactiveHour_stillFormatsWithFallbackLabel() {
        LocalDateTime t = LocalDateTime.of(2026, 5, 13, 9, 59, 0);
        assertThat(labeler.promptBlock(t))
                .isEqualTo("## 현재 시각 (KST)\n2026-05-13 (수) 09:59 (활성 시간 외)");
    }
```

- [ ] **Step 2: Run tests to verify compile/test fail**

Run: `cd /home/maitmus/projects/open-pjsk-spring-migration && ./gradlew test --tests TimeOfDayLabelerTest`
Expected: COMPILE FAIL — `promptBlock` method missing.

- [ ] **Step 3: Implement promptBlock()**

Add to `TimeOfDayLabeler.java` (full file shown):

```java
package com.maitmus.sekairouter.heartbeat;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Maps a KST LocalDateTime to a student-schedule-aware time-of-day label
 * used in heartbeat user prompts to keep topics plausible for the slot.
 */
@Component
public class TimeOfDayLabeler {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public String label(LocalDateTime now) {
        int hour = now.getHour();
        if (hour < 10 || hour >= 21) return "활성 시간 외";
        DayOfWeek dow = now.getDayOfWeek();
        boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;

        if (weekend) {
            if (hour < 12) return "주말 오전";
            if (hour < 13) return "주말 점심";
            if (hour < 18) return "주말 오후";
            return "주말 저녁";
        }
        if (hour < 12) return "오전 수업";
        if (hour < 13) return "점심시간";
        if (hour < 15) return "오후 수업";
        if (hour < 18) return "방과 후";
        if (hour < 20) return "귀가/저녁";
        return "밤 휴식";
    }

    public String promptBlock(LocalDateTime now) {
        return "## 현재 시각 (KST)\n"
                + now.format(DATE_FMT)
                + " (" + dayOfWeekKo(now.getDayOfWeek()) + ") "
                + now.format(TIME_FMT)
                + " (" + label(now) + ")";
    }

    private static String dayOfWeekKo(DayOfWeek dow) {
        return switch (dow) {
            case MONDAY -> "월";
            case TUESDAY -> "화";
            case WEDNESDAY -> "수";
            case THURSDAY -> "목";
            case FRIDAY -> "금";
            case SATURDAY -> "토";
            case SUNDAY -> "일";
        };
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/maitmus/projects/open-pjsk-spring-migration && ./gradlew test --tests TimeOfDayLabelerTest`
Expected: PASS (19 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/maitmus/projects/open-pjsk-spring-migration
git add src/main/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabeler.java \
        src/test/java/com/maitmus/sekairouter/heartbeat/TimeOfDayLabelerTest.java
git commit -m "feat(heartbeat): TimeOfDayLabeler promptBlock 포맷

'## 현재 시각 (KST)\\n2026-05-13 (수) 14:23 (오후 수업)' 형태.
HeartbeatService 4 경로에서 직접 호출."
```

---

## Task 5: HeartbeatService — 4 경로에 TimeOfDayLabeler 통합

**Files:**
- Modify: `src/test/java/com/maitmus/sekairouter/heartbeat/HeartbeatServiceTest.java`
- Modify: `src/main/java/com/maitmus/sekairouter/heartbeat/HeartbeatService.java`

**Context:** 기존 4 경로 user prompt는 `"\n## 오늘 날짜 (KST)\n" + LocalDate.now(clock)` 라인을 포함. 이걸 모두 `"\n" + timeOfDayLabeler.promptBlock(now)`로 교체. `now`는 각 `execute*Heartbeat()` 진입부에서 `LocalDateTime.now(clock)`로 한 번 캡처해서 재사용. Dialogue 2단계는 첫 발화의 `now`를 응답 프롬프트에서도 재사용.

Weather cast (`triggerDailyWeatherCast`)는 변경 없음 — 기존 `LocalDate.now(clock)` 그대로.

- [ ] **Step 1: Update buildService helper to inject TimeOfDayLabeler**

In `HeartbeatServiceTest.java`, locate `buildService(HeartbeatProperties, Clock)` (around line 358) and ensure the constructor call passes a real `TimeOfDayLabeler` instance.

If the constructor injection is positional via `@RequiredArgsConstructor`, the test instantiates `HeartbeatService` directly with all dependencies. Add `new TimeOfDayLabeler()` as a new positional argument in the slot matching the new field's declaration order in `HeartbeatService` (see Step 3 below for the exact field order).

Show the updated buildService (it constructs HeartbeatService with all 14 dependencies including the new labeler):

```java
private HeartbeatService buildService(HeartbeatProperties props, Clock clock) {
    return new HeartbeatService(
            props,
            new DailyWeatherProperties(false, "09:30", "Asia/Seoul", "test", null),
            state,
            events,
            promptBuilder,
            anthropic,
            randomSelector,
            seedPicker,
            proxy,
            typing,
            scheduler,
            discordProperties,
            personaRegistry,
            new TimeOfDayLabeler(),
            clock
    );
}
```

(Adjust the `DailyWeatherProperties` constructor args to match the existing test's literal — preserve whatever is currently there. The only change is adding `new TimeOfDayLabeler()` immediately before `clock`.)

- [ ] **Step 2: Add failing assertions for new prompt format**

In `HeartbeatServiceTest.java`, add 3 new tests (normal solo / dialogue pair / event). Place these after the existing `heartbeatCheck_fires_event` test.

`clockAt(14, 10)` produces `LocalDateTime.of(2026, 5, 7, 14, 10, 0)` which is Thursday at 14:10. Per the labeler mapping, hour 14 falls in the 13:00~14:59 weekday bucket → "오후 수업".

```java
    @Test
    void heartbeatCheck_normalSolo_userPromptContainsTimeBlock() {
        when(state.getThreshold()).thenReturn(0);
        HeartbeatService service = buildService(enabledProps(0.0), clockAt(14, 10));
        service.heartbeatCheck();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(anthropic).generateUtterance(any(), captor.capture());
        String prompt = captor.getValue();
        assertThat(prompt).contains("## 현재 시각 (KST)");
        assertThat(prompt).contains("2026-05-07 (목) 14:10 (오후 수업)");
        assertThat(prompt).doesNotContain("## 오늘 날짜 (KST)");
    }

    @Test
    void heartbeatCheck_dialoguePair_bothPromptsContainTimeBlock() {
        when(state.getThreshold()).thenReturn(0);
        HeartbeatService service = buildService(enabledProps(1.0), clockAt(14, 10));
        service.heartbeatCheck();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(anthropic, times(2)).generateUtterance(any(), captor.capture());
        for (String prompt : captor.getAllValues()) {
            assertThat(prompt).contains("## 현재 시각 (KST)");
            assertThat(prompt).contains("2026-05-07 (목) 14:10 (오후 수업)");
            assertThat(prompt).doesNotContain("## 오늘 날짜 (KST)");
        }
    }

    @Test
    void heartbeatCheck_event_userPromptContainsTimeBlock() {
        when(state.getThreshold()).thenReturn(0);
        EventsCalendar.EventOverride birthday = new EventsCalendar.EventOverride(
                "에무 생일", List.of(CharacterId.EMU), EventsCalendar.EventKind.BIRTHDAY);
        when(events.todayOverride()).thenReturn(Optional.of(birthday));
        HeartbeatService service = buildService(enabledProps(0.0), clockAt(14, 10));
        service.heartbeatCheck();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(anthropic).generateUtterance(any(), captor.capture());
        String prompt = captor.getValue();
        assertThat(prompt).contains("## 현재 시각 (KST)");
        assertThat(prompt).contains("2026-05-07 (목) 14:10 (오후 수업)");
        assertThat(prompt).doesNotContain("## 오늘 날짜 (KST)");
    }
```

- [ ] **Step 3: Update HeartbeatService — add field and replace date block in 4 paths**

In `HeartbeatService.java`:

a) Add imports at top:
```java
import java.time.LocalDateTime;
```

b) Add `TimeOfDayLabeler` field in the dependency declaration block (insert immediately before `private final Clock clock;`):
```java
    private final TimeOfDayLabeler timeOfDayLabeler;
    private final Clock clock;
```

c) In `executeNormalHeartbeat()`, capture `now` at the top, then replace the date block in both the solo prompt and the dialogue prompts:

```java
    private void executeNormalHeartbeat() {
        boolean dialogue = ThreadLocalRandom.current().nextDouble() < properties.dialogueProbability();
        CharacterId speaker = randomSelector.pickOne(state.lastSpeaker().orElse(null));
        PersonaType speakerType = personaRegistry.get(speaker).type();

        String channelId = discordProperties.sekaiChannelId();
        PromptBlocks systemPrompt = promptBuilder.build();
        LocalDateTime now = LocalDateTime.now(clock);
        String timeBlock = timeOfDayLabeler.promptBlock(now);

        if (!dialogue) {
            String topicSeed = seedPicker.pickTopic(speakerType);
            String userPrompt = "## 모드\n자율 발화 (솔로)\n## 발화자\n" + speaker.name().toLowerCase()
                    + "\n" + timeBlock
                    + "\n## 오늘의 토픽 시드 (이 각도에서 발화)\n" + topicSeed
                    + "\n## 지시\n" + speaker.name().toLowerCase()
                    + "이(가) 채널에 자기 일상/감상/취미/근황을 자연스럽게 한 마디 한다. **위 토픽 시드 각도를 살려** 1~3문장."
                    + outputSchemaBlock()
                    + recentUtterancesBlock();
            String message = callUtterance(systemPrompt, userPrompt);
            if (message != null) {
                scheduleProxySend(speaker, channelId, message, 0);
                state.recordLastSpeaker(speaker);
                state.recordUtterance(speaker, message);
            }
            return;
        }

        // 2-character dialogue
        CharacterId partner = randomSelector.pickOne(speaker);
        String topicSeed = seedPicker.pickTopic(speakerType);
        String dialoguePattern = seedPicker.pickDialoguePattern();
        String firstUser = "## 모드\n자율 발화 (2인 대화 — 첫 발화)\n## 발화자\n" + speaker.name().toLowerCase()
                + "\n## 동료\n" + partner.name().toLowerCase()
                + "\n" + timeBlock
                + "\n## 오늘의 토픽 시드\n" + topicSeed
                + "\n## 첫 발화 패턴 시드 (이 패턴으로 문장 시작)\n" + dialoguePattern
                + "\n## 지시\n" + speaker.name().toLowerCase()
                + "이(가) " + partner.name().toLowerCase()
                + "에게 채널에서 가볍게 말을 건다. **위 토픽 시드와 패턴 시드를 반영**해 1~2문장. "
                + "GRADES.md 호칭/존댓말 매트릭스 준수."
                + outputSchemaBlock()
                + recentUtterancesBlock();
        String firstLine = callUtterance(systemPrompt, firstUser);
        if (firstLine == null) return;
        state.recordUtterance(speaker, firstLine);

        String secondUser = "## 모드\n자율 발화 (2인 대화 — 응답)\n## 발화자\n" + partner.name().toLowerCase()
                + "\n## 직전 발화자\n" + speaker.name().toLowerCase()
                + "\n## 직전 대사\n" + firstLine
                + "\n" + timeBlock
                + "\n## 지시\n" + partner.name().toLowerCase()
                + "이(가) 위 대사에 자연스럽게 반응한다. GRADES.md 호칭/존댓말 매트릭스 준수. 1~2문장."
                + outputSchemaBlock()
                + recentUtterancesBlock();
        String secondLine = callUtterance(systemPrompt, secondUser);
        if (secondLine == null) {
            scheduleProxySend(speaker, channelId, firstLine, 0);
            return;
        }
        schedulePairChainedSend(speaker, partner, channelId, firstLine, secondLine);
        state.recordLastSpeaker(partner);
        state.recordUtterance(partner, secondLine);
    }
```

d) In `executeEventHeartbeat()`, capture `now` and replace the date block:

```java
    private void executeEventHeartbeat(EventsCalendar.EventOverride override, CharacterId speaker, LocalDate today) {
        String channelId = discordProperties.sekaiChannelId();
        PromptBlocks systemPrompt = promptBuilder.build();
        LocalDateTime now = LocalDateTime.now(clock);
        String userPrompt = "## 모드\n자율 발화 (이벤트)\n## 이벤트\n" + override.label() + " (" + override.kind() + ")"
                + "\n## 발화자\n" + speaker.name().toLowerCase()
                + "\n" + timeOfDayLabeler.promptBlock(now)
                + "\n## 지시\n오늘 이벤트와 연결되는 자연스러운 한 마디. 1~3문장."
                + outputSchemaBlock()
                + recentUtterancesBlock();
        String message = callUtterance(systemPrompt, userPrompt);
        if (message != null) {
            scheduleProxySend(speaker, channelId, message, 0);
            state.recordLastSpeaker(speaker);
            state.recordUtterance(speaker, message);
            state.recordEvent(speaker, today);
        }
    }
```

e) **Do NOT** modify `triggerDailyWeatherCast()` — weather cast keeps its existing `## 오늘 날짜 (KST)` block.

- [ ] **Step 4: Run full test suite**

Run: `cd /home/maitmus/projects/open-pjsk-spring-migration && ./gradlew test`
Expected: PASS — all tests including the 3 new HeartbeatService prompt-format tests, plus the 19 TimeOfDayLabeler tests. Existing tests should still pass because the only behavioral change in non-test code is the prompt text, which existing tests don't assert on (they only assert call counts / character / channel).

If any existing test fails because it asserted on `## 오늘 날짜 (KST)` text, update those assertions to expect `## 현재 시각 (KST)` instead.

- [ ] **Step 5: Commit**

```bash
cd /home/maitmus/projects/open-pjsk-spring-migration
git add src/main/java/com/maitmus/sekairouter/heartbeat/HeartbeatService.java \
        src/test/java/com/maitmus/sekairouter/heartbeat/HeartbeatServiceTest.java
git commit -m "feat(heartbeat): 4 경로에 시간대 라벨 주입

normal solo / dialogue 첫·응답 / event 경로의 user prompt에서
'## 오늘 날짜 (KST)' 블록을 TimeOfDayLabeler.promptBlock(now)으로 교체.
같은 슬롯 내 두 번째 발화는 첫 발화 시점 now를 재사용.
Weather cast는 변경 없음."
```

---

## Done Criteria

- 20 new tests for `TimeOfDayLabeler` (15 label + 4 promptBlock) pass
- 3 new tests for `HeartbeatService` prompt format pass
- All existing tests still pass
- 4 heartbeat paths emit user prompts containing `## 현재 시각 (KST)\n<date> (<dow>) <HH:mm> (<label>)`
- Daily weather cast prompt unchanged
- 5 atomic commits on `main` (or feature branch)

**Deployment:** Container restart is explicit user request only (per memory convention `feedback_container_restart`). Do not auto-restart after merge.

---

## Self-Review Notes

- **Spec coverage:** all 5 spec sections (배경/목표/결정/라벨매핑/프롬프트포맷/아키텍처/엣지케이스/테스트/구현순서/빌드게이트) mapped to tasks.
- **Type consistency:** `TimeOfDayLabeler` method names (`label`, `promptBlock`) match across all task code blocks.
- **14:10 label note:** `clockAt(14, 10)` falls in the 13:00~14:59 weekday bucket → "오후 수업" (not "오전 수업"). Plan assertions use the correct label.
