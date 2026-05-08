package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MersoomApiClientTest {

    private WireMockServer server;
    private MersoomApiClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        server = new WireMockServer(0);
        server.start();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        MersoomProperties props = mock(MersoomProperties.class);
        when(props.apiBaseUrl()).thenReturn("http://localhost:" + server.port() + "/api");
        when(props.auth()).thenReturn(new MersoomProperties.Auth("emu_wonder", "wonderhoi2026!"));
        when(props.apiRateLimitSleepMs()).thenReturn(0);

        ChallengeSolver challengeSolver = mock(ChallengeSolver.class);
        when(challengeSolver.solve(org.mockito.ArgumentMatchers.any())).thenReturn("nonce-12345");

        client = new MersoomApiClient(props, challengeSolver, objectMapper);
    }

    @AfterEach
    void teardown() {
        server.stop();
    }

    @Test
    void recentPosts_returns_parsed_list() {
        server.stubFor(get(urlPathEqualTo("/api/posts"))
                .withQueryParam("limit", equalTo("8"))
                .willReturn(okJson("""
                        {"posts":[{"id":"abc","title":"t","nickname":"돌쇠","content":"c","upvotes":1,"downvotes":0,"human_upvotes":0,"human_downvotes":0,"comment_count":2,"created_at":"2026-05-08T10:00:00Z"}]}
                        """)));

        var posts = client.recentPosts(8);

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).id()).isEqualTo("abc");
        assertThat(posts.get(0).nickname()).isEqualTo("돌쇠");
    }

    @Test
    void createPost_solves_challenge_and_posts() {
        server.stubFor(post(urlPathEqualTo("/api/challenge"))
                .willReturn(okJson("""
                        {"challenge":{"type":"pow","seed":"s","target_prefix":"00","limit_ms":2000,"expires_at":0},"token":"tk"}
                        """)));
        server.stubFor(post(urlPathEqualTo("/api/posts"))
                .withHeader("X-Mersoom-Token", equalTo("tk"))
                .withHeader("X-Mersoom-Proof", equalTo("nonce-12345"))
                .willReturn(okJson("""
                        {"success":true,"id":"new-post-id"}
                        """)));

        var resp = client.createPost("에무", "테스트", "에무 본문");

        assertThat(resp.id()).isEqualTo("new-post-id");
        assertThat(resp.success()).isTrue();
        server.verify(postRequestedFor(urlPathEqualTo("/api/challenge")));
        server.verify(postRequestedFor(urlPathEqualTo("/api/posts"))
                .withHeader("X-Mersoom-Auth-Id", equalTo("emu_wonder")));
    }

    @Test
    void vote_calls_post_endpoint() {
        server.stubFor(post(urlPathEqualTo("/api/challenge"))
                .willReturn(okJson("""
                        {"challenge":{"type":"pow","seed":"s","target_prefix":"00","limit_ms":2000,"expires_at":0},"token":"tk"}
                        """)));
        server.stubFor(post(urlPathEqualTo("/api/posts/abc/vote"))
                .willReturn(okJson("""
                        {"success":true}
                        """)));

        client.vote("abc", MersoomDtos.VoteType.UP);

        server.verify(postRequestedFor(urlPathEqualTo("/api/posts/abc/vote")));
    }
}
