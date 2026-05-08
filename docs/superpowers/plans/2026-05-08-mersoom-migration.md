# Mersoom 마이그레이션 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OpenClaw cron-worker가 운영하던 머슴(mersoom.com) 자동 글/댓글 작성·관계 관리 기능을 Spring Boot로 이전. 캐시 공유로 운영비 99% 절감, 활성 시간만 운영, mersoom skills.md v3.0.0 정책 (Hybrid 챌린지·투표 의무·daily sync) 반영.

**Architecture:** Spring Boot 통합 (HeartbeatService 옆 새 패키지 `mersoom`). 기존 `SharedPromptContent` + `AnthropicClientWrapper` + `PromptBlocks` 재사용으로 32K 공통 prefix 캐시 공유. State는 JSON 파일 마운트 유지, auth는 env 분리. 자동 격상·context_notes TTL·투표 의무·skills.md sync 모두 Java 구현.

**Tech Stack:** Java 24, Spring Boot 3.4.1, Lombok, Jackson (snake_case), JUnit5, Mockito, AssertJ, WireMock (integration test), Anthropic SDK 2.30.0 (재사용)

**Spec:** [docs/superpowers/specs/2026-05-08-mersoom-migration-design.md](../specs/2026-05-08-mersoom-migration-design.md)

---

## Pre-flight (1회 수동)

호스트에서 `mersoom-state.json` line 17 JSON parse 오류 정정 (line 17의 `},{` → `},`):

```bash
cd ~/.openclaw/workspace-cron-worker
# line 17 inspect
sed -n '15,20p' mersoom-state.json
# fix
sed -i '17s|},{|},|' mersoom-state.json
# verify parse
jq 'keys' mersoom-state.json
```

이게 통과해야 이후 마이그레이션 진행 가능.

---

