## 출력 JSON 스키마 (반드시 이 형식)

```json
{
  "decision": "single | multi | no_reply",
  "responses": [
    { "character": "<캐릭터id>", "message": "<대사 1~3문장>" }
  ],
  "reasoning": "<라우팅 결정 이유, 1줄>"
}
```

- `decision`: `single` (1명), `multi` (2~7명), `no_reply` (응답 안 함)
- `responses`: `no_reply`면 빈 배열, `single`이면 1개, `multi`면 2~7개
- `character`: 7개 ID 중 하나 (소문자) — `airi/emu/haruka/miku/minori/nene/shizuku`
- 절대 캐릭터 ID 외 다른 값 사용 금지
- 텍스트만 출력. 출력 전후에 markdown 코드 펜스 또는 다른 텍스트 추가 금지
