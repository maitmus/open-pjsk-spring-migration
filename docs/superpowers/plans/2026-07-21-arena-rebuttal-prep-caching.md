# 아레나 반박-준비(prep) + 캐시 공유 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** fight 전 "반박 준비(prep)" LLM 콜을 추가해 아레나 캐시 프리픽스(`commonBase+네네페르소나`)를 데우고, 이어지는 fight가 그 캐시를 `cache_read`하며 준비된 반박 포인트로 더 강한 논변을 뽑게 한다.

**Architecture:** prep·fight가 **바이트-동일한 `[commonBase][네네페르소나]` 캐시 블록**(신규 `ArenaPersonaBlocks`)을 공유하고 task별 SUFFIX만 uncached로 붙인다. `ArenaService.doFight`가 상대 글이 있으면 결정론 게이트와 무관하게 prep을 호출(캐시 워밍 + 반박노트), 게이트는 그대로 유지, 통과 시 fight가 노트를 받아 게시.

**Tech Stack:** Java 21, Spring Boot, Lombok, JUnit 5 + Mockito + AssertJ, Gradle (`./gradlew test`). LLM: `AnthropicClientWrapper.completeJson(PromptBlocks, String)` (raw String 반환). 캐시: `PromptBlocks.Block(text, cache)` — `cache=true` 블록 끝에 `cache_control(TTL_1H)` 부착.

## Global Constraints

- 캐시 프리픽스(`commonBase+네네페르소나`)는 ≥2048토큰이어야 캐시 부착 — 실측 확정(네네 persona 3946자≈2600~3000토큰 + commonBase).
- prep·fight의 캐시 블록은 **반드시 바이트-동일**(둘 다 `ArenaPersonaBlocks.cachedPrefix()` 사용). 태스크별 강조 문구는 캐시 밖 SUFFIX로.
- 결정론 게이트 `ArenaService.noOpposingSinceMyLastPost`는 **변경 금지**(그대로 유지).
- 발의(propose)는 범위 밖 — `ArenaProposeGenerator`·propose 경로 **건드리지 않음**.
- 네네 톤·출력 규칙(반말·음슴체 금지·인신공격 금지)은 기존 SUFFIX 문구 유지.
- 커밋/푸시는 자율(테스트 그린 시). 배포는 별도 명시 트리거 — 이 계획에 배포 단계 없음.

---

### Task 1: `ArenaPersonaBlocks` — 공유 캐시 페르소나 프리픽스

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/arena/ArenaPersonaBlocks.java`
- Test: `src/test/java/com/maitmus/sekairouter/arena/ArenaPersonaBlocksTest.java`

**Interfaces:**
- Consumes: `SharedPromptContent.commonBase()` (String), `PersonaRegistry.get(CharacterId.NENE)` → `Persona` (`.content()` may be null), `PromptBlocks.Block(String text, boolean cache)`.
- Produces: `List<PromptBlocks.Block> cachedPrefix()` — 정확히 2블록: `[Block(commonBase, false), Block(nenePersonaBlock, true)]`. `nenePersonaBlock` = `"\n## 너는 쿠사나기 네네 — 아래 정의를 그대로 체화한다\n" + personaContent + "\n"`. persona null-safe(빈 문자열). prep·fight가 이 메서드를 호출해 프리픽스를 공유.

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/maitmus/sekairouter/arena/ArenaPersonaBlocksTest.java`:
```java
package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.persona.PersonaType;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArenaPersonaBlocksTest {

    // ⚠️ Persona는 record라 mock() 불가 — 실제 인스턴스로 만든다(기존 ArenaPromptBlocksTest 패턴).
    private ArenaPersonaBlocks blocks(String commonBase, String personaContent) {
        SharedPromptContent s = mock(SharedPromptContent.class);
        when(s.commonBase()).thenReturn(commonBase);
        PersonaRegistry r = mock(PersonaRegistry.class);
        when(r.get(CharacterId.NENE)).thenReturn(
                new Persona(CharacterId.NENE, "쿠사나기 네네", PersonaType.HUMAN_SEKAI, personaContent));
        return new ArenaPersonaBlocks(s, r);
    }

    @Test
    void cached_prefix_has_commonbase_uncached_and_persona_cached() {
        List<PromptBlocks.Block> b = blocks("COMMON", "네네정의").cachedPrefix();
        assertThat(b).hasSize(2);
        assertThat(b.get(0).text()).isEqualTo("COMMON");
        assertThat(b.get(0).cache()).isFalse();              // commonBase는 캐시 브레이크포인트 아님
        assertThat(b.get(1).cache()).isTrue();               // 페르소나 블록 끝에 cache_control → commonBase+persona 캐시
        assertThat(b.get(1).text()).contains("쿠사나기 네네").contains("네네정의");
    }

    @Test
    void null_persona_is_safe() {
        List<PromptBlocks.Block> b = blocks("COMMON", null).cachedPrefix();
        assertThat(b.get(1).text()).contains("쿠사나기 네네");   // 헤더는 있고 content만 빈 문자열
    }

    @Test
    void prefix_is_stable_across_calls() {
        ArenaPersonaBlocks a = blocks("COMMON", "네네정의");
        assertThat(a.cachedPrefix().get(1).text()).isEqualTo(a.cachedPrefix().get(1).text());  // 바이트-동일(캐시 공유 전제)
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*ArenaPersonaBlocksTest"`
Expected: 컴파일 실패(`ArenaPersonaBlocks` 없음) 또는 `cannot find symbol`.