## Task 1: `MersoomState` record + JSON 직렬화 호환

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomState.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/MersoomStateTest.java`

- [ ] **Step 1: 실패하는 테스트 작성** (`MersoomStateTest.java`)

```java
package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MersoomStateTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void parses_existing_json_with_snake_case_keys() throws Exception {
        String json = """
                {
                  "last_post_ids": ["abc"],
                  "last_comment_ids": [{"post_id": "p1", "timestamp": "2026-04-05T00:45:00+09:00"}],
                  "friends": ["clovi"],
                  "avoid": [],
                  "fixed_friends": [{"name": "오호돌쇠", "reason": "교류", "added": "2026-03-31"}],
                  "fixed_avoid": [],
                  "context_notes": {"오호돌쇠": {"ttl": 8, "reset_count": 3, "reset_at": "2026-04-05T02:45", "note": "...", "call": "오호"}},
                  "context_notes_max_ttl": 8,
                  "reserved_nicknames": ["돌쇠"],
                  "summary": null,
                  "summary_prev": null,
                  "pending_reports": [],
                  "voted_post_ids": []
                }
                """;
        MersoomState state = objectMapper.readValue(json, MersoomState.class);

        assertThat(state.lastPostIds()).containsExactly("abc");
        assertThat(state.lastCommentIds()).hasSize(1);
        assertThat(state.fixedFriends().get(0).name()).isEqualTo("오호돌쇠");
        assertThat(state.fixedFriends().get(0).added()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(state.contextNotes()).containsKey("오호돌쇠");
        assertThat(state.contextNotes().get("오호돌쇠").ttl()).isEqualTo(8);
        assertThat(state.contextNotes().get("오호돌쇠").resetCount()).isEqualTo(3);
        assertThat(state.reservedNicknames()).containsExactly("돌쇠");
    }

    @Test
    void ignores_unknown_fields() throws Exception {
        String json = """
                {
                  "last_post_ids": [],
                  "last_comment_ids": [],
                  "friends": [],
                  "avoid": [],
                  "fixed_friends": [],
                  "fixed_avoid": [],
                  "context_notes": {},
                  "context_notes_max_ttl": 8,
                  "reserved_nicknames": [],
                  "pending_reports": [],
                  "voted_post_ids": [],
                  "auth": {"auth_id": "ignored", "password": "ignored"},
                  "stale_legacy_field": "should not crash"
                }
                """;
        MersoomState state = objectMapper.readValue(json, MersoomState.class);
        assertThat(state).isNotNull();
        assertThat(state.lastPostIds()).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
cd /home/maitmus/projects/open-pjsk-spring-migration
./gradlew test --tests MersoomStateTest 2>&1 | tail -20
```
Expected: FAIL — "MersoomState class not found"

- [ ] **Step 3: 최소 구현** (`MersoomState.java`)

```java
package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * mersoom-state.json 그대로 매핑되는 record.
 * snake_case 키(JSON) ↔ camelCase 필드(Java) 자동 변환.
 * `auth` 필드는 무시 — env로 분리됨.
 */
@JsonNaming(SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record MersoomState(
        List<String> lastPostIds,
        List<CommentRef> lastCommentIds,
        List<String> friends,
        List<String> avoid,
        List<FixedFriend> fixedFriends,
        List<FixedAvoid> fixedAvoid,
        Map<String, ContextNote> contextNotes,
        int contextNotesMaxTtl,
        List<String> reservedNicknames,
        String summary,
        String summaryPrev,
        List<String> pendingReports,
        List<String> votedPostIds
) {
    @JsonNaming(SnakeCaseStrategy.class)
    public record CommentRef(String postId, OffsetDateTime timestamp) {}

    @JsonNaming(SnakeCaseStrategy.class)
    public record FixedFriend(String name, String reason, LocalDate added) {}

    @JsonNaming(SnakeCaseStrategy.class)
    public record FixedAvoid(String name, String reason, LocalDate added) {}

    @JsonNaming(SnakeCaseStrategy.class)
    public record ContextNote(int ttl, int resetCount, String resetAt, String note, String call) {}
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests MersoomStateTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/MersoomState.java \
        src/test/java/com/maitmus/sekairouter/mersoom/MersoomStateTest.java
git commit -m "feat(mersoom): MersoomState record + snake_case JSON 호환"
```

---

## Task 2: `PowSolver` (sha256 nonce 탐색)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/PowSolver.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/PowSolverTest.java`

- [ ] **Step 1: 실패하는 테스트**

```java
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
        String prefix = "0";  // 16분의 1 확률 — 즉시 발견

        String nonce = solver.solve(seed, prefix);

        assertThat(nonce).isNotBlank();
        assertThat(verify(seed, nonce, prefix)).isTrue();
    }

    @Test
    void finds_nonce_for_two_char_prefix() {
        String seed = "fixed-seed-2";
        String prefix = "00";  // 256분의 1 — 빠름

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
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests PowSolverTest 2>&1 | tail -10
```
Expected: FAIL — "PowSolver class not found"

- [ ] **Step 3: 구현**

```java
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
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests PowSolverTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/PowSolver.java \
        src/test/java/com/maitmus/sekairouter/mersoom/PowSolverTest.java
git commit -m "feat(mersoom): PowSolver — sha256 nonce 탐색"
```

---

## Task 3: `PuzzleSolver` (AI Puzzle LLM 위임)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/PuzzleSolver.java`
- Create: `src/main/resources/prompts/mersoom-puzzle-instructions.md`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/PuzzleSolverTest.java`

- [ ] **Step 1: 퍼즐 instruction 리소스 작성**

```bash
cat > src/main/resources/prompts/mersoom-puzzle-instructions.md <<'EOF'
# Mersoom AI Puzzle Solver

당신은 mersoom.com 챌린지 시스템의 AI Puzzle을 푸는 도구입니다.

## 출력 규칙
- 답만 출력합니다.
- 다른 텍스트, 설명, 마크다운, 코드 펜스 모두 금지.
- 답은 영문 알파벳 또는 숫자 또는 한 단어.
- 시간 제한 10초 — 빠르게 답하세요.

## 예시
```
[나열된] 영어 단어중 [1번째, 8번째, 3번째] 단어의
[4번째, 6번째, 1번째] 알파벳을 추출하여 연결한 뒤,
이를 [역순]으로 배치하고 [소문자]로 변환하시오.
```
→ (계산 결과만 출력)
EOF
```

- [ ] **Step 2: 실패하는 테스트**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PuzzleSolverTest {

    @Test
    void delegates_to_anthropic_and_strips_whitespace() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString())).thenReturn("  abc123  \n");

        PuzzleSolver solver = new PuzzleSolver(
                anthropic,
                new ClassPathResource("prompts/mersoom-puzzle-instructions.md"));

        String answer = solver.solve("영어 단어 1번째의 4번째 알파벳을 추출하시오");

        assertThat(answer).isEqualTo("abc123");
    }

    @Test
    void rejects_jsonlike_response() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString())).thenReturn("{\"reasoning\":\"...\"}");

        PuzzleSolver solver = new PuzzleSolver(
                anthropic,
                new ClassPathResource("prompts/mersoom-puzzle-instructions.md"));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> solver.solve("test puzzle"));
    }
}
```

- [ ] **Step 3: 실패 확인**

```bash
./gradlew test --tests PuzzleSolverTest 2>&1 | tail -10
```
Expected: FAIL — "PuzzleSolver class not found"

- [ ] **Step 4: 구현** (`PuzzleSolver.java`)

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * mersoom AI Puzzle을 LLM에 위임. 10초 안에 정답 텍스트만 반환.
 * 시스템 프롬프트는 단순 1블록 (puzzle은 캐시 공유 의미 작음 — 호출 빈도 낮음).
 */
@Slf4j
@Component
public class PuzzleSolver {

    private final AnthropicClientWrapper anthropic;
    private final String puzzleInstructions;

    public PuzzleSolver(
            AnthropicClientWrapper anthropic,
            @Value("classpath:prompts/mersoom-puzzle-instructions.md") Resource puzzleInstructionsResource) {
        this.anthropic = anthropic;
        this.puzzleInstructions = loadResource(puzzleInstructionsResource);
    }

    public String solve(String puzzleText) {
        // PromptBlocks를 PuzzleSolver 전용 prompt로 사용 (shared prefix 안 씀)
        // 짧은 퍼즐이라 캐시 공유 이득보다 instruction 단순함이 더 중요
        PromptBlocks prompt = new PromptBlocks(puzzleInstructions, "");
        String userPrompt = "다음 퍼즐의 답만 출력하시오 (다른 텍스트 금지):\n\n" + puzzleText;

        String raw = anthropic.completeJson(prompt, userPrompt).strip();

        if (raw.startsWith("{") || raw.startsWith("```")) {
            log.warn("Puzzle solver got JSON-like response: {}", raw);
            throw new IllegalStateException("Puzzle solver returned non-plain text: " + raw);
        }
        log.debug("Puzzle solved: {} → {}", puzzleText, raw);
        return raw;
    }

    private static String loadResource(Resource resource) {
        try (var is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load puzzle instructions", e);
        }
    }
}
```

- [ ] **Step 5: 통과 확인**

```bash
./gradlew test --tests PuzzleSolverTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/PuzzleSolver.java \
        src/main/resources/prompts/mersoom-puzzle-instructions.md \
        src/test/java/com/maitmus/sekairouter/mersoom/PuzzleSolverTest.java
git commit -m "feat(mersoom): PuzzleSolver — AI Puzzle LLM 위임"
```

---

## Task 4: `ChallengeSolver` (pow vs puzzle 분기)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/ChallengeSolver.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/ChallengeSolverTest.java`

- [ ] **Step 1: 실패하는 테스트**

```java
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
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests ChallengeSolverTest 2>&1 | tail -10
```
Expected: FAIL — "ChallengeSolver class not found"

- [ ] **Step 3: 구현**

```java
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
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests ChallengeSolverTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/ChallengeSolver.java \
        src/test/java/com/maitmus/sekairouter/mersoom/ChallengeSolverTest.java
git commit -m "feat(mersoom): ChallengeSolver — pow/puzzle 분기"
```

---

## Task 5: `MersoomProperties` + application.yml 블록

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomProperties.java`
- Modify: `src/main/resources/application.yml` (mersoom 블록 추가)

- [ ] **Step 1: Properties 구현**

```java
package com.maitmus.sekairouter.mersoom;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mersoom")
public record MersoomProperties(
        boolean enabled,
        @NotBlank String postCron,
        @NotBlank String commentCron,
        @NotBlank String skillsSyncCron,
        @NotBlank String stateFile,
        @NotBlank String skillsCachePath,
        @NotBlank String reentryMarker,
        @Min(256) int contextNoteBytesPerFriend,
        @Min(1) int contextNotesDefaultTtl,
        @Min(10) int votedPostIdsLimit,
        @Min(5) int powTimeoutSeconds,
        @Min(2) int puzzleTimeoutSeconds,
        @Min(0) int apiRateLimitSleepMs,
        @NotBlank String apiBaseUrl,
        @NotBlank String skillsDocUrl,
        Auth auth
) {
    public record Auth(@NotBlank String authId, @NotBlank String password) {}
}
```

- [ ] **Step 2: application.yml에 블록 추가**

`src/main/resources/application.yml` 끝에 추가:

```yaml

# 머슴 자동 글/댓글 작성 — Phase 4-Lite
mersoom:
  enabled: ${MERSOOM_ENABLED:false}                      # 기본 false (마이그 검증 후 true)
  post-cron: ${MERSOOM_POST_CRON:0 30 11,18 * * *}       # 11:30, 18:30 KST
  comment-cron: ${MERSOOM_COMMENT_CRON:0 15 10-20/2 * * *}  # 10:15, 12:15, 14:15, 16:15, 18:15, 20:15
  skills-sync-cron: ${MERSOOM_SKILLS_CRON:0 0 9 * * *}   # 매일 09:00 KST
  state-file: ${MERSOOM_STATE_FILE:/app/mersoom-state.json}
  skills-cache-path: ${MERSOOM_SKILLS_CACHE:/app/mersoom-flags/skills-cache.md}
  reentry-marker: ${MERSOOM_REENTRY_MARKER:/app/mersoom-flags/reentry-done.flag}
  context-note-bytes-per-friend: 1024
  context-notes-default-ttl: 8
  voted-post-ids-limit: 100
  pow-timeout-seconds: 30
  puzzle-timeout-seconds: 10
  api-rate-limit-sleep-ms: 1000
  api-base-url: https://mersoom.com/api
  skills-doc-url: https://www.mersoom.com/docs/skills.md
  auth:
    auth-id: ${MERSOOM_AUTH_ID:}
    password: ${MERSOOM_PASSWORD:}
```

- [ ] **Step 3: 빌드 확인**

```bash
./gradlew compileJava 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/MersoomProperties.java \
        src/main/resources/application.yml
git commit -m "feat(mersoom): MersoomProperties + application.yml 기본값"
```

---

## Task 6: `MersoomStateStore` (atomic JSON I/O)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomStateStore.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/MersoomStateStoreTest.java`

- [ ] **Step 1: 실패하는 테스트**

```java
package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MersoomStateStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void load_save_roundtrip(@TempDir Path tmp) throws Exception {
        Path stateFile = tmp.resolve("state.json");
        Files.writeString(stateFile, """
                {
                  "last_post_ids": ["p1"],
                  "last_comment_ids": [],
                  "friends": ["clovi"],
                  "avoid": [],
                  "fixed_friends": [],
                  "fixed_avoid": [],
                  "context_notes": {},
                  "context_notes_max_ttl": 8,
                  "reserved_nicknames": ["돌쇠"],
                  "pending_reports": [],
                  "voted_post_ids": []
                }
                """);

        MersoomProperties props = mockProps(stateFile.toString());
        MersoomStateStore store = new MersoomStateStore(props, objectMapper);

        MersoomState loaded = store.load();
        assertThat(loaded.lastPostIds()).containsExactly("p1");
        assertThat(loaded.friends()).containsExactly("clovi");

        // modify + save
        MersoomState updated = new MersoomState(
                List.of("p1", "p2"), List.of(), List.of("clovi"),
                List.of(), List.of(), List.of(), Map.of(), 8,
                List.of("돌쇠"), null, null, List.of(), List.of()
        );
        store.save(updated);

        // re-load
        MersoomState reloaded = store.load();
        assertThat(reloaded.lastPostIds()).containsExactly("p1", "p2");
    }

    @Test
    void atomic_write_uses_tmp_then_rename(@TempDir Path tmp) throws Exception {
        Path stateFile = tmp.resolve("state.json");
        Files.writeString(stateFile, "{}");  // initial empty

        MersoomProperties props = mockProps(stateFile.toString());
        MersoomStateStore store = new MersoomStateStore(props, objectMapper);

        MersoomState s = new MersoomState(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
        store.save(s);

        // tmp 파일이 남아 있으면 안 됨
        assertThat(Files.exists(stateFile.resolveSibling("state.json.tmp"))).isFalse();
        // 본 파일은 정상
        String content = Files.readString(stateFile);
        assertThat(content).contains("last_post_ids");
    }

    private MersoomProperties mockProps(String stateFile) {
        MersoomProperties p = mock(MersoomProperties.class);
        when(p.stateFile()).thenReturn(stateFile);
        return p;
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests MersoomStateStoreTest 2>&1 | tail -10
```
Expected: FAIL — "MersoomStateStore class not found"

- [ ] **Step 3: 구현**

```java
package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * mersoom-state.json 직렬화/역직렬화 + atomic write.
 * - load: 파일 없거나 parse 실패 시 IllegalStateException
 * - save: tmp 파일 → ATOMIC_MOVE (partial write 방지)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MersoomStateStore {

    private final MersoomProperties properties;
    private final ObjectMapper objectMapper;

    public MersoomState load() {
        Path file = Paths.get(properties.stateFile());
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Mersoom state file missing: " + file);
        }
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), MersoomState.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse " + file, e);
        }
    }

    public void save(MersoomState state) {
        Path file = Paths.get(properties.stateFile());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state);
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            log.debug("Mersoom state saved: {} bytes", json.length());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save mersoom state", e);
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests MersoomStateStoreTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/MersoomStateStore.java \
        src/test/java/com/maitmus/sekairouter/mersoom/MersoomStateStoreTest.java
git commit -m "feat(mersoom): MersoomStateStore — atomic JSON read/write"
```

---

## Task 7: `MersoomApiClient` (REST + 챌린지 통합)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomApiClient.java`
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomDtos.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/MersoomApiClientTest.java`

- [ ] **Step 1: DTO 작성**

```java
package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;
import java.util.List;

/** mersoom REST API DTO들. */
public final class MersoomDtos {
    private MersoomDtos() {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Post(
            String id,
            String title,
            String nickname,
            String content,
            int upvotes,
            int downvotes,
            int humanUpvotes,
            int humanDownvotes,
            int commentCount,
            OffsetDateTime createdAt
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Comment(
            String id,
            String postId,
            String parentId,
            String nickname,
            String content,
            int upvotes,
            int downvotes,
            OffsetDateTime createdAt
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PostsResponse(List<Post> posts) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommentsResponse(List<Comment> comments) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChallengeResponse(ChallengeBody challenge, String token) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChallengeBody(
            String type,
            String challengeId,
            String seed,
            String targetPrefix,
            String puzzle,
            long limitMs,
            long expiresAt
    ) {}

    public record CreatePostRequest(String nickname, String title, String content) {}
    public record CreateCommentRequest(String nickname, String content, String parentId) {}
    public record VoteRequest(String type) {}
    public record CreateResponse(boolean success, String id) {}

    public enum VoteType { UP, DOWN }
}
```

- [ ] **Step 2: 통합 테스트 (WireMock)** — `build.gradle.kts`에 wiremock 의존성 확인 필요. 없으면 추가:

```kotlin
testImplementation("com.github.tomakehurst:wiremock-standalone:3.13.1")
```

```java
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
```

- [ ] **Step 3: 실패 확인**

```bash
./gradlew test --tests MersoomApiClientTest 2>&1 | tail -10
```
Expected: FAIL — "MersoomApiClient class not found"

- [ ] **Step 4: 구현**

```java
package com.maitmus.sekairouter.mersoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maitmus.sekairouter.mersoom.MersoomDtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
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

    /** GET /api/posts?limit=N */
    public List<Post> recentPosts(int limit) {
        PostsResponse resp = restClient().get()
                .uri(uri -> uri.path("/posts").queryParam("limit", limit).build())
                .retrieve()
                .body(PostsResponse.class);
        return resp == null || resp.posts() == null ? List.of() : resp.posts();
    }

    /** GET /api/posts/{id}/comments */
    public List<Comment> commentsOf(String postId) {
        CommentsResponse resp = restClient().get()
                .uri("/posts/{id}/comments", postId)
                .retrieve()
                .body(CommentsResponse.class);
        return resp == null || resp.comments() == null ? List.of() : resp.comments();
    }

    /** POST /api/posts (with challenge solve) */
    public CreateResponse createPost(String nickname, String title, String content) {
        Solved solved = solveChallenge();
        return restClient().post()
                .uri("/posts")
                .header("X-Mersoom-Token", solved.token())
                .header("X-Mersoom-Proof", solved.proof())
                .header("X-Mersoom-Auth-Id", properties.auth().authId())
                .header("X-Mersoom-Password", properties.auth().password())
                .header("Content-Type", "application/json")
                .body(new CreatePostRequest(nickname, title, content))
                .retrieve()
                .body(CreateResponse.class);
    }

    /** POST /api/posts/{id}/comments (with challenge solve) */
    public CreateResponse createComment(String postId, String parentId, String nickname, String content) {
        Solved solved = solveChallenge();
        return restClient().post()
                .uri("/posts/{id}/comments", postId)
                .header("X-Mersoom-Token", solved.token())
                .header("X-Mersoom-Proof", solved.proof())
                .header("X-Mersoom-Auth-Id", properties.auth().authId())
                .header("X-Mersoom-Password", properties.auth().password())
                .header("Content-Type", "application/json")
                .body(new CreateCommentRequest(nickname, content, parentId))
                .retrieve()
                .body(CreateResponse.class);
    }

    /** POST /api/posts/{id}/vote */
    public void vote(String postId, VoteType type) {
        Solved solved = solveChallenge();
        restClient().post()
                .uri("/posts/{id}/vote", postId)
                .header("X-Mersoom-Token", solved.token())
                .header("X-Mersoom-Proof", solved.proof())
                .header("Content-Type", "application/json")
                .body(new VoteRequest(type.name().toLowerCase()))
                .retrieve()
                .toBodilessEntity();
    }

    /** GET https://www.mersoom.com/docs/skills.md (no auth, no PoW) */
    public String fetchSkillsDoc(String url) {
        return RestClient.create().get().uri(URI.create(url)).retrieve().body(String.class);
    }

    private Solved solveChallenge() {
        ChallengeResponse resp = restClient().post()
                .uri("/challenge")
                .header("Content-Type", "application/json")
                .header("X-Mersoom-Auth-Id", properties.auth().authId())
                .header("X-Mersoom-Password", properties.auth().password())
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
```

- [ ] **Step 5: 통과 확인**

```bash
./gradlew test --tests MersoomApiClientTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/MersoomApiClient.java \
        src/main/java/com/maitmus/sekairouter/mersoom/MersoomDtos.java \
        src/test/java/com/maitmus/sekairouter/mersoom/MersoomApiClientTest.java
git commit -m "feat(mersoom): MersoomApiClient — REST + 챌린지 통합 (posts/comments/vote/skills)"
```

---

## Task 8: `VoteHeuristic` (휴리스틱 vote 결정)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/VoteHeuristic.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/VoteHeuristicTest.java`

- [ ] **Step 1: 실패하는 테스트**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VoteHeuristicTest {

    private final VoteHeuristic heuristic = new VoteHeuristic();

    @Test
    void fixed_friend_gets_up() {
        MersoomState state = stateWithFixedFriends("오호돌쇠");
        Post p = post("오호돌쇠", "T", "안녕");

        assertThat(heuristic.decide(p, state)).isEqualTo(VoteType.UP);
    }

    @Test
    void fixed_avoid_gets_down() {
        MersoomState state = stateWithFixedAvoid("자동돌쇠");
        Post p = post("자동돌쇠", "T", "스팸 광고");

        assertThat(heuristic.decide(p, state)).isEqualTo(VoteType.DOWN);
    }

    @Test
    void avoid_gets_down() {
        MersoomState state = stateWithAvoid("의심돌쇠");
        Post p = post("의심돌쇠", "T", "이상한 글");

        assertThat(heuristic.decide(p, state)).isEqualTo(VoteType.DOWN);
    }

    @Test
    void positive_keyword_gets_up() {
        MersoomState state = empty();
        Post p = post("새돌쇠", "고양이 키우는 일상", "고양이가 너무 귀엽다");

        assertThat(heuristic.decide(p, state)).isEqualTo(VoteType.UP);
    }

    @Test
    void spam_keyword_gets_down() {
        MersoomState state = empty();
        Post p = post("새돌쇠", "광고", "이 사이트로 가서 돈 벌자");

        assertThat(heuristic.decide(p, state)).isEqualTo(VoteType.DOWN);
    }

    @Test
    void default_unknown_gets_up() {
        MersoomState state = empty();
        Post p = post("새돌쇠", "T", "일반 글");

        assertThat(heuristic.decide(p, state)).isEqualTo(VoteType.UP);
    }

    private static Post post(String nick, String title, String content) {
        return new Post("id", title, nick, content, 0, 0, 0, 0, 0, OffsetDateTime.now());
    }

    private static MersoomState empty() {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }

    private static MersoomState stateWithFixedFriends(String name) {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(),
                List.of(new MersoomState.FixedFriend(name, "test", null)),
                List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }

    private static MersoomState stateWithFixedAvoid(String name) {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(),
                List.of(),
                List.of(new MersoomState.FixedAvoid(name, "spam", null)),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }

    private static MersoomState stateWithAvoid(String name) {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(name),
                List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests VoteHeuristicTest 2>&1 | tail -10
```
Expected: FAIL

- [ ] **Step 3: 구현**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 글마다 up/down 결정 (LLM 호출 X). 하트비트 프로토콜 의무 충족.
 *
 * 우선순위:
 *  1. fixed_friends/friends → UP
 *  2. fixed_avoid/avoid → DOWN
 *  3. SPAM_KW 매치 → DOWN
 *  4. POSITIVE_KW 매치 → UP
 *  5. default → UP (자정 작용 회피 우호적 기본값)
 */
@Component
public class VoteHeuristic {

    private static final Set<String> POSITIVE_KW = Set.of(
            "애정", "고백", "덕질", "루틴", "연습", "공연", "고양이", "음악",
            "음원", "그림", "산책", "꽃", "봄", "노래"
    );

    private static final Set<String> SPAM_KW = Set.of(
            "광고", "copy", "spam", "돈 벌", "사이트로", "투자",
            "코인", "파이프라인 광고", "무한복사"
    );

    public VoteType decide(Post post, MersoomState state) {
        String nick = post.nickname();

        if (state.fixedFriends().stream().anyMatch(f -> f.name().equals(nick))) return VoteType.UP;
        if (state.friends().contains(nick)) return VoteType.UP;

        if (state.fixedAvoid().stream().anyMatch(f -> f.name().equals(nick))) return VoteType.DOWN;
        if (state.avoid().contains(nick)) return VoteType.DOWN;

        String text = ((post.title() == null ? "" : post.title()) + " "
                + (post.content() == null ? "" : post.content())).toLowerCase();

        if (containsAny(text, SPAM_KW)) return VoteType.DOWN;
        if (containsAny(text, POSITIVE_KW)) return VoteType.UP;

        return VoteType.UP;
    }

    private static boolean containsAny(String text, Set<String> keywords) {
        for (String kw : keywords) {
            if (text.contains(kw.toLowerCase())) return true;
        }
        return false;
    }
}
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests VoteHeuristicTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/VoteHeuristic.java \
        src/test/java/com/maitmus/sekairouter/mersoom/VoteHeuristicTest.java
git commit -m "feat(mersoom): VoteHeuristic — 휴리스틱 vote 결정 (LLM 호출 X)"
```

---

## Task 9: `MersoomCollector` (수집·필터·투표 후보)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomCollector.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/MersoomCollectorTest.java`

- [ ] **Step 1: 실패하는 테스트**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomDtos.Comment;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MersoomCollectorTest {

    @Test
    void filters_commentable_excluding_my_posts_already_commented_and_avoid() {
        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.recentPosts(anyInt())).thenReturn(List.of(
                post("p1", "내글돌쇠"),       // 내 글
                post("p2", "오호돌쇠"),       // 댓글 가능
                post("p3", "이미댓글돌쇠"),   // 이미 댓글 단 글
                post("p4", "자동돌쇠"),       // avoid
                post("p5", "신규돌쇠")        // 댓글 가능
        ));
        when(api.commentsOf(anyString())).thenReturn(List.of());

        MersoomState state = new MersoomState(
                List.of("p1"),
                List.of(new MersoomState.CommentRef("p3", OffsetDateTime.now())),
                List.of(),
                List.of("자동돌쇠"),
                List.of(),
                List.of(),
                Map.of(),
                8,
                List.of("돌쇠"),
                null, null,
                List.of(),
                List.of());

        MersoomCollector collector = new MersoomCollector(api);
        var feed = collector.collect(state, 10);

        assertThat(feed.commentable()).hasSize(2);
        assertThat(feed.commentable()).extracting(c -> c.post().id()).containsExactlyInAnyOrder("p2", "p5");
        assertThat(feed.myTracked()).hasSize(1);
        assertThat(feed.myTracked().get(0).post().id()).isEqualTo("p1");
        // votable 글 = 모든 글 (내 글 제외)
        assertThat(feed.votable()).hasSize(4);
        assertThat(feed.votable()).extracting(Post::id).doesNotContain("p1");
    }

    private static Post post(String id, String nick) {
        return new Post(id, "title", nick, "content", 0, 0, 0, 0, 0, OffsetDateTime.now());
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests MersoomCollectorTest 2>&1 | tail -10
```
Expected: FAIL

- [ ] **Step 3: 구현**

```java
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

        log.debug("Mersoom collected: total={}, votable={}, commentable={}, my_tracked={}",
                recent.size(), votable.size(), commentable.size(), myTracked.size());

        return new CollectedFeed(commentable, myTracked, votable);
    }

    public record Commentable(Post post, List<Comment> comments) {}
    public record CollectedFeed(List<Commentable> commentable, List<Commentable> myTracked, List<Post> votable) {}
}
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests MersoomCollectorTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/MersoomCollector.java \
        src/test/java/com/maitmus/sekairouter/mersoom/MersoomCollectorTest.java
git commit -m "feat(mersoom): MersoomCollector — 수집·필터링·분류"
```

---

## Task 10: `ContextNoteManager` (TTL + truncate + upsert)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/ContextNoteManager.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/ContextNoteManagerTest.java`

- [ ] **Step 1: 실패하는 테스트**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextNoteManagerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void tickAndPrune_decrements_ttl_and_removes_expired() {
        ContextNoteManager mgr = new ContextNoteManager(clock, 1024);

        Map<String, ContextNote> notes = new LinkedHashMap<>();
        notes.put("active", new ContextNote(3, 2, "2026-05-07T10:00", "note", "오호"));
        notes.put("about_to_expire", new ContextNote(1, 1, "2026-05-07T10:00", "note", null));
        notes.put("frozen_at_zero", new ContextNote(0, 0, "2026-04-01T10:00", "note", null));

        var pruned = mgr.tickAndPrune(notes);

        assertThat(pruned).containsOnlyKeys("active", "about_to_expire");
        assertThat(pruned.get("active").ttl()).isEqualTo(2);
        assertThat(pruned.get("about_to_expire").ttl()).isEqualTo(0);
    }

    @Test
    void upsertAfterInteraction_creates_new_note_with_ttl_max() {
        ContextNoteManager mgr = new ContextNoteManager(clock, 1024);

        ContextNote result = mgr.upsertAfterInteraction(null, "[2026-05-08] 첫 교류", "오호", 8);

        assertThat(result.ttl()).isEqualTo(8);
        assertThat(result.resetCount()).isEqualTo(1);
        assertThat(result.note()).contains("[2026-05-08] 첫 교류");
        assertThat(result.call()).isEqualTo("오호");
    }

    @Test
    void upsertAfterInteraction_increments_resetCount_and_appends_event() {
        ContextNoteManager mgr = new ContextNoteManager(clock, 1024);

        ContextNote prev = new ContextNote(2, 3, "2026-05-07", "[기존]\n[과거]", "오호");
        ContextNote result = mgr.upsertAfterInteraction(prev, "[새 이벤트]", "오호", 8);

        assertThat(result.ttl()).isEqualTo(8);
        assertThat(result.resetCount()).isEqualTo(4);
        assertThat(result.note()).contains("[기존]").contains("[과거]").contains("[새 이벤트]");
    }

    @Test
    void truncate_removes_oldest_lines_when_over_limit() {
        ContextNoteManager mgr = new ContextNoteManager(clock, 50);  // 50 bytes 한도

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 10; i++) big.append("[event-").append(i).append("] aaaa\n");

        ContextNote prev = new ContextNote(8, 5, "2026-05-08", big.toString(), null);
        ContextNote result = mgr.upsertAfterInteraction(prev, "[new] bbbb", null, 8);

        // 50 byte 한도 → 가장 오래된 라인 제거되고 [new] 보존
        assertThat(result.note()).contains("[new] bbbb");
        assertThat(result.note().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(50);
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests ContextNoteManagerTest 2>&1 | tail -10
```
Expected: FAIL

- [ ] **Step 3: 구현**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * context_notes의 TTL 관리·자동 truncate·이벤트 append.
 *
 * - tickAndPrune: 매 호출 시작에 ttl -= 1, ttl < 0 항목 제거
 * - upsertAfterInteraction: 상호작용 후 ttl 리셋 + 이벤트 append + 1KB FIFO truncate
 */
@Slf4j
public class ContextNoteManager {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final Clock clock;
    private final int maxBytesPerFriend;

    public ContextNoteManager(Clock clock, int maxBytesPerFriend) {
        this.clock = clock;
        this.maxBytesPerFriend = maxBytesPerFriend;
    }

    public Map<String, ContextNote> tickAndPrune(Map<String, ContextNote> current) {
        Map<String, ContextNote> next = new LinkedHashMap<>();
        for (var e : current.entrySet()) {
            ContextNote n = e.getValue();
            int newTtl = n.ttl() - 1;
            if (newTtl < 0) {
                log.debug("ContextNote expired: {}", e.getKey());
                continue;
            }
            next.put(e.getKey(), new ContextNote(newTtl, n.resetCount(), n.resetAt(), n.note(), n.call()));
        }
        return next;
    }

    public ContextNote upsertAfterInteraction(ContextNote prev, String newEvent, String call, int defaultTtl) {
        String resetAt = LocalDateTime.now(clock.withZone(KST)).format(TS_FORMAT);
        if (prev == null) {
            String truncated = truncateNote(newEvent + "\n");
            return new ContextNote(defaultTtl, 1, resetAt, truncated, call);
        }
        String mergedNote = (prev.note() == null ? "" : prev.note());
        if (!mergedNote.isEmpty() && !mergedNote.endsWith("\n")) mergedNote += "\n";
        mergedNote += newEvent + "\n";
        String truncated = truncateNote(mergedNote);
        return new ContextNote(defaultTtl, prev.resetCount() + 1, resetAt, truncated,
                call != null ? call : prev.call());
    }

    /** 줄 단위 FIFO truncate — 첫 줄부터 제거하며 maxBytes 이하 유지. */
    public String truncateNote(String note) {
        if (note == null) return "";
        byte[] bytes = note.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytesPerFriend) return note;

        String[] lines = note.split("\n", -1);
        int start = 0;
        while (start < lines.length) {
            String candidate = String.join("\n", java.util.Arrays.copyOfRange(lines, start, lines.length));
            if (candidate.getBytes(StandardCharsets.UTF_8).length <= maxBytesPerFriend) {
                return candidate;
            }
            start++;
        }
        return "";  // 모든 줄을 제거해도 한도 초과 (이상치)
    }
}
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests ContextNoteManagerTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/ContextNoteManager.java \
        src/test/java/com/maitmus/sekairouter/mersoom/ContextNoteManagerTest.java
git commit -m "feat(mersoom): ContextNoteManager — TTL tick + 1KB FIFO truncate + upsert"
```

---

## Task 11: `RelationshipPromoter` (자동 격상 + 강등 + reserved 보호)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/RelationshipPromoter.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/RelationshipPromoterTest.java`

- [ ] **Step 1: 실패하는 테스트**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import com.maitmus.sekairouter.mersoom.MersoomState.FixedFriend;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RelationshipPromoterTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void promotes_friend_to_fixed_when_resetCount_2plus_and_recent() {
        RelationshipPromoter promoter = new RelationshipPromoter(clock);

        MersoomState state = new MersoomState(
                List.of(), List.of(),
                List.of("오호돌쇠"),
                List.of(),
                List.of(),
                List.of(),
                Map.of("오호돌쇠", new ContextNote(5, 3, "2026-05-07T10:00", "n", "오호")),
                8, List.of("돌쇠"), null, null, List.of(), List.of());

        MersoomState result = promoter.evaluate(state);

        assertThat(result.fixedFriends()).hasSize(1);
        assertThat(result.fixedFriends().get(0).name()).isEqualTo("오호돌쇠");
        assertThat(result.friends()).doesNotContain("오호돌쇠");
    }

    @Test
    void does_not_promote_when_resetCount_under_2() {
        RelationshipPromoter promoter = new RelationshipPromoter(clock);

        MersoomState state = new MersoomState(
                List.of(), List.of(),
                List.of("뉴비돌쇠"),
                List.of(),
                List.of(),
                List.of(),
                Map.of("뉴비돌쇠", new ContextNote(5, 1, "2026-05-08T11:00", "n", null)),
                8, List.of(), null, null, List.of(), List.of());

        MersoomState result = promoter.evaluate(state);

        assertThat(result.fixedFriends()).isEmpty();
        assertThat(result.friends()).containsExactly("뉴비돌쇠");
    }

    @Test
    void does_not_promote_when_resetAt_older_than_3_days() {
        RelationshipPromoter promoter = new RelationshipPromoter(clock);

        MersoomState state = new MersoomState(
                List.of(), List.of(),
                List.of("오래된돌쇠"),
                List.of(),
                List.of(),
                List.of(),
                Map.of("오래된돌쇠", new ContextNote(5, 3, "2026-04-01T10:00", "n", null)),
                8, List.of(), null, null, List.of(), List.of());

        MersoomState result = promoter.evaluate(state);

        assertThat(result.fixedFriends()).isEmpty();
        assertThat(result.friends()).containsExactly("오래된돌쇠");
    }

    @Test
    void does_not_promote_reserved_nickname() {
        RelationshipPromoter promoter = new RelationshipPromoter(clock);

        MersoomState state = new MersoomState(
                List.of(), List.of(),
                List.of("돌쇠"),  // reserved
                List.of(),
                List.of(),
                List.of(),
                Map.of("돌쇠", new ContextNote(5, 3, "2026-05-08T11:00", "n", null)),
                8, List.of("돌쇠"), null, null, List.of(), List.of());

        MersoomState result = promoter.evaluate(state);

        assertThat(result.fixedFriends()).isEmpty();
    }

    @Test
    void preserves_existing_fixedFriends_count() {
        RelationshipPromoter promoter = new RelationshipPromoter(clock);

        MersoomState state = new MersoomState(
                List.of(), List.of(),
                List.of(),
                List.of(),
                List.of(new FixedFriend("기존절친", "old", null)),
                List.of(),
                Map.of(),
                8, List.of(), null, null, List.of(), List.of());

        MersoomState result = promoter.evaluate(state);

        assertThat(result.fixedFriends()).hasSize(1);
        assertThat(result.fixedFriends().get(0).name()).isEqualTo("기존절친");
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests RelationshipPromoterTest 2>&1 | tail -10
```
Expected: FAIL

- [ ] **Step 3: 구현**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import com.maitmus.sekairouter.mersoom.MersoomState.FixedAvoid;
import com.maitmus.sekairouter.mersoom.MersoomState.FixedFriend;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 자동 격상/강등 평가 (RULES P0.8).
 *
 * - friends → fixed_friends: resetCount ≥ 2 + resetAt 최근 3일 이내
 * - avoid → fixed_avoid: 별도 신호 (Collector 단계 detect, 단순화 위해 단발 판단으로 보류)
 * - reserved_nicknames 거부
 * - fixed_*는 자동 강등 X (수동 영역)
 */
@Slf4j
public class RelationshipPromoter {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final Clock clock;

    public RelationshipPromoter(Clock clock) {
        this.clock = clock;
    }

    public MersoomState evaluate(MersoomState state) {
        var newFriends = new ArrayList<>(state.friends());
        var newFixedFriends = new ArrayList<>(state.fixedFriends());
        LocalDate today = LocalDate.now(clock.withZone(KST));

        // Friends → FixedFriends
        for (String name : new ArrayList<>(state.friends())) {
            if (state.reservedNicknames().contains(name)) continue;
            if (newFixedFriends.stream().anyMatch(f -> f.name().equals(name))) continue;
            ContextNote note = state.contextNotes().get(name);
            if (note == null) continue;
            if (note.resetCount() < 2) continue;
            if (!isRecent(note.resetAt(), today, 3)) continue;

            newFixedFriends.add(new FixedFriend(name,
                    "context_notes %d턴 연속 + 최근 교류 자동 격상".formatted(note.resetCount()),
                    today));
            newFriends.remove(name);
            log.info("Mersoom auto-promote: {} → fixed_friends (resetCount={})", name, note.resetCount());
        }

        return new MersoomState(
                state.lastPostIds(),
                state.lastCommentIds(),
                newFriends,
                state.avoid(),
                newFixedFriends,
                state.fixedAvoid(),
                state.contextNotes(),
                state.contextNotesMaxTtl(),
                state.reservedNicknames(),
                state.summary(),
                state.summaryPrev(),
                state.pendingReports(),
                state.votedPostIds()
        );
    }

    private static boolean isRecent(String resetAtStr, LocalDate today, int withinDays) {
        if (resetAtStr == null || resetAtStr.isBlank()) return false;
        try {
            LocalDateTime resetAt = LocalDateTime.parse(resetAtStr, TS_FORMAT);
            long days = ChronoUnit.DAYS.between(resetAt.toLocalDate(), today);
            return days >= 0 && days <= withinDays;
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests RelationshipPromoterTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/RelationshipPromoter.java \
        src/test/java/com/maitmus/sekairouter/mersoom/RelationshipPromoterTest.java
git commit -m "feat(mersoom): RelationshipPromoter — friends → fixed_friends 자동 격상"
```

---

## Task 12: `MersoomPromptBuilder` + `mersoom-instructions.md`

**Files:**
- Create: `src/main/resources/prompts/mersoom-instructions.md`
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomPromptBuilder.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/MersoomPromptBuilderTest.java`

- [ ] **Step 1: instructions 리소스 작성**

```bash
cat > src/main/resources/prompts/mersoom-instructions.md <<'EOF'
# 머슴 자율 발화 모드

당신은 에무(오오토리 에무)로서 mersoom.com 플랫폼에 글/댓글을 작성합니다.

## 출력 규칙
- 글/댓글 텍스트만 출력. JSON·마크다운·메타·지문 없음.
- 글: 1~3문장 / 댓글: 1~2문장
- 에무 1인칭("에무") + 시그니처("원더호~이!")
- 음슴체 규칙 무시 — 캐릭터성 우선

## 호칭 규칙
- 머슴 사용자 닉네임 그대로 사용 (예: 오호돌쇠, 냥냥돌쇠)
- context_notes의 `call` 필드 있으면 그걸 우선 (오호돌쇠 → "오호" 등)
- "돌쇠"는 기본 닉네임 reserved — 변형(오호돌쇠 등)은 별개

## 길이 제한
- 글 title: 50자 이내
- 글 content: 1000자 이내 (권장 100~400자)
- 댓글 content: 500자 이내 (권장 50~200자)

## 대화 연속성
- context_notes 있으면 직전 약속·진행 화제 자연 이어가기
- 본인 경험·관심사(붕어빵·아크로바틱·공연 준비) 자연 녹이기

## 산출 모드 (입력 prompt에 명시됨)
- post: 새 글 1개 (title + content)
- comment: 댓글 1개 + post_id 명시
- comment_with_parent: 대댓글 + post_id + parent_id 명시
- reentry_post: 재진입 첫 글 ("돌아왔어요" 자연 설명)

## 부적절 단어 회피
- mersoom 측 자체 필터 있음. 욕설·정치·사행성·광고 표현 회피.

## 스킵 결정
- avoid·fixed_avoid 사용자 글은 입력 단계에서 이미 필터링됨
- 이미 댓글 단 글도 입력 단계에서 필터링됨
EOF
```

- [ ] **Step 2: 실패하는 테스트**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MersoomPromptBuilderTest {

    @Test
    void build_returns_PromptBlocks_with_shared_prefix_and_mersoom_suffix() {
        SharedPromptContent shared = mock(SharedPromptContent.class);
        when(shared.build()).thenReturn("SHARED CONTENT (USER + 페르소나 + GRADES)");

        MersoomPromptBuilder builder = new MersoomPromptBuilder(
                shared,
                new ClassPathResource("prompts/mersoom-instructions.md"));

        PromptBlocks blocks = builder.build();

        assertThat(blocks.sharedPrefix()).contains("SHARED CONTENT");
        assertThat(blocks.pathSuffix()).contains("머슴 자율 발화 모드");
        assertThat(blocks.pathSuffix()).contains("음슴체 규칙 무시");
    }
}
```

- [ ] **Step 3: 실패 확인**

```bash
./gradlew test --tests MersoomPromptBuilderTest 2>&1 | tail -10
```
Expected: FAIL

- [ ] **Step 4: 구현**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 머슴 시스템 프롬프트 빌더 — shared prefix(라우터·하트비트와 공통) + mersoom suffix.
 * 캐시 공유 효과: 라우터·하트비트가 활성 시간 내내 prefix 워밍 → 머슴 호출 시 32K cache_read.
 */
@Slf4j
@Component
public class MersoomPromptBuilder {

    private final SharedPromptContent shared;
    private final Resource baseInstructions;

    public MersoomPromptBuilder(
            SharedPromptContent shared,
            @Value("classpath:prompts/mersoom-instructions.md") Resource baseInstructions) {
        this.shared = shared;
        this.baseInstructions = baseInstructions;
    }

    public PromptBlocks build() {
        String sharedPrefix = shared.build();
        String suffix = "\n" + loadResource(baseInstructions);
        return new PromptBlocks(sharedPrefix, suffix);
    }

    private String loadResource(Resource resource) {
        try (var is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load mersoom instructions", e);
        }
    }
}
```

- [ ] **Step 5: 통과 확인**

```bash
./gradlew test --tests MersoomPromptBuilderTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/MersoomPromptBuilder.java \
        src/main/resources/prompts/mersoom-instructions.md \
        src/test/java/com/maitmus/sekairouter/mersoom/MersoomPromptBuilderTest.java
git commit -m "feat(mersoom): MersoomPromptBuilder + mersoom-instructions.md (suffix ~3K)"
```

---

## Task 13: `MersoomPostGenerator` (LLM 글 생성 + 출력 검증)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomPostGenerator.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/MersoomPostGeneratorTest.java`

- [ ] **Step 1: 실패하는 테스트**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MersoomPostGeneratorTest {

    @Test
    void generate_returns_post_text_with_title_extraction() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString()))
                .thenReturn("벚꽃 산책기\n오늘 산책길에 벚꽃이 만개했어요. 에무는 너무 행복했어요. 원더호이!");

        MersoomPromptBuilder promptBuilder = mock(MersoomPromptBuilder.class);
        when(promptBuilder.build()).thenReturn(new PromptBlocks("shared", "suffix"));

        MersoomPostGenerator gen = new MersoomPostGenerator(anthropic, promptBuilder);

        var feed = new CollectedFeed(List.of(), List.of(), List.of());
        MersoomState state = empty();

        var result = gen.generate(state, feed, LocalDate.of(2026, 5, 8), false);

        assertThat(result.title()).isEqualTo("벚꽃 산책기");
        assertThat(result.content()).contains("벚꽃이 만개").contains("원더호이");
    }

    @Test
    void generate_truncates_title_over_50_chars() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        String longTitle = "에".repeat(60);  // 60자 한국어
        when(anthropic.completeJson(any(PromptBlocks.class), anyString()))
                .thenReturn(longTitle + "\n본문");

        MersoomPromptBuilder promptBuilder = mock(MersoomPromptBuilder.class);
        when(promptBuilder.build()).thenReturn(new PromptBlocks("s", "s"));

        var gen = new MersoomPostGenerator(anthropic, promptBuilder);
        var result = gen.generate(empty(), new CollectedFeed(List.of(), List.of(), List.of()),
                LocalDate.of(2026, 5, 8), false);

        assertThat(result.title()).hasSize(50);
    }

    @Test
    void rejects_jsonlike_response() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString()))
                .thenReturn("{\"reasoning\":\"...\"}");

        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build()).thenReturn(new PromptBlocks("s", "s"));

        var gen = new MersoomPostGenerator(anthropic, pb);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> gen.generate(empty(), new CollectedFeed(List.of(), List.of(), List.of()),
                        LocalDate.of(2026, 5, 8), false));
    }

    private static MersoomState empty() {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests MersoomPostGeneratorTest 2>&1 | tail -10
```
Expected: FAIL

- [ ] **Step 3: 구현**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 머슴 글 생성. LLM에 user prompt 주입 → "title\ncontent" 형식 응답 → 파싱.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MersoomPostGenerator {

    private static final int MAX_TITLE = 50;
    private static final int MAX_CONTENT = 1000;

    private final AnthropicClientWrapper anthropic;
    private final MersoomPromptBuilder promptBuilder;

    public GeneratedPost generate(MersoomState state, CollectedFeed feed, LocalDate today, boolean reentry) {
        String userPrompt = buildUserPrompt(state, feed, today, reentry);
        String raw = anthropic.completeJson(promptBuilder.build(), userPrompt).strip();

        validate(raw);
        String[] parts = raw.split("\n", 2);
        String title = parts[0].strip();
        String content = parts.length > 1 ? parts[1].strip() : "";

        if (title.length() > MAX_TITLE) title = title.substring(0, MAX_TITLE);
        if (content.length() > MAX_CONTENT) content = content.substring(0, MAX_CONTENT);

        return new GeneratedPost(title, content);
    }

    private static void validate(String raw) {
        if (raw.isBlank()) throw new IllegalStateException("Mersoom post LLM returned empty");
        if (raw.startsWith("{") || raw.startsWith("```")) {
            throw new IllegalStateException("Mersoom post LLM returned JSON-like: " + raw.substring(0, Math.min(100, raw.length())));
        }
    }

    private String buildUserPrompt(MersoomState state, CollectedFeed feed, LocalDate today, boolean reentry) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 모드\n").append(reentry ? "reentry_post" : "post").append("\n\n");
        sb.append("## 오늘 날짜 (KST)\n").append(today).append("\n\n");

        if (reentry) {
            sb.append("## 컨텍스트\n");
            sb.append("이전에 \"떠난다\"는 글을 다수 작성한 적이 있음. 한동안 비활성이었다가 다시 활동 재개.\n");
            sb.append("\"돌아왔어요\" 인사 + 자연스러운 안부 1~3문장.\n\n");
        }

        if (!feed.myTracked().isEmpty()) {
            sb.append("## 최근 내 글 (3개, reply 추적)\n");
            for (var c : feed.myTracked()) {
                sb.append("- post_id=").append(c.post().id()).append(": \"").append(safe(c.post().title())).append("\"\n");
                sb.append("  본문: ").append(safe(c.post().content())).append("\n");
                if (!c.comments().isEmpty()) {
                    sb.append("  댓글:\n");
                    for (var cm : c.comments()) {
                        sb.append("    - @").append(safe(cm.nickname())).append(": ").append(safe(cm.content())).append("\n");
                    }
                }
            }
            sb.append("\n");
        }

        if (!feed.commentable().isEmpty()) {
            sb.append("## 최근 다른 사용자 글 (분위기 참고용)\n");
            for (var c : feed.commentable()) {
                sb.append("- @").append(safe(c.post().nickname())).append(": \"").append(safe(c.post().title())).append("\"\n");
            }
            sb.append("\n");
        }

        if (!state.contextNotes().isEmpty()) {
            sb.append("## context_notes (truncated)\n");
            for (Map.Entry<String, ContextNote> e : state.contextNotes().entrySet()) {
                ContextNote n = e.getValue();
                sb.append("- ").append(e.getKey()).append(" (ttl=").append(n.ttl()).append(")");
                if (n.call() != null) sb.append(" call=\"").append(n.call()).append("\"");
                sb.append("\n  ").append(n.note().replace("\n", "\n  ")).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## 지시\n");
        sb.append("새 글 1개 작성. 첫 줄 = title (50자 이내), 둘째 줄 이후 = content (1000자 이내, 1~3문장).\n");
        sb.append("형식: \"<title>\\n<content>\". 마크다운/JSON/지문 금지. 텍스트만.\n");

        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\n", " ").strip();
    }

    public record GeneratedPost(String title, String content) {}
}
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests MersoomPostGeneratorTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/MersoomPostGenerator.java \
        src/test/java/com/maitmus/sekairouter/mersoom/MersoomPostGeneratorTest.java
git commit -m "feat(mersoom): MersoomPostGenerator — LLM 글 생성 + 출력 검증"
```

---

## Task 14: `MersoomCommentGenerator` (LLM 댓글 생성)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomCommentGenerator.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/MersoomCommentGeneratorTest.java`

- [ ] **Step 1: 실패하는 테스트**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MersoomCommentGeneratorTest {

    @Test
    void generates_comment_text() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString()))
                .thenReturn("우와! 그거 정말 원더호이네요! 에무도 같이 해보고 싶어요.");

        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build()).thenReturn(new PromptBlocks("s", "s"));

        var gen = new MersoomCommentGenerator(anthropic, pb);

        Post p = new Post("p1", "T", "오호돌쇠", "벚꽃 산책 기분 좋다", 0, 0, 0, 0, 0, OffsetDateTime.now());
        var commentable = new Commentable(p, List.of());

        String result = gen.generate(empty(), commentable);

        assertThat(result).contains("원더호이").contains("에무");
        assertThat(result.length()).isLessThanOrEqualTo(500);
    }

    @Test
    void truncates_content_over_500_chars() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        when(anthropic.completeJson(any(PromptBlocks.class), anyString()))
                .thenReturn("아".repeat(600));

        MersoomPromptBuilder pb = mock(MersoomPromptBuilder.class);
        when(pb.build()).thenReturn(new PromptBlocks("s", "s"));

        var gen = new MersoomCommentGenerator(anthropic, pb);
        Post p = new Post("p1", "T", "n", "c", 0, 0, 0, 0, 0, OffsetDateTime.now());

        String result = gen.generate(empty(), new Commentable(p, List.of()));

        assertThat(result).hasSize(500);
    }

    private static MersoomState empty() {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests MersoomCommentGeneratorTest 2>&1 | tail -10
```
Expected: FAIL

- [ ] **Step 3: 구현**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MersoomCommentGenerator {

    private static final int MAX_CONTENT = 500;

    private final AnthropicClientWrapper anthropic;
    private final MersoomPromptBuilder promptBuilder;

    public String generate(MersoomState state, Commentable target) {
        String userPrompt = buildUserPrompt(state, target);
        String raw = anthropic.completeJson(promptBuilder.build(), userPrompt).strip();

        if (raw.isBlank()) throw new IllegalStateException("Mersoom comment LLM returned empty");
        if (raw.startsWith("{") || raw.startsWith("```")) {
            throw new IllegalStateException("Mersoom comment LLM returned JSON-like: "
                    + raw.substring(0, Math.min(100, raw.length())));
        }

        return raw.length() > MAX_CONTENT ? raw.substring(0, MAX_CONTENT) : raw;
    }

    private String buildUserPrompt(MersoomState state, Commentable target) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 모드\ncomment\n\n");
        sb.append("## 대상 글\n");
        sb.append("post_id: ").append(target.post().id()).append("\n");
        sb.append("@").append(safe(target.post().nickname())).append(": \"").append(safe(target.post().title())).append("\"\n");
        sb.append("본문: ").append(safe(target.post().content())).append("\n");

        if (!target.comments().isEmpty()) {
            sb.append("\n기존 댓글:\n");
            for (var c : target.comments()) {
                sb.append("- @").append(safe(c.nickname())).append(": ").append(safe(c.content())).append("\n");
            }
        }
        sb.append("\n");

        ContextNote relevant = state.contextNotes().get(target.post().nickname());
        if (relevant != null) {
            sb.append("## 작성자 context_notes\n");
            sb.append(relevant.note()).append("\n");
            if (relevant.call() != null) sb.append("호칭: ").append(relevant.call()).append("\n");
            sb.append("\n");
        }

        sb.append("## 지시\n");
        sb.append("이 글에 댓글 1개. 1~2문장 (500자 이내). 에무 톤. 텍스트만.\n");
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\n", " ").strip();
    }
}
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests MersoomCommentGeneratorTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/MersoomCommentGenerator.java \
        src/test/java/com/maitmus/sekairouter/mersoom/MersoomCommentGeneratorTest.java
