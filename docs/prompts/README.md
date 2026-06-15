# 프롬프트 문서 (인라인 프롬프트 스냅샷)

코드에 인라인(Java `sb.append("...")`/상수)으로 박혀 있는 LLM 프롬프트들을 사람이 모니터링하기 쉽게
마크다운으로 추출한 문서다.

> ⚠️ **이 문서들은 코드에서 추출한 스냅샷이다. 코드가 원본(source of truth)** 이며, 프롬프트를 코드에서
> 바꾸면 이 문서도 갱신해야 한다(자동 동기화 아님). 변경 후 "프롬프트 문서 갱신" 요청하면 다시 떠서 맞춰줄 수 있다.
> 최종 추출 기준 커밋은 git log로 확인(이 디렉터리 최초 커밋 시점).

## 프롬프트가 조립되는 구조

LLM 호출 1건 = **시스템 프롬프트(2블록)** + **유저 프롬프트** 로 구성된다.

1. **공유 prefix (cache되는 앞부분)** — `SharedPromptContent`. 7개 페르소나 정의 + USER.md + GRADES.md(호칭·존댓말) + events.json.
   **모든 경로(라우터/하트비트/머슴/아레나)에서 바이트 동일** → Anthropic prefix-cache(TTL 1h)로 재사용(비용 절감).
   - 본문은 외부 파일(`identities/*.md`, `USER.md`, `GRADES.md`, `events.json`)에서 로드 — 인라인 아님. 인라인은 섹션 헤더뿐.
2. **경로별 suffix** — 각 생성기가 자기 모드 지시를 덧붙임(일부는 `resources/prompts/*.md`에서 로드, 일부는 인라인 상수=SUFFIX).
3. **유저 프롬프트** — 각 생성기의 `buildUserPrompt(...)`가 **인라인으로 조립**(모드·피드·지시·형식·예시·출력스키마). ← **이 문서들의 주 대상.**

### 인라인 vs 파일 로드
- **파일 로드(이미 문서)**: `src/main/resources/prompts/` — `heartbeat-base-instructions.md`, `mersoom-instructions.md`,
  `mersoom-instructions-nene.md`, `mersoom-puzzle-instructions.md`, `router-base-instructions.md`, `output-schema.md`.
  (이건 코드 밖이라 그 파일 자체가 곧 문서다.)
- **인라인(이 디렉터리가 문서화)**: 아래 생성기들의 `buildUserPrompt`/SUFFIX/persona-injection 인라인 문자열.

## 문서 목록

| 문서 | 대상 코드 | 내용 |
|---|---|---|
| [mersoom-comment.md](mersoom-comment.md) | `mersoom/MersoomCommentGenerator.java` | 머슴 댓글 — 피드 투표 판정 + 댓글(최대 3) + 별명 제안. 에무/네네 분기, 관계 라인(형제봇 반말·차단·일반), 반말 보강·seam 금지·제3자 관찰 금지 규칙 |
| [mersoom-post-ad.md](mersoom-post-ad.md) | `mersoom/MersoomPostGenerator.java`, `mersoom/MersoomAdGenerator.java` | 머슴 글 생성(에무/네네 분기, shouldPost 규칙) + 광고 한마디 생성 |
| [arena.md](arena.md) | `arena/ArenaFightGenerator.java`, `arena/ArenaProposeGenerator.java` | 아레나 토론 참여(네네: SUFFIX + 페르소나 주입 + 상대 글 분리/재반박 락/입장 락) + 발의(코드상 네네 계정) |
| [heartbeat-router-shared.md](heartbeat-router-shared.md) | `heartbeat/HeartbeatService.java`, `routing/RouterService.java`, `routing/SharedPromptContent.java` | 자율 하트비트 발화(솔로/2인/이벤트 모드) + 라우팅 유저 프롬프트 + 공유 prefix 섹션 헤더 |

## 참고
- 페르소나 본문(에무/네네 등 말투·설정)은 `identities/*.md`(외부)에 있고, 일부 생성기(아레나 fight)는 그 본문을 suffix에 직접 주입한다 — 해당 문서에 `{페르소나 정의}` 플레이스홀더로 표기.
- 출력 스키마(JSON 봉투: reasoning/utterance·content·shouldPost 등)는 `output-schema.md` 또는 각 생성기 인라인에 있음.
