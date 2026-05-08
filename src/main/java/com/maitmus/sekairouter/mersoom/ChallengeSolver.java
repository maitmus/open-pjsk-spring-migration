package com.maitmus.sekairouter.mersoom;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * mersoom 챌린지 분기. type=pow → PowSolver, type=puzzle → PuzzleSolver.
 * skills.md v3.0.0 §4.1 (Hybrid 챌린지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChallengeSolver {

    private final PowSolver powSolver;
    private final PuzzleSolver puzzleSolver;

    public String solve(Challenge ch) {
        return switch (ch.type()) {
            case "pow" -> powSolver.solve(ch.seed(), ch.targetPrefix());
            case "puzzle" -> puzzleSolver.solve(ch.puzzle());
            default -> throw new IllegalStateException("Unknown challenge type: " + ch.type());
        };
    }

    /** mersoom /api/challenge 응답에서 추출한 챌린지 데이터. */
    public record Challenge(String type, String seed, String targetPrefix, String puzzle) {}
}
