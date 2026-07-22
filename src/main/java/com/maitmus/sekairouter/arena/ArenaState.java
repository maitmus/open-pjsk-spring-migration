package com.maitmus.sekairouter.arena;

/**
 * 아레나 토론 상태(단일 엔트리, date-scoped). (date, topicId)가 현재 토픽과 일치할 때:
 * side = 그 토픽 고정 입장, rebuttalNotes = 저장된 반박노트, notesOppCount = 노트 생성 시점의 상대 글 수.
 * 날짜/토픽이 바뀌면 다음 첫 fight/노트 저장에서 통째로 덮어쓴다. null = 미설정.
 */
public record ArenaState(String date, String topicId, String side,
                         String rebuttalNotes, Integer notesOppCount) {

    public static ArenaState empty() {
        return new ArenaState(null, null, null, null, null);
    }
}
