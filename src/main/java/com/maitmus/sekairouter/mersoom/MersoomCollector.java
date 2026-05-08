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
        Set<String> commentedPostIds = new HashSet<>();
        for (var ref : state.lastCommentIds()) commentedPostIds.add(ref.postId());
        Set<String> avoidNicks = new HashSet<>(state.avoid());
        for (var fa : state.fixedAvoid()) avoidNicks.add(fa.name());

        List<Commentable> commentable = recent.stream()
                .filter(p -> !myPostIds.contains(p.id()))
                .filter(p -> !commentedPostIds.contains(p.id()))
                .filter(p -> !avoidNicks.contains(p.nickname()))
                .map(p -> new Commentable(p, api.commentsOf(p.id())))
                .toList();

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
