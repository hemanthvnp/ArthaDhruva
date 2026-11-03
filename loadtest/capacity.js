// Capacity test: the smoke test's traffic mix offered at a rising series of fixed rates, each held for
// STEP_SECONDS, to find where the service stops meeting its target. Not a CI gate (it is meant to run past
// the limit); the table it prints, also written to /work/capacity-summary.json, is what PERFORMANCE.md quotes.
//   STEPS=25,50,100,150,200,300 STEP_SECONDS=30 k6 run capacity.js
// A step meets the target when the offered rate was actually achieved, p95 stayed under TARGET_P95_MS
// (default 400 ms, the scoring gate in smoke.js) and under 1% of requests failed for a reason other than the
// rate limiter. 429s are counted apart: they are the limiter refusing one account more than its plan allows,
// so to measure the service behind it, run this under an account on a plan large enough for the top step.
import { Counter } from 'k6/metrics';
import { hit } from './lib.js';

export { setup } from './lib.js';

const STEPS = (__ENV.STEPS || '25,50,100,150,200,300').split(',').map(Number);
const STEP_SECONDS = Number(__ENV.STEP_SECONDS || 30);
const TARGET_P95_MS = Number(__ENV.TARGET_P95_MS || 400);
const WARMUP_SECONDS = 20;

const limited = new Counter('limited');
const failures = new Counter('failures');   // anything but 2xx and 429: errors, timeouts, refused connections

const scenarios = {
  warmup: { executor: 'constant-arrival-rate', exec: 'run', rate: 15, timeUnit: '1s', duration: `${WARMUP_SECONDS}s`,
            preAllocatedVUs: 10, maxVUs: 40, tags: { step: 'warmup' } },
};
// Thresholds that always pass, there only so that each step's figures are reported on their own.
const thresholds = {};
STEPS.forEach((rate, i) => {
  scenarios[`step_${rate}`] = {
    executor: 'constant-arrival-rate', exec: 'run', rate, timeUnit: '1s', duration: `${STEP_SECONDS}s`,
    startTime: `${WARMUP_SECONDS + 2 + i * (STEP_SECONDS + 3)}s`, gracefulStop: '5s',
    preAllocatedVUs: Math.max(10, rate), maxVUs: Math.max(40, rate * 4), tags: { step: String(rate) },
  };
  thresholds[`http_req_duration{step:${rate}}`] = ['p(95)>=0'];
  thresholds[`http_reqs{step:${rate}}`] = ['count>=0'];
  thresholds[`limited{step:${rate}}`] = ['count>=0'];
  thresholds[`failures{step:${rate}}`] = ['count>=0'];
});

export const options = { scenarios, thresholds, summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'] };

export function run(data) {
  const r = hit(data, {});
  if (r.status === 429) {
    limited.add(1);
  } else if (r.status < 200 || r.status >= 300) {
    failures.add(1);
  }
}

function value(summary, name, stat) {
  const metric = summary.metrics[name];
  return metric ? metric.values[stat] : 0;
}

export function handleSummary(summary) {
  const rows = STEPS.map((rate) => {
    const requests = value(summary, `http_reqs{step:${rate}}`, 'count');
    const p95 = value(summary, `http_req_duration{step:${rate}}`, 'p(95)');
    const lim = value(summary, `limited{step:${rate}}`, 'count');
    const failed = value(summary, `failures{step:${rate}}`, 'count');
    const achieved = requests / STEP_SECONDS;
    const failedShare = requests ? failed / requests : 0;
    return {
      offered: rate, achieved: +achieved.toFixed(1), p95_ms: Math.round(p95), failed_pct: +(failedShare * 100).toFixed(2),
      limited_429: lim, meets_target: requests > 0 && achieved >= rate * 0.95 && p95 < TARGET_P95_MS && failedShare < 0.01,
    };
  });
  const passing = rows.filter((r) => r.meets_target).map((r) => r.offered);
  const ceiling = passing.length ? Math.max(...passing) : 0;
  const lines = [
    '',
    `Target: the offered rate achieved, p95 < ${TARGET_P95_MS} ms, < 1% failures other than 429.`,
    'offered/s  achieved/s  p95 ms  failed %   429s  meets target',
    ...rows.map((r) => `${String(r.offered).padStart(9)}  ${String(r.achieved).padStart(10)}  ${String(r.p95_ms).padStart(6)}  `
      + `${String(r.failed_pct).padStart(8)}  ${String(r.limited_429).padStart(5)}  ${r.meets_target ? 'yes' : 'NO'}`),
    `Highest step meeting the target: ${ceiling} req/s`,
    '',
  ];
  return {
    stdout: lines.join('\n'),
    '/work/capacity-summary.json': JSON.stringify({ target_p95_ms: TARGET_P95_MS, step_seconds: STEP_SECONDS, rows, ceiling }, null, 2),
  };
}
