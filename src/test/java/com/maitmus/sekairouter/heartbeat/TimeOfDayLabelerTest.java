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
}