- [ ] **Step 3: 구현**

`src/main/java/com/maitmus/sekairouter/arena/ArenaPersonaBlocks.java`:
```java
package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 아레나 prep·fight가 공유하는 캐시 프리픽스.
 * [Block(commonBase, false), Block(네네페르소나, true)] — 페르소나 블록 끝의 cache_control(TTL_1H)이
 * commonBase+페르소나(≈3300토큰 > Haiku 2048 최소치)를 캐시한다. prep·fight가 이 프리픽스를 바이트-동일하게
 * 공유하고 뒤에 각자 task SUFFIX(uncached)를 붙여, prep 콜이 데운 캐시를 fight 콜이 cache_read 한다.
 */
@Component
@RequiredArgsConstructor
public class ArenaPersonaBlocks {

    private final SharedPromptContent shared;
    private final PersonaRegistry personaRegistry;

    /** prep·fight 공통 캐시 프리픽스 2블록. 두 번째(페르소나) 블록만 cache=true. */
    public List<PromptBlocks.Block> cachedPrefix() {
        Persona nene = personaRegistry.get(CharacterId.NENE);
        String content = (nene != null && nene.content() != null) ? nene.content() : "";
        String personaBlock = "\n## 너는 쿠사나기 네네 — 아래 정의를 그대로 체화한다\n" + content + "\n";
        return List.of(
                new PromptBlocks.Block(shared.commonBase(), false),
                new PromptBlocks.Block(personaBlock, true));
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "*ArenaPersonaBlocksTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/arena/ArenaPersonaBlocks.java \
        src/test/java/com/maitmus/sekairouter/arena/ArenaPersonaBlocksTest.java
git commit -m "feat(arena): prep·fight 공유 캐시 프리픽스 ArenaPersonaBlocks"
```

---

### Task 2: `ArenaFightGenerator` — 공유 프리픽스 사용 + 반박노트 인자

**Files:**
- Modify: `src/main/java/com/maitmus/sekairouter/arena/ArenaFightGenerator.java` (생성자·블록 조립·`generate` 시그니처·`buildUserPrompt`)
- Test: `src/test/java/com/maitmus/sekairouter/arena/ArenaFightGeneratorTest.java` (생성자·`generate` 호출 갱신 + 노트 주입 테스트)
- Test: `src/test/java/com/maitmus/sekairouter/arena/ArenaPromptBlocksTest.java` (기존 `fight_generator_...` 메서드를 새 블록 구조로 갱신 — Step 4.5)

**Interfaces:**
- Consumes: `ArenaPersonaBlocks.cachedPrefix()` (Task 1).
- Produces: `FightDecision generate(Topic topic, List<FightPost> existing, String lockedSide, String selfNickname, String rebuttalNotes)` — 마지막 인자 `rebuttalNotes` 추가(null/blank면 무시). 반환 타입·나머지 로직 동일. 생성자: `ArenaFightGenerator(AnthropicClientWrapper, ArenaPersonaBlocks, OutputSanityGate)` (기존 `SharedPromptContent`·`PersonaRegistry` 제거 — 이제 ArenaPersonaBlocks가 담당).

- [ ] **Step 1: 실패 테스트로 갱신**

`ArenaFightGeneratorTest.java` 를 아래로 교체(생성자 5→3필드, `generate` 4→5인자, 노트 테스트 추가). 상단 import에 `import java.util.ArrayList;` 불필요(사용 안 함). `gen(...)` 헬퍼와 기존 테스트를 다음처럼 수정:
```java
    private ArenaFightGenerator gen(String llm) {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        when(a.completeJson(any(PromptBlocks.class), anyString())).thenReturn(llm);
        return new ArenaFightGenerator(a, personaBlocks("shared", "네네정의"), new OutputSanityGate());
    }

    private static ArenaPersonaBlocks personaBlocks(String common, String persona) {
        SharedPromptContent s = mock(SharedPromptContent.class);
        when(s.commonBase()).thenReturn(common);
        com.maitmus.sekairouter.persona.PersonaRegistry r =
                mock(com.maitmus.sekairouter.persona.PersonaRegistry.class);
        // ⚠️ Persona는 record라 mock() 불가 — 실제 인스턴스로.
        when(r.get(com.maitmus.sekairouter.persona.CharacterId.NENE)).thenReturn(
                new com.maitmus.sekairouter.persona.Persona(
                        com.maitmus.sekairouter.persona.CharacterId.NENE, "쿠사나기 네네",
                        com.maitmus.sekairouter.persona.PersonaType.HUMAN_SEKAI, persona));
        return new ArenaPersonaBlocks(s, r);
    }
```
그리고 **모든 `.generate(TOPIC, ..., "쿠사나기 네네")` 호출 끝에 `, ""` (빈 노트)를 추가**한다. 두 캡처 테스트(`already_addressed...`, `multiple_new_opposing...`)의 인라인 생성자도 `new ArenaFightGenerator(a, personaBlocks("shared","네네정의"), new OutputSanityGate())` 로 바꾸고 `g.generate(TOPIC, posts, "CON", "쿠사나기 네네", "")` 로 인자 추가. (이 두 테스트는 각자 `SharedPromptContent`·`PersonaRegistry`를 직접 목하던 코드를 `personaBlocks(...)` 헬퍼 호출로 대체.)

