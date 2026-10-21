# 머슴 대댓글 (내 댓글에 달린 답글 받아치기) — 설계

2026-10-22 · 에무·네네 공통(캐릭터 특수룰 없음)

## 목적

봇이 남긴 댓글에 누가 답글을 달면, 사람처럼 다시 받아칠 수 있게 한다. 지금은 대댓글 경로가 아예 없다(`createComment` parentId 항상 null).
답글 대응은 **보조 기능**이다 — 새 글 댓글 흐름은 그대로 두고, 같은 판정 안에서 "가끔 자연스럽게 받아치는" 선택지를 하나 더 준다.

성공 기준
- 내 댓글에 달린 답글이 다음 댓글 크론의 후보로 보이고, 고를 만할 때만 대댓글이 달린다(0개가 정상).
- 한 가지(branch)는 depth 5(A-B-A-B-A)를 넘지 않는다 — 상대가 매번 답하는 봇(Maid-Chibi)이어도 핑퐁이 끝난다.
- 기존 품질(당사자성·말투·자연스러움) 회귀 없음, 크론당 요청 증가는 throttle된 소량.

비목표
- 내 *글*에 달린 댓글에 답하기(별개 기능).
- 형제봇 동일 글 겹침 해소(별개 — 대댓글이 상한을 나눠 쓰는 간접효과만).

## 사전 확인된 플랫폼 사실 (2026-10-22 실측)

- **중첩 트리 지원**: `POST /posts/{id}/comments` body `parent_id`=답글 id → 200, 그대로 저장. 웹은 parent_id 재귀 렌더(depth마다 들여쓰기).
- **생성 응답에 댓글 id 없음**: `{"success":true,"ad":"…"}`. → 내 댓글 id를 state에 저장할 수 없으니, 조회 시 `auth_id`로 내 댓글을 식별한다.
- `GET /posts/{id}/comments`의 각 댓글에 `id`·`parent_id`·`auth_id`·`nickname`·`content`·`created_at`·`is_deleted`가 있다.
- 전역 최근 댓글 엔드포인트 없음 → 글 단위 조회만.
- 최근 실측: 봇 댓글 20개 중 5개에 답글, 전부 Maid-Chibi("답글 고마워요! ✨" 정형).

## 규칙 (사용자 확정)

1. **최상위 댓글은 한 글에 봇당 1회** — 기존 `lastCommentIds`/`alreadyCommented` 장치 그대로. 대댓글은 이 규칙에 걸리지 않는다(같은 글에 내 최상위 댓글이 있어도 그 아래 대댓글은 허용).
2. **depth 상한 5**: depth = 루트(최상위) 댓글을 1로, parent 사슬을 따라 센 깊이. 답글 후보는 **그 답글의 depth ≤ 4**일 때만 → 내 대댓글은 최대 depth 5. depth 5 이상 노드 아래 새 답글은 무조건 무시.

## 구성

### 1) 수집 — `MersoomReplyCollector` (신규, 댓글 크론에서만)
- 입력: state `lastCommentIds`(최신순). **최근 12시간 내** 항목의 고유 postId 중 **최신 6개**.
- 각 글 `commentsOf(postId)` — 호출 사이 `apiRateLimitSleepMs`(현재 1s)보다 넉넉히 **3초** 간격(6/14 버스트 차단 재발 방지). 실패한 글은 건너뛴다(INFO 로그, 크론 계속).
- 글별 트리 구성(`id → node`, `parent_id`로 연결, `is_deleted` 제외). **내 댓글 = `auth_id == profile.auth().authId()`**.
- 후보 조건 (답글 R 각각):
  - R의 parent가 **내 댓글**이고, R 작성자가 내가 아님.
  - R에 내가 아직 답하지 않음(R의 자식 중 내 댓글 없음).
  - `depth(R) ≤ 4`.
  - R 작성자(identityKey: auth_id 없으면 ip)가 fixedAvoid 아님.
- 후보는 R의 `created_at` 최신순, **최대 4개**로 자른다.
- 출력 `ReplyCandidate(postId, postTitle, thread[루트→R 경로의 (작성자, 본문)], replyToId=R.id, replyAuthorKey, replyAuthorNick)`.
- 글 제목: `commentsOf`는 제목을 주지 않으므로 피드에 있는 글이면 피드 제목을, 없으면 `"(예전 글)"`을 쓴다(추가 GET 없음 — 스레드 본문으로 충분).

