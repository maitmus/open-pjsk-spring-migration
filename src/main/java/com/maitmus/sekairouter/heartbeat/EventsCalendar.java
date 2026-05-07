package com.maitmus.sekairouter.heartbeat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maitmus.sekairouter.config.PersonaProperties;
import com.maitmus.sekairouter.persona.CharacterId;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class EventsCalendar {

    public enum EventKind { BIRTHDAY, ANNIVERSARY }

    public record EventOverride(String label, List<CharacterId> characters, EventKind kind) {}

    private static final String EVENTS_FILE = "events.json";

    private final PersonaProperties personaProperties;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private List<EventEntry> entries = List.of();

    /**
     * Single constructor — Spring injects PersonaProperties + the Clock bean
     * (HeartbeatConfig.heartbeatClock). Tests pass a fixed Clock directly.
     */
    public EventsCalendar(PersonaProperties personaProperties, Clock clock) {
        this.personaProperties = personaProperties;
        this.clock = clock;
    }

    @PostConstruct
    void load() {
        Path eventsPath = Paths.get(personaProperties.dir()).resolve(EVENTS_FILE);
        if (!Files.isRegularFile(eventsPath)) {
            log.warn("events.json not found at {} — EventsCalendar will return empty overrides", eventsPath);
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(eventsPath.toFile());
            List<EventEntry> loaded = new ArrayList<>();
            // events.json structure (date is the JSON key):
            //   {
            //     "birthdays":     { "MM-DD": { "character": "id|null", "name": "...", "label": "..." }, ... },
            //     "anniversaries": { "MM-DD": { "label": "...", "characters": ["id", ...] }, ... }
            //   }
            parseDateMap(root.path("birthdays"), EventKind.BIRTHDAY, loaded);
            parseDateMap(root.path("anniversaries"), EventKind.ANNIVERSARY, loaded);
            this.entries = List.copyOf(loaded);
            log.info("EventsCalendar loaded {} entries from {}", loaded.size(), eventsPath);
        } catch (IOException e) {
            log.error("Failed to parse events.json: {}", e.getMessage(), e);
        }
    }

    private void parseDateMap(JsonNode node, EventKind kind, List<EventEntry> out) {
        if (!node.isObject()) return;
        node.fields().forEachRemaining(entry -> {
            String date = entry.getKey();        // "MM-DD"
            JsonNode val = entry.getValue();
            String label = val.path("label").asText("");
            List<CharacterId> chars = new ArrayList<>();
            // birthday entries have a single "character" (may be null for non-routed characters)
            if (val.has("character") && !val.path("character").isNull()) {
                CharacterId.fromString(val.path("character").asText()).ifPresent(chars::add);
            }
            // anniversary entries have a "characters" array
            JsonNode arr = val.path("characters");
            if (arr.isArray()) {
                for (JsonNode item : arr) {
                    CharacterId.fromString(item.asText()).ifPresent(chars::add);
                }
            }
            out.add(new EventEntry(date, label, List.copyOf(chars), kind));
        });
    }

    /**
     * Returns today's override (in injected Clock's zone), if any.
     * Birthdays loaded first so they take precedence on the same date if both exist.
     * Empty character list means the event isn't tied to a routable character (e.g. 츠카사·루이 birthday)
     * — caller decides who speaks.
     */
    public Optional<EventOverride> todayOverride() {
        LocalDate today = LocalDate.now(clock);
        String todayMmDd = String.format("%02d-%02d", today.getMonthValue(), today.getDayOfMonth());

        return entries.stream()
                .filter(e -> e.date().equals(todayMmDd))
                .findFirst()
                .map(e -> new EventOverride(e.label(), e.characters(), e.kind()));
    }

    private record EventEntry(String date, String label, List<CharacterId> characters, EventKind kind) {}
}