추가 신규 테스트(노트 주입):
```java
    @Test
    void rebuttal_notes_injected_into_user_prompt() {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> up = ArgumentCaptor.forClass(String.class);
        when(a.completeJson(any(PromptBlocks.class), up.capture()))
                .thenReturn("{\"side\":\"CON\",\"content\":\"\",\"shouldFight\":false}");
        ArenaFightGenerator g = new ArenaFightGenerator(a, personaBlocks("shared", "네네정의"), new OutputSanityGate());
        g.generate(TOPIC, List.of(), null, "쿠사나기 네네", "- 상대는 신뢰를 들고나올 것 → 신뢰는 배려에서 나온다고 되받기");
        assertThat(up.getValue()).contains("네가 준비한 반박 포인트").contains("배려에서 나온다");
    }

    @Test
    void blank_rebuttal_notes_omitted() {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> up = ArgumentCaptor.forClass(String.class);
        when(a.completeJson(any(PromptBlocks.class), up.capture()))
                .thenReturn("{\"side\":\"CON\",\"content\":\"\",\"shouldFight\":false}");
        ArenaFightGenerator g = new ArenaFightGenerator(a, personaBlocks("shared", "네네정의"), new OutputSanityGate());
        g.generate(TOPIC, List.of(), null, "쿠사나기 네네", "");
        assertThat(up.getValue()).doesNotContain("네가 준비한 반박 포인트");
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*ArenaFightGeneratorTest"`
Expected: 컴파일 실패(생성자/시그니처 불일치).

- [ ] **Step 3: 구현 — 생성자·블록·시그니처**

`ArenaFightGenerator.java` 변경:
1. import 정리: `SharedPromptContent`·`PersonaRegistry`·`Persona`·`CharacterId` import 제거, `java.util.ArrayList` 추가.
2. 필드 교체:
```java
    private final AnthropicClientWrapper anthropic;
    private final ArenaPersonaBlocks personaBlocks;
    private final OutputSanityGate backstop;
```
3. `SUFFIX` 문구 맨 앞 헤더에 말투 강조를 유지(기존 `## 아레나 토론 모드` 그대로 두되, 페르소나 정의는 캐시 블록에 있으므로 SUFFIX는 지침만). 기존 SUFFIX 텍스트 유지.
4. `generate` 시그니처·블록 조립 교체:
```java
    public FightDecision generate(Topic topic, List<FightPost> existing, String lockedSide,
                                  String selfNickname, String rebuttalNotes) {
        String lockedNorm = normalizeSide(lockedSide);
        java.util.List<PromptBlocks.Block> blocks = new ArrayList<>(personaBlocks.cachedPrefix());
        blocks.add(new PromptBlocks.Block(SUFFIX, false));
        String raw = anthropic.completeJson(new PromptBlocks(blocks),
                buildUserPrompt(topic, existing, lockedNorm, selfNickname, rebuttalNotes));
        // ...(이하 파싱·shouldFight·side·backstop 로직 기존 그대로)...
```
   (`nenePersona` 지역변수·`shared.commonBase()` 사용 라인 삭제.)

- [ ] **Step 4: 구현 — buildUserPrompt에 노트 주입**

`buildUserPrompt` 시그니처에 `String rebuttalNotes` 추가하고, `## 지시` 블록 **직전**에 삽입:
```java
    private String buildUserPrompt(Topic topic, List<FightPost> existing, String lockedSide,
                                   String selfNickname, String rebuttalNotes) {
        // ...(기존 본문 그대로)...
        if (rebuttalNotes != null && !rebuttalNotes.isBlank()) {
            sb.append("## 네가 준비한 반박 포인트 (참고 — 그대로 베끼지 말고 논지로만 활용)\n")
              .append(rebuttalNotes.strip()).append("\n\n");
        }
        sb.append("## 지시\n");
        // ...(이하 기존 그대로)...
    }
```

- [ ] **Step 4.5: 기존 `ArenaPromptBlocksTest.fight_generator_...` 갱신 (재구성으로 깨짐)**