`Comment` DTO에 `authId`(snake `auth_id`)·`isDeleted` 필드 추가.

### 2) 프롬프트 — `MersoomCommentGenerator`
- USER 프롬프트, 피드 목록 뒤에 후보가 있을 때만:
  ```
  ## 내 댓글에 달린 답글 (받아칠지는 자유 — 0개가 정상)
  {"r":1,"post":"<제목>","thread":[{"by":"나","text":"…"},{"by":"Maid-Chibi","text":"…"}]}
  ```
  thread는 루트→R 경로(최대 4줄), 각 text는 기존 피드 본문 자르기 규칙과 동일 길이로 트림.
- 댓글 기준에 한 줄: **답글이 고마움 인사·맞장구뿐이면 굳이 받지 않는다. 받을 땐 그 답글에 이어 말하듯 짧게 — 대화가 이어질 거리가 있을 때만.** (문구는 sim에서 확정)
- 출력 스키마: comments 항목은 `targetIndex`(피드 글) **또는** `replyIndex`(답글 후보 r) 중 하나. 합산 상한 `MAX_COMMENTS`(4) 그대로. 시스템 프롬프트(`mersoom-instructions*.md`)의 출력 형식 설명도 갱신.
- 형제봇 답글(예: 네네가 에무 댓글에 답)도 같은 후보로 들어온다 — 별도 분기 없음. 형제 관계줄은 기존 `relationshipLine` 재사용.

### 3) 파서 — `MersoomFeedJudgmentParser`
- `Comment(Integer targetIndex, Integer replyIndex, String utterance)`. 둘 다 있거나 둘 다 없으면 버림. 부분 복구 경로(스트리밍)에도 `replyIndex` 인식.

### 4) 게시 — `MersoomCitizenEngine.runComment`
- `replyIndex` 항목 → 후보 매핑 → `createComment(auth, postId, parentId=R.id, …)`.
- eligibility: 토픽 게이트(글 본문 기준은 없으므로 R 본문에 `CommentTopicGate` 적용), fixedAvoid, 같은 크론 내 같은 R 중복 ❌. **`commentedIds`(최상위 1회) 검사는 대댓글엔 적용 안 함.**
- 성공 시 `lastCommentIds`에 기록(`CommentRef(postId, ts, text)` — 최근 내 댓글 기억에도 들어감). 평판 note는 기존과 같은 중립 마커(`… 답글에 … 대댓글`).
- 로그: `[emu] Mersoom reply created: post=… parent=… target_nick=… content_len=…` + content 라인(기존 형식 미러).
- 대댓글엔 투표 없음.

### 5) 순서·비용
- 수집은 피드 수집 직후, LLM 판정 전. 크론당 추가 요청 ≤ 6(GET) + 대댓글 게시(상한 내).
- 프롬프트 증분: 후보 최대 4 × 짧은 스레드 ≈ +0.5~1k 입력 토큰. 캐시 블록 변화 없음(USER 영역).

## 오류 처리
- `commentsOf` 실패/타임아웃 → 그 글만 스킵. 전부 실패해도 기존 댓글 흐름 정상.
- 대댓글 POST 400(욕설필터 오탐 등) → 기존 댓글 실패와 동일 처리(현행 catch 로그, 다음 항목 계속).
- 답글 R이 그새 삭제됨 → 서버 오류면 위와 동일.

## 테스트
- 단위: 트리 depth 계산, depth 4 답글은 후보·depth 5 이하 무시, 내가 이미 답한 답글 제외, 내 댓글 아닌 parent 제외, fixedAvoid 제외, 12h·6개 창, 후보 4개 상한, is_deleted 제외.
- 생성기: 후보 있을 때만 섹션 생성, `"r":` 형식·"나" 표기, 출력 상한 합산.
- 파서: `replyIndex` 정상/부분 복구/둘 다 있는 항목 버림.
- 엔진: replyIndex → parentId=R.id로 `createComment`, 최상위 1회 규칙 미적용, 상한 4 합산.
- sim-refine 게이트(필수): 실제 Maid-Chibi 답글 스레드(고마움 정형) + 형제봇 답글 + 이어 말할 거리가 있는 답글 케이스를 실제 코드 덤프로 Opus low 2회. 판정: 정형 감사엔 넘기는지, 받을 땐 자연스러운지, 피드 글 댓글 품질 무회귀.
- docs 손문서(`docs/prompts/mersoom-comment.md`) 동기화.
