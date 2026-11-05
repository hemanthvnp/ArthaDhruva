import { buildServer, isPositiveInt, isUrl, loadConfig, Redactor } from '@arthadhruva/mcp-kit';
import { Az, NAME, allowlist } from './azure.js';
import { CommandRunner } from '@arthadhruva/mcp-kit';
import { Prometheus } from './prometheus.js';
import { MAX_RETRIES, maxTotalMs, type Fetch, type RetryPolicy } from './retry.js';
import { tools } from './tools.js';
import { WakeClient, appAnswers } from './wake.js';

/**
 * Configuration, from the environment only. Everything is optional except what the tools you use need: with nothing
 * set, `health` reports that there is nothing to assess and `metric_query` asks a Prometheus on localhost.
 */
export const CONFIG = {
  DEPLOY_OPS_PROMETHEUS_URL: { description: 'Prometheus base URL', default: 'http://localhost:9090', check: isUrl },
  DEPLOY_OPS_APP_URL: { description: 'public URL of the application (to see whether it answers)', check: isUrl },
  DEPLOY_OPS_WAKE_URL: { description: 'public URL of the wake function (deploy/azure/wake); needed by `wake`', check: isUrl },
  DEPLOY_OPS_AZ_RESOURCE_GROUP: { description: 'resource group of the VM, to read its power state with az', check: (v: string) => (NAME.test(v) ? undefined : 'letters, digits, . - _ only') },
  DEPLOY_OPS_AZ_VM: { description: 'name of the VM', check: (v: string) => (NAME.test(v) ? undefined : 'letters, digits, . - _ only') },
  DEPLOY_OPS_TIMEOUT_MS: { description: 'per-attempt timeout of each network call', default: '3000', check: isPositiveInt },
  DEPLOY_OPS_RETRIES: { description: `retries after the first attempt (0 to ${MAX_RETRIES})`, default: '2',
    check: (v: string) => (/^\d+$/.test(v) && Number(v) <= MAX_RETRIES ? undefined : `must be 0 to ${MAX_RETRIES}`) },
} as const;

function trimSlashes(url: string): string {
  let end = url.length;
  while (end > 0 && url[end - 1] === '/') end--;
  return url.slice(0, end);
}

export interface Injected {
  fetchImpl?: Fetch;
  /** For tests: runs az without a process. */
  runner?: { run: CommandRunner['run'] };
  backoffMs?: number;
}

export function createServer(env: Record<string, string | undefined> = process.env, injected: Injected = {}) {
  const redactor = new Redactor();
  const config = loadConfig(CONFIG, env, redactor);
  if ((config.DEPLOY_OPS_AZ_RESOURCE_GROUP === undefined) !== (config.DEPLOY_OPS_AZ_VM === undefined)) {
    throw new Error('Configuration error:\n  - DEPLOY_OPS_AZ_RESOURCE_GROUP and DEPLOY_OPS_AZ_VM must be set together or not at all');
  }
  const policy: RetryPolicy = { timeoutMs: Number(config.DEPLOY_OPS_TIMEOUT_MS), retries: Number(config.DEPLOY_OPS_RETRIES), backoffMs: injected.backoffMs ?? 200 };
  const fetchImpl = injected.fetchImpl;
  const target = config.DEPLOY_OPS_AZ_VM ? { resourceGroup: config.DEPLOY_OPS_AZ_RESOURCE_GROUP!, vm: config.DEPLOY_OPS_AZ_VM } : undefined;
  const az = target ? new Az(injected.runner ?? new CommandRunner(process.cwd(), allowlist(target)), target) : undefined;
  const wake = config.DEPLOY_OPS_WAKE_URL ? new WakeClient(trimSlashes(config.DEPLOY_OPS_WAKE_URL), policy, fetchImpl) : undefined;
  const prometheus = new Prometheus(trimSlashes(config.DEPLOY_OPS_PROMETHEUS_URL!), policy, fetchImpl);
  const appUrl = config.DEPLOY_OPS_APP_URL ? trimSlashes(config.DEPLOY_OPS_APP_URL) : undefined;
  const parts = {
    az, wake, prometheus,
    appAnswers: appUrl ? () => appAnswers(appUrl, policy, fetchImpl) : undefined,
    maxCallSeconds: Math.ceil(maxTotalMs(policy) / 1000),
  };
  const toolList = tools(parts);
  return buildServer({
    name: 'deploy-ops',
    version: '0.1.0',
    instructions: 'The deployed ArthaDhruva stack. The VM is deliberately switched off when idle: "asleep" is a normal state, not a failure. '
      + 'Use `health` first. `wake` starts the VM and costs money while it runs: call it only when asked.',
    tools: toolList,
    adapters: [...(wake ? [wake] : []), prometheus, ...(az ? [az] : [])],
    redactor,
  });
}