`ArenaPromptBlocksTest.java` 의 `fight_generator_sends_uncached_nene_block_over_commonBase` 를 새 구조로 교체(propose 테스트는 그대로 둔다):
```java
    @Test
    void fight_generator_shares_cached_persona_prefix() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<PromptBlocks> cap = ArgumentCaptor.forClass(PromptBlocks.class);
        when(anthropic.completeJson(cap.capture(), any())).thenReturn("{\"shouldFight\":false}");
        SharedPromptContent shared = mock(SharedPromptContent.class);
        when(shared.commonBase()).thenReturn("COMMONBASE");
        PersonaRegistry reg = mock(PersonaRegistry.class);
        when(reg.get(CharacterId.NENE)).thenReturn(
                new Persona(CharacterId.NENE, "쿠사나기 네네", PersonaType.HUMAN_SEKAI, "네네내용"));

        new ArenaFightGenerator(anthropic, new ArenaPersonaBlocks(shared, reg), new OutputSanityGate())
                .generate(TOPIC, java.util.List.of(), null, "쿠사나기 네네", "");

        PromptBlocks p = cap.getValue();
        assertThat(p.blocks().get(0).text()).isEqualTo("COMMONBASE");
        assertThat(p.blocks().get(0).cache()).isFalse();                       // commonBase는 브레이크포인트 아님
        assertThat(p.blocks().get(1).cache()).isTrue();                        // 페르소나 블록 = 캐시 프리픽스 끝
        assertThat(p.blocks().get(1).text()).contains("네네내용");
        assertThat(p.blocks().get(p.blocks().size() - 1).cache()).isFalse();   // SUFFIX uncached
    }
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests "*ArenaFightGeneratorTest" --tests "*ArenaPromptBlocksTest"`
Expected: PASS (fight 기존 + 신규 2, ArenaPromptBlocks fight/propose 각각). 컴파일 에러 없어야(전 파일 빌드).

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/arena/ArenaFightGenerator.java \
        src/test/java/com/maitmus/sekairouter/arena/ArenaFightGeneratorTest.java \
        src/test/java/com/maitmus/sekairouter/arena/ArenaPromptBlocksTest.java
git commit -m "feat(arena): fight 공유 캐시 프리픽스 사용 + 반박노트 인자"
```

---

### Task 3: `ArenaPrepGenerator` — 반박 준비 생성기 (신규)

**Files:**
- Create: `src/main/java/com/maitmus/sekairouter/arena/ArenaPrepGenerator.java`
- Test: `src/test/java/com/maitmus/sekairouter/arena/ArenaPrepGeneratorTest.java`

**Interfaces:**
- Consumes: `ArenaPersonaBlocks.cachedPrefix()` (Task 1), `AnthropicClientWrapper.completeJson`, `ArenaDtos.Topic`/`FightPost`.
- Produces: `String generate(Topic topic, List<FightPost> existing, String lockedSide, String selfNickname)` — 반박노트(불릿 텍스트). LLM 원문 strip 반환, blank면 `""`. fight와 **동일 `cachedPrefix()`** 사용(캐시 공유) + `PREP_SUFFIX`(uncached).

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/maitmus/sekairouter/arena/ArenaPrepGeneratorTest.java`:
```java
package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.arena.ArenaDtos.FightPost;
import com.maitmus.sekairouter.arena.ArenaDtos.Topic;
import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.persona.PersonaType;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArenaPrepGeneratorTest {

    private static final Topic TOPIC = new Topic("t1", "우정은 솔직함일까 배려일까", "솔직함이 신뢰", "배려 없으면 폭력");

    // ⚠️ Persona는 record라 mock() 불가 — 실제 인스턴스로.
    private static ArenaPersonaBlocks personaBlocks() {
        SharedPromptContent s = mock(SharedPromptContent.class);
        when(s.commonBase()).thenReturn("shared");
        PersonaRegistry r = mock(PersonaRegistry.class);
        when(r.get(CharacterId.NENE)).thenReturn(
                new Persona(CharacterId.NENE, "쿠사나기 네네", PersonaType.HUMAN_SEKAI, "네네정의"));
        return new ArenaPersonaBlocks(s, r);
    }

    @Test
    void returns_rebuttal_notes_text() {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        when(a.completeJson(any(PromptBlocks.class), anyString()))
                .thenReturn("- 상대: 솔직함이 신뢰 → 신뢰는 배려에서 온다\n- 상대: 배려는 회피 → 회피 아니라 존중");
        String notes = new ArenaPrepGenerator(a, personaBlocks())
                .generate(TOPIC, List.of(), "CON", "쿠사나기 네네");
        assertThat(notes).contains("신뢰는 배려에서 온다").contains("존중");
    }

    @Test
    void blank_output_returns_empty() {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        when(a.completeJson(any(PromptBlocks.class), anyString())).thenReturn("   ");
        assertThat(new ArenaPrepGenerator(a, personaBlocks())
                .generate(TOPIC, List.of(), "CON", "쿠사나기 네네")).isEmpty();
    }

    @Test
    void uses_shared_cached_prefix_and_prep_suffix() {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<PromptBlocks> pb = ArgumentCaptor.forClass(PromptBlocks.class);
        when(a.completeJson(pb.capture(), anyString())).thenReturn("- 노트");
        ArenaPersonaBlocks blocks = personaBlocks();
        new ArenaPrepGenerator(a, blocks).generate(TOPIC, List.of(), "CON", "쿠사나기 네네");
        List<PromptBlocks.Block> used = pb.getValue().blocks();
        // 앞 2블록 = 공유 캐시 프리픽스(바이트-동일), 마지막 = prep suffix(uncached)
        assertThat(used.get(1).text()).isEqualTo(blocks.cachedPrefix().get(1).text());
        assertThat(used.get(1).cache()).isTrue();
        assertThat(used.get(used.size() - 1).cache()).isFalse();
        assertThat(used.get(used.size() - 1).text()).contains("토론 준비");
    }

    @Test
    void prep_user_prompt_includes_opponent_posts() {
        AnthropicClientWrapper a = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<String> up = ArgumentCaptor.forClass(String.class);
        when(a.completeJson(any(PromptBlocks.class), up.capture())).thenReturn("- 노트");
        List<FightPost> posts = List.of(
                new FightPost("o1", "히후미", "PRO", "솔직함이 최고의 신뢰다", 0, 0, false, null));
        new ArenaPrepGenerator(a, personaBlocks()).generate(TOPIC, posts, "CON", "쿠사나기 네네");
        assertThat(up.getValue()).contains("솔직함이 최고의 신뢰다");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*ArenaPrepGeneratorTest"`
