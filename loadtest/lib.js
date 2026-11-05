// Shared by the load tests (smoke.js, capacity.js, soak.js): how a request is authenticated, and the traffic
// mix. TOKEN is a valid session JWT, BASE the API root (.../v1).
import http from 'k6/http';
import { check, sleep } from 'k6';

export const BASE = __ENV.BASE || 'http://backend:8080/v1';
const TOKEN = __ENV.TOKEN;
// The session JWT travels as the ad_session cookie, not a bearer header -- see SessionCookie.java /
// JwtAuthenticationFilter, which no longer accepts a bearer header for a session token at all.
export const headers = { Cookie: `ad_session=${TOKEN}`, 'Content-Type': 'application/json' };
// A write also needs the double-submit CSRF token (SecurityConfig): any response carries the XSRF-TOKEN cookie
// (GET /csrf is the request made for it), and a write sends it back as that cookie and again in the
// X-XSRF-TOKEN header. Reads need neither.
function writeHeaders(csrf) {
  return { ...headers, Cookie: `ad_session=${TOKEN}; XSRF-TOKEN=${csrf}`, 'X-XSRF-TOKEN': csrf };
}

export function setup() {
  // GET /csrf has no handler on purpose: it answers 401 with the cookie attached, so 401 is the expected status.
  const csrfCookies = http.get(`${BASE}/csrf`, { responseCallback: http.expectedStatuses(401) }).cookies['XSRF-TOKEN'];
  if (!csrfCookies || csrfCookies.length === 0) {
    throw new Error('GET /csrf did not set the XSRF-TOKEN cookie; every write in this test would be refused');
  }
  const res = http.get(`${BASE}/loans`, { headers });
  return { loan: res.json()[0], writeHeaders: writeHeaders(csrfCookies[0].value) };
}

// 40% scoring (the expensive call: inference plus the explanation), 40% case search, 20% the loan catalog.
// `tags` go on every request, so a test can split its results by phase or by load step. Returns the response.
export function hit(data, tags) {
  const roll = Math.random();
  let r;
  if (roll < 0.4) {
    r = http.post(`${BASE}/score`, JSON.stringify(data.loan), { headers: data.writeHeaders, tags: { endpoint: 'score', ...tags } });
    check(r, { 'score 200': (x) => x.status === 200 });
  } else if (roll < 0.8) {
    r = http.get(`${BASE}/loan-cases/search?size=25`, { headers, tags: { endpoint: 'search', ...tags } });
    check(r, { 'search 200': (x) => x.status === 200 });
  } else {
    r = http.get(`${BASE}/loans`, { headers, tags: { endpoint: 'catalog', ...tags } });
    check(r, { 'catalog 200': (x) => x.status === 200 });
  }
  sleep(0.01);
  return r;
}
