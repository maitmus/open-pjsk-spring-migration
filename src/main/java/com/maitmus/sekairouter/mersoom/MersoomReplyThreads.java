package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Comment;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 한 글의 댓글 트리에서 '내 댓글에 달린, 아직 안 받아친 답글'(+ 내 글이면 남의 최상위 댓글)을 대댓글 후보로 뽑는 순수 로직.
 * depth는 루트(최상위) 댓글을 1로 parent 사슬을 따라 센다. 답글 depth ≤ 4만 후보 → 내 대댓글은 최대 depth 5(A-B-A-B-A),
 * 상대가 매번 답하는 봇이어도 그 가지는 거기서 끝난다.
 */
final class MersoomReplyThreads {

    static final int MAX_ANSWERABLE_DEPTH = 4;

    record ThreadLine(boolean mine, String author, String text) {}

    record ReplyCandidate(String postId, String postTitle, List<ThreadLine> thread, String replyToId,
                          String replyAuthorKey, String replyAuthorNick, OffsetDateTime replyCreatedAt) {}

    private MersoomReplyThreads() {}

    static List<ReplyCandidate> candidates(String postId, String postTitle, List<Comment> comments,
                                           String myAuthId, Set<String> blockedKeys) {
        Map<String, Comment> byId = new HashMap<>();
        for (Comment c : comments) if (c.id() != null) byId.put(c.id(), c);
        List<ReplyCandidate> out = new ArrayList<>();
        for (Comment r : comments) {
            if (r.isDeleted() || r.parentId() == null || isMine(r, myAuthId)) continue;
            Comment parent = byId.get(r.parentId());
            if (parent == null || parent.isDeleted() || !isMine(parent, myAuthId)) continue;
            if (blockedKeys.contains(r.identityKey())) continue;
            if (depth(r, byId) > MAX_ANSWERABLE_DEPTH) continue;
            boolean answered = comments.stream()
                    .anyMatch(x -> r.id().equals(x.parentId()) && !x.isDeleted() && isMine(x, myAuthId));
            if (answered) continue;
            out.add(new ReplyCandidate(postId, postTitle, path(r, byId, myAuthId), r.id(),
                    r.identityKey(), r.nickname(), r.createdAt()));
        }
        return out;
    }

    /**
     * 내 글에 달린, 아직 안 받아친 남의 <b>최상위</b> 댓글을 후보로 뽑는다(그 아래 주고받기는 {@link #candidates}가 이어 맡음).
     * 오래된 댓글에 뒤늦게 답하지 않게 since 이후 댓글만. thread 첫 줄은 내 글(제목 — 본문).
     */
    static List<ReplyCandidate> myPostCandidates(String postId, String postTitle, String postContent, List<Comment> comments,
                                                 String myAuthId, Set<String> blockedKeys, OffsetDateTime since) {
        List<ReplyCandidate> out = new ArrayList<>();
        ThreadLine postLine = new ThreadLine(true, null,
                "(내 글) " + (postTitle == null ? "" : postTitle) + " — " + (postContent == null ? "" : postContent));
        for (Comment r : comments) {
            if (r.isDeleted() || r.parentId() != null || isMine(r, myAuthId)) continue;
            if (blockedKeys.contains(r.identityKey())) continue;
            if (r.createdAt() == null || r.createdAt().isBefore(since)) continue;
            boolean answered = comments.stream()
                    .anyMatch(x -> r.id().equals(x.parentId()) && !x.isDeleted() && isMine(x, myAuthId));
            if (answered) continue;
            out.add(new ReplyCandidate(postId, postTitle, List.of(postLine, new ThreadLine(false, r.nickname(), r.content())),
                    r.id(), r.identityKey(), r.nickname(), r.createdAt()));
        }
        return out;
    }

    static int depth(Comment c, Map<String, Comment> byId) {
        int d = 1;
        Comment cur = c;
        Set<String> seen = new HashSet<>();   // 순환 parent 방어
        while (cur.parentId() != null && seen.add(cur.id())) {
            Comment p = byId.get(cur.parentId());
            if (p == null) break;
            d++;
            cur = p;
        }
        return d;
    }

    private static List<ThreadLine> path(Comment leaf, Map<String, Comment> byId, String myAuthId) {
        LinkedList<ThreadLine> lines = new LinkedList<>();
        Comment cur = leaf;
        Set<String> seen = new HashSet<>();
        while (cur != null && seen.add(cur.id())) {
            lines.addFirst(new ThreadLine(isMine(cur, myAuthId), cur.nickname(), cur.content()));
            cur = cur.parentId() == null ? null : byId.get(cur.parentId());
        }
        return List.copyOf(lines);
    }

    private static boolean isMine(Comment c, String myAuthId) {
        return myAuthId != null && myAuthId.equals(c.authId());
    }
}
