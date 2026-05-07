package com.maitmus.sekairouter.memory;

public record ConversationTurn(
        String speaker,    // "user" 또는 캐릭터 ID 소문자 (예: "emu")
        String content,
        long timestampSec
) {}
