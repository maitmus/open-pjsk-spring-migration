package com.maitmus.sekairouter.persona;

public record Persona(
        CharacterId id,
        String displayName,
        PersonaType type,
        String content
) {}
