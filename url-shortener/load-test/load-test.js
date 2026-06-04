import http from 'k6/http';
import { check } from 'k6';

// 대상 서버 (기본 localhost:8080) / 시드 개수
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SEED_COUNT = Number(__ENV.SEED_COUNT || 200);

const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

export const options = {
  // 요약 트렌드 통계 — 기본(avg,min,med,max,p90,p95)에서 p90을 빼고 p99를 추가.
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  scenarios: {
    // 읽기(리다이렉트) — 설계의 read-heavy 프로파일. 캐시 적중 위주.
    redirect: {
      executor: 'ramping-vus',
      exec: 'redirect',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 50 }, // ramp-up
        { duration: '30s', target: 50 }, // sustain
        { duration: '15s', target: 0 },  // ramp-down
      ],
      gracefulRampDown: '5s',
    },
    // 쓰기(단축) — 소수 VU 상시
    create: {
      executor: 'constant-vus',
      exec: 'create',
      vus: 5,
      duration: '60s',
    },
  },
  thresholds: {
    // 가용성/정확성 게이트 — 운영 SLO의 핵심.
    http_req_failed: ['rate<0.01'],
    // 지연시간 — 아래 값은 WSL2 + Docker dev 환경의 회귀 가드 수준.
    // 운영(전용 인프라) 목표는 훨씬 타이트해야 함: 캐시 히트 리다이렉트 p95 < 50ms.
    'http_req_duration{scenario:redirect}': ['p(95)<350', 'p(99)<600'],
    'http_req_duration{scenario:create}': ['p(95)<400'],
  },
};

// 부하 전에 단축 URL을 미리 만들어 키 목록을 확보한다.
export function setup() {
  const keys = [];
  for (let i = 0; i < SEED_COUNT; i++) {
    const res = http.post(
      `${BASE_URL}/api/v1/urls`,
      JSON.stringify({ longUrl: `https://example.com/seed/${i}` }),
      JSON_HEADERS,
    );
    if (res.status === 201) {
      keys.push(String(res.json('shortUrl')).split('/').pop());
    }
  }
  if (keys.length === 0) {
    throw new Error('시드 실패: 단축 URL을 하나도 만들지 못함');
  }
  return { keys };
}

// 리다이렉트: 302를 따라가지 않도록 redirects:0 (외부 호출 방지)
export function redirect(data) {
  const key = data.keys[Math.floor(Math.random() * data.keys.length)];
  const res = http.get(`${BASE_URL}/${key}`, { redirects: 0 });
  check(res, { 'status is 302': (r) => r.status === 302 });
}

// 단축: 매번 고유 URL 생성
export function create() {
  const url = `https://example.com/load/${__VU}-${__ITER}`;
  const res = http.post(`${BASE_URL}/api/v1/urls`, JSON.stringify({ longUrl: url }), JSON_HEADERS);
  check(res, { 'status is 201': (r) => r.status === 201 });
}
