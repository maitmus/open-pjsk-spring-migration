# 머슴 형제봇 공유 이벤트 기억 (shared events) — 설계

- 날짜: 2026-10-27
- 상태: 사용자 승인(대화 설계 확정, "구현도 문서 정리되는 대로 바로 착수")

## 목적

에무·네네가 글·댓글에서 **둘이 함께 얽힌 일**(원더쇼 일정·같이 하기로 한 약속·둘 다 참여하는 행사)을 지어내면
그걸 공유 파일에 기억해 두고, 며칠 뒤 서로 글/댓글을 쓸 때 날짜·사실이 이어지게 한다.

계기: 10-27 네네 16:00 글 '할로윈 쇼 연습 끝' → 에무 16:30 '의상 못 고르겠어요'("할로윈 쇼까지 나흘")가
피드(최근 10개 ≈ 2시간) 안에 있어 우연히 이어졌다. 하루만 지나면 상대가 지어낸 일정을 다시 볼 길이 없어
날짜가 엇갈리거나 잊힌다.

## 범위 (사용자 확정)

- **둘이 함께 얽힌 일만**. 한쪽 개인사(시험·부활동·'언니 핀')는 제외.
- 추출 방식 = **게시 후 별도 LLM 추출 호출**(생성 프롬프트는 불변).
- 초기값 = **최근 7일 글·형제 댓글 소급 추출**, 결과를 사용자 검토 후 반영.

## 비목표

- 일반 유저와의 일, 개인사 기억.
- 대시보드 표시(마운트만 해 두고 UI는 나중).
- 생성 프롬프트(글/댓글 생성 규칙) 변경 — 주입 섹션 추가 외 규칙 문구는 건드리지 않는다.

## 1. 저장

- 호스트 `/home/maitmus/.openclaw/workspace-cron-worker/shared-events.json` ↔ 컨테이너 `/app/shared-events.json`(rw, single-file bind mount).
  대시보드에도 `/data/shared-events.json:ro`. 배포 inode 검증 목록에 추가.
- 경로 설정: `mersoom.shared-events-file`(기본 `/app/shared-events.json`, env `MERSOOM_SHARED_EVENTS_FILE`).
- 두 봇 공용 1파일. 저장소 빈(`SharedEventStore`) 하나가 `synchronized`로 load-modify-save. 파일 없으면 빈 목록으로 시작.
  저장은 `MersoomStateStore`와 같은 atomic→direct write fallback.
- 형식:

```json
{
  "events": [
    {
      "id": "halloween-show",
      "title": "원더쇼 할로윈 쇼",
      "date": "2026-10-31",
      "status": "upcoming",
      "facts": ["츠카사 흡혈귀 망토, 리허설 중 호박 소품 굴림", "에무 의상: 호박 망토 vs 별 마녀 모자 고민"],
      "createdBy": "nene",
      "updatedAt": "2026-10-27T16:30",
      "sources": ["IHWQCbNy8nisisMUKlMw", "lQJNM89WLGN9ihIYMIOy"]
    }
  ]
}
```

- `date`: 절대 날짜(yyyy-MM-dd) 또는 null(날짜 미정 약속). 상대 표현("나흘 남음")은 추출 시 그날 기준으로 절대화.
- `status`: `upcoming` | `done` | `cancelled`.
- `facts`: 최대 5줄, 넘치면 오래된 줄부터 FIFO. 각 줄 ≤ 80자.
- `sources`: 근거 글/댓글이 달린 글 id, 최대 5개.

## 2. 수명 (`prune(today)`, 로드·저장 시 적용)

- `date` 있음: `today > date + 3일`이면 삭제(지난 일 후속 대화 3일 허용).
- `status` done/cancelled: `today > updatedAt + 3일`이면 삭제.
- `date` 없음 + upcoming: `today > updatedAt + 7일`이면 삭제.
- 활성 상한 8개 — 넘치면 `updatedAt` 오래된 것부터 삭제.

## 3. 추출 (`SharedEventExtractor`)

- **트리거** (`MersoomCitizenEngine`, 게시 성공 직후):
  - 글: 모든 글.
  - 댓글: 대상 글 작성자가 형제(siblingAuthIds)인 최상위 댓글.
  - 대댓글: 받아친 답글 작성자(replyAuthorKey)가 형제인 대댓글.
  - 일반 유저 대상 댓글·대댓글은 추출하지 않는다.
- **입력(USER)**: 오늘 날짜(요일 포함), 작성자(에무/네네), 형제 이름, 현재 활성 이벤트 목록(JSON), 방금 쓴 텍스트,
  댓글이면 원글 제목·본문, 대댓글이면 스레드(작성자: 텍스트 순서).
