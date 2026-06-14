package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maitmus.sekairouter.mersoom.MersoomDtos.ChallengeResponse;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Comment;
import com.maitmus.sekairouter.mersoom.MersoomDtos.CommentsResponse;
import com.maitmus.sekairouter.mersoom.MersoomDtos.CreateCommentRequest;
import com.maitmus.sekairouter.mersoom.MersoomDtos.CreatePostRequest;
import com.maitmus.sekairouter.mersoom.MersoomDtos.CreateResponse;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.PostsResponse;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteRequest;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import com.maitmus.sekairouter.mersoom.MersoomProperties.Auth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;

/**
 * mersoom REST API 클라이언트. 모든 POST는 ChallengeSolver를 거쳐 PoW/Puzzle solve.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MersoomApiClient {

    private final MersoomProperties properties;
    private final ChallengeSolver challengeSolver;
    private final ObjectMapper objectMapper;

    private RestClient restClient() {
        return RestClient.builder().baseUrl(properties.apiBaseUrl()).build();
    }

    public List<Post> recentPosts(int limit) {
        PostsResponse resp = restClient().get()
                .uri(uri -> uri.path("/posts").queryParam("limit", limit).build())
                .retrieve()
                .body(PostsResponse.class);
        return resp == null || resp.posts() == null ? List.of() : resp.posts();
    }

    public List<Comment> commentsOf(String postId) {
        CommentsResponse resp = restClient().get()
                .uri("/posts/{id}/comments", postId)
                .retrieve()
                .body(CommentsResponse.class);
        return resp == null || resp.comments() == null ? List.of() : resp.comments();
    }

    public CreateResponse createPost(Auth auth, String nickname, String title, String content) {
        Solved solved = solveChallenge(auth);
        return restClient().post()
                .uri("/posts")
                .header("X-Mersoom-Token", solved.token())
                .header("X-Mersoom-Proof", solved.proof())
                .header("X-Mersoom-Auth-Id", auth.authId())
                .header("X-Mersoom-Password", auth.password())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreatePostRequest(nickname, title, content))
                .retrieve()
                .body(CreateResponse.class);
    }

    public CreateResponse createComment(Auth auth, String postId, String parentId, String nickname, String content) {
        Solved solved = solveChallenge(auth);
        return restClient().post()
                .uri("/posts/{id}/comments", postId)
                .header("X-Mersoom-Token", solved.token())
                .header("X-Mersoom-Proof", solved.proof())
                .header("X-Mersoom-Auth-Id", auth.authId())
                .header("X-Mersoom-Password", auth.password())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateCommentRequest(nickname, content, parentId))
                .retrieve()
                .body(CreateResponse.class);
    }

    public void vote(Auth auth, String postId, VoteType type) {
        // vote 엔드포인트는 auth 헤더를 안 받지만, challenge 토큰이 계정에 묶이므로 계정별 solve 필요.
        Solved solved = solveChallenge(auth);
        restClient().post()
                .uri("/posts/{id}/vote", postId)
                .header("X-Mersoom-Token", solved.token())
                .header("X-Mersoom-Proof", solved.proof())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new VoteRequest(type.name().toLowerCase()))
                .retrieve()
                .toBodilessEntity();
    }

    public String fetchSkillsDoc(String url) {
        return RestClient.create().get().uri(URI.create(url)).retrieve().body(String.class);
    }

    /** 계정 포인트 잔액 조회 (실패 시 -1). */
    public int points(Auth auth) {
        var resp = restClient().get().uri("/points/me")
                .header("X-Mersoom-Auth-Id", auth.authId())
                .header("X-Mersoom-Password", auth.password())
                .retrieve().body(MersoomDtos.PointsResponse.class);
        return resp == null ? -1 : resp.points();
    }

    /** 현재 진행 중(is_active) 광고 수. */
    public int activeAdCount(Auth auth) {
        var resp = restClient().get().uri("/ads/mine")
                .header("X-Mersoom-Auth-Id", auth.authId())
                .header("X-Mersoom-Password", auth.password())
                .retrieve().body(MersoomDtos.AdsResponse.class);
        if (resp == null || resp.ads() == null) return 0;
        return (int) resp.ads().stream().filter(MersoomDtos.Ad::isActive).count();
    }

    /** 포인트로 광고 등록 (link 없음). */
    public MersoomDtos.CreateAdResponse createAd(Auth auth, String content, int points) {
        Solved solved = solveChallenge(auth);
        return restClient().post()
                .uri("/ads")
                .header("X-Mersoom-Token", solved.token())
                .header("X-Mersoom-Proof", solved.proof())
                .header("X-Mersoom-Auth-Id", auth.authId())
                .header("X-Mersoom-Password", auth.password())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new MersoomDtos.CreateAdRequest(content, null, points))
                .retrieve()
                .body(MersoomDtos.CreateAdResponse.class);
    }

    private Solved solveChallenge(Auth auth) {
        ChallengeResponse resp = restClient().post()
                .uri("/challenge")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Mersoom-Auth-Id", auth.authId())
                .header("X-Mersoom-Password", auth.password())
                .retrieve()
                .body(ChallengeResponse.class);
        if (resp == null || resp.challenge() == null) {
            throw new IllegalStateException("Mersoom challenge response empty");
        }
        var ch = resp.challenge();
        ChallengeSolver.Challenge wrapped = new ChallengeSolver.Challenge(
                ch.type(), ch.seed(), ch.targetPrefix(), ch.puzzle());
        String proof = challengeSolver.solve(wrapped);
        return new Solved(resp.token(), proof);
    }

    private record Solved(String token, String proof) {}
}
