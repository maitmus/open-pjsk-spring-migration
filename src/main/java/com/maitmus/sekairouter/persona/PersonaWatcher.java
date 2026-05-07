package com.maitmus.sekairouter.persona;

import com.maitmus.sekairouter.config.PersonaProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class PersonaWatcher {

    private final PersonaProperties properties;
    private final PersonaLoader loader;
    private final PersonaRegistry registry;

    private volatile long lastMaxMtime = -1;

    @PostConstruct
    public void loadInitial() throws IOException {
        Path dir = Paths.get(properties.dir());
        Map<CharacterId, Persona> personas = loader.loadAll(dir);
        registry.replace(personas);
        lastMaxMtime = currentMaxMtime(dir);
        log.info("Initial persona load — {} entries", personas.size());
    }

    @Scheduled(fixedDelayString = "${persona.watch-interval-ms}")
    public void checkAndReload() throws IOException {
        Path dir = Paths.get(properties.dir());
        long current = currentMaxMtime(dir);
        if (current > lastMaxMtime) {
            Map<CharacterId, Persona> personas = loader.loadAll(dir);
            registry.replace(personas);
            lastMaxMtime = current;
            log.info("Persona reload triggered (mtime change detected) — {} entries", personas.size());
        }
    }

    private long currentMaxMtime(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".md"))
                         .mapToLong(this::mtime)
                         .max()
                         .orElse(0);
        }
    }

    private long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
