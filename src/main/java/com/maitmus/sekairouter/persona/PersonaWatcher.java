package com.maitmus.sekairouter.persona;

import com.maitmus.sekairouter.config.PersonaProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
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
    private volatile long lastFileCount = -1;

    @PostConstruct
    public void loadInitial() {
        try {
            doReload(true);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load personas at startup", e);
        }
    }

    @Scheduled(fixedDelayString = "${persona.watch-interval-ms}")
    public void checkAndReload() throws IOException {
        Path dir = Paths.get(properties.dir());
        DirState state = currentDirState(dir);
        if (state.fileCount != lastFileCount || state.maxMtime > lastMaxMtime) {
            doReloadFromState(dir, state);
        }
    }

    private void doReload(boolean isInitial) throws IOException {
        Path dir = Paths.get(properties.dir());
        DirState state = currentDirState(dir);
        doReloadFromState(dir, state);
        if (isInitial) {
            log.info("Initial persona load — {} entries", registry.all().size());
        }
    }

    private void doReloadFromState(Path dir, DirState state) throws IOException {
        Map<CharacterId, Persona> personas = loader.loadAll(dir);
        registry.replace(personas);
        lastMaxMtime = state.maxMtime;
        lastFileCount = state.fileCount;
        log.info("Persona reload — {} entries (mtime={}, count={})",
                personas.size(), state.maxMtime, state.fileCount);
    }

    private DirState currentDirState(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            long[] result = stream
                    .filter(p -> p.toString().endsWith(".md"))
                    .reduce(new long[]{0, 0},
                            (acc, p) -> new long[]{acc[0] + 1, Math.max(acc[1], mtime(p))},
                            (a, b) -> new long[]{a[0] + b[0], Math.max(a[1], b[1])});
            return new DirState(result[0], result[1]);
        }
    }

    private long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private record DirState(long fileCount, long maxMtime) {}
}
