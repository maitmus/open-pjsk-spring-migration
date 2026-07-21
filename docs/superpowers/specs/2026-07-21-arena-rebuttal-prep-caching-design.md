# 아레나 반박-준비(prep) + 캐시 공유 설계

**작성일:** 2026-07-21
**상태:** 설계(브레인스토밍 산출) — 구현 계획 전 사용자 리뷰 대상

## 목표 (한 문장)

네네 토론(fight)의 **양·질을 올리되**, 실제 게시 전에 "상대 논지 예상 + 반박 리스트"를 만드는 **준비(prep) LLM 콜**을 추가해 — 그 콜이 **아레나 캐시 프리픽스를 데우고**(그동안 uncached였던 것을 실사용), 이어지는 fight가 그 캐시를 읽어 **강한 논변을 저렴하게** 뽑게 한다.

## 배경 — 현재 상태 (측정치)

- **fight 크론**: `0 30 12-18/2` → 12:30·14:30·16:30·18:30 = **4회/일, 2h 간격**.
- **게이트**: `ArenaService.noOpposingSinceMyLastPost(...)` — **결정론 코드**(타임스탬프 비교). 통과 못 하면 `return`(LLM 콜 0 → 캐시 워밍 0). "일방 도배 방지".
- **캐시 실측**: 아레나 fight 콜 `cache_creation=0, cache_read=0, input=8110` → **완전 uncached**. 원인: 공유 블록 `commonBase`가 Haiku 캐시 최소치(2048토큰) 미만이라 `cache_control`이 무시됨. 머슴의 warm 프리픽스(`commonBase+머슴지침`≈15k)엔 머슴 전용 지침이 섞여 아레나가 편승 불가.
- **fightGenerator**: 이미 `(topic, existing, lockedSide, nickname)`를 받고, `{reasoning, side, content, shouldFight}` 봉투를 반환(`shouldFight`는 현재 '극단 부적합'만 판정).
- **발의(propose)**: `proposeCount=0`으로 비활성(채택률 낮아). **본 설계 범위 밖 — 계속 비활성 유지.**
- **계정 제약**: 발의는 계정당 시간당 1건(429). fight는 쿨다운 있음. 본 설계는 fight/prep만 다루며 429는 발의에만 적용.

## 아키텍처 개요

fight 크론 매 틱마다:

```
battle():
  1. phase=BATTLE + 활성 토픽 확인 (기존 가드 유지)
  2. 활성 토픽 + 상대(반대 side) 글이 하나라도 있으면
       → PREP LLM 콜: "상대 논지 예상 + 네네 반박 포인트" 생성   [게이트와 무관]
         · 캐시 프리픽스(commonBase+네네페르소나) 생성/워밍
         · 결과=반박노트(문자열) — 같은 틱 fight에 인메모리 전달
  3. 결정론 게이트 noOpposingSinceMyLastPost(...)   [그대로 유지]
       → skip이면 종료 (prep은 이미 캐시 데움)
  4. FIGHT LLM 콜: 반박노트를 컨텍스트로 사용 + prep이 데운 캐시 read
       → side/content 게시
```

**캐시가 붙는 지점:** 2번 prep이 `commonBase+네네페르소나`(≥2048토큰) 프리픽스를 캐시 생성 → 4번 fight가 **초 단위 뒤**에 같은 프리픽스를 `cache_read`. 즉 fight는 항상 warm 프리픽스를 읽는다(높은 크론 빈도 없이도 fight당 캐시 이득 보장).

## 컴포넌트

### 1. 공유 네네 페르소나 캐시 블록 — `ArenaPersonaBlocks` (신규, 작은 유틸)

**책임:** prep·fight(·향후 propose)가 **바이트-동일**하게 쓰는 `[commonBase][네네페르소나]` 캐시 프리픽스를 한 곳에서 만든다.

**왜 필요:** 현재 두 생성기가 페르소나 헤더를 다르게 조립("특히 말투" vs "주제 선정 안목") → 바이트-비동일 → 캐시 공유 불가. 태스크별 강조 문구는 **캐시 밖(SUFFIX)** 으로 빼고, 캐시 블록은 중립 페르소나 정의로 통일한다.

**인터페이스:**
- `List<PromptBlocks.Block> cachedPrefix()` → `[ Block(commonBase, false), Block(nenePersonaNeutral, true) ]`
  - `commonBase`: 기존 `shared.commonBase()` (작아서 캐시 안 붙지만 프리픽스 선두 유지 — 바이트 정렬용).
  - `nenePersonaNeutral`: `"\n## 너는 쿠사나기 네네 — 아래 정의를 그대로 체화한다\n" + persona.content()` (태스크 강조 문구 **제거**). `cache=true` → cache_control(TTL_1H)이 이 블록 끝에 붙어 `commonBase+nenePersonaNeutral`이 캐시됨.
