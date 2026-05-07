package com.maitmus.sekairouter.routing;

import com.maitmus.sekairouter.memory.ConversationTurn;
import com.maitmus.sekairouter.persona.CharacterId;

import java.util.List;

public record RouterRequest(
        String channelId,
        List<ConversationTurn> recentTurns,
        String newMessage,
        CharacterId lastSpeaker  // null 가능
) {
    public RouterRequest {
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
    }
}
