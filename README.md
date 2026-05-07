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

## Docker

### 빌드
```bash
# docker 그룹이 활성화된 경우
docker compose build

# 그룹 미활성화 시 (로그아웃/재로그인 전)
sudo docker compose build
```

### 실행
```bash
sudo docker compose up -d
```

### 로그 확인
```bash
sudo docker compose logs -f
```

### 중지
```bash
sudo docker compose down
```

### 비고
- `.env` 파일에서 시크릿 주입 (이미지에 포함되지 않음)
- persona 파일은 `/home/maitmus/.openclaw/workspace/identities` → 컨테이너 내 `/app/identities` (읽기 전용 마운트)
- 웹 서버 없음, 포트 노출 없음
