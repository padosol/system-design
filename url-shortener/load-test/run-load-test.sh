#!/usr/bin/env bash
# URL 단축 부하 테스트 (all-docker): 빌드 → 기동 → k6 실행. 요약은 k6가 콘솔에 출력.
# 종료 시 컨테이너 정리. (남겨두려면 끝에 trap 줄 삭제)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

COMPOSE="docker compose -f docker-compose.yml -f docker-compose.loadtest.yml"
trap '$COMPOSE down' EXIT

./gradlew bootJar --no-daemon --console=plain
$COMPOSE up -d --build app

# 앱 health 대기 (최대 60s)
for _ in $(seq 60); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8088/actuator/health 2>/dev/null)" = "200" ] && break
  sleep 1
done

$COMPOSE run --rm k6 run /scripts/load-test.js
