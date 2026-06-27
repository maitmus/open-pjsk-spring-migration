# 머슴/아레나 프롬프트 격리 설계 (per-function prompt isolation)

작성일: 2026-06-28

## 배경 / 문제

현재 `SharedPromptContent.build()`가 **하나의 공유 prefix**(USER.md + 7페르소나 + GRADES 21KB + events + 출력 공통 규칙, 총 ~85KB)를 만들어 **모든 경로**(라우터 발화·하트비트·머슴·아레나)가 system 블록1로 동일하게 사용한다. 각 경로는 자기 지침을 suffix로 붙인다.

충실 프롬프트 재현(`faithful_*` 하네스, 2026-06-28)으로 확인된 두 문제:

1. **Dilution(룰 묻힘)**: 머슴 에무 댓글이 라이브에서 멀티비트 정형 아크(에코→자기화→평결)로 나오는 원인이, 머슴에 *불필요한* 콘텐츠(타 5페르소나 + GRADES 21KB + 발화용 톤 폭발 예시)가 87KB로 [형식]"한 비트" 룰을 희석시켜서임. 짧은(paraphrase) sim에선 룰을 잘 따랐으나(가짜 GREEN), 충실 87KB 프롬프트에선 4/4 멀티비트 재현됨. **캐싱은 비용만 줄일 뿐 모델은 87KB 전부 attend하므로 dilution을 못 푼다 → 실제로 덜 보내야(slim) 함.**
2. **비용**: 머슴은 호출이 많음(댓글 :15/:45 ≈ 22/일 + 글 8/일). 매 호출이 85KB를 읽음. 또 아레나 fight 블록은 2h 주기 > 1h TTL이라 캐싱하면 매번 cache_creation(1h 캐시 write ≈ 2배)만 물고 읽기 상각이 안 됨.

## 목표 / 비목표

**목표**
- 각 기능이 *필요한 프롬프트만* 받도록 격리 → 머슴 모델 읽는 양 87→~37KB(dilution↓).
- 캐시 비용 개선(발화 페르소나 중복 제거, 아레나 uncached).
- commonBase 공유 캐시 유지(격리해도 캐시 효율 안 깨짐).

**비목표(이번 범위 밖)**
- 머슴 에무 멀티비트 *품질 교정*. 이 격리(깨끗한 base) **후** 별도로, 충실 repro 하네스에 대고 진행.

## 결정 사항

- **형제봇 granularity**: 본인 전체 + **형제봇 최소**(호칭·관계·말투 몇 줄). 머슴은 한 캐릭터로만 생성되니 상대 캐릭터 전체 페르소나(대사 예시 등) 불필요.
- **범위**: 블록 재구조 + USER.md→발화 전용 이동 + 아레나 tail uncached를 **한 리팩터로**(셋이 맞물림).
- **구조 접근**: A+C — `SharedPromptContent`가 조각(commonBase·personaSection) 노출 + 각 빌더가 명시적 조립, 형제봇 최소는 **손작성 작은 리소스**.
- **PromptBlocks**: 2필드 record → **캐시 플래그 붙은 블록 리스트**로 격상(발화 페르소나 공유 + 아레나 uncached 동시 지원).

## 블록 레이아웃

`system = 블록 리스트` (각 블록 독립 cache_control)

| 기능 | 블록 구성 (각 cache 여부) |
|---|---|
| 라우터 발화 | [commonBase ✅·전경로 공유] [USER+7페르소나+GRADES ✅·발화 공유] [baseInstr+outputSchema ✅] |
| 하트비트 발화 | [commonBase ✅·전경로 공유] [USER+7페르소나+GRADES ✅·발화 공유] [baseInstr ✅] |
| 머슴 에무 | [commonBase ✅·전경로 공유] [에무 전체 + 네네-최소 + 머슴 에무 지침 ✅] |
| 머슴 네네 | [commonBase ✅·전경로 공유] [네네 전체 + 에무-최소 + injection + 머슴 네네 지침 ✅] |
| 아레나(발의·토론) | [commonBase ✅·전경로 공유] [네네 전체 + 아레나 지침 ❌ uncached] |

- **commonBase = events.json + 출력 공통 규칙**(전 경로 바이트 동일 → 캐시 1개 공유, 발화·머슴이 15~30분 읽어 종일 warm).
- **USER.md**(운영자 MaiT 정보)는 봇이 MaiT를 상대하는 **발화에만** 필요 → 발화 블록2로 이동(머슴·아레나엔 미포함).
- 라우터·하트비트의 발화 페르소나 블록은 **바이트 동일** → 캐시 1개 공유(중복 제거).

## API / 컴포넌트

**`SharedPromptContent`** — 조각 노출:
```
String commonBase()                              // events.json + 출력 공통 규칙
String personaSection(Set<CharacterId> ids,      // "## 페르소나 정의" + 지정 캐릭터 전체
                      boolean includeUser,        //   includeUser → USER.md 앞에, includeGrades → GRADES 뒤에
                      boolean includeGrades)
```
- 기존 `SharedPromptContent.build()` 제거 → 발화 빌더가 `personaSection(전체=CharacterId 전부, user=true, grades=true)`로 대체(내용 동일). (※ `MersoomPromptBuilder.build()/build(profile)`는 별개 메서드 — 유지하되 내부 조립만 교체.)

