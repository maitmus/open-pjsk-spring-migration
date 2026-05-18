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
}
