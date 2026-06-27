# 격리 리팩터 후속 문서·주석 동기화 — 최종 정리 리포트

## 변경 파일 목록

| 파일 | 변경 내용 |
|---|---|
| `src/main/resources/prompts/mersoom-instructions.md` | GRADES 반말 → 반말(line 44), GRADES.md 호칭표 포인터 제거(line 61), 페르소나 섹션명 갱신(line 67) |
| `src/main/java/com/maitmus/sekairouter/routing/SystemPromptBuilder.java` | Javadoc 2블록(sharedPrefix/pathSuffix) → 3블록(commonBase/voiceRoster/instr) |
| `src/main/java/com/maitmus/sekairouter/heartbeat/HeartbeatPromptBuilder.java` | Javadoc 2블록(sharedPrefix/pathSuffix) → 3블록(commonBase/voiceRoster/instr) |
| `docs/prompts/heartbeat-router-shared.md` | 프롬프트 조립 개요 3블록으로 갱신, commonBase·voiceRoster 정의 추가, 머슴/아레나 commonBase 공유 명시, AnthropicClientWrapper 2블록→3블록 |
| `docs/prompts/mersoom-comment.md` | 시스템 프롬프트 블록 레이아웃 추가(2블록: commonBase+personaBlock), NO GRADES·로스터·USER.md 명시, ~37KB 절감 기록 |
| `docs/prompts/arena.md` | 프롬프트 조립 방식 2블록 레이아웃으로 갱신, 네네persona+SUFFIX UNcached 이유(2h cadence > 1h TTL) 명시 |

## grep -n "GRADES" 결과 (mersoom-instructions.md)

```
(출력 없음 — GRADES 포인터 완전 제거됨)
```

## 테스트 결과

```
BUILD SUCCESSFUL in 16s
5 actionable tasks: 4 executed, 1 up-to-date
```

## 자기 검증

- Java 로직 변경: 없음 (Javadoc 주석 블록만 교체)
- 머슴 호칭 값 보존: `"네네쨩" + 반말` 라인 61에 유지
- GRADES.md 댕글링 포인터: 완전 제거 (grep 결과 없음)
- 페르소나-정의-섹션 댕글링 포인터: 제거 후 `'너는 오오토리 에무 …' 로 체화된 너 자신의 정의` 로 대체