**형제봇 최소 리소스(C)** — 손작성 상수/리소스 `siblingMinimal(CharacterId)` (~5줄): 상대의 호칭·관계·핵심 말투만. 예) 네네: "원더랜즈×쇼타임 동료. 에무→'네네쨩'+반말. 까다롭고 직설·츤데레." / 에무: 역방향. (GRADES 룩업 대신 명시 — 현 댓글 생성기가 siblingCall 하드코딩하는 이유와 동일.)

**`PromptBlocks`** — 리스트화:
```
record Block(String text, boolean cache)
record PromptBlocks(List<Block> blocks)
```
- `AnthropicClientWrapper.buildSystemBlocks`: 각 Block을 TextBlockParam으로, `cache=true`일 때만 `cacheControl(TTL_1H)` 적용. `cache=false`면 일반 블록.

## 소비자 변경

- **SystemPromptBuilder**(라우터): `[commonBase] [personaSection(ALL,user=t,grades=t)] [baseInstr+outputSchema]` 모두 cache=true.
- **HeartbeatPromptBuilder**: `[commonBase] [personaSection(ALL,user=t,grades=t)] [baseInstr]` 모두 cache=true. (발화 페르소나 블록을 라우터와 바이트 동일하게 emit.)
- **MersoomPromptBuilder**:
  - `build()`(에무): `[commonBase] [personaSection({EMU},f,f) + siblingMinimal(NENE) + emuInstructions]` cache=true.
  - `build(profile)`(네네): `[commonBase] [personaSection({NENE},f,f) + siblingMinimal(EMU) + injection + neneInstructions]` cache=true.
- **ArenaProposeGenerator / ArenaFightGenerator**: `[commonBase] [personaSection({NENE},f,f) + SUFFIX]` — **2번째 블록 cache=false**. 기존의 별도 `nenePersona` append 제거(이제 personaSection에서 옴, 중복 해소).

## 캐시 동작

- commonBase: 전 경로 공유 캐시 1개, 종일 warm(발화·머슴 잦은 읽기로 TTL 계속 리셋).
- 발화 페르소나 블록: 라우터·하트비트 공유 1개, 발화창(09–20) warm.
- 머슴 에무/네네 블록: 각 1개, 머슴창(09–19) warm. **읽는 양 87→37KB.**
- 아레나 블록: uncached(1.0배) — 2h>1h TTL이라 캐싱 시 매 fight 2배 write만 물고 상각 0 → 안 캐싱이 절반 비용.
- 매너타임(20:00–08:30) 무읽기 → overnight cold → 아침 첫 크론 1회 cold-start(현 동작과 동일).

## 테스트 / 검증

1. **단위테스트**:
   - `personaSection` 선택 정확성: 머슴 블록에 GRADES·타 페르소나 **없음**, 발화 블록에 전 페르소나+GRADES **있음**.
   - 아레나 2번째 블록 `cache=false`.
   - 라우터·하트비트 발화 페르소나 블록 **바이트 동일**.
   - `siblingMinimal`이 머슴 블록에 포함.
   - `./gradlew test` 그린.
2. **충실 repro 무회귀 게이트(배포 전)**: slim 프롬프트로 머슴 에무·네네 + 아레나 네네를 충실 sim → 캐릭터·말투·동아리·형제봇 관계 안 깨짐 확인(품질 *교정* 아님, *무회귀* 확인). 발화는 내용 무변경이라 스킵.
3. **배포 후 점검**: 머슴 글/댓글·아레나 라이브 정합 + 모델 읽는 양 감소 확인.

## 리스크 / 가드

- 머슴/아레나에서 타 캐릭터·GRADES 참조가 깨질 위험 → 에무·네네 본인 페르소나에 원더쇼 동료 정보 있음 + 형제봇 호칭은 user 프롬프트 하드코딩 + siblingMinimal 한 줄. **저위험**, 충실 repro로 확인.
- 블록 리스트화는 `PromptBlocks`·`buildSystemBlocks`·6개 빌더/테스트 동시 변경 → 단위테스트로 커버.
- 캐시 churn: 배포 시 재생성 대상이 commonBase·발화·머슴 블록으로 늘지만 각 작아짐. 아레나는 uncached라 churn 무관.

## 작업 순서(구현 계획에서 상세화)

1. `PromptBlocks` 리스트화 + `buildSystemBlocks` cache 플래그 처리.
2. `SharedPromptContent`에 `commonBase()`·`personaSection(...)` 추가, `build()` 제거.
3. `siblingMinimal` 리소스/상수.
4. 6개 소비자(System/Heartbeat/Mersoom×2/Arena×2) 블록 조립 교체.
5. 단위테스트 갱신/추가 → `./gradlew test`.
6. 충실 repro 무회귀 게이트 → 커밋 → 배포 결정.
