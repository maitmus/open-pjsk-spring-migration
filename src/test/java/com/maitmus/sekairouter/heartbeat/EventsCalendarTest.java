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
        // events.json structure: { "birthdays": { "MM-DD": {...}, ... } }
        writeEventsJson(tmp, """
                {
                  "birthdays": {
                    "09-09": { "character": "emu", "name": "에무", "label": "오오토리 에무 생일" }
                  },
                  "anniversaries": {}
                }
                """);

        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);
        Clock fixed = Clock.fixed(
                Instant.parse("2026-09-08T15:30:00Z"), // UTC → 2026-09-09 00:30 KST
                KST);
        EventsCalendar calendar = new EventsCalendar(props, fixed);
        calendar.load();

        Optional<EventsCalendar.EventOverride> result = calendar.todayOverride();

        assertThat(result).isPresent();
        assertThat(result.get().label()).isEqualTo("오오토리 에무 생일");
        assertThat(result.get().kind()).isEqualTo(EventsCalendar.EventKind.BIRTHDAY);
        assertThat(result.get().characters()).containsExactly(CharacterId.EMU);
    }

    @Test
    void todayOverride_empty_whenNoEventToday(@TempDir Path tmp) throws IOException {
        writeEventsJson(tmp, """
                {
                  "birthdays": {
                    "09-09": { "character": "emu", "name": "에무", "label": "오오토리 에무 생일" }
                  },
                  "anniversaries": {}
                }
                """);

        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);
        Clock fixed = Clock.fixed(
                Instant.parse("2025-12-31T15:00:00Z"), // → 2026-01-01 00:00 KST
                KST);
        EventsCalendar calendar = new EventsCalendar(props, fixed);
        calendar.load();

        assertThat(calendar.todayOverride()).isEmpty();
    }

    @Test
    void todayOverride_nullCharacter_returnsEmptyCharactersList(@TempDir Path tmp) throws IOException {
        // Real events.json has entries with character=null for non-routed characters (츠카사·루이)
        writeEventsJson(tmp, """
                {
                  "birthdays": {
                    "05-17": { "character": null, "name": "츠카사", "label": "텐마 츠카사 생일" }
                  },
                  "anniversaries": {}
                }
                """);

        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);
        Clock fixed = Clock.fixed(
                Instant.parse("2026-05-16T15:00:00Z"), // → 2026-05-17 00:00 KST
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
                  "birthdays": {},
                  "anniversaries": {
                    "09-30": { "label": "PJSK 출시 기념일", "characters": ["emu", "nene"] }
                  }
                }
                """);

        PersonaProperties props = new PersonaProperties(tmp.toString(), 60_000);
        Clock fixed = Clock.fixed(
                Instant.parse("2026-09-29T15:00:00Z"), // → 2026-09-30 00:00 KST
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
