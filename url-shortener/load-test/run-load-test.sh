#!/usr/bin/env bash
#
# URL 단축 서비스 부하 테스트 하니스
# 1(이웃 정리 안내) + 2(워밍업·DB리셋·N회 median) + 3(코어 핀) 적용.
#
# 사용:   ./load-test/run-load-test.sh
# 옵션(환경변수):
#   PORT=8088 REPEAT=3 WARMUP=1 SEED_COUNT=200 KEEP_INFRA=0 SKIP_PROMPT=0
#
# 코어 배치(12코어): 0-1 OS/이웃 | 2-4 k6 | 5 redis | 6-7 postgres | 8-11 app
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # url-shortener 루트
cd "$DIR"

PORT="${PORT:-8088}"
REPEAT="${REPEAT:-3}"
WARMUP="${WARMUP:-1}"
SEED_COUNT="${SEED_COUNT:-200}"
KEEP_INFRA="${KEEP_INFRA:-0}"
SKIP_PROMPT="${SKIP_PROMPT:-0}"
BASE_URL="http://localhost:${PORT}"
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.loadtest.yml"
RESULT_DIR="load-test/results"
CORES_APP="8-11"
CORES_K6="2-4"
mkdir -p "$RESULT_DIR"

APP_PID=""
cleanup() {
  [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null || true
  if [ "$KEEP_INFRA" != "1" ]; then $COMPOSE down >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT INT TERM

# ── 1. 이웃 프로세스 점검 (죽이지 않고 경고만) ──────────────────────────────
echo "▶ 1. 이웃 프로세스 점검"
NEIGHBORS=$(pgrep -a java 2>/dev/null \
  | grep -iE 'kafka|PropertiesLauncher|jdt\.ls|GradleDaemon|gradle-server' || true)
if [ -n "$NEIGHBORS" ]; then
  echo "  ⚠ 측정 변동을 유발할 수 있는 java 프로세스:"
  echo "$NEIGHBORS" | sed -E 's/(-cp|-classpath|--add-opens).*$/[...]/' | cut -c1-100 | sed 's/^/    /'
  echo "  최상의 재현성을 위해 테스트 동안 이들을 잠시 멈추길 권장합니다 (스크립트는 죽이지 않음)."
  [ "$SKIP_PROMPT" != "1" ] && read -r -p "  계속하려면 Enter, 중단하려면 Ctrl-C ... " _
else
  echo "  이웃 java 프로세스 없음 — 좋습니다."
fi

# ── 3. 인프라 기동 (CPU 핀) ────────────────────────────────────────────────
echo "▶ 인프라 기동 (postgres→cpuset 6,7 / redis→cpuset 5)"
$COMPOSE up -d --wait

JAR=$(ls build/libs/*.jar 2>/dev/null | grep -v plain | head -1 || true)
if [ -z "$JAR" ]; then
  echo "▶ jar 빌드"
  ./gradlew bootJar --no-daemon --console=plain >/dev/null
  JAR=$(ls build/libs/*.jar | grep -v plain | head -1)
fi

echo "▶ 앱 실행 (taskset -c $CORES_APP, -Xmx512m, port $PORT)"
taskset -c "$CORES_APP" java -Xms512m -Xmx512m -jar "$JAR" --server.port="$PORT" \
  > /tmp/url-shortener-app.log 2>&1 &
APP_PID=$!

for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/actuator/health" 2>/dev/null || true)
  [ "$code" = "200" ] && { echo "  앱 UP (${i}s)"; break; }
  sleep 1
done

reset_state() {  # 매 런 동일 상태로 초기화
  docker exec url-shortener-postgres psql -U urlshortener -d urlshortener -q \
    -c "TRUNCATE url; ALTER SEQUENCE url_id_seq RESTART WITH 1;" >/dev/null
  docker exec url-shortener-redis redis-cli FLUSHDB >/dev/null
}

run_k6() {  # $1 = summary json 출력 경로
  taskset -c "$CORES_K6" k6 run -q \
    --summary-export="$1" \
    --summary-time-unit=ms \
    -e BASE_URL="$BASE_URL" -e SEED_COUNT="$SEED_COUNT" \
    load-test/load-test.js >/dev/null 2>&1 || true
}

# ── 2. 워밍업 (결과 버림) ──────────────────────────────────────────────────
if [ "$WARMUP" = "1" ]; then
  echo "▶ 2. 워밍업 1회 (결과 버림)"
  reset_state
  run_k6 /tmp/warmup-summary.json
fi

# ── 측정 N회 (각 런 전 초기화) ─────────────────────────────────────────────
echo "▶ 측정 ${REPEAT}회 (각 런 전 DB/Redis 초기화, k6→cpuset $CORES_K6)"
RP95=(); RP99=(); CP95=(); RPS=(); FAIL=()
for i in $(seq 1 "$REPEAT"); do
  reset_state
  OUT="$RESULT_DIR/run-$i.json"
  run_k6 "$OUT"
  rp95=$(jq -r '.metrics["http_req_duration{scenario:redirect}"]["p(95)"] // 0' "$OUT" 2>/dev/null || echo 0)
  rp99=$(jq -r '.metrics["http_req_duration{scenario:redirect}"]["p(99)"] // 0' "$OUT" 2>/dev/null || echo 0)
  cp95=$(jq -r '.metrics["http_req_duration{scenario:create}"]["p(95)"] // 0' "$OUT" 2>/dev/null || echo 0)
  rps=$(jq -r '.metrics.http_reqs.rate // 0' "$OUT" 2>/dev/null || echo 0)
  fail=$(jq -r '.metrics.http_req_failed.value // .metrics.http_req_failed.rate // 0' "$OUT" 2>/dev/null || echo 0)
  RP95+=("$rp95"); RP99+=("$rp99"); CP95+=("$cp95"); RPS+=("$rps"); FAIL+=("$fail")
  printf "  run %d: %5.0f req/s | redirect p95 %6.1f ms p99 %6.1f ms | 실패율 %s\n" "$i" "$rps" "$rp95" "$rp99" "$fail"
done

median() { printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END{print (NR%2)?a[(NR+1)/2]:(a[NR/2]+a[NR/2+1])/2}'; }

echo
echo "================= 결과 (median of ${REPEAT}) ================="
printf " 처리량        : %.0f req/s\n"  "$(median "${RPS[@]}")"
printf " redirect p95  : %.1f ms\n"     "$(median "${RP95[@]}")"
printf " redirect p99  : %.1f ms\n"     "$(median "${RP99[@]}")"
printf " create   p95  : %.1f ms\n"     "$(median "${CP95[@]}")"
printf " 실패율        : %s\n"          "$(median "${FAIL[@]}")"
echo " 원시 결과     : $RESULT_DIR/run-*.json"
echo "============================================================"
