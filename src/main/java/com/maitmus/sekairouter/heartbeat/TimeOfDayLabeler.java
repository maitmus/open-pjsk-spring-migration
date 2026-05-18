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
