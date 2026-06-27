# 머슴/아레나 프롬프트 격리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 각 기능(발화·머슴·아레나)이 필요한 페르소나/규칙만 받도록 system 프롬프트를 격리해, 머슴이 읽는 양을 87→~37KB로 줄이고(룰 dilution↓) 아레나는 캐시 패널티를 없앤다.

**Architecture:** system 프롬프트를 `commonBase`(전 경로 공유 캐시) + 기능별 블록으로 재구성. `PromptBlocks`를 캐시 플래그 붙은 블록 리스트로 격상. 발화는 전 페르소나+GRADES 블록을 공유, 머슴은 본인+형제봇최소만, 아레나는 네네만+uncached.

**Tech Stack:** Java 21, Spring Boot, Anthropic Java SDK, JUnit5 + Mockito + AssertJ, Gradle.

## Global Constraints

- 프롬프트 변경은 **머슴·아레나 system 조립 한정** — 발화(라우터·하트비트)는 *내용 무변경*(블록 경계만 재배치, 바이트 동일성 유지).
- 페르소나 원본은 `PersonaRegistry`(런타임 로드)에서만 가져온다 — 하드코딩 금지.
- `PromptBlocks` 2-인자 생성자는 **하위호환 유지**(빈 블록 필터링 포함) — `PuzzleSolver` 등 기존 caller가 안 깨지게.
- 아레나 2번째 블록은 **cache=false**(uncached).
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- 빌드 검증: `./gradlew test`.
- CharacterId 값: `AIRI EMU HARUKA MIKU MINORI NENE SHIZUKU`.

---

### Task 1: PromptBlocks를 캐시 플래그 블록 리스트로 격상

**Files:**
- Modify: `src/main/java/com/maitmus/sekairouter/routing/PromptBlocks.java`
- Modify: `src/main/java/com/maitmus/sekairouter/routing/AnthropicClientWrapper.java` (buildSystemBlocks/buildBlock)
- Test: `src/test/java/com/maitmus/sekairouter/routing/PromptBlocksTest.java` (create)