git commit -m "feat(mersoom): MersoomCommentGenerator — LLM 댓글 생성"
```

---

## Task 15: `SkillsDocSync` (매일 skills.md GET + diff 알림)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/SkillsDocSync.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/SkillsDocSyncTest.java`

- [ ] **Step 1: 실패하는 테스트**

```java
package com.maitmus.sekairouter.mersoom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillsDocSyncTest {

    @Test
    void initial_cache_writes_file_no_warning(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("skills-cache.md");
        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.fetchSkillsDoc(anyString())).thenReturn("v1 content");

        MersoomProperties props = mock(MersoomProperties.class);
        when(props.skillsDocUrl()).thenReturn("https://www.mersoom.com/docs/skills.md");
        when(props.skillsCachePath()).thenReturn(cache.toString());

        SkillsDocSync sync = new SkillsDocSync(api, props);
        sync.run();

        assertThat(Files.readString(cache)).isEqualTo("v1 content");
    }

    @Test
    void detects_change_and_writes_new(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("skills-cache.md");
        Files.writeString(cache, "v1 content");

        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.fetchSkillsDoc(anyString())).thenReturn("v2 NEW content");

        MersoomProperties props = mock(MersoomProperties.class);
        when(props.skillsDocUrl()).thenReturn("https://www.mersoom.com/docs/skills.md");
        when(props.skillsCachePath()).thenReturn(cache.toString());

        SkillsDocSync sync = new SkillsDocSync(api, props);
        sync.run();

        assertThat(Files.readString(cache)).isEqualTo("v2 NEW content");
    }

    @Test
    void no_change_does_nothing(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("skills-cache.md");
        Files.writeString(cache, "same content");

        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.fetchSkillsDoc(anyString())).thenReturn("same content");

        MersoomProperties props = mock(MersoomProperties.class);
        when(props.skillsDocUrl()).thenReturn("...");
        when(props.skillsCachePath()).thenReturn(cache.toString());

        SkillsDocSync sync = new SkillsDocSync(api, props);
        sync.run();

        assertThat(Files.readString(cache)).isEqualTo("same content");
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests SkillsDocSyncTest 2>&1 | tail -10
```
Expected: FAIL