- 소비 측은 뒤에 **태스크 SUFFIX 블록(cache=false)** 을 붙인다: `cachedPrefix() + [ Block(taskSuffix, false) ]`.

**의존:** `SharedPromptContent`, `PersonaRegistry`.

**검증 포인트(실측 완료):** `commonBase+nenePersonaNeutral`의 토큰 수가 2048 이상이어야 캐시가 붙는다. **네네 페르소나 `identities/nene.md` = 3946자 ≈ 2600~3000토큰**(한국어 토큰 밀도)에 commonBase(~수백 토큰)를 더하면 **캐시 프리픽스 ≈3300토큰+ → 2048 여유 초과, 캐시 부착 확정.** (배포 후 `cache_read>0`로 재확인.)

### 2. `ArenaPrepGenerator` (신규)

**책임:** 활성 토픽 + 현재까지의 상대 글을 받아, **"상대가 이렇게 주장할 것이다 + 네네의 반박 포인트"** 를 짧은 노트로 생성.

**인터페이스:**
- `String generate(Topic topic, List<FightPost> existing, String lockedSide, String selfNickname)`
  - 반환: 반박노트 문자열(불릿 몇 개). 생성 실패/파싱 실패 시 `""`(빈 문자열 — fight는 노트 없이 진행).
- 프롬프트 = `ArenaPersonaBlocks.cachedPrefix()` + `Block(PREP_SUFFIX, false)`, USER=토픽+상대 글 요약.
- `PREP_SUFFIX`: "너는 곧 이 토픽에 [lockedSide] 입장으로 토론한다. 상대(반대편)가 펼칠 주장을 2~4개 예상하고, 각각에 네네다운 반박 포인트를 붙여 불릿으로. 게시물 아님 — 너의 준비 메모."

**의존:** `AnthropicClientWrapper`, `ArenaPersonaBlocks`.

**설계 근거:** fight와 **별개 클래스**로 분리(단일 책임). 단 **캐시 프리픽스는 fight와 공유**(같은 `cachedPrefix()`), SUFFIX만 다름 → prep 콜이 fight가 읽을 캐시를 생성.

### 3. `ArenaFightGenerator` (수정)

**변경점:**
- (a) 프롬프트 조립을 `ArenaPersonaBlocks.cachedPrefix() + [ Block(FIGHT_SUFFIX, false) ]`로 교체(기존 `Block(commonBase,true)+Block(nenePersona+SUFFIX,false)` 대체). → prep과 캐시 프리픽스 공유.
- (b) `generate(...)`에 **반박노트 파라미터 추가**: `generate(Topic, List<FightPost> existing, String lockedSide, String selfNickname, String rebuttalNotes)`. USER 프롬프트에 "네가 준비한 반박 포인트: {rebuttalNotes}" 주입(비어 있으면 생략).
- (c) `shouldFight`·side 락·정규화 등 **기존 로직 유지**(일방 도배 방지는 여전히 ArenaService 결정론 게이트가 담당 — 여기 안 옮김).

**인터페이스(변경 후):** `FightDecision generate(Topic topic, List<FightPost> existing, String lockedSide, String selfNickname, String rebuttalNotes)` — 반환 동일(`FightDecision(side, content)` 또는 null).

### 4. `ArenaService.battle()` (수정)

**변경점(위 아키텍처 2~4):**
- phase/topic 가드 통과 후, **상대 글 존재 시 `prepGenerator.generate(...)` 호출**(게이트 이전, 게이트 무관). 결과 `rebuttalNotes` 보관.
- 기존 `noOpposingSinceMyLastPost` 결정론 게이트 **그대로 유지** → skip이면 여기서 종료(prep은 이미 실행됨).
- 게이트 통과 시 `fightGenerator.generate(..., rebuttalNotes)` 호출.
- **prep 실패는 치명적이지 않음** — `rebuttalNotes=""`로 fight 정상 진행(노트 없이). try/catch로 격리, 실패 로깅.

**prep 실행 조건(확정):** 활성 토픽 + `existing`에 반대 side 글이 1개 이상. (빈 토픽·상대 무발언 → prep 스킵, 낭비 없음.) 게이트(신규 상대 의견 여부)와는 **독립** — 상대가 말한 적 있으면 prep은 돈다.

### 5. 설정 — fight 크론 빈도 (수정)

- `ARENA_FIGHT_CRON` 기본값을 **빈도 상향**: `0 30 12-18/2`(4회) → 예 `0 0,30 12-19 * * *`(12:00~19:30, 30분 간격, ~16틱/일). **configurable** — 배포 시 튜닝.
- 효과: 상대 의견 창을 더 자주 포착 → 게이트 통과 기회↑ → **fight 볼륨↑**. 매 틱 prep이 캐시를 데워 continuity도 향상.
- **주의(ops):** 빈도↑는 sekai-deploy 위험 크론 윈도우와 무관(배포 타이밍은 그 스킬이 판정). 단 계정 쿨다운으로 일부 fight는 서버가 막을 수 있음(정상 skip).

