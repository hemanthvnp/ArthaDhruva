import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import type { Job } from './model.js';

/**
 * Typical CI time from GitHub Actions history: per job, the median and 90th percentile of successful runs,
 * and the longest chain of jobs as the expected total. No failure probabilities: a guess dressed as a
 * percentage is worse than none, and static_checks reports the concrete risks instead.
 */
export interface JobRun {
  name: string;
  conclusion: string;
  seconds: number;
}

export interface Run {
  id: number;
  workflow: string;
  conclusion: string;
  createdAt: string;
  jobs: JobRun[];
}

export interface GitHub {
  /** Completed runs, newest first. Throws when `gh` is missing or not signed in. */
  recentRuns(limit: number): Run[];
}

/** `gh` with a cache under .git/ci-guard: a completed run never changes, so each is fetched once. */
export function ghHistory(root: string, stateDir: string): GitHub {
  const gh = (args: string[]) => execFileSync('gh', args, { cwd: root, encoding: 'utf8', maxBuffer: 64 << 20, timeout: 60_000, windowsHide: true });
  return {
    recentRuns(limit) {
      const file = path.join(stateDir, 'history.json');
      let cache: Record<string, Run> = {};
      try {
        cache = (JSON.parse(fs.readFileSync(file, 'utf8')) as { runs: Record<string, Run> }).runs;
      } catch {
        /* first use */
      }
      const listed = JSON.parse(gh(['run', 'list', '--limit', String(limit), '--json', 'databaseId,workflowName,conclusion,status,createdAt'])) as
        Array<{ databaseId: number; workflowName: string; conclusion: string; status: string; createdAt: string }>;
      for (const r of listed) {
        if (r.status !== 'completed' || cache[r.databaseId] || !['success', 'failure'].includes(r.conclusion)) continue;
        const detail = JSON.parse(gh(['run', 'view', String(r.databaseId), '--json', 'jobs'])) as
          { jobs: Array<{ name: string; conclusion: string; startedAt?: string; completedAt?: string }> };
        cache[r.databaseId] = {
          id: r.databaseId, workflow: r.workflowName, conclusion: r.conclusion, createdAt: r.createdAt,
          jobs: detail.jobs.filter((j) => j.startedAt && j.completedAt).map((j) => ({
            name: j.name, conclusion: j.conclusion, seconds: Math.max(0, (Date.parse(j.completedAt!) - Date.parse(j.startedAt!)) / 1000),
          })),
        };
      }
      fs.writeFileSync(file, JSON.stringify({ runs: cache }));
      return Object.values(cache).sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, limit * 2);
    },
  };
}

export interface JobTime {
  job: string;
  p50Sec: number;
  p90Sec: number;
  samples: number;
  source: 'history' | 'fallback guess';
}

export interface TypicalTime {
  jobs: JobTime[];
  /** The longest chain (jobs run in parallel; loadtest and failover wait for backend), plus queueing. */
  expectedTotalSec: number;
  p90TotalSec: number;
  criticalPath: string[];
  runsRead: number;
  note?: string;
}

export const QUEUE_SEC = 15;
export const MIN_SAMPLES = 3;

export function quantile(values: readonly number[], q: number): number {
  const s = [...values].sort((a, b) => a - b);
  if (s.length === 0) return 0;
  const pos = q * (s.length - 1);
  const lo = Math.floor(pos);
  const hi = Math.min(s.length - 1, lo + 1);
  return s[lo]! + (pos - lo) * (s[hi]! - s[lo]!);
}

/** GitHub names matrix jobs "CodeQL (java-kotlin)"; they count as the job they expand from. */
function matches(job: Job, name: string): boolean {
  return name === job.githubName || name.startsWith(`${job.githubName} (`);
}

export function typicalTime(jobs: readonly Job[], runs: readonly Run[], note?: string): TypicalTime {
  const times: JobTime[] = jobs.map((job) => {
    const ok = runs.flatMap((r) => r.jobs).filter((j) => matches(job, j.name) && j.conclusion === 'success').map((j) => j.seconds);
    const have = ok.length >= MIN_SAMPLES;
    return {
      job: job.id,
      p50Sec: Math.round(have ? quantile(ok, 0.5) : job.fallbackSec),
      p90Sec: Math.round(have ? quantile(ok, 0.9) : job.fallbackSec * 1.5),
      samples: ok.length,
      source: have ? 'history' : 'fallback guess',
    };
  });
  const byId = new Map(times.map((t) => [t.job, t]));
  const chains = jobs.map((j) => {
    const chain = j.after ? [j.after, j.id] : [j.id];
    return { chain, p50: chain.reduce((a, id) => a + byId.get(id)!.p50Sec, 0), p90: chain.reduce((a, id) => a + byId.get(id)!.p90Sec, 0) };
  });
  const longest = chains.reduce((a, b) => (b.p50 > a.p50 ? b : a));
  return {
    jobs: times,
    expectedTotalSec: longest.p50 + QUEUE_SEC,
    p90TotalSec: Math.max(...chains.map((c) => c.p90)) + QUEUE_SEC,
    criticalPath: longest.chain,
    runsRead: runs.length,
    ...(note ? { note } : {}),
  };
}