- **시스템 프롬프트**: 페르소나 없이 추출 전용 고정 지시(캐시 블록 1개). 기준:
  - 에무와 네네 **둘 다 관련된** 일만(둘 다 참여·둘이 약속·원더쇼 공동 일정). 한쪽만의 일·일반 감상은 무시.
  - 기존 이벤트와 같은 일이면 새로 만들지 말고 update(사실 추가·상태·날짜 정정).
  - 상대 날짜 표현은 오늘 날짜 기준 절대 날짜로. 날짜 근거가 없으면 null.
  - 대부분은 `none`이 정상.
  - facts는 며칠 뒤에도 쓸 '정해진 것'(일정·결정·역할/의상·결과)만. 대사·반응·잔소리 같은 대화 한 마디는 기록하지 않는다(10-28 소급 1차에서 대댓글마다 대사가 쌓여 핵심이 FIFO로 밀린 뒤 추가).
- **출력(JSON 1개)**:

```json
{"reasoning":"...","ops":[
  {"op":"add","id":"kebab-id","title":"...","date":"2026-10-31","facts":["..."]},
  {"op":"update","id":"halloween-show","addFacts":["..."],"status":"done","date":null}
]}
```

  `ops: []` = 변화 없음.
- **검증**: id는 `[a-z0-9-]{2,40}`; add의 id가 이미 있으면 update로 취급; update의 id가 없으면 무시;
  date는 `today-7 ≤ date ≤ today+60`만, 아니면 null 처리(add) / 무시(update); title ≤ 30자, fact ≤ 80자, 빈 값 무시.
  파싱 실패·예외는 `WARN` 후 무시(게시 흐름에 영향 없음).
- **로그**: `Shared event added: id=… title=… date=…` / `Shared event updated: id=… status=… facts+N`.
- 모델: 라이브와 같은 `AnthropicClientWrapper.completeJson`(Opus 5.5 low).

## 4. 주입 (`SharedEventPrompt.render`)

코드가 날짜 상대화(오늘·내일·모레·N일 뒤/N일 전·지난 일) + 요일을 계산해 섹션 문자열 생성. 활성 이벤트가 없으면 빈 문자열.

```
## 네네와 얽힌 최근 일 (공유 기억 — 이어 쓸 수 있는 배경일 뿐)
- 원더쇼 할로윈 쇼 — 10-31(토), 나흘 뒤
  · 츠카사 흡혈귀 망토, 리허설 중 호박 소품 굴림
  · 에무 의상: 호박 망토 vs 별 마녀 모자 고민
- 은행나무 길 낙엽 세기 약속 — 날짜 미정
```

- **글 프롬프트**(두 봇): 활성 이벤트가 있으면 시드 섹션 뒤에 섹션 + 가드
  "이번 글 소재로 고를 필요 없음 — 이 일을 쓸 때만 날짜·사실이 여기와 어긋나지 않게."
- **댓글 프롬프트**: 피드에 형제 글이 있거나, 대댓글 후보 중 답글 작성자가 형제인 게 있을 때만 섹션 + 가드
  "형제 글·답글이 이 일을 다룰 때만 맞춰 받을 것. 일반 유저 글 댓글엔 꺼내지 말 것."
- docs/prompts/mersoom-post-ad.md·mersoom-comment.md 손문서에 섹션·가드 문구 미러.

## 5. 소급 (일회성)

- 최근 7일 볼륨 로그(`sekai-router.log` + `*.gz`)에서 시간순으로:
  글(`post created`+`post content`), 형제 대상 댓글(`comment created target_nick=형제` + `target-post` + `content`),
  형제 대상 대댓글(`reply created target_nick=형제` + `reply content`, 원글은 그 글의 post 로그/API).
- 각 항목을 **그 항목 날짜를 today로** 실제 `SharedEventExtractor` + `SharedEventStore` 로직에 순서대로 넣어 누적.
- 일회성 테스트 러너(`SIM_` 환경변수 게이트, 실 API 키)로 실행 → 결과 JSON을 스크래치에 출력 → 사용자 검토 →
  확정본을 호스트 파일에 기록. 러너는 사용 후 삭제(sim 덤프 패턴).

## 6. 검증

- 단위 테스트: 저장소(prune·상한·FIFO·동시성 없는 순차 load/save), 추출 파서·검증(날짜 범위·id·add→update 승격),
  렌더(상대 날짜·요일·빈 목록), 엔진 트리거(글/형제 댓글/형제 대댓글만, 실패 무해), 생성기 주입 조건(댓글: 형제 없으면 미주입).
- sim-refine 게이트(배포 전 필수): 같은 피드로 주입 vs 미주입 비교, 에무·네네 각 2런+.
  판정: ①형제 글이 그 일을 다룰 때 날짜·사실 일관 ②무관 글/댓글에 이벤트 끼어들기 없음 ③글 소재가 이벤트로 쏠리지 않음.
  쏠림이 보이면 글 주입을 일부 슬롯으로 제한하는 안으로 재시도.
- 배포: sekai-deploy 경유, 배포 전 호스트 `shared-events.json` 생성(없으면 docker가 디렉터리로 만듦).

## 위험

- 소재 쏠림(하나뿐인 소재 반복 픽) — sim ③으로 측정, 슬롯 제한 폴백.
- 꼬리 언급(하루카 생일 사례) — 댓글은 형제 맥락에서만 주입 + 가드.
- 추출 오탐(개인사·일반 감상 등록) — 추출 기준 + 소급 결과 사용자 검토로 초기 품질 확인, 이후 점검에서 로그로 관찰.
