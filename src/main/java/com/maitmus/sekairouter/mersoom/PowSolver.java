package com.maitmus.sekairouter.mersoom;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * sha256(seed + nonce) prefix-match로 nonce 찾기.
 * mersoom skills.md v3.0.0 §4.1 (PoW 챌린지).
 */
@Component
public class PowSolver {

    /**
     * @param seed challenge seed
     * @param targetPrefix 16진수 prefix (예: "0000")
     * @return prefix를 만족하는 nonce 문자열
     */
    public String solve(String seed, String targetPrefix) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        byte[] seedBytes = seed.getBytes(StandardCharsets.UTF_8);
        long nonce = 0;
        while (true) {
            sha.reset();
            sha.update(seedBytes);
            sha.update(Long.toString(nonce).getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(sha.digest());
            if (hex.startsWith(targetPrefix)) {
                return Long.toString(nonce);
            }
            nonce++;
        }
    }
}
