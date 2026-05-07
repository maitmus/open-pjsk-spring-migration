package com.maitmus.sekairouter.routing;

import java.util.List;

public sealed interface RoutingDecision {

    record Single(PersonaResponse response, String reasoning) implements RoutingDecision {}

    record Multi(List<PersonaResponse> responses, String reasoning) implements RoutingDecision {
        public Multi {
            if (responses == null || responses.size() < 2) {
                throw new IllegalArgumentException("Multi requires 2+ responses");
            }
            responses = List.copyOf(responses);
        }
    }

    record NoReply(String reasoning) implements RoutingDecision {}

    default List<PersonaResponse> responses() {
        return switch (this) {
            case Single s -> List.of(s.response);
            case Multi m -> m.responses;
            case NoReply n -> List.of();
        };
    }
}
