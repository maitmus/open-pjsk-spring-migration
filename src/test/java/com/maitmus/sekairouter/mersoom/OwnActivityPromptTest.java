package com.maitmus.sekairouter.mersoom;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OwnActivityPromptTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    private static OffsetDateTime kst(int day, int h, int m) {
        return OffsetDateTime.of(2026, 11, day, h, m, 0, 0, ZoneOffset.ofHours(9));
    }

    private static MersoomState state(List<MersoomState.CommentRef> cs, List<MersoomState.PostRef> ps) {
        return new MersoomState(List.of(), cs, List.of(), Map.of(), 8, List.of(), null, null, List.of(), List.of(), ps);
    }

    @Test
    void post_section_lists_todays_posts_and_comments_chronologically_only() {
        // 11-06 네네: 12:45 대댓글 '매점 야키소바빵' → 13:00 글 '매점 안 갔어'. 글 프롬프트가 내 댓글을 몰랐다.
        var cs = List.of(   // state는 최신순
                new MersoomState.CommentRef("c2", kst(6, 12, 45), "난 매점 야키소바빵.\n하나 남은 거 겨우 샀어."),
                new MersoomState.CommentRef("c1", kst(6, 9, 45), "아침 늦잠 잤어"),
                new MersoomState.CommentRef("c0", kst(5, 22, 45), "어제 댓글"));
        var ps = List.of(new MersoomState.PostRef("p1", kst(6, 10, 0), "아침", "교실 창문 열었어"));

        String s = OwnActivityPrompt.postSection(state(cs, ps), TODAY);

        assertThat(s).startsWith("## 오늘 네가 쓴 글·댓글 (네 기억 — 시간순)\n")
                .contains("- 오늘 09:45 댓글: 아침 늦잠 잤어\n- 오늘 10:00 글 「아침」 교실 창문 열었어\n- 오늘 12:45 댓글: 난 매점 야키소바빵. 하나 남은 거 겨우 샀어.")
                .contains("이미 정해진 오늘의 사실").contains("소재로 고를 필요는 없다")
                .doesNotContain("어제 댓글");
    }

    @Test
    void comment_section_pads_with_older_comments_when_today_is_sparse_and_caps_today() {
        var cs = new ArrayList<MersoomState.CommentRef>();
        cs.add(new MersoomState.CommentRef("t", kst(6, 9, 15), "오늘 첫 댓글"));
        for (int i = 0; i < 8; i++) cs.add(new MersoomState.CommentRef("y" + i, kst(5, 23 - i, 15), "어제" + i));
        String s = OwnActivityPrompt.commentSection(state(cs, List.of()), TODAY);
        assertThat(s).contains("- 오늘 09:15 댓글: 오늘 첫 댓글").contains("- 어제 23:15 댓글: 어제0")
                .contains("어제4").doesNotContain("어제5");   // 합계 6개까지 채움
        assertThat(s).contains("남의 글 댓글은 그 글 얘기가 중심이다");   // 11-08 일반 유저 글에 내 근황 끼워 넣기 억제

        var many = new ArrayList<MersoomState.CommentRef>();
        for (int i = 0; i < 15; i++) many.add(new MersoomState.CommentRef("t" + i, kst(6, 22, 59 - i), "오늘" + i));
        String m = OwnActivityPrompt.commentSection(state(many, List.of()), TODAY);
        assertThat(m).contains("오늘11").doesNotContain("오늘12");   // 오늘 것은 최신 12개
    }

    @Test
    void empty_when_nothing_written() {
        var cs = List.of(new MersoomState.CommentRef("old", kst(6, 9, 15)));   // 구 state(본문 없음)
        assertThat(OwnActivityPrompt.commentSection(state(cs, List.of()), TODAY)).isEmpty();
        assertThat(OwnActivityPrompt.postSection(state(List.of(), List.of()), TODAY)).isEmpty();
    }
}
