package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Comment;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** /api/posts 수집 → 분류 (내 글 / 댓글 가능 / 투표 대상). */
@Slf4j
@Component
@RequiredArgsConstructor
public class MersoomCollector {

    private final MersoomApiClient api;

    public CollectedFeed collect(MersoomState state, int limit) {
        List<Post> recent = api.recentPosts(limit);
        Set<String> myPostIds = new HashSet<>(state.lastPostIds());

        // 내 글만 제외하고 전부 LLM 판정 대상(투표). fixedAvoid·이미 댓글 단 글의 '댓글 대상' 제외는
        // 후처리 eligibility에서 — fixedAvoid도 투표는 받아야 평판이 계속 집계되므로.
        // **commentsOf는 피드 글마다 호출하지 않는다(lazy)** — 크론 시작 시 글 10개의 댓글을 연타로
        // 당기면 IP 요청 스파이크 → 차단(댓글 유실)이 나서. 댓글 작성엔 본문·관계로 충분.
        List<Commentable> commentable = recent.stream()
                .filter(p -> !myPostIds.contains(p.id()))
                .map(p -> new Commentable(p, List.of()))
                .toList();

        // 내 글의 답글(reply)은 유지 — ≤3개라 호출 적고, 글 생성 시 내 글 반응 참고에 가치 큼.
        List<Commentable> myTracked = recent.stream()
                .filter(p -> myPostIds.contains(p.id()))
                .limit(3)
                .map(p -> new Commentable(p, api.commentsOf(p.id())))
                .toList();

        List<Post> votable = recent.stream()
                .filter(p -> !myPostIds.contains(p.id()))
                .toList();

        log.info("Mersoom collected: total={}, votable={}, commentable={}, my_tracked={}",
                recent.size(), votable.size(), commentable.size(), myTracked.size());

        return new CollectedFeed(commentable, myTracked, votable);
    }

    public record Commentable(Post post, List<Comment> comments) {}
    public record CollectedFeed(List<Commentable> commentable, List<Commentable> myTracked, List<Post> votable) {}
}