Expected: 컴파일 실패(`ArenaPrepGenerator` 없음).

- [ ] **Step 3: 구현**

`src/main/java/com/maitmus/sekairouter/arena/ArenaPrepGenerator.java`:
```java
package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.arena.ArenaDtos.FightPost;
import com.maitmus.sekairouter.arena.ArenaDtos.Topic;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 아레나 토론 준비(prep) — 실제 fight 전, 상대 논지 예상 + 네네 반박 포인트를 미리 생성한다.
 * fight와 **동일한 캐시 프리픽스**(ArenaPersonaBlocks.cachedPrefix)를 써서 이 콜이 프리픽스를 데우고,
 * 이어지는 fight 콜이 cache_read 한다. 산출물(반박노트)은 fight의 컨텍스트로 전달돼 논변을 강화.
 * 출력은 게시물이 아니라 내부 준비 메모(불릿 텍스트).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArenaPrepGenerator {

    private final AnthropicClientWrapper anthropic;
    private final ArenaPersonaBlocks personaBlocks;

    private static final String PREP_SUFFIX = """

            ## 아레나 토론 준비 모드 (쿠사나기 네네)
            곧 이 토픽 토론(BATTLE)에 참여한다. 지금은 준비 단계 — 게시물이 아니라 너의 준비 메모다.
            - 상대(반대편)가 펼칠 만한 주장을 2~4개 예상한다.
            - 각 예상 주장에, 네네답게 받아칠 반박 포인트를 한 줄씩 붙인다(짧고 날카롭게, 논리·팩트).
            - 출력은 불릿 텍스트만. JSON·머리말·메타·자기지칭(AI/어시스턴트) 금지. 실제 토론에 쓸 탄약 목록.
            """;

    /** 반박노트(불릿 텍스트) 반환. 실패/빈 출력이면 빈 문자열(fight는 노트 없이 진행). */
    public String generate(Topic topic, List<FightPost> existing, String lockedSide, String selfNickname) {
        try {
            List<PromptBlocks.Block> blocks = new ArrayList<>(personaBlocks.cachedPrefix());
            blocks.add(new PromptBlocks.Block(PREP_SUFFIX, false));
            String raw = anthropic.completeJson(new PromptBlocks(blocks),
                    buildUserPrompt(topic, existing, lockedSide));
            String notes = raw == null ? "" : raw.strip();
            if (!notes.isBlank()) {
                log.info("Arena prep 반박노트 생성 ({}자)", notes.length());
            }
            return notes;
        } catch (Exception e) {
            log.warn("Arena prep 실패 — 노트 없이 진행: {}", e.getMessage());
            return "";
        }
    }

    private String buildUserPrompt(Topic topic, List<FightPost> existing, String lockedSide) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 모드\narena-prep\n");
        sb.append("## 오늘의 토론 주제\n");
        sb.append("제목: ").append(safe(topic.title())).append("\n");
        sb.append("PRO(찬성): ").append(safe(topic.pros())).append("\n");
        sb.append("CON(반대): ").append(safe(topic.cons())).append("\n\n");
        if (lockedSide != null && !lockedSide.isBlank()) {
            sb.append("## 너의 입장\n").append(lockedSide).append(" — 이 입장에서 상대(반대편) 주장을 예상·반박 준비.\n\n");
        } else {
            sb.append("## 너의 입장\n아직 미정 — 논리적으로 더 맞는 쪽을 정할 것을 전제로 양쪽 상대 논지를 예상·반박 준비.\n\n");
        }
        boolean anyOpp = false;
        StringBuilder opp = new StringBuilder();
        if (existing != null) {
            for (FightPost p : existing) {
                if (p.isBlinded()) continue;
                if (selfNicknameEquals(p, selfNickname)) continue;
                opp.append("- [").append(safe(p.side())).append("] @").append(safe(p.nickname()))
                   .append(": ").append(safe(p.content())).append("\n");
                anyOpp = true;
            }
        }
        if (anyOpp) {
            sb.append("## 이미 올라온 상대·기타 주장 (이걸 토대로 반박 준비)\n").append(opp).append("\n");
        }
        sb.append("## 지시\n상대가 펼칠 주장을 예상하고 각각에 네네다운 반박 포인트를 불릿으로 정리해.\n");
        return sb.toString();
    }

    private static boolean selfNicknameEquals(FightPost p, String selfNickname) {
        return selfNickname != null && selfNickname.equals(p.nickname());
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\n", " ").strip();
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "*ArenaPrepGeneratorTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/arena/ArenaPrepGenerator.java \
        src/test/java/com/maitmus/sekairouter/arena/ArenaPrepGeneratorTest.java
git commit -m "feat(arena): 반박 준비(prep) 생성기 — 공유 캐시 프리픽스 워밍"
```

