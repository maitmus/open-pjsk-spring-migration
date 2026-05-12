package com.maitmus.sekairouter.routing;

import com.maitmus.sekairouter.memory.ConversationTurn;
import com.maitmus.sekairouter.persona.CharacterId;

import java.util.List;

public record RouterRequest(
        String channelId,
        List<ConversationTurn> recentTurns,
        String newMessage,
        CharacterId lastSpeaker,   // null 가능
        CharacterId forceCharacter // null 가능. Discord reply로 특정 캐릭터 봇 메시지에 답장한 경우 그 캐릭터로 강제
) {
    public RouterRequest {
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
    }

    public RouterRequest(String channelId, List<ConversationTurn> recentTurns, String newMessage, CharacterId lastSpeaker) {
        this(channelId, recentTurns, newMessage, lastSpeaker, null);
    }
}
