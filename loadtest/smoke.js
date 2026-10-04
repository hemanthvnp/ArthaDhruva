// k6 load test against the key endpoints. Thresholds are the CI gate: the run FAILS if p95 latency
// or the error rate regress past them. TOKEN is a valid JWT, BASE the API root (.../v1).
// A short warm-up phase runs first and is excluded from the thresholds: a freshly started JVM is
// interpreting cold code, and gating on that would make the check flaky rather than meaningful.
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE || 'http://backend:8080/v1';
const TOKEN = __ENV.TOKEN;
// The session JWT travels as the ad_session cookie, not a bearer header -- see SessionCookie.java /
// JwtAuthenticationFilter, which no longer accepts a bearer header for a session token at all.
const headers = { Cookie: `ad_session=${TOKEN}`, 'Content-Type': 'application/json' };
// A write also needs the double-submit CSRF token (SecurityConfig): any response carries the XSRF-TOKEN cookie
// (GET /csrf is the request made for it), and a write sends it back as that cookie and again in the
// X-XSRF-TOKEN header. Reads need neither.
function writeHeaders(csrf) {
  return { ...headers, Cookie: `ad_session=${TOKEN}; XSRF-TOKEN=${csrf}`, 'X-XSRF-TOKEN': csrf };
}

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
  // GET /csrf has no handler on purpose: it answers 401 with the cookie attached, so 401 is the expected status.
  const csrfCookies = http.get(`${BASE}/csrf`, { responseCallback: http.expectedStatuses(401) }).cookies['XSRF-TOKEN'];
  if (!csrfCookies || csrfCookies.length === 0) {
    throw new Error('GET /csrf did not set the XSRF-TOKEN cookie; every write in this test would be refused');
  }
  const res = http.get(`${BASE}/loans`, { headers });
  return { loan: res.json()[0], writeHeaders: writeHeaders(csrfCookies[0].value) };
}

function hit(data, phase) {
  const roll = Math.random();
  if (roll < 0.4) {
    const r = http.post(`${BASE}/score`, JSON.stringify(data.loan), { headers: data.writeHeaders, tags: { endpoint: 'score', phase } });
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
