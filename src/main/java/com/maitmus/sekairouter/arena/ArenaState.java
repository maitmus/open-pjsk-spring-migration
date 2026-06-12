package com.maitmus.sekairouter.arena;

/**
 * 아레나 토론 side 락 상태. 하루 1토픽이라 단일 엔트리로 충분 —
 * (date, topicId)가 현재 토픽과 일치하면 side가 그 토픽에 고정한 입장이다.
 * 날짜/토픽이 바뀌면 다음 첫 fight에서 통째로 덮어쓴다. 모든 필드 null = 아직 미고정.
 */
public record ArenaState(String date, String topicId, String side) {

    public static ArenaState empty() {
        return new ArenaState(null, null, null);
    }
}
