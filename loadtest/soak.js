// Soak test: the smoke test's traffic mix at a steady, moderate rate for a long time (DURATION, default 30
// minutes), looking for what only shows with time: a slow leak (heap, connections, threads), a cache or table
// that grows until it hurts, latency that creeps. Each request is tagged with the quarter of the run it fell
// in. The run fails if more than 0.1% of requests fail, or if the last quarter misses the latency targets the
// smoke test gates on (by then a leak has had the whole run to show); the summary also compares the last
// quarter with the first and reports drift, which is for a person to judge, not a gate (k6 cannot fail a run
// from its summary).
//   RATE=30 DURATION=30m k6 run soak.js
// Watch the JVM and pool panels in Grafana alongside (docker compose --profile observability).
import exec from 'k6/execution';
import { hit } from './lib.js';

export { setup } from './lib.js';

const RATE = Number(__ENV.RATE || 30);
const DURATION = __ENV.DURATION || '30m';
const WARMUP_SECONDS = 20;

function seconds(duration) {
  const m = /^(\d+)(s|m|h)$/.exec(duration);
  if (!m) throw new Error(`DURATION must look like 90s, 30m or 2h, not ${duration}`);
  return Number(m[1]) * { s: 1, m: 60, h: 3600 }[m[2]];
}
const TOTAL = seconds(DURATION);

export const options = {
  scenarios: {
    warmup: { executor: 'constant-arrival-rate', exec: 'warmup', rate: 15, timeUnit: '1s', duration: `${WARMUP_SECONDS}s`,
              preAllocatedVUs: 10, maxVUs: 40 },
    soak: { executor: 'constant-arrival-rate', exec: 'soak', startTime: `${WARMUP_SECONDS + 1}s`, rate: RATE, timeUnit: '1s',
            duration: DURATION, preAllocatedVUs: Math.max(10, RATE), maxVUs: RATE * 4 },
  },
  thresholds: {
    'http_req_failed{phase:soak}': ['rate<0.001'],
    'http_req_duration{phase:soak,endpoint:score}': ['p(95)<400'],
    'http_req_duration{quarter:4,endpoint:score}': ['p(95)<400'],
    'http_req_duration{quarter:4,endpoint:search}': ['p(95)<250'],
    'http_req_duration{quarter:4,endpoint:catalog}': ['p(95)<250'],
    // Reported per quarter for the drift comparison in handleSummary.
    'http_req_duration{quarter:1}': ['p(95)>=0'],
    'http_req_duration{quarter:4}': ['p(95)>=0'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function warmup(data) { hit(data, { phase: 'warmup' }); }

export function soak(data) {
  const elapsed = (Date.now() - exec.scenario.startTime) / 1000;
  const quarter = Math.min(4, 1 + Math.floor((4 * elapsed) / TOTAL));
  hit(data, { phase: 'soak', quarter: String(quarter) });
}

// Latency that grows over a steady load is the signature of a leak or of something growing without bound.
// Reported as drift when the last quarter's p95 is over 1.5 times the first's, plus 20 ms of noise.
export function handleSummary(summary) {
  const p95 = (q) => (summary.metrics[`http_req_duration{quarter:${q}}`] || { values: { 'p(95)': 0 } }).values['p(95)'];
  const first = p95(1);
  const last = p95(4);
  const drift = last > first * 1.5 + 20;
  const text = `\nSoak at ${RATE} req/s for ${DURATION}: p95 first quarter ${first.toFixed(0)} ms, last quarter ${last.toFixed(0)} ms`
    + `${drift ? ' -- DRIFT: the service slowed down over the run' : ', no drift'}\n`;
  return { stdout: text, '/work/soak-summary.json': JSON.stringify({ rate: RATE, duration: DURATION, p95_first_ms: first, p95_last_ms: last, drift }, null, 2) };
}