### 6. (선택) arena-state 반박노트 영속 — v1 범위 밖

`ArenaState(date, topicId, side)`에 `rebuttalNotes` 필드를 더해 관찰성(대시보드)·틱 간 재사용을 줄 수 있으나, **v1은 인메모리 same-tick 전달로 충분**(prep→fight 같은 호출). 영속은 후속 개선으로 남김.

## 데이터 흐름

```
fight-cron tick
  → ArenaService.battle()
      status = api.arena/status (phase, topic)
      existing = api.fightPosts(today)
      lockedSide = stateStore.lockedSide(today, topicId)
      ── 상대 글 있음? ──► prepGenerator.generate(topic, existing, lockedSide, nick)
                              └─(LLM, cachedPrefix 생성/워밍)─► rebuttalNotes
      ── noOpposingSinceMyLastPost? ──► (true) skip, 종료
                              (false)
      fightGenerator.generate(topic, existing, lockedSide, nick, rebuttalNotes)
        └─(LLM, cachedPrefix cache_read + FIGHT_SUFFIX + notes)─► FightDecision
      api.fight(...) → 게시, side 락 기록
```

## 캐시 동작(왜 작동하나)

- `commonBase`(작음)는 단독 캐시 안 붙지만, `commonBase+nenePersonaNeutral`(≥2048)에 붙인 `cache_control`이 **그 합쳐진 프리픽스**를 캐시한다.
- prep과 fight는 **동일한 `cachedPrefix()`** 를 앞에 두고 SUFFIX만 다름 → prep 콜이 만든 캐시를 fight가 **cache_read**.
- TTL_1H, prep→fight는 초 단위 → 항상 read 성공. 크론 빈도가 높으면 틱-간(prep→다음 prep)도 read.
- **기대 실측:** 배포 후 fight 콜에서 `cache_read > 0`(현재 0). prep 콜은 첫 생성 시 `cache_creation>0`, 이후 read.

## 비용 트레이드오프 (솔직히)

- **순수 절감 아님.** prep이 콜을 하나 더 늘린다. fight당 비용 ≈ (prep 캐시생성 + fight 캐시read) > 기존 단일 fight(uncached). 대신:
  - 그동안 놀던 **캐시 인프라를 실사용**(원래 요구).
  - **토론 품질↑**(반박 준비).
  - 크론 빈도↑로 **볼륨↑** — 증분 fight는 캐시로 상각되어 비례 이상으로 비싸지진 않음.
- **콜 수 증가**: 활성 토픽이면 매 틱 prep 1콜(최대 ~16/일) + fight. 각 콜은 warm 후 저렴하나 총량은 는다. 활성 토픽 없으면 prep 안 돎(낭비 0).

## 에러 처리

- prep 실패(파싱/네트워크): `rebuttalNotes=""`, fight는 노트 없이 진행. 로깅 후 계속.
- fight 실패/쿨다운: 기존대로 스킵 로깅.
- 캐시 미형성(프리픽스가 2048 미만 판명): 기능은 정상(uncached로 동작), 다만 절감 없음 → 구현 시 실측으로 프리픽스 크기 확인, 부족하면 페르소나 블록에 공유 가능한 안정 텍스트를 더해 임계 넘김(별도 판단).

## 테스트 전략

- **단위**:
  - `ArenaPersonaBlocks.cachedPrefix()`가 prep·fight에서 **바이트-동일 페르소나 블록** 반환(문자열 동일성 assert), 페르소나 블록 `cache=true`.
  - `ArenaFightGenerator`: rebuttalNotes 주입 시 USER 프롬프트에 포함, 빈 노트면 생략.
  - `ArenaPrepGenerator`: PREP_SUFFIX 핵심 문구 포함, 파싱 실패 시 `""`.
  - `ArenaService.battle()`: 상대 글 있으면 prep 호출됨 / 상대 글 없으면 prep 스킵 / 게이트 skip이어도 prep은 호출됨(mock 검증).
- **sim-refine**: prep 산출 반박노트 품질 + 그 노트를 먹인 fight의 논변 품질(네네 톤·논리, 노트 없을 때 대비 개선/과의존 없음). Haiku 실코드 덤프.
- **라이브 검증**: 배포 후 fight 콜 `cache_read>0` 로그 확인, prep 로그 확인, fight 볼륨 증가 관측.

## 범위 밖 / 후속

- 발의(propose) 재개 — 별개 결정, 본 설계는 fight/prep만.
- 반박노트 arena-state 영속 + 대시보드 노출.
- prep을 게이트 skip 틱에도 "무조건" 돌릴지(현 설계: 상대 글 있을 때만) — 관측 후 조정.
- 일방 도배 방지를 LLM으로 이관하는 대안(이번엔 결정론 유지로 확정).
