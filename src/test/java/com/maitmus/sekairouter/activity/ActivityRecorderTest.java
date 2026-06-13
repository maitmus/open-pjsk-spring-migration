package com.maitmus.sekairouter.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityRecorderTest {

    private final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:45:00Z"), ZoneId.of("Asia/Seoul"));

    private ActivityRecorder recorder(Path file) {
        ActivityFeedStore store = new ActivityFeedStore(om, file.toString());
        ActivityRecorder r = new ActivityRecorder(store, clock);
        r.restore();
        return r;
    }

    @Test
    void records_comment_post_utterance_newest_first(@TempDir Path tmp) {
        Path f = tmp.resolve("activity-feed.json");
        ActivityRecorder r = recorder(f);

        r.recordComment("에무", "오호돌쇠", "산책 좋았겠어요!");
        r.recordPost("네네", "오늘 연습 끝");
        r.recordUtterance("miku", "123", "안녕");

        ActivityFeed feed = new ActivityFeedStore(om, f.toString()).load();
        assertThat(feed.events()).hasSize(3);
        // 최신순 — utterance가 맨 앞
        assertThat(feed.events().get(0).kind()).isEqualTo("utterance");
        assertThat(feed.events().get(0).actor()).isEqualTo("miku");
        assertThat(feed.events().get(0).channel()).isEqualTo("123");
        assertThat(feed.events().get(1).kind()).isEqualTo("post");
        assertThat(feed.events().get(1).actor()).isEqualTo("네네");
        assertThat(feed.events().get(2).kind()).isEqualTo("comment");
        assertThat(feed.events().get(2).target()).isEqualTo("오호돌쇠");
        assertThat(feed.events().get(2).ts()).startsWith("2026-06-14T09:45");  // KST 변환
    }

    @Test
    void caps_at_80_events(@TempDir Path tmp) {
        Path f = tmp.resolve("activity-feed.json");
        ActivityRecorder r = recorder(f);
        for (int i = 0; i < 100; i++) r.recordComment("에무", "친구" + i, "내용" + i);

        ActivityFeed feed = new ActivityFeedStore(om, f.toString()).load();
        assertThat(feed.events()).hasSize(ActivityRecorder.CAP);
        assertThat(feed.events().get(0).target()).isEqualTo("친구99");   // 최신 보존
        assertThat(feed.events()).noneMatch(e -> "친구0".equals(e.target()));  // 오래된 트림
    }

    @Test
    void restore_survives_restart(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("activity-feed.json");
        recorder(f).recordComment("네네", "냥냥돌쇠", "안녕");
        assertThat(Files.readString(f)).contains("냥냥돌쇠");

        // 새 인스턴스(=재시작) 가 파일에서 복원 후 이어붙임
        ActivityRecorder r2 = recorder(f);
        r2.recordPost("에무", "새 글");
        ActivityFeed feed = new ActivityFeedStore(om, f.toString()).load();
        assertThat(feed.events()).hasSize(2);
        assertThat(feed.events()).anyMatch(e -> "냥냥돌쇠".equals(e.target()));   // 재시작 전 이벤트 보존
    }

    @Test
    void load_returns_empty_when_file_missing(@TempDir Path tmp) {
        ActivityFeed feed = new ActivityFeedStore(om, tmp.resolve("nope.json").toString()).load();
        assertThat(feed.events()).isEmpty();
    }
}
