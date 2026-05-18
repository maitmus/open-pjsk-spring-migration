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
