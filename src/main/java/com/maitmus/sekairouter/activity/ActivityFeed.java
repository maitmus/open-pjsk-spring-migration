package com.maitmus.sekairouter.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** activity-feed.json 매핑 — 최신순 이벤트 목록(cap은 {@link ActivityRecorder}가 관리). */
@JsonNaming(SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityFeed(List<ActivityEvent> events) {
    public ActivityFeed {
        if (events == null) events = List.of();
    }
}
