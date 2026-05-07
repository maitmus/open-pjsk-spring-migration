# open-pjsk-spring-migration

OpenClaw 기반 sekai-router 에이전트의 Spring Boot 마이그레이션. Discord 메시지를 받아 Project Sekai 캐릭터로 라우팅 + 대리 발화.

## 개요
- 라우터 봇 1개 (LLM 호출) + 캐릭터 봇 7개 (대리 발화 대상)
- Anthropic Claude Haiku 4.5
- 자세한 마이그레이션 컨텍스트: [open-pjsk/migration-handoff.md](https://github.com/maitmus/open-pjsk/blob/main/migration-handoff.md)

## 빌드
```
./gradlew build
```

## 실행
```
./gradlew bootRun
```
