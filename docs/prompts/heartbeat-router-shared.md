# 하트비트 / 라우팅 / 공유 prefix — 인라인 프롬프트 스냅샷

> 이 문서는 코드에서 추출한 스냅샷이다. 코드가 원본(source of truth)이며, 프롬프트 변경 시 갱신 필요.

추출 대상 파일과 인라인 프롬프트 보유 여부:

| 파일 | 인라인 프롬프트 | 비고 |
|---|---|---|
| `heartbeat/HeartbeatService.java` | **있음** | 모드별 user prompt 4종 + 출력 스키마/최근 발화 블록 (전부 인라인) |
| `heartbeat/HeartbeatPromptBuilder.java` | 없음 | system 본문은 `resources/prompts/heartbeat-base-instructions.md`에서 로드 — 인라인 아님 |
| `routing/RouterService.java` | **있음** | user prompt 조립(채널 발화 이력·강제 응답자·판단 요청 등 인라인) |
| `routing/SharedPromptContent.java` | **있음 (얇은 섹션 헤더)** | 본문은 USER.md/personas/GRADES.md/events.json에서 로드, 인라인은 섹션 헤더 문자열뿐 |
| `routing/SystemPromptBuilder.java` | 없음 | system 본문은 `resources/prompts/router-base-instructions.md` + `output-schema.md`에서 로드 — 인라인 아님 |
| `routing/AnthropicClientWrapper.java` | 없음 | 프롬프트 조립/캐시(TTL_1H 2블록 레이아웃)만 담당, 인라인 프롬프트 텍스트 없음 |

## 프롬프트 조립 개요 (시스템 vs 유저)

두 경로(하트비트·라우팅) 모두 system 프롬프트는 `PromptBlocks(sharedPrefix, pathSuffix)` 2블록 구조이며, `AnthropicClientWrapper`가 각 블록에 `cache_control TTL_1H`를 붙여 전송한다. sharedPrefix는 양 경로가 byte-identical이라 prefix 캐시를 공유한다.

- **sharedPrefix** = `SharedPromptContent.build()` (USER.md + 페르소나 정의 + GRADES.md + events.json)
- **pathSuffix (하트비트)** = `resources/prompts/heartbeat-base-instructions.md`
- **pathSuffix (라우팅)** = `resources/prompts/router-base-instructions.md` + `resources/prompts/output-schema.md`

