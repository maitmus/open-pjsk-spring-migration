package com.maitmus.sekairouter.routing;

import com.maitmus.sekairouter.config.PersonaProperties;
import com.maitmus.sekairouter.persona.CharacterId;
import com.maitmus.sekairouter.persona.Persona;
import com.maitmus.sekairouter.persona.PersonaRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Builds the byte-identical shared prefix used by both {@link SystemPromptBuilder}
 * (routing) and {@code HeartbeatPromptBuilder}. Holds USER.md, persona definitions,
 * GRADES.md matrix, and events.json — content that does NOT vary by path.
 *
 * Path-specific content (instructions, output schema) lives in each builder's suffix.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SharedPromptContent {

    private static final String GRADES_FILE = "GRADES.md";
    private static final String EVENTS_FILE = "events.json";
    private static final String USER_FILE = "USER.md";

    private final PersonaRegistry registry;
    private final PersonaProperties personaProperties;

    /** events.json + 출력 공통 규칙 (전 경로 공유 — 페르소나·GRADES·USER 없음). */
    public String commonBase() {
        StringBuilder sb = new StringBuilder();
        Path baseDir = Paths.get(personaProperties.dir());
        loadFile(baseDir, EVENTS_FILE).ifPresent(c ->
                sb.append("## 이벤트 캘린더 (events.json)\n\n```json\n").append(c).append("```\n"));
        sb.append("\n## 출력 공통 규칙 (모든 발화·게시 공통)\n");
        sb.append("- **발행 텍스트(발화·글·댓글·광고·토론 본문)는 전부 한글로 쓴다.** ");
        sb.append("중국·일본 한자와 불필요한 일본어/영어 원어 표기 금지 — 한자어도 전부 한글 발음으로만 적는다(예: '가희'·'세계'를 한자로 쓰지 말 것). ");
        sb.append("**특히 일본어 가나(카타카나 ン·ダ 류·히라가나) 문자를 한글 단어 속에 한 글자라도 섞지 말 것 — 예: '텐션'을 '테ン션'으로, '다'를 'ダ'로 쓰지 말고 전부 한글로.** ");
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

    private void appendPersona(StringBuilder sb, Persona p) {
        sb.append("### ").append(p.id().name().toLowerCase())
          .append(" — ").append(p.displayName()).append("\n\n")
          .append(p.content()).append("\n\n");
    }

    private Optional<String> loadFile(Path dir, String name) {
        if (dir == null) return Optional.empty();
        Path p = dir.resolve(name);
        if (!Files.isRegularFile(p)) {
            log.debug("{} not found at {} — skipping", name, p);
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", p, e.getMessage());
            return Optional.empty();
        }
    }
}
