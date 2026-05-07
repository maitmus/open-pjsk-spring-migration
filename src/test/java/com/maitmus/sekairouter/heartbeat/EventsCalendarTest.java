package com.maitmus.sekairouter.heartbeat;

import com.maitmus.sekairouter.config.PersonaProperties;
import com.maitmus.sekairouter.persona.CharacterId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EventsCalendarTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test
    void todayOverride_returnsBirthday_whenDateMatches(@TempDir Path tmp) throws IOException {
        // 2026-09-09 in KST: EMU birthday
        writeEventsJson(tmp, """
                {
                  "birthdays": [
                    { "date": "09-09", "label": "에무 생일", "characters": ["EMU"] }
                  ],
                  "anniversaries": []
                }
                """);

        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);
        // Fix clock to 2026-09-09 00:30 KST
        Clock fixed = Clock.fixed(
                Instant.parse("2026-09-08T15:30:00Z"), // UTC = KST - 9h → this is 2026-09-09 00:30 KST
                KST);
        EventsCalendar calendar = new EventsCalendar(props, fixed);
        calendar.load();

        Optional<EventsCalendar.EventOverride> result = calendar.todayOverride();

        assertThat(result).isPresent();
        assertThat(result.get().label()).isEqualTo("에무 생일");
        assertThat(result.get().kind()).isEqualTo(EventsCalendar.EventKind.BIRTHDAY);
        assertThat(result.get().characters()).containsExactly(CharacterId.EMU);
    }

    @Test
    void todayOverride_empty_whenNoEventToday(@TempDir Path tmp) throws IOException {
        writeEventsJson(tmp, """
                {
                  "birthdays": [
                    { "date": "09-09", "label": "에무 생일", "characters": ["EMU"] }
                  ],
                  "anniversaries": []
                }
                """);

        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);
        // Fix clock to 2026-01-01 KST — no event on that day
        Clock fixed = Clock.fixed(
                Instant.parse("2025-12-31T15:00:00Z"), // UTC → 2026-01-01 00:00 KST
                KST);
        EventsCalendar calendar = new EventsCalendar(props, fixed);
        calendar.load();

        assertThat(calendar.todayOverride()).isEmpty();
    }

    @Test
    void todayOverride_nullCharacter_returnsEmptyCharactersList(@TempDir Path tmp) throws IOException {
        // Birthdays where the character list is empty (e.g. 츠카사·루이 birthday — other characters mention)
        writeEventsJson(tmp, """
                {
                  "birthdays": [
                    { "date": "03-08", "label": "츠카사 생일", "characters": [] }
                  ],
                  "anniversaries": []
                }
                """);

        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);
        // 2026-03-08 KST
        Clock fixed = Clock.fixed(
                Instant.parse("2026-03-07T15:00:00Z"), // → 2026-03-08 00:00 KST
                KST);
        EventsCalendar calendar = new EventsCalendar(props, fixed);
        calendar.load();

        Optional<EventsCalendar.EventOverride> result = calendar.todayOverride();

        assertThat(result).isPresent();
        assertThat(result.get().characters()).isEmpty();
        assertThat(result.get().kind()).isEqualTo(EventsCalendar.EventKind.BIRTHDAY);
    }

    @Test
    void todayOverride_anniversary(@TempDir Path tmp) throws IOException {
        writeEventsJson(tmp, """
                {
                  "birthdays": [],
                  "anniversaries": [
                    { "date": "04-12", "label": "WxS 결성 기념일", "characters": ["EMU", "NENE"] }
                  ]
                }
                """);

        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);
        Clock fixed = Clock.fixed(
                Instant.parse("2026-04-11T15:00:00Z"), // → 2026-04-12 00:00 KST
                KST);
        EventsCalendar calendar = new EventsCalendar(props, fixed);
        calendar.load();

        Optional<EventsCalendar.EventOverride> result = calendar.todayOverride();

        assertThat(result).isPresent();
        assertThat(result.get().kind()).isEqualTo(EventsCalendar.EventKind.ANNIVERSARY);
        assertThat(result.get().characters()).containsExactlyInAnyOrder(CharacterId.EMU, CharacterId.NENE);
    }

    @Test
    void load_graceful_whenNoEventsFile(@TempDir Path tmp) {
        // events.json doesn't exist — should not throw, just return empty
        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);
        Clock fixed = Clock.fixed(Instant.parse("2026-09-08T15:30:00Z"), KST);
        EventsCalendar calendar = new EventsCalendar(props, fixed);
        calendar.load();

        assertThat(calendar.todayOverride()).isEmpty();
    }

    private void writeEventsJson(Path dir, String json) throws IOException {
        Files.writeString(dir.resolve("events.json"), json);
    }
}
