// k6 load test against the key endpoints. Thresholds are the CI gate: the run FAILS if p95 latency
// or the error rate regress past them. TOKEN is a valid JWT, BASE the API root (.../v1).
// A short warm-up phase runs first and is excluded from the thresholds: a freshly started JVM is
// interpreting cold code, and gating on that would make the check flaky rather than meaningful.
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE || 'http://backend:8080/v1';
const TOKEN = __ENV.TOKEN;
const headers = { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' };

export const options = {
  scenarios: {
    warmup: { executor: 'constant-arrival-rate', exec: 'warmup', rate: 15, timeUnit: '1s',
              duration: '15s', preAllocatedVUs: 10, maxVUs: 40 },
    api: { executor: 'constant-arrival-rate', exec: 'measured', startTime: '16s',
           rate: Number(__ENV.RATE || 60), timeUnit: '1s', duration: __ENV.DURATION || '30s',
           preAllocatedVUs: 30, maxVUs: 120 },
  },
  thresholds: {
    'http_req_failed{phase:measured}': ['rate<0.01'],
    'http_req_duration{phase:measured,endpoint:score}': ['p(95)<400'],
    'http_req_duration{phase:measured,endpoint:search}': ['p(95)<250'],
    'http_req_duration{phase:measured,endpoint:catalog}': ['p(95)<250'],
  },
};

export function setup() {
  const res = http.get(`${BASE}/loans`, { headers });
  return { loan: res.json()[0] };
}

function hit(data, phase) {
  const roll = Math.random();
  if (roll < 0.4) {
    const r = http.post(`${BASE}/score`, JSON.stringify(data.loan), { headers, tags: { endpoint: 'score', phase } });
    check(r, { 'score 200': (x) => x.status === 200 });
  } else if (roll < 0.8) {
    const r = http.get(`${BASE}/loan-cases/search?size=25`, { headers, tags: { endpoint: 'search', phase } });
    check(r, { 'search 200': (x) => x.status === 200 });
  } else {
    const r = http.get(`${BASE}/loans`, { headers, tags: { endpoint: 'catalog', phase } });
    check(r, { 'catalog 200': (x) => x.status === 200 });
  }
  sleep(0.01);
}

export function warmup(data) { hit(data, 'warmup'); }
export function measured(data) { hit(data, 'measured'); }