**Interfaces:**
- Produces:
  - `record PromptBlocks.Block(String text, boolean cache)`
  - `PromptBlocks(java.util.List<Block> blocks)` — 리스트 생성자
  - `PromptBlocks(String sharedPrefix, String pathSuffix)` — 하위호환(둘 다 cache=true Block으로)
  - `List<Block> PromptBlocks.blocks()`
  - `AnthropicClientWrapper.buildSystemBlocks(PromptBlocks)` — **package-private static**(테스트용), 각 Block을 TextBlockParam으로 매핑, `cache=true`일 때만 cache_control 적용, 빈 text 블록 제외.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/maitmus/sekairouter/routing/PromptBlocksTest.java`:
```java
package com.maitmus.sekairouter.routing;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PromptBlocksTest {

    @Test
    void list_constructor_keeps_blocks_and_cache_flags() {
        PromptBlocks p = new PromptBlocks(List.of(
                new PromptBlocks.Block("common", true),
                new PromptBlocks.Block("arena", false)));
        assertThat(p.blocks()).hasSize(2);
        assertThat(p.blocks().get(0).cache()).isTrue();
        assertThat(p.blocks().get(1).text()).isEqualTo("arena");
        assertThat(p.blocks().get(1).cache()).isFalse();
    }

    @Test
    void legacy_two_arg_constructor_makes_two_cached_blocks() {
        PromptBlocks p = new PromptBlocks("prefix", "suffix");
        assertThat(p.blocks()).hasSize(2);
        assertThat(p.blocks()).allMatch(PromptBlocks.Block::cache);
        assertThat(p.blocks().get(0).text()).isEqualTo("prefix");
        assertThat(p.blocks().get(1).text()).isEqualTo("suffix");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*PromptBlocksTest*'`
Expected: FAIL — compile error (`Block` / list constructor not defined).

- [ ] **Step 3: Rewrite PromptBlocks**

`src/main/java/com/maitmus/sekairouter/routing/PromptBlocks.java` (full file):
```java
package com.maitmus.sekairouter.routing;

import java.util.ArrayList;
import java.util.List;

/**
 * System 프롬프트를 캐시 플래그 붙은 블록 리스트로 표현한다.
 * 바이트 동일한 선두 블록을 공유하는 경로끼리 Anthropic prefix-cache를 공유한다.
 * cache=false 블록은 cache_control 없이(uncached) 보낸다 — 읽기 상각이 안 되는 저빈도 경로(아레나)용.
 */
public record PromptBlocks(List<Block> blocks) {

    public record Block(String text, boolean cache) {}

    /** 하위호환: prefix/suffix 두 블록(둘 다 캐시). */
    public PromptBlocks(String sharedPrefix, String pathSuffix) {
        this(twoBlocks(sharedPrefix, pathSuffix));
    }

    private static List<Block> twoBlocks(String prefix, String suffix) {
        List<Block> list = new ArrayList<>(2);
        list.add(new Block(prefix, true));
        list.add(new Block(suffix, true));
        return List.copyOf(list);
    }
}
```

- [ ] **Step 4: Rewrite buildSystemBlocks + buildBlock**

In `AnthropicClientWrapper.java`, replace the `private static List<TextBlockParam> buildSystemBlocks(...)` and `private static TextBlockParam buildBlock(...)` methods with:
```java
    static List<TextBlockParam> buildSystemBlocks(PromptBlocks prompt) {
        java.util.List<TextBlockParam> out = new java.util.ArrayList<>();
        for (PromptBlocks.Block b : prompt.blocks()) {
            if (b.text() == null || b.text().isEmpty()) continue;   // 빈 블록 제외(API 400 회피)
            out.add(buildBlock(b.text(), b.cache()));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("PromptBlocks produced no non-empty system blocks");
        }
        return List.copyOf(out);
    }

    private static TextBlockParam buildBlock(String text, boolean cache) {
        TextBlockParam.Builder b = TextBlockParam.builder().text(text);
        if (cache) {
            b.cacheControl(CacheControlEphemeral.builder()
                    .ttl(CacheControlEphemeral.Ttl.TTL_1H)
                    .build());
        }
        return b.build();
    }
```

- [ ] **Step 5: Add a cache-flag test for buildSystemBlocks**

Append to `PromptBlocksTest.java`:
```java
    @Test
    void buildSystemBlocks_omits_cache_control_on_uncached_block() {
        PromptBlocks p = new PromptBlocks(List.of(
                new PromptBlocks.Block("c", true),
                new PromptBlocks.Block("u", false)));
        var blocks = com.maitmus.sekairouter.routing.AnthropicClientWrapper.buildSystemBlocks(p);
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).cacheControl()).isPresent();
        assertThat(blocks.get(1).cacheControl()).isEmpty();
    }

    @Test
    void buildSystemBlocks_skips_empty_blocks() {
        PromptBlocks p = new PromptBlocks(List.of(
                new PromptBlocks.Block("only", true),
                new PromptBlocks.Block("", true)));
        assertThat(com.maitmus.sekairouter.routing.AnthropicClientWrapper.buildSystemBlocks(p)).hasSize(1);
    }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests '*PromptBlocksTest*'`
Expected: PASS (4 tests). (`PuzzleSolver`의 `new PromptBlocks(puzzleInstructions, "")`는 2-인자 생성자로 그대로 컴파일됨 — 빈 suffix는 buildSystemBlocks가 필터.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/maitmus/sekairouter/routing/PromptBlocks.java \
        src/main/java/com/maitmus/sekairouter/routing/AnthropicClientWrapper.java \
        src/test/java/com/maitmus/sekairouter/routing/PromptBlocksTest.java
git commit -m "refactor(prompt): PromptBlocks를 캐시 플래그 블록 리스트로 격상

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: SharedPromptContent에 commonBase() + voiceRoster() + personaInjection() 추가

**Files:**
- Modify: `src/main/java/com/maitmus/sekairouter/routing/SharedPromptContent.java`
- Test: `src/test/java/com/maitmus/sekairouter/routing/SharedPromptContentTest.java` (이미 존재 시 추가, 없으면 create)

**Interfaces:**
- Produces:
  - `String commonBase()` — events.json 블록 + 출력 공통 규칙(페르소나·GRADES·USER 없음).
  - `String voiceRoster()` — USER.md + "## 페르소나 정의" 전 페르소나 + GRADES.md (발화 전용).
  - `String personaInjection(CharacterId id, String note)` — "\n## 너는 <displayName> — 아래 정의를 그대로 체화한다 (<note>)\n" + persona.content() + "\n" (머슴/아레나 단일 캐릭터 체화용).
- Consumes: `PersonaRegistry.all()`, `PersonaRegistry.get(CharacterId)`, `personaProperties.dir()`.
- Note: 기존 `build()`는 이 Task에선 **남겨둔다**(Task 6에서 제거). 새 메서드만 추가.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/maitmus/sekairouter/routing/SharedPromptContentTest.java` — 추가(파일 없으면 패키지/임포트 포함 생성). 가정: 테스트가 `SharedPromptContent`를 실제 PersonaRegistry+PersonaProperties로 구성하거나, 최소한 메서드 호출 가능. 실제 파일 로드를 피하려면 PersonaProperties.dir()이 가리키는 곳에 테스트 픽스처가 없을 수 있으니, **registry는 mock**으로 EMU/NENE Persona를 주입한다:
```java
package com.maitmus.sekairouter.routing;

import com.maitmus.sekairouter.config.PersonaProperties;
import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SharedPromptContentTest {

    private SharedPromptContent withPersonas() {
        PersonaRegistry reg = mock(PersonaRegistry.class);
        Persona emu = new Persona(CharacterId.EMU, "오오토리 에무", "에무 페르소나 내용");
        Persona nene = new Persona(CharacterId.NENE, "쿠사나기 네네", "네네 페르소나 내용");
        Map<CharacterId, Persona> all = new EnumMap<>(CharacterId.class);
        all.put(CharacterId.EMU, emu);
        all.put(CharacterId.NENE, nene);
        when(reg.all()).thenReturn(all);
        when(reg.get(CharacterId.EMU)).thenReturn(emu);
        when(reg.get(CharacterId.NENE)).thenReturn(nene);
        PersonaProperties props = mock(PersonaProperties.class);
        when(props.dir()).thenReturn("/nonexistent");   // 파일들은 없으면 skip → commonBase엔 출력규칙만
        return new SharedPromptContent(reg, props);
    }

    @Test
    void commonBase_has_output_rules_no_personas() {
        String c = withPersonas().commonBase();
        assertThat(c).contains("출력 공통 규칙").contains("전부 한글");
        assertThat(c).doesNotContain("페르소나 정의").doesNotContain("매트릭스");
    }

    @Test
    void voiceRoster_has_all_personas() {
        String v = withPersonas().voiceRoster();
        assertThat(v).contains("페르소나 정의").contains("에무 페르소나 내용").contains("네네 페르소나 내용");
    }

    @Test
    void personaInjection_is_single_character_with_note() {
        String inj = withPersonas().personaInjection(CharacterId.EMU, "특히 말투");
        assertThat(inj).contains("너는 오오토리 에무").contains("특히 말투").contains("에무 페르소나 내용");
        assertThat(inj).doesNotContain("네네 페르소나 내용");
    }
}
```
> ⚠️ `Persona` 생성자 시그니처(`new Persona(id, displayName, content)`)는 실제 `Persona` 레코드/클래스에 맞춰 확인(필드명 `id()/displayName()/content()` 사용 중). 다르면 테스트의 생성자만 맞춘다.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*SharedPromptContentTest*'`
Expected: FAIL — `commonBase()/voiceRoster()/personaInjection()` 미정의.

- [ ] **Step 3: Add the three methods (refactor build() to reuse them)**

`SharedPromptContent.java` — 기존 `build()` 위/아래에 추가, 그리고 내부 로딩 로직을 재사용. 아래를 클래스에 추가:
```java
    /** events.json + 출력 공통 규칙 (전 경로 공유 — 페르소나·GRADES·USER 없음). */
    public String commonBase() {
        StringBuilder sb = new StringBuilder();
        Path baseDir = Paths.get(personaProperties.dir());
        loadFile(baseDir, EVENTS_FILE).ifPresent(c ->
                sb.append("## 이벤트 캘린더 (events.json)\n\n```json\n").append(c).append("```\n"));
        sb.append("\n## 출력 공통 규칙 (모든 발화·게시 공통)\n");
        sb.append("- **발행 텍스트(발화·글·댓글·광고·토론 본문)는 전부 한글로 쓴다.** ");
        sb.append("중국·일본 한자와 불필요한 일본어/영어 원어 표기 금지 — 한자어도 전부 한글 발음으로만 적는다(예: '가희'·'세계'를 한자로 쓰지 말 것). ");
        sb.append("시그니처·기호·이모지(♪ ☆ ★ 등)는 그대로 써도 된다.\n");
        sb.append("- **출력 직전 한 번 더 검수**한다 — 오탈자·띄어쓰기·조사·깨진 글자가 없는지 확인하고, 어색한 표기는 자연스러운 한국어로 다듬어 내보낸다.\n");
        return sb.toString();
    }

    /** USER.md + 전 페르소나 + GRADES (발화 전용 블록). */
    public String voiceRoster() {
        StringBuilder sb = new StringBuilder();
        Path baseDir = Paths.get(personaProperties.dir());
        Path workspaceDir = baseDir.getParent();
        loadFile(workspaceDir, USER_FILE).ifPresent(c ->
                sb.append("## 사용자 정보 (USER.md)\n\n").append(c).append("\n"));
        sb.append("\n## 페르소나 정의\n\n");
        registry.all().values().forEach(p -> appendPersona(sb, p));
        loadFile(baseDir, GRADES_FILE).ifPresent(c ->
                sb.append("\n## 호칭·존댓말 매트릭스 (GRADES.md)\n\n").append(c).append("\n"));
        return sb.toString();
    }

    /** 단일 캐릭터 체화 주입(머슴·아레나용). */
    public String personaInjection(CharacterId id, String note) {
        Persona p = registry.get(id);
        String content = (p != null && p.content() != null) ? p.content() : "";
        return "\n## 너는 " + (p != null ? p.displayName() : id.name())
                + " — 아래 정의를 그대로 체화한다 (" + note + ")\n" + content + "\n";
    }
```
임포트 추가: `import com.maitmus.sekairouter.persona.CharacterId;`

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*SharedPromptContentTest*'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maitmus/sekairouter/routing/SharedPromptContent.java \
        src/test/java/com/maitmus/sekairouter/routing/SharedPromptContentTest.java
git commit -m "feat(prompt): SharedPromptContent에 commonBase/voiceRoster/personaInjection 추가

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: 발화 빌더(System·Heartbeat)를 commonBase+voiceRoster 블록으로 이전

**Files:**
- Modify: `src/main/java/com/maitmus/sekairouter/routing/SystemPromptBuilder.java:build`
- Modify: `src/main/java/com/maitmus/sekairouter/heartbeat/HeartbeatPromptBuilder.java:build`
- Test: `src/test/java/com/maitmus/sekairouter/routing/SystemPromptBuilderTest.java` (존재 — 갱신/추가)

**Interfaces:**
- Consumes: `shared.commonBase()`, `shared.voiceRoster()` (Task 2).
- Produces: 두 빌더의 `build()`가 3블록 `PromptBlocks`(commonBase, voiceRoster, instr) 반환. 라우터·하트비트의 **블록[0]·블록[1] 텍스트가 바이트 동일**.

- [ ] **Step 1: Write the failing test**

`SystemPromptBuilderTest.java`에 추가(빌더가 mock shared로 구성된다면 그 패턴 따름. 아래는 실제 shared 주입 가정 — 기존 테스트 구성 방식에 맞춰 조정):
```java
    @Test
    void voice_builders_share_byte_identical_common_and_roster_blocks() {
        // SystemPromptBuilder.build()와 HeartbeatPromptBuilder.build()의 블록[0],[1] 동일성
        var sys = systemPromptBuilder.build().blocks();
        var hb = heartbeatPromptBuilder.build().blocks();
        assertThat(sys.get(0).text()).isEqualTo(hb.get(0).text());   // commonBase
        assertThat(sys.get(1).text()).isEqualTo(hb.get(1).text());   // voiceRoster
        assertThat(sys.get(0).text()).contains("출력 공통 규칙");
        assertThat(sys.get(1).text()).contains("페르소나 정의");
    }
```
(두 빌더를 같은 `SharedPromptContent` 인스턴스로 구성해야 byte-identical — 테스트에서 동일 mock/실인스턴스 주입.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*SystemPromptBuilderTest*'`
Expected: FAIL — `build().blocks()`가 현재 2블록(prefix=build(), suffix), voiceRoster 분리 안 됨 / 블록[1]에 "페르소나 정의" 없을 수 있음(현재 suffix=instr).

- [ ] **Step 3: Migrate SystemPromptBuilder.build()**

`SystemPromptBuilder.java`의 `build()` 교체:
```java
    public PromptBlocks build() {
        String instr = "\n" + loadResource(baseInstructions) + "\n\n" + loadResource(outputSchema);
        return new PromptBlocks(java.util.List.of(
                new PromptBlocks.Block(shared.commonBase(), true),
                new PromptBlocks.Block(shared.voiceRoster(), true),
                new PromptBlocks.Block(instr, true)));
    }
```

- [ ] **Step 4: Migrate HeartbeatPromptBuilder.build()**

`HeartbeatPromptBuilder.java`의 `build()` 교체:
```java
    public PromptBlocks build() {
        String instr = "\n" + loadResource(baseInstructions);
        return new PromptBlocks(java.util.List.of(
                new PromptBlocks.Block(shared.commonBase(), true),
                new PromptBlocks.Block(shared.voiceRoster(), true),
                new PromptBlocks.Block(instr, true)));
    }
```

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew test --tests '*SystemPromptBuilderTest*' --tests '*Heartbeat*'`
Expected: PASS. (발화 내용은 commonBase+voiceRoster 합이 옛 build()와 동일 의미 — 바이트 순서만 다름.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/maitmus/sekairouter/routing/SystemPromptBuilder.java \
        src/main/java/com/maitmus/sekairouter/heartbeat/HeartbeatPromptBuilder.java \
        src/test/java/com/maitmus/sekairouter/routing/SystemPromptBuilderTest.java
git commit -m "refactor(prompt): 발화 빌더를 commonBase+voiceRoster 3블록으로

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: 머슴 빌더를 본인 체화 + 형제봇 최소로 이전

**Files:**
- Modify: `src/main/java/com/maitmus/sekairouter/mersoom/MersoomPromptBuilder.java`
- Test: `src/test/java/com/maitmus/sekairouter/mersoom/MersoomPromptBuilderTest.java` (create)

**Interfaces:**
- Consumes: `shared.commonBase()`, `shared.personaInjection(id, note)` (Task 2).
- Produces: `MersoomPromptBuilder.build()`(에무)·`build(profile)`(네네) → 2블록 `PromptBlocks`(commonBase, self체화+형제봇최소+지침). **GRADES·타 페르소나 미포함.**
- 새 상수: `SIBLING_MINIMAL` (EnumMap<CharacterId,String>) — 형제봇 호칭·관계·말투 ~3줄.

- [ ] **Step 1: Write the failing test**

`MersoomPromptBuilderTest.java`:
```java
package com.maitmus.sekairouter.mersoom;

import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MersoomPromptBuilderTest {

    private MersoomPromptBuilder builder() {
        SharedPromptContent shared = mock(SharedPromptContent.class);
        when(shared.commonBase()).thenReturn("COMMONBASE");
        when(shared.personaInjection(CharacterId.EMU, "특히 말투")).thenReturn("EMU체화");
        when(shared.personaInjection(CharacterId.NENE, "특히 말투")).thenReturn("NENE체화");
        PersonaRegistry reg = mock(PersonaRegistry.class);
        return new MersoomPromptBuilder(shared, reg,
                new ByteArrayResource("에무지침".getBytes()),
                new ByteArrayResource("네네지침".getBytes()));
    }

    @Test
    void emu_block_has_self_sibling_min_instructions_no_grades() {
        var blocks = builder().build().blocks();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).text()).isEqualTo("COMMONBASE");
        String b1 = blocks.get(1).text();
        assertThat(b1).contains("EMU체화").contains("에무지침").contains("네네쨩");  // 형제봇 최소
        assertThat(b1).doesNotContain("매트릭스").doesNotContain("페르소나 정의");
    }

    @Test
    void nene_block_uses_nene_self_and_emu_sibling_min() {
        CitizenProfile nene = mock(CitizenProfile.class);
        when(nene.persona()).thenReturn(CharacterId.NENE);
        var blocks = builder().build(nene).blocks();
        String b1 = blocks.get(1).text();
        assertThat(b1).contains("NENE체화").contains("네네지침").contains("에무");  // 형제봇=에무
        assertThat(b1).doesNotContain("매트릭스");
    }
}
```
> ⚠️ `CitizenProfile.persona()` 반환 타입 확인. 기존 `MersoomPostGeneratorTest`가 `CitizenProfile`를 실제 생성하므로 그 패턴 재사용 가능(필요 시 mock 대신 실 객체).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*MersoomPromptBuilderTest*'`
Expected: FAIL — 현재 `build()`가 `shared.build()`(전 페르소나+GRADES) 사용, 형제봇 최소 없음.

- [ ] **Step 3: Migrate MersoomPromptBuilder**

`MersoomPromptBuilder.java`에 상수 추가 + `build()`/`build(profile)` 교체:
```java
    private static final java.util.Map<CharacterId, String> SIBLING_MINIMAL = java.util.Map.of(
        CharacterId.NENE,
        "\n## 형제봇(원더쇼 동료) — 네네\n쿠사나기 네네: 원더랜즈×쇼타임 동료. 호칭 '네네쨩' + 반말. 까다롭고 직설·츤데레. 머슴에서 네네 글/언급엔 이 관계로 당사자처럼.\n",
        CharacterId.EMU,
        "\n## 형제봇(원더쇼 동료) — 에무\n오오토리 에무: 원더랜즈×쇼타임 동료. 호칭 '에무' + 반말. 천진·텐션 폭발. 머슴에서 에무 글/언급엔 이 관계로 당사자처럼.\n");

    /** 에무 기본. */
    public PromptBlocks build() {
        String b1 = shared.personaInjection(CharacterId.EMU, "특히 말투")
                + SIBLING_MINIMAL.get(CharacterId.NENE)
                + "\n" + loadResource(emuInstructions);
        return new PromptBlocks(java.util.List.of(
                new PromptBlocks.Block(shared.commonBase(), true),
                new PromptBlocks.Block(b1, true)));
    }

    /** 페르소나별 시스템 프롬프트. */
    public PromptBlocks build(CitizenProfile profile) {
        if (profile != null && profile.persona() == CharacterId.NENE) {
            String b1 = shared.personaInjection(CharacterId.NENE, "특히 말투")
                    + SIBLING_MINIMAL.get(CharacterId.EMU)
                    + "\n" + loadResource(neneInstructions);
            return new PromptBlocks(java.util.List.of(
                    new PromptBlocks.Block(shared.commonBase(), true),
                    new PromptBlocks.Block(b1, true)));
        }
        return build();
    }
```
- `personaRegistry` 필드는 더 이상 직접 안 쓰지만(주입은 personaInjection이 함) 생성자 시그니처는 유지(Spring 배선·테스트 호환). 미사용 경고 무시 또는 필드 제거는 별도.

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew test --tests '*MersoomPromptBuilderTest*'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maitmus/sekairouter/mersoom/MersoomPromptBuilder.java \
        src/test/java/com/maitmus/sekairouter/mersoom/MersoomPromptBuilderTest.java
git commit -m "refactor(prompt): 머슴 빌더를 본인 체화+형제봇 최소 2블록으로 (GRADES·타 페르소나 제거)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: 아레나 생성기를 commonBase + uncached 네네 블록으로 이전

**Files:**
- Modify: `src/main/java/com/maitmus/sekairouter/arena/ArenaProposeGenerator.java:73`
- Modify: `src/main/java/com/maitmus/sekairouter/arena/ArenaFightGenerator.java:67`
- Test: `src/test/java/com/maitmus/sekairouter/arena/ArenaPromptBlocksTest.java` (create) — `completeJson`에 전달되는 PromptBlocks 캡처

**Interfaces:**
- Consumes: `shared.commonBase()`. (`nenePersona`는 이미 생성기 내부에서 만듦 — 유지.)
- Produces: 두 생성기가 `completeJson`에 넘기는 PromptBlocks = `[Block(commonBase, true), Block(nenePersona+SUFFIX, false)]`.

- [ ] **Step 1: Write the failing test**

`ArenaPromptBlocksTest.java`:
```java
package com.maitmus.sekairouter.arena;

import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import com.maitmus.sekairouter.routing.AnthropicClientWrapper;
import com.maitmus.sekairouter.routing.PromptBlocks;
import com.maitmus.sekairouter.routing.SharedPromptContent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArenaPromptBlocksTest {

    @Test
    void fight_generator_sends_uncached_nene_block_over_commonBase() {
        AnthropicClientWrapper anthropic = mock(AnthropicClientWrapper.class);
        ArgumentCaptor<PromptBlocks> cap = ArgumentCaptor.forClass(PromptBlocks.class);
        when(anthropic.completeJson(cap.capture(), any())).thenReturn("{\"shouldFight\":false}");
        SharedPromptContent shared = mock(SharedPromptContent.class);
        when(shared.commonBase()).thenReturn("COMMONBASE");
        PersonaRegistry reg = mock(PersonaRegistry.class);
        when(reg.get(CharacterId.NENE)).thenReturn(new Persona(CharacterId.NENE, "쿠사나기 네네", "네네내용"));

        // 생성기 호출 — fightPosts 비어도 생성 트리거 경로를 타도록 최소 인자(시그니처에 맞춰 조정)
        // 핵심: completeJson이 한 번이라도 호출되면 캡처됨. 호출 조건이 까다로우면 ArenaProposeGenerator로 대체.
        new ArenaFightGenerator(anthropic, shared, reg)
                .generate(/* status·posts·side·nick — 실제 시그니처대로 채움 */ null, java.util.List.of(), null, "쿠사나기 네네");

        PromptBlocks p = cap.getValue();
        assertThat(p.blocks().get(0).text()).isEqualTo("COMMONBASE");
        assertThat(p.blocks().get(0).cache()).isTrue();
        assertThat(p.blocks().get(1).cache()).isFalse();             // 아레나 tail uncached
        assertThat(p.blocks().get(1).text()).contains("네네내용");
    }
}
```
> ⚠️ `ArenaFightGenerator`/`ArenaProposeGenerator` 생성자·`generate(...)` 시그니처는 실제대로 채운다. `generate`가 completeJson을 안 타는 가드(예: shouldFight 사전조건)면, completeJson 호출이 보장되는 쪽(Propose) 또는 가드를 통과하는 인자로 조정. 캡처가 핵심.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ArenaPromptBlocksTest*'`
Expected: FAIL — 현재 `new PromptBlocks(shared.build(), nenePersona + SUFFIX)` → 블록[0]=전 페르소나(COMMONBASE 아님), 블록[1] cache=true.

- [ ] **Step 3: Migrate ArenaFightGenerator**

`ArenaFightGenerator.java` line 67 영역의 `new PromptBlocks(shared.build(), nenePersona + SUFFIX)` 교체:
```java
        String raw = anthropic.completeJson(new PromptBlocks(java.util.List.of(
                new PromptBlocks.Block(shared.commonBase(), true),
                new PromptBlocks.Block(nenePersona + SUFFIX, false))),
                /* 기존 user 인자 그대로 */ user);
```
(원래 호출의 2번째 인자(user prompt)는 그대로 유지 — 실제 변수명 확인.)

- [ ] **Step 4: Migrate ArenaProposeGenerator**

`ArenaProposeGenerator.java` line 73 동일 교체:
```java
        String raw = anthropic.completeJson(new PromptBlocks(java.util.List.of(
                new PromptBlocks.Block(shared.commonBase(), true),
                new PromptBlocks.Block(nenePersona + SUFFIX, false))), user);
```

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew test --tests '*ArenaPromptBlocksTest*' --tests '*ArenaServiceTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/maitmus/sekairouter/arena/ArenaProposeGenerator.java \
        src/main/java/com/maitmus/sekairouter/arena/ArenaFightGenerator.java \
        src/test/java/com/maitmus/sekairouter/arena/ArenaPromptBlocksTest.java
git commit -m "refactor(prompt): 아레나를 commonBase + uncached 네네 블록으로 (캐시 패널티 회피)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: SharedPromptContent.build() 제거 + 전체 회귀 + 충실 repro 게이트

**Files:**
- Modify: `src/main/java/com/maitmus/sekairouter/routing/SharedPromptContent.java` (build() 제거)
- (검증) 전체 테스트 + 충실 repro 무회귀

**Interfaces:**
- 이 시점에 `shared.build()` 호출처는 0이어야 함(Task 3·4·5에서 전부 이전). `PuzzleSolver`는 PromptBlocks 2-인자만 쓰고 shared.build() 안 씀.

- [ ] **Step 1: Confirm no remaining callers**

Run: `grep -rn "shared.build()\|\.build()" src/main/java | grep -i sharedprompt`
또한: `grep -rn "shared.build()" src/main/java`
Expected: `SharedPromptContent` 내부 정의 외 호출 0건.

- [ ] **Step 2: Remove build()**

`SharedPromptContent.java`에서 `public String build() { ... }` 메서드 전체 삭제(commonBase/voiceRoster/personaInjection만 남김).

- [ ] **Step 3: Full test**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. (실패 시 누락 호출처 수정.)

- [ ] **Step 4: 충실 repro 무회귀 게이트 (수동, 배포 전)**

scratchpad의 충실 프롬프트 조립 스크립트로 **slim 버전** system을 만들어(머슴 에무·네네 / 아레나 네네) Haiku sim 각 3~4명 → 캐릭터·말투(에무 존댓말·네네 ~다체 해체)·동아리·형제봇 호칭이 안 깨지는지 확인. **품질 교정 아님 — 무회귀 확인.** 발화는 내용 무변경이라 스킵. (이 단계는 코드 테스트가 아니라 sim 검증 — `sim-refine` 패턴 재사용.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maitmus/sekairouter/routing/SharedPromptContent.java
git commit -m "refactor(prompt): SharedPromptContent.build() 제거 (commonBase/voiceRoster/personaInjection으로 대체 완료)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 6: 배포 결정**

`sekai-deploy` 스킬 ⭐물음대로(급함/배치 판단). 배포 후 점검에서 머슴 글/댓글·아레나 라이브 정합 + 모델 읽는 양 감소(머슴 system 토큰↓) 확인.

---

## Self-Review (작성자 점검)

**Spec coverage:**
- §블록 레이아웃 → Task 3(발화)·4(머슴)·5(아레나) ✅
- §API(commonBase/personaSection/siblingMinimal/PromptBlocks 리스트) → Task 1(PromptBlocks)·2(commonBase/voiceRoster/personaInjection)·4(siblingMinimal) ✅ (※ 스펙의 `personaSection(ids,...)`는 구현상 발화=`voiceRoster()`, 머슴/아레나=`personaInjection()`로 분화 — 단일 캐릭터 체화 framing(말투 가드) 보존 위해. 설계 의도(격리·self+sibling-min) 충실.)
- §캐시(아레나 uncached, 발화 공유) → Task 1(cache 플래그)·3(발화 공유)·5(아레나 uncached) ✅
- §USER.md→발화 → Task 2(voiceRoster가 USER 포함, commonBase는 미포함) ✅
- §테스트(단위 + 충실 repro 게이트) → 각 Task 단위테스트 + Task 6 Step 4 ✅

**Placeholder scan:** 코드 step은 전부 실제 코드. 단 Task 5 테스트의 `generate(...)` 인자·Task 2/4의 `Persona`/`CitizenProfile` 생성자는 "실제 시그니처 확인" 주석 — 구현자가 해당 클래스 보고 채움(완전 placeholder 아님, 가드 명시).

**Type consistency:** `PromptBlocks.Block(text, cache)`·`PromptBlocks(List<Block>)`·`PromptBlocks(String,String)`·`commonBase()`·`voiceRoster()`·`personaInjection(CharacterId,String)`·`buildSystemBlocks`(package-private) — Task 간 일관.

**남은 주의:** `personaRegistry` 미사용 필드(Task 4 후), `appendPersona`는 voiceRoster가 계속 사용 — OK.