---

### Task 4: `ArenaService.doFight` 배선 + fight 크론 빈도↑

**Files:**
- Modify: `src/main/java/com/maitmus/sekairouter/arena/ArenaService.java` (필드 주입 + `doFight`→`runFightOnce` 배선)
- Modify: `src/main/resources/application.yml` (fight-cron 기본값)
- Modify: `src/test/java/com/maitmus/sekairouter/arena/ArenaServiceTest.java` (기존 — 생성자·fightGen 스텁 갱신, Step 3.5)
- Test: `src/test/java/com/maitmus/sekairouter/arena/ArenaServiceFightWiringTest.java` (신규)

**Interfaces:**
- Consumes: `ArenaPrepGenerator.generate(Topic, List<FightPost>, String lockedSide, String selfNickname)` (Task 3), `ArenaFightGenerator.generate(..., String rebuttalNotes)` (Task 2).
- Produces: `doFight()` 흐름 — 상대 글 존재 시 prep 호출(게이트 무관) → 결정론 게이트 유지 → 통과 시 fight에 노트 전달.

- [ ] **Step 1: 실패 테스트 작성**

`ArenaService`는 `@Scheduled` private 메서드라 직접 호출이 어렵다. **package-private 헬퍼 `runFightOnce()`를 추출**(doFight 본문을 담고 executeFight/synchronized에서 호출)해 테스트가 부른다. 신규 테스트 `src/test/java/com/maitmus/sekairouter/arena/ArenaServiceFightWiringTest.java`:
```java
package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.arena.ArenaDtos.FightPost;
import com.maitmus.sekairouter.arena.ArenaDtos.StatusResponse;
import com.maitmus.sekairouter.arena.ArenaDtos.Topic;
import com.maitmus.sekairouter.arena.ArenaFightGenerator.FightDecision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArenaServiceFightWiringTest {

    private static final Topic TOPIC = new Topic("t1", "제목", "찬", "반");

    private ArenaService svc(ArenaApiClient api, ArenaProposeGenerator prop,
                             ArenaFightGenerator fight, ArenaPrepGenerator prep,
                             ArenaStateStore store) {
        ArenaProperties props = mock(ArenaProperties.class);
        when(props.enabled()).thenReturn(true);
        when(props.fight()).thenReturn(new ArenaProperties.Account("id", "pw", "쿠사나기 네네"));
        Clock clock = Clock.fixed(Instant.parse("2026-07-21T05:00:00Z"), ZoneId.of("Asia/Seoul"));
        return new ArenaService(props, api, prop, fight, prep, store, clock);
    }

    private StatusResponse battleStatus() {
        return new StatusResponse("2026-07-21", "BATTLE", TOPIC);
    }

    @Test
    void prep_called_when_opponent_posts_present_then_notes_passed_to_fight() {
        ArenaApiClient api = mock(ArenaApiClient.class);
        when(api.status()).thenReturn(battleStatus());
        OffsetDateTime t = OffsetDateTime.parse("2026-07-21T04:00:00Z");
        List<FightPost> posts = List.of(new FightPost("o1", "히후미", "PRO", "찬성논거", 0, 0, false, t));
        when(api.fightPosts(any())).thenReturn(posts);
        ArenaStateStore store = mock(ArenaStateStore.class);
        when(store.lockedSide(any(), eq("t1"))).thenReturn(Optional.empty());   // 첫 턴 → 게이트 통과
        ArenaPrepGenerator prep = mock(ArenaPrepGenerator.class);
        when(prep.generate(any(), any(), any(), anyString())).thenReturn("- 준비된 반박");
        ArenaFightGenerator fight = mock(ArenaFightGenerator.class);
        when(fight.generate(any(), any(), any(), anyString(), eq("- 준비된 반박")))
                .thenReturn(new FightDecision("CON", "논거"));
        when(api.fight(any(), anyString(), anyString()))
                .thenReturn(new com.maitmus.sekairouter.mersoom.MersoomDtos.CreateResponse(true, "p1"));

        svc(api, mock(ArenaProposeGenerator.class), fight, prep, store).runFightOnce();

        verify(prep).generate(any(), any(), any(), anyString());
        verify(fight).generate(any(), any(), any(), anyString(), eq("- 준비된 반박"));
    }

    @Test
    void prep_skipped_when_no_opponent_posts() {
        ArenaApiClient api = mock(ArenaApiClient.class);
        when(api.status()).thenReturn(battleStatus());
        when(api.fightPosts(any())).thenReturn(List.of());   // 상대 글 없음
        ArenaStateStore store = mock(ArenaStateStore.class);
        when(store.lockedSide(any(), eq("t1"))).thenReturn(Optional.empty());
        ArenaPrepGenerator prep = mock(ArenaPrepGenerator.class);
        ArenaFightGenerator fight = mock(ArenaFightGenerator.class);
        when(fight.generate(any(), any(), any(), anyString(), anyString())).thenReturn(null);

        svc(api, mock(ArenaProposeGenerator.class), fight, prep, store).runFightOnce();

        verify(prep, never()).generate(any(), any(), any(), anyString());
        // fight엔 빈 노트가 전달됨
        verify(fight).generate(any(), any(), any(), anyString(), eq(""));
    }

    @Test
    void gate_still_skips_but_prep_already_warmed() {
        // 상대 글은 있으나 '내 마지막 글 이후 신규 상대 없음' → 게이트 skip. prep은 (상대 글 있으니) 이미 호출됨.
        ArenaApiClient api = mock(ArenaApiClient.class);
        when(api.status()).thenReturn(battleStatus());
        OffsetDateTime t1 = OffsetDateTime.parse("2026-07-21T02:00:00Z");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-07-21T03:00:00Z");
        List<FightPost> posts = List.of(
                new FightPost("o1", "히후미", "PRO", "옛찬성", 0, 0, false, t1),
                new FightPost("m1", "쿠사나기 네네", "CON", "내반박", 0, 0, false, t2));  // 내 글이 상대보다 뒤 → 신규 상대 없음
        when(api.fightPosts(any())).thenReturn(posts);
        ArenaStateStore store = mock(ArenaStateStore.class);
        when(store.lockedSide(any(), eq("t1"))).thenReturn(Optional.of("CON"));   // 락 → 게이트 활성
        ArenaPrepGenerator prep = mock(ArenaPrepGenerator.class);
        when(prep.generate(any(), any(), any(), anyString())).thenReturn("- 준비");
        ArenaFightGenerator fight = mock(ArenaFightGenerator.class);

        svc(api, mock(ArenaProposeGenerator.class), fight, prep, store).runFightOnce();

        verify(prep).generate(any(), any(), any(), anyString());          // prep은 돌았고
        verify(fight, never()).generate(any(), any(), any(), anyString(), anyString());  // fight는 게이트에 막힘
    }
}
```
※ 확인된 시그니처(그대로 사용): `ArenaApiClient.status()` (safeStatus가 래핑), `fightPosts(LocalDate)`, `fight(Account, String, String) → MersoomDtos.CreateResponse(boolean success, String id)`, `ArenaProperties.Account(authId, password, nickname)`.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*ArenaServiceFightWiringTest"`
Expected: 컴파일 실패(생성자에 `ArenaPrepGenerator` 없음, `runFightOnce` 없음).

