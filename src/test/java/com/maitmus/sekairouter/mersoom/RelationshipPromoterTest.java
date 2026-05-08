package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import com.maitmus.sekairouter.mersoom.MersoomState.FixedFriend;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RelationshipPromoterTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void promotes_friend_to_fixed_when_resetCount_2plus_and_recent() {
        RelationshipPromoter promoter = new RelationshipPromoter(clock);

        MersoomState state = new MersoomState(
                List.of(), List.of(),
                List.of("오호돌쇠"),
                List.of(),
                List.of(),
                List.of(),
                Map.of("오호돌쇠", new ContextNote(5, 3, "2026-05-07T10:00", "n", "오호")),
                8, List.of("돌쇠"), null, null, List.of(), List.of());

        MersoomState result = promoter.evaluate(state);

        assertThat(result.fixedFriends()).hasSize(1);
        assertThat(result.fixedFriends().get(0).name()).isEqualTo("오호돌쇠");
        assertThat(result.friends()).doesNotContain("오호돌쇠");
    }

    @Test
    void does_not_promote_when_resetCount_under_2() {
        RelationshipPromoter promoter = new RelationshipPromoter(clock);

        MersoomState state = new MersoomState(
                List.of(), List.of(),
                List.of("뉴비돌쇠"),
                List.of(),
                List.of(),
                List.of(),
                Map.of("뉴비돌쇠", new ContextNote(5, 1, "2026-05-08T11:00", "n", null)),
                8, List.of(), null, null, List.of(), List.of());

        MersoomState result = promoter.evaluate(state);

        assertThat(result.fixedFriends()).isEmpty();
        assertThat(result.friends()).containsExactly("뉴비돌쇠");
    }

    @Test
    void does_not_promote_when_resetAt_older_than_3_days() {
        RelationshipPromoter promoter = new RelationshipPromoter(clock);

        MersoomState state = new MersoomState(
                List.of(), List.of(),
                List.of("오래된돌쇠"),
                List.of(),
                List.of(),
                List.of(),
                Map.of("오래된돌쇠", new ContextNote(5, 3, "2026-04-01T10:00", "n", null)),
                8, List.of(), null, null, List.of(), List.of());

        MersoomState result = promoter.evaluate(state);

        assertThat(result.fixedFriends()).isEmpty();
        assertThat(result.friends()).containsExactly("오래된돌쇠");
    }

    @Test
    void does_not_promote_reserved_nickname() {
        RelationshipPromoter promoter = new RelationshipPromoter(clock);

        MersoomState state = new MersoomState(
                List.of(), List.of(),
                List.of("돌쇠"),
                List.of(),
                List.of(),
                List.of(),
                Map.of("돌쇠", new ContextNote(5, 3, "2026-05-08T11:00", "n", null)),
                8, List.of("돌쇠"), null, null, List.of(), List.of());

        MersoomState result = promoter.evaluate(state);

        assertThat(result.fixedFriends()).isEmpty();
    }

    @Test
    void preserves_existing_fixedFriends_count() {
        RelationshipPromoter promoter = new RelationshipPromoter(clock);

        MersoomState state = new MersoomState(
                List.of(), List.of(),
                List.of(),
                List.of(),
                List.of(new FixedFriend("기존절친", "old", null)),
                List.of(),
                Map.of(),
                8, List.of(), null, null, List.of(), List.of());

        MersoomState result = promoter.evaluate(state);

        assertThat(result.fixedFriends()).hasSize(1);
        assertThat(result.fixedFriends().get(0).name()).isEqualTo("기존절친");
    }
}