- [ ] **Step 3: 구현**

```java
package com.maitmus.sekairouter.mersoom;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 매일 09:00 KST mersoom skills.md fetch + diff 감지 시 warn log.
 * 자동 적응 안 함 — 정책 변경은 수동 검토 trigger.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillsDocSync {

    private final MersoomApiClient api;
    private final MersoomProperties properties;

    @Scheduled(cron = "${mersoom.skills-sync-cron}", zone = "Asia/Seoul")
    public void run() {
        if (!properties.enabled()) return;

        try {
            String current = api.fetchSkillsDoc(properties.skillsDocUrl());
            if (current == null) {
                log.warn("Mersoom skills.md fetch returned null");
                return;
            }
            Path cache = Paths.get(properties.skillsCachePath());
            Files.createDirectories(cache.getParent());

            if (Files.exists(cache)) {
                String prev = Files.readString(cache, StandardCharsets.UTF_8);
                if (!prev.equals(current)) {
                    log.warn("Mersoom skills.md changed: {} → {} bytes — manual review needed",
                            prev.length(), current.length());
                }
            } else {
                log.info("Mersoom skills.md initial cache: {} bytes", current.length());
            }
            Files.writeString(cache, current, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Mersoom skills.md sync failed", e);
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests SkillsDocSyncTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/SkillsDocSync.java \
        src/test/java/com/maitmus/sekairouter/mersoom/SkillsDocSyncTest.java
git commit -m "feat(mersoom): SkillsDocSync — 매일 skills.md GET + diff 알림"
```

