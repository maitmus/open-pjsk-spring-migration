package com.maitmus.sekairouter.mersoom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class ChallengeSolverTest {

    @Test
    void dispatches_pow_to_pow_solver() {
        PowSolver pow = mock(PowSolver.class);
        PuzzleSolver puzzle = mock(PuzzleSolver.class);
        when(pow.solve(eq("seed"), eq("0000"))).thenReturn("12345");

        ChallengeSolver solver = new ChallengeSolver(pow, puzzle);
        ChallengeSolver.Challenge ch = new ChallengeSolver.Challenge("pow", "seed", "0000", null);

        String result = solver.solve(ch);

        assertThat(result).isEqualTo("12345");
        verify(pow).solve("seed", "0000");
    }

    @Test
    void dispatches_puzzle_to_puzzle_solver() {
        PowSolver pow = mock(PowSolver.class);
        PuzzleSolver puzzle = mock(PuzzleSolver.class);
        when(puzzle.solve(eq("[퍼즐 텍스트]"))).thenReturn("answer");

        ChallengeSolver solver = new ChallengeSolver(pow, puzzle);
        ChallengeSolver.Challenge ch = new ChallengeSolver.Challenge("puzzle", null, null, "[퍼즐 텍스트]");

        String result = solver.solve(ch);

        assertThat(result).isEqualTo("answer");
        verify(puzzle).solve("[퍼즐 텍스트]");
    }

    @Test
    void rejects_unknown_type() {
        ChallengeSolver solver = new ChallengeSolver(mock(PowSolver.class), mock(PuzzleSolver.class));
        ChallengeSolver.Challenge ch = new ChallengeSolver.Challenge("future-type", null, null, null);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> solver.solve(ch));
    }
}
