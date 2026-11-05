import { buildServer, CommandRunner, isPositiveInt, isUrl, loadConfig, Redactor, type RunResult } from '@arthadhruva/mcp-kit';
import { ReportApi, type Fetch } from './api.js';
import { GOLDEN_ID, allowlist } from './golden.js';
import { gitRepo, type Repo } from './git.js';
import { tools } from './tools.js';

/**
 * Configuration, from the environment only. The two reports need a signed-in session (the value of `ad_session`);
 * the checksum check, the golden test and the advisory need nothing but the repository.
 */
export const CONFIG = {
  RISK_ENGINE_URL: { description: 'backend base URL, without /v1', default: 'http://localhost:8080', check: isUrl },
  RISK_ENGINE_SESSION: { description: 'ad_session cookie of a signed-in user (for drift_report and survival_backtest)', secret: true,
    check: (v: string) => (v.split('.').length === 3 ? undefined : 'not a JWT') },
  MODEL_VALIDATION_TIMEOUT_MS: { description: 'per-request timeout for the reports', default: '30000', check: isPositiveInt },
} as const;

function withoutTrailingSlashes(url: string): string {
  let end = url.length;
  while (end > 0 && url[end - 1] === '/') end--;
  return url.slice(0, end);
}

export interface Injected {
  repo?: Repo;
  fetchImpl?: Fetch;
  /** For tests: runs the golden test without Maven. */
  runGolden?: (signal?: AbortSignal) => Promise<RunResult>;
}

export function createServer(root: string, env: Record<string, string | undefined> = process.env, injected: Injected = {}) {
  const redactor = new Redactor();
  const config = loadConfig(CONFIG, env, redactor);
  const repo = injected.repo ?? gitRepo(root);
  const runner = new CommandRunner(root, allowlist());
  const api = new ReportApi({
    baseUrl: withoutTrailingSlashes(config.RISK_ENGINE_URL!),
    session: config.RISK_ENGINE_SESSION ?? '',
    timeoutMs: Number(config.MODEL_VALIDATION_TIMEOUT_MS),
  }, redactor, injected.fetchImpl);
  return buildServer({
    name: 'model-validation',
    version: '0.1.0',
    instructions: 'Checks on the committed model artifacts. It never exports, retrains or regenerates anything: when a golden script needs re-running, '
      + 'the result says the owner must run it. A skipped check is not a passed one.',
    tools: tools({ repo, api, sessionConfigured: config.RISK_ENGINE_SESSION !== undefined, runGolden: injected.runGolden ?? ((signal) => runner.run(GOLDEN_ID, signal ? { signal } : {})) }),
    adapters: [repo, api],
    redactor,
  });
}
