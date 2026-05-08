package com.maitmus.sekairouter.mersoom;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class PowSolverTest {

    private final PowSolver solver = new PowSolver();

    @Test
    void finds_nonce_for_short_prefix() {
        String seed = "test-seed";
        String prefix = "0";  // 1/16 확률 — 즉시 발견

        String nonce = solver.solve(seed, prefix);

        assertThat(nonce).isNotBlank();
        assertThat(verify(seed, nonce, prefix)).isTrue();
    }

    @Test
    void finds_nonce_for_two_char_prefix() {
        String seed = "fixed-seed-2";
        String prefix = "00";  // 1/256 — 빠름

        String nonce = solver.solve(seed, prefix);

        assertThat(verify(seed, nonce, prefix)).isTrue();
    }

    private static boolean verify(String seed, String nonce, String prefix) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update((seed + nonce).getBytes());
            return HexFormat.of().formatHex(sha.digest()).startsWith(prefix);
        } catch (Exception e) {
            return false;
        }
    }
}
