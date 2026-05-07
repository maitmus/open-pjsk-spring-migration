package com.maitmus.sekairouter.routing;

import com.maitmus.sekairouter.persona.CharacterId;

public record PersonaResponse(CharacterId character, String message) {
    public PersonaResponse {
        if (character == null) throw new IllegalArgumentException("character required");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message required");
    }
}