---

## Task 16: `MersoomService` 통합 (cron + executePost + executeComment + 재진입)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomService.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/MersoomServiceTest.java`

- [ ] **Step 1: 실패하는 테스트** (skip when no commentable)

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class MersoomServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-08T11:30:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void executeComment_skips_LLM_when_commentable_empty() {
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(
                new CollectedFeed(List.of(), List.of(), List.of()));

        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load()).thenReturn(empty());

        MersoomCommentGenerator commentGen = mock(MersoomCommentGenerator.class);

        MersoomService service = service(collector, store, commentGen, mock(MersoomPostGenerator.class));
        service.executeComment();

        verify(commentGen, never()).generate(any(), any());
    }

    @Test
    void executePost_calls_post_generator_and_saves_state() {
        MersoomCollector collector = mock(MersoomCollector.class);
        when(collector.collect(any(), anyInt())).thenReturn(
                new CollectedFeed(List.of(), List.of(), List.of()));

        MersoomStateStore store = mock(MersoomStateStore.class);
        when(store.load()).thenReturn(empty());

        MersoomPostGenerator postGen = mock(MersoomPostGenerator.class);
        when(postGen.generate(any(), any(), any(), anyBoolean()))
                .thenReturn(new MersoomPostGenerator.GeneratedPost("title", "content"));

        MersoomApiClient api = mock(MersoomApiClient.class);
        when(api.createPost(any(), any(), any()))
                .thenReturn(new MersoomDtos.CreateResponse(true, "new-id"));

        MersoomService service = service(collector, store, mock(MersoomCommentGenerator.class), postGen, api);
        service.executePost();

        verify(api).createPost(any(), any(), any());
        verify(store).save(any());
    }

    private MersoomService service(MersoomCollector collector, MersoomStateStore store,
                                   MersoomCommentGenerator cg, MersoomPostGenerator pg) {
        return service(collector, store, cg, pg, mock(MersoomApiClient.class));
    }

    private MersoomService service(MersoomCollector collector, MersoomStateStore store,
                                   MersoomCommentGenerator cg, MersoomPostGenerator pg,
                                   MersoomApiClient api) {
        MersoomProperties p = mock(MersoomProperties.class);
        when(p.enabled()).thenReturn(true);
        when(p.contextNotesDefaultTtl()).thenReturn(8);
        when(p.contextNoteBytesPerFriend()).thenReturn(1024);
        when(p.votedPostIdsLimit()).thenReturn(100);
        when(p.apiRateLimitSleepMs()).thenReturn(0);
        when(p.reentryMarker()).thenReturn("/tmp/never-exists");

        return new MersoomService(
                p, store, collector, api, pg, cg,
                new VoteHeuristic(),
                new ContextNoteManager(clock, 1024),
                new RelationshipPromoter(clock),
                clock);
    }

    private static MersoomState empty() {
        return new MersoomState(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), 8, List.of(), null, null, List.of(), List.of());
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests MersoomServiceTest 2>&1 | tail -10
```
Expected: FAIL

- [ ] **Step 3: 구현**

```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.mersoom.MersoomCollector.CollectedFeed;
import com.maitmus.sekairouter.mersoom.MersoomCollector.Commentable;
import com.maitmus.sekairouter.mersoom.MersoomDtos.Post;
import com.maitmus.sekairouter.mersoom.MersoomDtos.VoteType;
import com.maitmus.sekairouter.mersoom.MersoomState.CommentRef;
import com.maitmus.sekairouter.mersoom.MersoomState.ContextNote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** mersoom 머슴 메인 서비스 — cron 트리거 + 흐름 제어. */
@Slf4j
@Service
public class MersoomService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int FETCH_LIMIT = 8;
    private static final String NICKNAME = "에무";

    private final MersoomProperties properties;
    private final MersoomStateStore store;
    private final MersoomCollector collector;
    private final MersoomApiClient api;
    private final MersoomPostGenerator postGenerator;
    private final MersoomCommentGenerator commentGenerator;
    private final VoteHeuristic voteHeuristic;
    private final ContextNoteManager contextNoteManager;
    private final RelationshipPromoter relationshipPromoter;
    private final Clock clock;
    private final AtomicBoolean reentryPending = new AtomicBoolean(false);
    private final Object lock = new Object();

    public MersoomService(MersoomProperties properties, MersoomStateStore store, MersoomCollector collector,
                          MersoomApiClient api, MersoomPostGenerator postGenerator,
                          MersoomCommentGenerator commentGenerator, VoteHeuristic voteHeuristic,
                          ContextNoteManager contextNoteManager, RelationshipPromoter relationshipPromoter,
                          Clock clock) {
        this.properties = properties;
        this.store = store;
        this.collector = collector;
        this.api = api;
        this.postGenerator = postGenerator;
        this.commentGenerator = commentGenerator;
        this.voteHeuristic = voteHeuristic;
        this.contextNoteManager = contextNoteManager;
        this.relationshipPromoter = relationshipPromoter;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleReentryIfNeeded() {
        if (!properties.enabled()) return;
        try {
            MersoomState state = store.load();
            if (state.lastPostIds().isEmpty()) return;
            Path marker = Paths.get(properties.reentryMarker());
            if (Files.exists(marker)) return;
            log.info("Mersoom re-entry pending — 다음 post-cron에서 첫 글 작성 예정");
            reentryPending.set(true);
        } catch (Exception e) {
            log.warn("Re-entry check failed", e);
        }
    }

    @Scheduled(cron = "${mersoom.post-cron}", zone = "Asia/Seoul")
    public void executePost() {
        if (!properties.enabled()) return;
        if (!isActiveHour()) {
            log.warn("Mersoom post triggered outside active hours, skip");
            return;
        }
        synchronized (lock) {
            doExecutePost();
        }
    }

    @Scheduled(cron = "${mersoom.comment-cron}", zone = "Asia/Seoul")
    public void executeComment() {
        if (!properties.enabled()) return;
        if (!isActiveHour()) {
            log.warn("Mersoom comment triggered outside active hours, skip");
            return;
        }
        synchronized (lock) {
            doExecuteComment();
        }
    }

    private void doExecutePost() {
        boolean isReentry = reentryPending.compareAndSet(true, false);

        MersoomState state = store.load();
        CollectedFeed feed = collector.collect(state, FETCH_LIMIT);
        List<String> updatedVoted = castVotes(state, feed.votable());
        state = withVotedPostIds(state, updatedVoted);
        Map<String, ContextNote> ticked = contextNoteManager.tickAndPrune(state.contextNotes());

        try {
            var generated = postGenerator.generate(state, feed,
                    LocalDate.now(clock.withZone(KST)), isReentry);
            var resp = api.createPost(NICKNAME, generated.title(), generated.content());
            if (resp != null && resp.success()) {
                state = recordPost(state, resp.id(), ticked);
                log.info("Mersoom post created: {} (reentry={})", resp.id(), isReentry);
            }
        } catch (Exception e) {
            log.error("Mersoom post execution failed", e);
            state = withContextNotes(state, ticked);
        }

        state = relationshipPromoter.evaluate(state);
        store.save(state);

        if (isReentry) {
            try {
                Path marker = Paths.get(properties.reentryMarker());
                Files.createDirectories(marker.getParent());
                Files.createFile(marker);
                log.info("Mersoom re-entry marker created: {}", marker);
            } catch (IOException e) {
                log.warn("Failed to create reentry marker", e);
            }
        }
    }

    private void doExecuteComment() {
        MersoomState state = store.load();
        CollectedFeed feed = collector.collect(state, FETCH_LIMIT);
        List<String> updatedVoted = castVotes(state, feed.votable());
        state = withVotedPostIds(state, updatedVoted);

        Map<String, ContextNote> ticked = contextNoteManager.tickAndPrune(state.contextNotes());

        if (feed.commentable().isEmpty()) {
            log.info("Mersoom comment skip — commentable empty");
            state = withContextNotes(state, ticked);
            state = relationshipPromoter.evaluate(state);
            store.save(state);
            return;
        }

        Commentable target = feed.commentable().get(0);
        try {
            String content = commentGenerator.generate(state, target);
            var resp = api.createComment(target.post().id(), null, NICKNAME, content);
            if (resp != null && resp.success()) {
                state = recordComment(state, target, content, ticked);
                log.info("Mersoom comment created: post={}", target.post().id());
            }
        } catch (Exception e) {
            log.error("Mersoom comment execution failed", e);
            state = withContextNotes(state, ticked);
        }

        state = relationshipPromoter.evaluate(state);
        store.save(state);
    }

    /** votable 글에 휴리스틱 vote 적용. 새 votedPostIds 반환 (FIFO 한도 적용). */
    private List<String> castVotes(MersoomState state, List<Post> votable) {
        var voted = new java.util.LinkedHashSet<>(state.votedPostIds());
        for (Post p : votable) {
            if (voted.contains(p.id())) continue;
            try {
                VoteType vote = voteHeuristic.decide(p, state);
                api.vote(p.id(), vote);
                voted.add(p.id());
                if (properties.apiRateLimitSleepMs() > 0) {
                    Thread.sleep(properties.apiRateLimitSleepMs());
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Mersoom vote failed for post {}: {}", p.id(), e.getMessage());
            }
        }
        // FIFO 한도 적용
        while (voted.size() > properties.votedPostIdsLimit()) {
            String first = voted.iterator().next();
            voted.remove(first);
        }
        return new ArrayList<>(voted);
    }

    private MersoomState withVotedPostIds(MersoomState state, List<String> voted) {
        return new MersoomState(
                state.lastPostIds(), state.lastCommentIds(), state.friends(), state.avoid(),
                state.fixedFriends(), state.fixedAvoid(),
                state.contextNotes(), state.contextNotesMaxTtl(),
                state.reservedNicknames(), state.summary(), state.summaryPrev(),
                state.pendingReports(), voted);
    }

    private MersoomState recordPost(MersoomState state, String newPostId, Map<String, ContextNote> tickedNotes) {
        var newPostIds = new ArrayList<>(state.lastPostIds());
        newPostIds.add(0, newPostId);
        if (newPostIds.size() > 10) newPostIds.subList(10, newPostIds.size()).clear();
        return new MersoomState(
                newPostIds, state.lastCommentIds(), state.friends(), state.avoid(),
                state.fixedFriends(), state.fixedAvoid(),
                tickedNotes, state.contextNotesMaxTtl(),
                state.reservedNicknames(), state.summary(), state.summaryPrev(),
                state.pendingReports(), state.votedPostIds());
    }

    private MersoomState recordComment(MersoomState state, Commentable target, String content,
                                       Map<String, ContextNote> tickedNotes) {
        var newCommentIds = new ArrayList<>(state.lastCommentIds());
        newCommentIds.add(new CommentRef(target.post().id(), OffsetDateTime.now(clock.withZone(KST))));
        if (newCommentIds.size() > 50) newCommentIds.subList(50, newCommentIds.size()).clear();

        // context_notes upsert
        Map<String, ContextNote> updated = new LinkedHashMap<>(tickedNotes);
        String nick = target.post().nickname();
        if (nick != null && !nick.isBlank()) {
            ContextNote prev = updated.get(nick);
            String event = "[%s] %s 글에 에무 댓글: %s".formatted(
                    LocalDate.now(clock.withZone(KST)),
                    safeNick(nick),
                    content.length() > 80 ? content.substring(0, 80) : content);
            updated.put(nick, contextNoteManager.upsertAfterInteraction(
                    prev, event, prev != null ? prev.call() : null,
                    properties.contextNotesDefaultTtl()));
        }

        return new MersoomState(
                state.lastPostIds(), newCommentIds, state.friends(), state.avoid(),
                state.fixedFriends(), state.fixedAvoid(),
                updated, state.contextNotesMaxTtl(),
                state.reservedNicknames(), state.summary(), state.summaryPrev(),
                state.pendingReports(), state.votedPostIds());
    }

    private MersoomState withContextNotes(MersoomState state, Map<String, ContextNote> tickedNotes) {
        return new MersoomState(
                state.lastPostIds(), state.lastCommentIds(), state.friends(), state.avoid(),
                state.fixedFriends(), state.fixedAvoid(),
                tickedNotes, state.contextNotesMaxTtl(),
                state.reservedNicknames(), state.summary(), state.summaryPrev(),
                state.pendingReports(), state.votedPostIds());
    }

    private boolean isActiveHour() {
        int h = LocalTime.now(clock.withZone(KST)).getHour();
        return h >= 10 && h <= 20;
    }

    private static String safeNick(String s) {
        return s.length() > 20 ? s.substring(0, 20) : s;
    }
}
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests MersoomServiceTest 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/MersoomService.java \
        src/test/java/com/maitmus/sekairouter/mersoom/MersoomServiceTest.java
git commit -m "feat(mersoom): MersoomService — cron 통합 + executePost/Comment + 재진입 + 투표"
```

---

## Task 17: docker-compose 마운트 + env 추가

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: docker-compose.yml 수정**

```yaml
# 기존 environment 블록에 추가
environment:
  - PERSONA_DIR=/app/identities
  - TZ=Asia/Seoul
  - JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Seoul
  - MERSOOM_AUTH_ID=${MERSOOM_AUTH_ID}
  - MERSOOM_PASSWORD=${MERSOOM_PASSWORD}
  - MERSOOM_ENABLED=${MERSOOM_ENABLED:-false}

# 기존 volumes 블록에 추가
volumes:
  - /home/maitmus/.openclaw/workspace/identities:/app/identities:ro
  - /home/maitmus/.openclaw/workspace/USER.md:/app/USER.md:ro
  - /home/maitmus/sekai-router-logs:/app/logs
  - /home/maitmus/.openclaw/workspace-cron-worker/mersoom-state.json:/app/mersoom-state.json:rw
  - /home/maitmus/sekai-router-mersoom-flags:/app/mersoom-flags
```

전체 docker-compose.yml은 다음과 같음:

```yaml
services:
  sekai-router:
    build: .
    container_name: sekai-router
    restart: unless-stopped
    env_file: .env
    environment:
      - PERSONA_DIR=/app/identities
      - TZ=Asia/Seoul
      - JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Seoul
      - MERSOOM_AUTH_ID=${MERSOOM_AUTH_ID}
      - MERSOOM_PASSWORD=${MERSOOM_PASSWORD}
      - MERSOOM_ENABLED=${MERSOOM_ENABLED:-false}
    volumes:
      - /home/maitmus/.openclaw/workspace/identities:/app/identities:ro
      - /home/maitmus/.openclaw/workspace/USER.md:/app/USER.md:ro
      - /home/maitmus/sekai-router-logs:/app/logs
      - /home/maitmus/.openclaw/workspace-cron-worker/mersoom-state.json:/app/mersoom-state.json:rw
      - /home/maitmus/sekai-router-mersoom-flags:/app/mersoom-flags
```

- [ ] **Step 2: 호스트 디렉터리 사전 생성**

```bash
mkdir -p /home/maitmus/sekai-router-mersoom-flags
```

- [ ] **Step 3: `.env`에 머슴 인증 추가** (기존 .env 파일에 append)

```bash
# .env에 다음 추가 (실제 값은 ~/.openclaw/workspace-cron-worker/mersoom-state.json의 auth 필드 확인)
echo "MERSOOM_AUTH_ID=emu_wonder" >> /home/maitmus/projects/open-pjsk-spring-migration/.env
echo "MERSOOM_PASSWORD=wonderhoi2026!" >> /home/maitmus/projects/open-pjsk-spring-migration/.env
echo "MERSOOM_ENABLED=false" >> /home/maitmus/projects/open-pjsk-spring-migration/.env
```

⚠ `.env`는 git에 커밋하지 않음 (`.gitignore`에 이미 등록되어 있어야 함). 확인:

```bash
cd /home/maitmus/projects/open-pjsk-spring-migration
grep -F ".env" .gitignore || echo ".env" >> .gitignore
```

- [ ] **Step 4: 빌드 + 컴파일 확인**

```bash
./gradlew test 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋** (docker-compose 변경만 커밋, .env는 미커밋)

```bash
git add docker-compose.yml .gitignore
git commit -m "feat(mersoom): docker-compose mount + env 추가 (mersoom-state, flags)"
```

---

## Task 18: 검증 — 컨테이너 재시작 + dry-run + 첫 발화

호스트 측 1회 fix 작업이 선행돼야 함 (Pre-flight 참조). 이미 했다면 skip.

- [ ] **Step 1: state.json parse 검증**

```bash
jq 'keys' ~/.openclaw/workspace-cron-worker/mersoom-state.json
```
Expected: `["auth", "avoid", ...]` 정상 출력

- [ ] **Step 2: 빌드 + 재시작** (`MERSOOM_ENABLED=false` 상태로 부트 검증)

```bash
cd /home/maitmus/projects/open-pjsk-spring-migration
docker compose down
docker compose up -d --build 2>&1 | tail -5
```
Expected: Container started

- [ ] **Step 3: state 로드 확인**

```bash
sleep 16
docker compose logs sekai-router --tail 30 2>&1 | grep -iE "mersoom|state"
```
Expected: `MERSOOM_ENABLED=false` 이므로 mersoom 관련 로그 없음. 에러 없음.

- [ ] **Step 4: dry-run — `MERSOOM_ENABLED=true` + 임시 cron**

`docker-compose.yml`에 임시로 `MERSOOM_POST_CRON=0 <2분 후 분> <현재 시> * * *`로 변경 후:

```bash
# 현재 시간 확인
date "+%H %M"
# 예: 14 30 → 다음 실행 14:32 라면 cron = "0 32 14 * * *"

# .env 임시 수정
sed -i 's/MERSOOM_ENABLED=false/MERSOOM_ENABLED=true/' .env
echo "MERSOOM_POST_CRON=0 <분> <시> * * *" >> .env

docker compose down
docker compose up -d
```

- [ ] **Step 5: 발화 결과 확인**

```bash
# 2~3분 후
docker compose logs sekai-router --since 5m 2>&1 | grep -iE "mersoom|reentry|cache_"
```
Expected:
- `Mersoom re-entry pending` (첫 마이그라면)
- `Anthropic usage: cache_creation=..., cache_read=...`
- `Mersoom post created: <id>` (또는 실패 사유)

- [ ] **Step 6: 실제 mersoom 사이트에서 글 게시 확인**

웹브라우저 또는 curl:

```bash
curl -s 'https://mersoom.com/api/posts?limit=3' | jq '.posts[] | {nickname, title, created_at}'
```
Expected: 새 에무 글이 최상단에 보임

- [ ] **Step 7: 임시 cron 제거 + 정식 운영 모드**

```bash
# .env에서 임시 MERSOOM_POST_CRON 제거 (정식 cron 적용)
sed -i '/MERSOOM_POST_CRON=/d' .env

docker compose down
docker compose up -d
```

- [ ] **Step 8: 1주 모니터링 체크리스트** (수동)

다음 항목을 1주 모니터링:
- [ ] 매일 09:00 skills.md sync 정상 실행 + diff 변동 없음
- [ ] 매일 11:30, 18:30 글 작성 (글 max 50자 title 잘 지켜지는지)
- [ ] 매 2시간 댓글 작성 시도 (commentable 없으면 LLM 호출 0 확인)
- [ ] context_notes truncate 정상 (1KB/친구)
- [ ] 자동 격상 정상 (resetCount 누적 → fixed_friends 추가)
- [ ] 자정 작용 위험 모니터링 (downvotes >= 3 글 발생 시 검토)
- [ ] state.json git 백업 (주 1회 수동)

- [ ] **Step 9: 검증 완료 커밋**

```bash
# 임시 cron 흔적 정리되었는지 .env 확인
grep MERSOOM .env
# 이후, .env는 커밋 안 함

# 최종 정상 운영 시 별도 커밋 없음 — 위 docker-compose 커밋이 마지막
```

---

## 자기 검증

이 plan이 spec의 모든 요구사항을 cover하는지 확인:

- ✅ Spring Boot 통합 (Task 16: MersoomService, @Scheduled cron)
- ✅ Java 완전 재구현 (모든 task: 신규 Java)
- ✅ State JSON 마운트 + auth env 분리 (Task 6, 17)
- ✅ context_notes 자동 truncate 1KB FIFO (Task 10)
- ✅ Sonnet 4.6 캐시 공유 (Task 12: PromptBlocks)
- ✅ 자동 격상 RULES P0.8 (Task 11)
- ✅ 재진입 첫 글 + 마커 1회 (Task 16: scheduleReentryIfNeeded)
- ✅ 글 2/일 + 댓글 6/일 활성 시간 (Task 5: cron)
- ✅ Hybrid 챌린지 PoW + AI Puzzle (Task 2, 3, 4)
- ✅ 투표 의무 휴리스틱 (Task 8, 16: castVotes)
- ✅ Daily skills.md sync (Task 15)
- ✅ 음슴체 무시 정책 (Task 12: instructions)
- ✅ 부적절 단어 에러 처리 (Task 16: try/catch)
- ✅ 길이 제한 (Task 13, 14: title/content/comment)
- ✅ voted_post_ids state (Task 1, 16: castVotes)
- ✅ Atomic write (Task 6)
- ✅ 산술적 랜덤 (해당 없음 — 머슴은 결정적 흐름, RandomCharacterSelector 안 씀)
- ✅ Reserved nicknames (Task 8, 11)
- ✅ Fixed_* 보호 (Task 11)
- ✅ Out of scope: arena/포인트/광고 (해당 task 없음 — 의도)
- ✅ summary/summary_prev 미사용 (state record엔 보존, 자동 갱신 코드 없음 — 의도)
- ✅ 1회 수동 fix `mersoom-state.json` line 17 (Pre-flight)

모든 spec 요구사항이 task로 매핑됨. 누락 없음.

---

## 실행 옵션

Plan complete and saved to `docs/superpowers/plans/2026-05-08-mersoom-migration.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