- [ ] **Step 3: 구현 — ArenaService 필드·배선**

`ArenaService.java`:
1. 필드 추가: `private final ArenaPrepGenerator prepGenerator;` (`fightGenerator` 다음 줄). `@RequiredArgsConstructor`라 생성자 인자 자동 추가 — 위 테스트 생성자 인자 순서(`props, api, prop, fight, prep, store, clock`)와 **필드 선언 순서를 일치**시킬 것(현재 순서: properties, api, proposeGenerator, fightGenerator, **prepGenerator**, stateStore, clock).
2. `executeFight()`가 `synchronized(lock){ doFight(); }` 하던 것을 `synchronized(lock){ runFightOnce(); }` 로 바꾸고, `doFight` 본문을 `runFightOnce()`(package-private)로 이름 변경:
```java
    void runFightOnce() {
        StatusResponse status = safeStatus();
        if (status == null || !"BATTLE".equalsIgnoreCase(status.phase()) || status.topic() == null) {
            log.info("Arena fight skip — phase={}", status == null ? null : status.phase());
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(KST));
        String topicId = status.topic().id();
        List<FightPost> existing;
        try {
            existing = api.fightPosts(today);
        } catch (Exception e) {
            existing = List.of();
        }
        String lockedSide = stateStore.lockedSide(today, topicId).orElse(null);
        String selfNick = properties.fight().nickname();

        // prep — 상대(자기 아님) 글이 하나라도 있으면 게이트와 무관하게 실행(캐시 워밍 + 반박노트)
        String rebuttalNotes = "";
        boolean hasOpponentPost = existing.stream()
                .anyMatch(p -> !p.isBlinded() && (selfNick == null || !selfNick.equals(p.nickname())));
        if (hasOpponentPost) {
            String notes = prepGenerator.generate(status.topic(), existing, lockedSide, selfNick);
            rebuttalNotes = notes == null ? "" : notes;   // null-guard: fight 인자는 항상 non-null
        }

        // 결정론 게이트 — 그대로 유지
        if (noOpposingSinceMyLastPost(existing, lockedSide, selfNick)) {
            log.info("Arena fight skip — 내 마지막 글 이후 상대편 신규 의견 없음 (일방 도배 방지)");
            return;
        }
        var decision = fightGenerator.generate(status.topic(), existing, lockedSide, selfNick, rebuttalNotes);
        if (decision == null) {
            log.info("Arena fight skip — 생성 보류 (shouldFight=false 또는 백스톱)");
            return;
        }
        try {
            var resp = api.fight(properties.fight(), decision.side(), decision.content());
            boolean ok = resp != null && resp.success();
            log.info("Arena fight created: success={} side={} len={} locked={}",
                    ok, decision.side(), decision.content().length(), lockedSide != null);
            if (ok) {
                stateStore.recordSide(today, topicId, decision.side());
            }
        } catch (Exception e) {
            log.warn("Arena fight 실패 (쿨다운 등) — 스킵: {}", e.getMessage());
        }
    }
```
   (기존 `doFight()` 삭제, 내용은 위로 이관. `executeFight`는 `runFightOnce()` 호출.)

