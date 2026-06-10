#!/usr/bin/env bash
# 예약 재시작 + 검증 (at 잡으로 세션 무관 실행). 무거운 주제 댓글 스킵(1f67668) 배포용.
set -uo pipefail
LOG=/tmp/sekai-scheduled-restart.log
PROJ=/home/maitmus/projects/open-pjsk-spring-migration
{
  echo "=== scheduled restart @ $(TZ=Asia/Seoul date '+%F %T %Z') ==="
  cd "$PROJ" || { echo "FAIL: cd $PROJ"; exit 1; }
  echo "[git] $(git log --oneline -1)"
  docker compose down && docker compose up -d --build
  sleep 5
  echo "[ps] $(docker ps --format '{{.Names}} {{.Status}} {{.CreatedAt}}' | grep -i sekai)"
  echo "[verify] CommentTopicGate in deployed jar:"
  docker cp sekai-router:/app/app.jar /tmp/deployed.jar 2>/dev/null \
    && (unzip -l /tmp/deployed.jar | grep -i CommentTopicGate || echo "  MISSING CommentTopicGate")
  echo "[boot] waiting for app start..."
  for i in $(seq 1 40); do
    if docker logs sekai-router 2>&1 | grep -q "Started SekaiRouterApplication"; then
      docker logs sekai-router 2>&1 | grep "Started SekaiRouterApplication" | tail -1
      break
    fi
    sleep 3
  done
  echo "[errors] $(docker logs sekai-router 2>&1 | grep -cE ' ERROR ')"
  echo "=== done @ $(TZ=Asia/Seoul date '+%F %T %Z') ==="
} >>"$LOG" 2>&1
