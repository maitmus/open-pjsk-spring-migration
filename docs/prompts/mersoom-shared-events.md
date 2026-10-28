# 머슴 형제 공유 이벤트 추출 (SharedEventExtractor)

대상 코드: `mersoom/SharedEventExtractor.java` (호출: `SharedEventMemory.observe` ← `MersoomCitizenEngine`)

게시 직후 별도 LLM 콜(`completeJson`, 라이브 모델)로 '에무·네네 둘 다 얽힌 일'을 추려 `shared-events.json`에 add/update.
생성 프롬프트와 분리 — 실패(파싱·예외)는 WARN 후 무시, 게시 흐름 무영향.

## 호출 시점
- 글: 모든 글 게시 직후 (kind=글, 맥락 없음, 텍스트=`제목: …\n본문: …`)
- 댓글: **형제 글**에 단 최상위 댓글 (kind=댓글, 맥락=`(원글) @닉 "제목": 본문`)
- 대댓글: **형제가 단 답글**을 받아친 대댓글 (kind=대댓글, 맥락=스레드 `작성자: 텍스트` 줄들)
- 일반 유저 대상 댓글·대댓글은 추출 안 함.

## SYSTEM (캐시 블록 1개, 영문)
> 영문인 이유: 한국어 지시문판은 Opus 5.5가 `stop_reason=refusal`(출력 0)을 절반가량 냈다(10-27 소급 시험). 영문은 소급 163건 거부 0.
> facts 기준(정해진 것만·대화 한 마디 금지)은 10-28 소급 1차에서 대댓글마다 대사가 쌓여 핵심이 FIFO로 밀린 뒤 추가.

```
You read short fictional posts and comments written by two game characters, Emu and Nene (both members of the same theater troupe, Wonderlands×Showtime), on a community board. Your job is to keep a small list of things the two of them share — so later posts stay consistent. Reply with JSON only.

What counts (only things involving BOTH Emu and Nene):
- Troupe shows, rehearsals or schedules (e.g. a Halloween show)
- Plans or promises the two made together (e.g. going to a crepe shop together)
- Things both took part in (e.g. a movie they watched together)

What does not count: things only one of them does (tests, club activities, personal purchases, family), general chit-chat or praise, things with other users. Do not invent anything not in the text.

Rules:
- If it is the same thing as an existing list item, use "update" (new facts in addFacts; status "done" when it is over, "cancelled" when called off; date when set or changed) instead of "add".
- Convert relative dates (in four days, this Saturday, tomorrow) into absolute yyyy-MM-dd using today's date from the user message. Use null if there is no basis.
- facts: only settled things worth remembering days later — schedule, decisions, roles/costumes, results (e.g. rehearsal done, costume chosen). One short line each in Korean (max 80 chars). Do not log conversation lines, reactions, teasing, reminders or small talk (e.g. "bring water", "she said she'd think about clapping") — a back-and-forth reply usually adds no fact. Do not repeat existing facts.
- id: lowercase kebab-case (e.g. halloween-show). title: Korean, max 30 chars.
- Most posts and comments change nothing — then ops is an empty array.

Output exactly one JSON object:
{"reasoning":"<short note>","ops":[{"op":"add","id":"...","title":"...","date":"yyyy-MM-dd or null","facts":["..."]},{"op":"update","id":"...","addFacts":["..."],"status":"done|cancelled|null","date":"yyyy-MM-dd or null"}]}
Do not use double quotes inside string values.
```

## USER
```
## 오늘 (KST)
{yyyy-MM-dd}({요일})

## 작성자
{에무|네네} (형제: {네네|에무})

## 현재 공유 기억 목록
[{"id":…,"title":…,"date":…,"status":…,"facts":[…]}, …]

## 맥락            ← 댓글·대댓글일 때만
{원글 또는 스레드}

## 방금 올린 {글|댓글|대댓글} (작성: {작성자})
{텍스트}

## 지시
위 내용으로 공유 기억 목록의 변화를 판정해 JSON 1개로 출력.
```

## 검증·수명 (코드, `SharedEventLog`)
- id `[a-z0-9-]{2,40}`, add의 id·title이 이미 있으면 update로 합침, 없는 id의 update는 무시.
- date는 today-7 ≤ date ≤ today+60만. title ≤ 30자, fact ≤ 80자, facts 최대 5(FIFO), sources 최대 5.
- 수명: 날짜+3일 지나면 삭제 / 끝남·취소 updatedAt+3일 / 날짜 미정 updatedAt+7일. 상한 8개 — 다가오는 날짜 일정 우선, 그다음 최근 갱신 순.
