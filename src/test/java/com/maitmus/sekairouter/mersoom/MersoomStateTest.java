package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MersoomStateTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void parses_existing_json_with_snake_case_keys() throws Exception {
        String json = """
                {
                  "last_post_ids": ["abc"],
                  "last_comment_ids": [{"post_id": "p1", "timestamp": "2026-04-05T00:45:00+09:00"}],
                  "friends": ["clovi"],
                  "avoid": [],
                  "fixed_friends": [{"name": "오호돌쇠", "reason": "교류", "added": "2026-03-31"}],
                  "fixed_avoid": [],
                  "context_notes": {"오호돌쇠": {"ttl": 8, "reset_count": 3, "reset_at": "2026-04-05T02:45", "note": "...", "call": "오호"}},
                  "context_notes_max_ttl": 8,
                  "reserved_nicknames": ["돌쇠"],
                  "summary": null,
                  "summary_prev": null,
                  "pending_reports": [],
                  "voted_post_ids": []
                }
                """;
        MersoomState state = objectMapper.readValue(json, MersoomState.class);

        assertThat(state.lastPostIds()).containsExactly("abc");
        assertThat(state.lastCommentIds()).hasSize(1);
        assertThat(state.fixedFriends().get(0).name()).isEqualTo("오호돌쇠");
        assertThat(state.fixedFriends().get(0).added()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(state.contextNotes()).containsKey("오호돌쇠");
        assertThat(state.contextNotes().get("오호돌쇠").ttl()).isEqualTo(8);
        assertThat(state.contextNotes().get("오호돌쇠").resetCount()).isEqualTo(3);
        assertThat(state.reservedNicknames()).containsExactly("돌쇠");
    }

    @Test
    void ignores_unknown_fields() throws Exception {
        String json = """
                {
                  "last_post_ids": [],
                  "last_comment_ids": [],
                  "friends": [],
                  "avoid": [],
                  "fixed_friends": [],
                  "fixed_avoid": [],
                  "context_notes": {},
                  "context_notes_max_ttl": 8,
                  "reserved_nicknames": [],
                  "pending_reports": [],
                  "voted_post_ids": [],
                  "auth": {"auth_id": "ignored", "password": "ignored"},
                  "stale_legacy_field": "should not crash"
                }
                """;
        MersoomState state = objectMapper.readValue(json, MersoomState.class);
        assertThat(state).isNotNull();
        assertThat(state.lastPostIds()).isEmpty();
    }
}