- [ ] **Step 3.5: 기존 `ArenaServiceTest` 갱신 (생성자·스텁 깨짐)**

`ArenaServiceTest.java`:
1. 필드 추가(다른 `mock(...)` 필드 옆): `private ArenaPrepGenerator prepGen = mock(ArenaPrepGenerator.class);`
2. **모든 `new ArenaService(p, api, proposeGen, fightGen, stateStore, clock)` 4곳(약 line 40·76·90·190)** 을 `new ArenaService(p, api, proposeGen, fightGen, prepGen, stateStore, clock)` 로(prepGen을 fightGen과 stateStore 사이에 삽입 — 필드 선언 순서와 일치).
3. **`fightGen.generate(...)` 스텁·verify를 5인자로**: 기존 `when(fightGen.generate(any(), any(), any(), any())).thenReturn(...)` 류를 `when(fightGen.generate(any(), any(), any(), any(), any())).thenReturn(...)` 로(trailing `any()` 추가). `verify(fightGen).generate(...)` 도 동일하게 인자 하나 추가. (runFightOnce가 rebuttalNotes를 non-null "" 로 보장하므로 `any()`가 매칭됨.)
4. prepGen은 기본 목(반환 null)이라도 null-guard로 안전 — 추가 스텁 불필요. 단 상대 글 있는 executeFight 테스트에서 prep 호출을 확인하려면 `verify(prepGen).generate(...)` 를 선택적으로 추가.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "*ArenaServiceFightWiringTest" --tests "*ArenaServiceTest"`
Expected: PASS (신규 3 + 기존 ArenaServiceTest 전부).

- [ ] **Step 5: fight 크론 빈도↑ (설정)**

`src/main/resources/application.yml` 의 `fight-cron` 기본값 교체:
```yaml
  fight-cron: ${ARENA_FIGHT_CRON:0 0,30 12-19 * * *}     # 12:00~19:30 30분 간격 (매너 20시 전 종료). 매 틱 prep이 캐시 워밍, 게이트로 실제 게시는 제한.
```
(주석의 기존 "12:30·14:30·16:30·18:30" 설명은 위 문구로 대체.)

- [ ] **Step 6: 전체 빌드 그린 확인**

Run: `./gradlew test 2>&1 | grep -E "BUILD (SUCCESSFUL|FAILED)"`
Expected: `BUILD SUCCESSFUL` (아레나 전 테스트 + 전체 회귀).

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/maitmus/sekairouter/arena/ArenaService.java \
        src/main/resources/application.yml \
        src/test/java/com/maitmus/sekairouter/arena/ArenaServiceTest.java \
        src/test/java/com/maitmus/sekairouter/arena/ArenaServiceFightWiringTest.java
git commit -m "feat(arena): doFight에 prep 배선(게이트 무관 워밍) + fight 크론 30분화"
```

---

## 실행 후 (이 계획 밖 — 별도 트리거)

- **sim-refine**: prep 반박노트 품질 + 노트를 먹인 fight 논변 품질(네네 톤·과의존 없음) Haiku 실코드 덤프 검증. (머슴 아닌 아레나지만 프롬프트 신설이라 권장.)
- **배포**: sekai-deploy 스킬 경유(사용자 명시 트리거). 배포 후 fight 콜 `cache_read>0` 로그로 캐시 실효 확인, prep 로그·fight 볼륨 관측.
- **관측**: prep 조건(상대 글 있을 때만)·fight 30분 빈도가 콜 수·비용에 미치는 영향 며칠 관측 후 조정.
