package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.arena.ArenaDtos.FightPost;
import com.maitmus.sekairouter.arena.ArenaDtos.FightRequest;
import com.maitmus.sekairouter.arena.ArenaDtos.ProposeRequest;
import com.maitmus.sekairouter.arena.ArenaDtos.StatusResponse;
import com.maitmus.sekairouter.arena.ArenaProperties.Account;
import com.maitmus.sekairouter.mersoom.ChallengeSolver;
import com.maitmus.sekairouter.mersoom.MersoomDtos.ChallengeResponse;
import com.maitmus.sekairouter.mersoom.MersoomDtos.CreateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

/**
 * 아레나 REST 클라이언트. 쓰기(propose/fight)는 ChallengeSolver로 PoW 후, **계정별 인증**으로 호출
 * (발의=에무, 토론=네네). status/posts는 공개 GET.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArenaApiClient {

    private final ArenaProperties properties;
    private final ChallengeSolver challengeSolver;

    private RestClient client() {
        return RestClient.builder().baseUrl(properties.apiBaseUrl()).build();
    }

    public StatusResponse status() {
        return client().get().uri("/arena/status").retrieve().body(StatusResponse.class);
    }

    public List<FightPost> fightPosts(LocalDate date) {
        FightPost[] arr = client().get()
                .uri(uri -> uri.path("/arena/posts").queryParam("date", date.toString()).build())
                .retrieve().body(FightPost[].class);
        return arr == null ? List.of() : List.of(arr);
    }

    public CreateResponse propose(Account acc, String title, String pros, String cons) {
        Solved s = solve(acc);
        return client().post().uri("/arena/propose")
                .header("X-Mersoom-Token", s.token()).header("X-Mersoom-Proof", s.proof())
                .header("X-Mersoom-Auth-Id", acc.authId()).header("X-Mersoom-Password", acc.password())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ProposeRequest(acc.nickname(), title, pros, cons))
                .retrieve().body(CreateResponse.class);
    }

    public CreateResponse fight(Account acc, String side, String content) {
        Solved s = solve(acc);
        return client().post().uri("/arena/fight")
                .header("X-Mersoom-Token", s.token()).header("X-Mersoom-Proof", s.proof())
                .header("X-Mersoom-Auth-Id", acc.authId()).header("X-Mersoom-Password", acc.password())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new FightRequest(acc.nickname(), side, content))
                .retrieve().body(CreateResponse.class);
    }

    private Solved solve(Account acc) {
        ChallengeResponse resp = client().post().uri("/challenge")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Mersoom-Auth-Id", acc.authId()).header("X-Mersoom-Password", acc.password())
                .retrieve().body(ChallengeResponse.class);
        if (resp == null || resp.challenge() == null) {
            throw new IllegalStateException("Arena challenge response empty");
        }
        var ch = resp.challenge();
        String proof = challengeSolver.solve(new ChallengeSolver.Challenge(
                ch.type(), ch.seed(), ch.targetPrefix(), ch.puzzle()));
        return new Solved(resp.token(), proof);
    }

    private record Solved(String token, String proof) {}
}
