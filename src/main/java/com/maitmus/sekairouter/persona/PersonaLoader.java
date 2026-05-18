package com.maitmus.sekairouter.persona;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Component
public class PersonaLoader {

    private static final Pattern NAME_PATTERN = Pattern.compile(
            "^- \\*\\*Name:\\*\\*\\s*([^(]+?)\\s*(?:\\(.*\\))?\\s*$",
            Pattern.MULTILINE
    );

    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "^- \\*\\*Type:\\*\\*\\s*(\\S+)\\s*$",
            Pattern.MULTILINE
    );

    public Map<CharacterId, Persona> loadAll(Path dir) throws IOException {
        Map<CharacterId, Persona> personas = new EnumMap<>(CharacterId.class);
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                  .forEach(p -> loadOne(p).ifPresent(persona ->
                          personas.put(persona.id(), persona)));
        }
        return personas;
    }

    private Optional<Persona> loadOne(Path file) {
        String fileName = file.getFileName().toString();
        String idPart = fileName.replace(".md", "");
        return CharacterId.fromString(idPart).flatMap(id -> {
            try {
                String content = Files.readString(file);
                String displayName = extractDisplayName(content).orElse(id.name());
                PersonaType type = extractType(content).orElse(PersonaType.HUMAN_SEKAI);
                return Optional.of(new Persona(id, displayName, type, content));
            } catch (IOException e) {
                log.error("Failed to read persona file {}", file, e);
                return Optional.empty();
            }
        });
    }

    private Optional<String> extractDisplayName(String content) {
        Matcher m = NAME_PATTERN.matcher(content);
        return m.find() ? Optional.of(m.group(1).trim()) : Optional.empty();
    }

    private Optional<PersonaType> extractType(String content) {
        Matcher m = TYPE_PATTERN.matcher(content);
        return m.find() ? PersonaType.fromString(m.group(1)) : Optional.empty();
    }
}
