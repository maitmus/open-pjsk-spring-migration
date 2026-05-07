package com.maitmus.sekairouter.persona;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PersonaRegistry {

    private volatile Map<CharacterId, Persona> personas = new EnumMap<>(CharacterId.class);

    public Map<CharacterId, Persona> all() {
        return personas;
    }

    public Persona get(CharacterId id) {
        Persona p = personas.get(id);
        if (p == null) {
            throw new IllegalStateException("Persona not loaded: " + id);
        }
        return p;
    }

    public void replace(Map<CharacterId, Persona> next) {
        log.info("Persona registry replaced — {} entries", next.size());
        this.personas = Map.copyOf(next);
    }
}
