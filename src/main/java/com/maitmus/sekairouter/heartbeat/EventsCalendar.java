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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class EventsCalendar {

    public enum EventKind { BIRTHDAY, ANNIVERSARY }

    public record EventOverride(String label, List<CharacterId> characters, EventKind kind) {}

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String EVENTS_FILE = "events.json";

    private final PersonaProperties personaProperties;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private List<EventEntry> entries = List.of();

    /** Production constructor — uses system clock in KST. */
    public EventsCalendar(PersonaProperties personaProperties) {
        this(personaProperties, Clock.system(KST));
    }

    /** Testable constructor — accepts a fixed/custom clock. */
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

            JsonNode birthdays = root.path("birthdays");
            if (birthdays.isArray()) {
                for (JsonNode node : birthdays) {
                    String date = node.path("date").asText();
                    String label = node.path("label").asText();
                    List<CharacterId> chars = parseCharacters(node.path("characters"));
                    loaded.add(new EventEntry(date, label, chars, EventKind.BIRTHDAY));
                }
            }

            JsonNode anniversaries = root.path("anniversaries");
            if (anniversaries.isArray()) {
                for (JsonNode node : anniversaries) {
                    String date = node.path("date").asText();
                    String label = node.path("label").asText();
                    List<CharacterId> chars = parseCharacters(node.path("characters"));
                    loaded.add(new EventEntry(date, label, chars, EventKind.ANNIVERSARY));
                }
            }

            this.entries = List.copyOf(loaded);
            log.info("EventsCalendar loaded {} entries from {}", loaded.size(), eventsPath);
        } catch (IOException e) {
            log.error("Failed to parse events.json: {}", e.getMessage(), e);
        }
    }

    /**
     * Returns today's override (in KST), if any.
     * Birthdays take precedence over anniversaries when both fall on the same day.
     * For null-character birthdays (e.g. 츠카사·루이), characters list is empty — caller decides who speaks.
     */
    public Optional<EventOverride> todayOverride() {
        LocalDate today = LocalDate.now(clock);
        // MM-DD format for matching
        String todayMmDd = String.format("%02d-%02d", today.getMonthValue(), today.getDayOfMonth());

        return entries.stream()
                .filter(e -> e.date().equals(todayMmDd))
                .findFirst()
                .map(e -> new EventOverride(e.label(), e.characters(), e.kind()));
    }

    private List<CharacterId> parseCharacters(JsonNode node) {
        List<CharacterId> list = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                CharacterId.fromString(item.asText()).ifPresent(list::add);
            }
        }
        return List.copyOf(list);
    }

    private record EventEntry(String date, String label, List<CharacterId> characters, EventKind kind) {}
}
