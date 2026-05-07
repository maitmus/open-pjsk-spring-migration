package com.maitmus.sekairouter.persona;

import java.util.Arrays;
import java.util.Optional;

public enum CharacterId {
    AIRI, EMU, HARUKA, MIKU, MINORI, NENE, SHIZUKU;

    public String fileName() {
        return name().toLowerCase() + ".md";
    }

    public static Optional<CharacterId> fromString(String s) {
        if (s == null) return Optional.empty();
        return Arrays.stream(values())
                .filter(c -> c.name().equalsIgnoreCase(s))
                .findFirst();
    }
}