아래에 문서화하는 인라인 문자열은 모두 **user 프롬프트**(또는 sharedPrefix의 섹션 헤더)이며, system 본문(resources/prompts/*.md)은 이 문서 범위 밖이다.

---

# 일반 하트비트 (HeartbeatService)

자율 발화 user 프롬프트는 전부 `HeartbeatService.java`에 인라인 문자열로 조립된다. system 프롬프트(본문)는 `HeartbeatPromptBuilder` → `heartbeat-base-instructions.md`에서 로드되며 인라인 아님.

세 갈래 모드가 있고(솔로 / 2인 대화 / 이벤트), 모든 user 프롬프트 끝에 공통으로 **출력 형식 블록**(`outputSchemaBlock()`)과 **최근 발화 이력 블록**(`recentUtterancesBlock()`)이 append된다.

동적 치환값 placeholder:
- `{발화자}` = `speaker.name().toLowerCase()` (CharacterId 소문자명, 예: `miku`)
- `{동료}` / `{직전 발화자}` = `partner` / 상대 캐릭터 소문자명
- `{시간 블록}` = `timeOfDayLabeler.promptBlock(now)` (시간대 라벨 블록, 별도 컴포넌트가 생성)
- `{토픽 시드}` = `seedPicker.pickTopic(speakerType)`
- `{대화 패턴 시드}` = `seedPicker.pickDialoguePattern()`
- `{직전 대사}` = 첫 발화 결과 텍스트
- `{이벤트 라벨}` / `{이벤트 종류}` = `override.label()` / `override.kind()`

## 모드: 자율 발화 (솔로) — `executeNormalHeartbeat()` non-dialogue 분기

> ```
> ## 모드
> 자율 발화 (솔로)
> ## 발화자
> {발화자}
> {시간 블록}
> ## 오늘의 토픽 시드 (이 각도에서 발화)
> {토픽 시드}
> ## 지시
> {발화자}이(가) 채널에 자기 일상/감상/취미/근황을 자연스럽게 한 마디 한다. **위 토픽 시드 각도를 살려** 1~3문장.
> ```
> *(이어서 `outputSchemaBlock()` + `recentUtterancesBlock()` append)*

조립 주의: 코드상 `"...자기 일상/감상/취미/근황을 자연스럽게 한 마디 한다. **위 토픽 시드 각도를 살려** 1~3문장."` 문장에서 `{발화자}`가 `## 지시\n` 직후와 "이(가)" 사이에 두 번째로 들어간다.

## 모드: 자율 발화 (2인 대화 — 첫 발화) — dialogue 분기 firstUser

> ```
> ## 모드
> 자율 발화 (2인 대화 — 첫 발화)
> ## 발화자
> {발화자}
> ## 동료
> {동료}
> {시간 블록}
> ## 오늘의 토픽 시드
> {토픽 시드}
> ## 첫 발화 패턴 시드 (이 패턴으로 문장 시작)
> {대화 패턴 시드}
> ## 지시
> {발화자}이(가) {동료}에게 채널에서 가볍게 말을 건다. **위 토픽 시드와 패턴 시드를 반영**해 1~2문장. GRADES.md 호칭/존댓말 매트릭스 준수.
> ```
> *(이어서 `outputSchemaBlock()` + `recentUtterancesBlock()` append)*

## 모드: 자율 발화 (2인 대화 — 응답) — dialogue 분기 secondUser

> ```
> ## 모드
> 자율 발화 (2인 대화 — 응답)
> ## 발화자
> {동료}
> ## 직전 발화자
> {발화자}
> ## 직전 대사
> {직전 대사}
> {시간 블록}
> ## 지시
> {동료}이(가) 위 대사에 자연스럽게 반응한다. GRADES.md 호칭/존댓말 매트릭스 준수. 1~2문장.
> ```
> *(이어서 `outputSchemaBlock()` + `recentUtterancesBlock()` append)*

주의: 응답 모드에서 `## 발화자`는 `partner`(=`{동료}`), `## 직전 발화자`는 `speaker`(=`{발화자}`)다.

## 모드: 자율 발화 (이벤트) — `executeEventHeartbeat()`

> ```
> ## 모드
> 자율 발화 (이벤트)
> ## 이벤트
> {이벤트 라벨} ({이벤트 종류})
> ## 발화자
> {발화자}
> {시간 블록}
> ## 지시
> 오늘 이벤트와 연결되는 자연스러운 한 마디. 1~3문장.
> ```
> *(이어서 `outputSchemaBlock()` + `recentUtterancesBlock()` append)*

`{이벤트 라벨}` = `override.label()`, `{이벤트 종류}` = `override.kind()`.

## 형식: 출력 형식 블록 (`outputSchemaBlock()` — 모든 모드 공통)

모든 하트비트 user 프롬프트 끝에 append된다. reasoning과 utterance를 분리해 LLM 메타 사고가 발화로 새는 것을 차단.

> ````
>
> ## 출력 형식 (정확히 이 JSON, 다른 텍스트 금지)
> ```json
> {
>   "reasoning": "<토픽-페르소나 조정 등 메타 사고를 여기에. 비워도 됨>",
>   "utterance": "<캐릭터가 채널에 발화하는 1인칭 텍스트만. 메타 설명·자기 분석·3인칭 자칭·\"미쿠는 ...\" 같은 토픽 처리 자문 금지>"
> }
> ```
> - utterance 필드는 디스코드 채널에 그대로 노출되니, 캐릭터의 입에서 나오는 발화 그 자체여야 함.
> - 토픽 시드가 페르소나와 안 맞으면 reasoning에 그 사고를 적고, utterance는 페르소나 권장 소재로 자연스럽게 변환해서 작성.
> ````

(코드상 `\"미쿠는 ...\"`는 자바 문자열 이스케이프이며 프롬프트에는 `"미쿠는 ..."`로 들어간다.)

## 형식: 최근 발화 이력 블록 (`recentUtterancesBlock()` — 모든 모드 공통)

`state.recentUtterances()`가 비어 있으면 빈 문자열(append 없음). 비어 있지 않으면 헤더 + 발화 목록 + 회피 지시를 붙인다.

헤더:

> ```
>
>
> ## 최근 발화 이력 (반복 회피용 — 오래된 → 최근)
> ```

발화 목록 각 줄 포맷 (`{발화자}` = 해당 발화 화자 소문자명, `{발화 한 줄}` = 개행 제거된 본문):

> ```
> - [{발화자}] {발화 한 줄}
> ```

목록 뒤 회피 지시(말미):

> ```
>
> 위 발화들과 **소재·토픽·문장 시작 패턴이 겹치지 않도록** 다른 각도로 발화. 특히 같은 캐릭터의 직전 발화에서 사용한 시그니처 소재(예: 붕어빵·조깅·대전 게임·화과자·이미지 트레이닝·산책·새 곡 등)는 의식적으로 회피하고 페르소나 안에서 다른 면을 보일 것.
> ```

---

# 라우팅 (RouterService)

라우팅 system 프롬프트(본문)는 `SystemPromptBuilder` → `router-base-instructions.md` + `output-schema.md`에서 로드 — 인라인 아님. 아래는 `buildUserPrompt()`가 조립하는 **user 프롬프트** 인라인 문자열이다.

동적 치환값:
- `{오늘 날짜}` = `LocalDate.now(Asia/Seoul)` (events.json 생일/기념일 매칭용)
- `{채널 최근 발화}` = `request.recentTurns()` 각 항목 `{화자}: {내용}` 줄 (없으면 `(없음 — 새 대화)`)
- `{직전 응답자}` = `request.lastSpeaker().name().toLowerCase()` (null이면 줄 생략)
- `{강제 응답자}` = `request.forceCharacter().name().toLowerCase()` (Discord reply 감지 시)
- `{suggestedCharacter}` = `suggested.name().toLowerCase()` (forceCharacter 없고 suggested 있을 때만)
- `{새 메시지}` = `request.newMessage()`

조립 순서대로 인라인 텍스트(placeholder 포함):

> ```
> 오늘 날짜 (KST): {오늘 날짜}
>
> ## 채널 최근 발화
> ```

recentTurns가 비면:

> ```
> (없음 — 새 대화)
> ```

비어있지 않으면 각 턴마다 `{화자}: {내용}` 한 줄씩.

`lastSpeaker`가 있으면:

> ```
>
> 직전 응답자: {직전 응답자}
> ```

**분기 A — `forceCharacter != null` (Discord reply 강제 응답)**:

> ```
>
> ## 강제 응답자 (Discord reply 감지)
> 사용자가 **{강제 응답자}**의 메시지에 답장(reply)했습니다. 반드시 `single` 결정 + character="{강제 응답자}" 만 응답합니다. `multi`/`no_reply` 금지. 다른 캐릭터 응답 금지.
> ```

(코드상 세 조각이 이어붙어 한 문단이 됨: `"사용자가 **...**의 메시지에 답장(reply)했습니다. "` + `"반드시 \`single\` 결정 + character=\"...\" 만 응답합니다. "` + `` "`multi`/`no_reply` 금지. 다른 캐릭터 응답 금지.\n" ``)

**분기 B — `forceCharacter == null && suggested != null`**:

> ```
>
> suggestedCharacter: {suggestedCharacter}
> ```

마지막에 항상 append:

> ```
>
> ## 새 메시지
> {새 메시지}
>
> ## 판단 요청
> 위 라우팅 규칙대로 출력 JSON 스키마 형식으로만 응답하세요.
> ```

발행 직전 백스톱(`applyBackstop`)에서 누수 마커가 잡혀 no_reply로 전환되면 reasoning 끝에 다음 마커 문자열이 인라인으로 붙는다:

> ```
>  [백스톱: 누수 마커 차단]
> ```

---

# 공유 prefix (SharedPromptContent)

system 프롬프트 sharedPrefix를 조립한다. **본문 콘텐츠는 전부 외부 파일에서 로드**되며(USER.md, 페르소나 정의는 PersonaRegistry, GRADES.md, events.json), 인라인 문자열은 각 블록 앞에 붙는 **섹션 헤더**뿐이다.

조립 순서와 인라인 헤더(verbatim):

USER.md 로드 시:
> ```
> ## 사용자 정보 (USER.md)
>
> ```
> *(뒤에 USER.md 내용 + `\n`)*

페르소나 정의 섹션:
> ```
>
> ## 페르소나 정의
>
> ```

각 페르소나 항목 헤더(`appendPersona`, `{id}` = 캐릭터 소문자명, `{표시명}` = displayName):
> ```
> ### {id} — {표시명}
>
> ```
> *(뒤에 `p.content()` + `\n\n`)*

GRADES.md 로드 시:
> ```
>
> ## 호칭·존댓말 매트릭스 (GRADES.md)
>
> ```
> *(뒤에 GRADES.md 내용 + `\n`)*

events.json 로드 시 (json 코드펜스로 감쌈):
> ````
>
> ## 이벤트 캘린더 (events.json)
>
> ```json
> {events.json 내용}```
> ````

(events.json 블록은 `"```json\n" + 내용 + "```\n"` 형태로, 내용과 닫는 펜스 사이 개행은 events.json 파일 내용에 의존.)

---

## 인라인 프롬프트 없음 (참고)

- **HeartbeatPromptBuilder** — system 본문은 `resources/prompts/heartbeat-base-instructions.md`에서 로드. 인라인 프롬프트 텍스트 없음 (suffix 앞에 `"\n"`만 붙임).
- **SystemPromptBuilder** — system 본문은 `resources/prompts/router-base-instructions.md` + `resources/prompts/output-schema.md`에서 로드. 인라인 프롬프트 텍스트 없음 (구분자 `"\n"`, `"\n\n"`만 사용).
- **AnthropicClientWrapper** — 프롬프트 2블록 조립 + `cache_control TTL_1H` + web_search 툴 부착만 담당. 인라인 프롬프트 텍스트 없음. (한국어 주석 `// 빈 블록은 제외...`는 코드 주석일 뿐 프롬프트 아님.)
