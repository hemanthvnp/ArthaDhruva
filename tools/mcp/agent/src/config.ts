import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { isPositiveInt, loadConfig } from '@arthadhruva/mcp-kit';
import { KNOWN_PRICES } from './budget.js';
import type { StdioServer } from './host.js';
import type { Limits, Pricing } from './types.js';

/**
 * Configuration, from the environment only. The Claude credential is read by the SDK itself (ANTHROPIC_API_KEY, or a profile from
 * `ant auth login`), so it never passes through this file. The servers' own settings (the API session, the database URL) are passed
 * on to the servers that need them and to no others.
 */
export const CONFIG = {
  AGENT_TENANT_ID: { description: 'id of the organization this agent works for, fixed for the run', required: true, check: isPositiveInt },
  AGENT_MODEL: { description: 'Claude model id', default: 'claude-opus-5-5' },
  AGENT_EFFORT: { description: 'reasoning depth: low, medium, high, xhigh or max', default: 'high',
    check: (v: string) => (['low', 'medium', 'high', 'xhigh', 'max'].includes(v) ? undefined : 'one of low, medium, high, xhigh, max') },
  AGENT_REFUSAL_FALLBACK: { description: 'on (default) re-runs a safety decline on a fallback model; off disables it', default: 'on',
    check: (v: string) => (v === 'on' || v === 'off' ? undefined : 'on or off') },
  AGENT_MAX_OUTPUT_TOKENS: { description: 'max_tokens of one model call', default: '16000', check: isPositiveInt },
  AGENT_MAX_STEPS: { description: 'most model calls for one question', default: '12', check: isPositiveInt },
  AGENT_MAX_TOKENS: { description: 'most tokens (input plus output) for one question', default: '300000', check: isPositiveInt },
  AGENT_MAX_SECONDS: { description: 'most wall-clock seconds for one question', default: '180', check: isPositiveInt },
  AGENT_MAX_COST_USD: { description: 'most estimated dollars for one question, or "none"; needs prices', default: '2',
    check: (v: string) => (v === 'none' || (Number.isFinite(Number(v)) && Number(v) > 0) ? undefined : 'a positive number, or none') },
  AGENT_PRICE_INPUT_PER_MTOK: { description: 'dollars per million input tokens (overrides the built-in price)', check: positive },
  AGENT_PRICE_OUTPUT_PER_MTOK: { description: 'dollars per million output tokens', check: positive },
  AGENT_TRACE_DIR: { description: 'where run traces are written', default: '.agent-traces' },
} as const;

function positive(v: string): string | undefined {
  return Number.isFinite(Number(v)) && Number(v) > 0 ? undefined : 'a positive number';
}

export type AgentConfig = ReturnType<typeof loadAgentConfig>;

export function loadAgentConfig(env: Record<string, string | undefined> = process.env) {
  const c = loadConfig(CONFIG, env);
  const limits: Limits = {
    maxSteps: Number(c.AGENT_MAX_STEPS), maxTokens: Number(c.AGENT_MAX_TOKENS), maxSeconds: Number(c.AGENT_MAX_SECONDS),
    maxCostUsd: c.AGENT_MAX_COST_USD === 'none' ? null : Number(c.AGENT_MAX_COST_USD),
  };
  const pricing = resolvePricing(c.AGENT_MODEL!, c.AGENT_PRICE_INPUT_PER_MTOK, c.AGENT_PRICE_OUTPUT_PER_MTOK);
  if (limits.maxCostUsd !== null && pricing === null) {
    throw new Error(`Configuration error:\n  - ${c.AGENT_MODEL} has no built-in price: set AGENT_PRICE_INPUT_PER_MTOK and AGENT_PRICE_OUTPUT_PER_MTOK, or set AGENT_MAX_COST_USD=none (the token limit still applies)`);
  }
  return {
    tenant: c.AGENT_TENANT_ID!, model: c.AGENT_MODEL!, effort: c.AGENT_EFFORT as 'low' | 'medium' | 'high' | 'xhigh' | 'max',
    fallback: c.AGENT_REFUSAL_FALLBACK === 'on', maxOutputTokens: Number(c.AGENT_MAX_OUTPUT_TOKENS),
    limits, pricing, traceDir: c.AGENT_TRACE_DIR!,
  };
}

/** Explicit prices win; cache reads default to a tenth of input and cache writes to 1.25 times, the API's own ratios. */
export function resolvePricing(model: string, input: string | undefined, output: string | undefined): Pricing | null {
  if (input !== undefined && output !== undefined) {
    const i = Number(input);
    return { inputPerMTok: i, outputPerMTok: Number(output), cacheReadPerMTok: i * 0.1, cacheWritePerMTok: i * 1.25 };
  }
  if (input !== undefined || output !== undefined) throw new Error('Configuration error:\n  - set both AGENT_PRICE_INPUT_PER_MTOK and AGENT_PRICE_OUTPUT_PER_MTOK, or neither');
  return KNOWN_PRICES[model] ?? null;
}

/** The servers the agent can use, and which of the process's variables each is given. Nothing else is passed on. */
interface ServerDef {
  /** Package directory under tools/mcp. */
  dir: string;
  env: readonly string[];
}

export const SERVERS: Record<string, ServerDef> = {
  'risk-engine': { dir: 'risk-engine', env: ['RISK_ENGINE_URL', 'RISK_ENGINE_SESSION', 'RISK_ENGINE_XSRF', 'RISK_ENGINE_TIMEOUT_MS', 'RISK_ENGINE_MAX_ROWS'] },
  'tenant-ops': { dir: 'tenant-ops', env: ['TENANT_OPS_DATABASE_URL', 'RISK_ENGINE_URL', 'RISK_ENGINE_SESSION', 'TENANT_OPS_TIMEOUT_MS', 'TENANT_OPS_DB_STATEMENT_TIMEOUT_MS'] },
  'model-validation': { dir: 'model-validation', env: ['MODEL_VALIDATION_REPO', 'RISK_ENGINE_URL', 'RISK_ENGINE_SESSION', 'MODEL_VALIDATION_TIMEOUT_MS'] },
  'deploy-ops': { dir: 'deploy-ops', env: ['DEPLOY_OPS_PROMETHEUS_URL', 'DEPLOY_OPS_APP_URL', 'DEPLOY_OPS_WAKE_URL', 'DEPLOY_OPS_AZ_RESOURCE_GROUP', 'DEPLOY_OPS_AZ_VM', 'DEPLOY_OPS_TIMEOUT_MS', 'DEPLOY_OPS_RETRIES'] },
  'ci-guard': { dir: 'ci-guard', env: ['CI_GUARD_REPO'] },
};

/** The default session works with servers 1 and 2; operator mode adds the other three. */
export const DEFAULT_SERVERS = ['risk-engine', 'tenant-ops'] as const;
export const OPERATOR_SERVERS = ['risk-engine', 'tenant-ops', 'model-validation', 'deploy-ops', 'ci-guard'] as const;

export function stdioServers(names: readonly string[], tenant: string, env: Record<string, string | undefined> = process.env): StdioServer[] {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
  return names.map((name) => {
    const def = SERVERS[name];
    if (!def) throw new Error(`Unknown server ${name}.`);
    const passed: Record<string, string> = {};
    for (const key of def.env) if (env[key] !== undefined && env[key] !== '') passed[key] = env[key]!;
    // The tenant of tenant-ops is the agent's, not whatever the shell happens to hold.
    if (name === 'tenant-ops') passed.TENANT_OPS_TENANT_ID = tenant;
    return { name, entry: path.join(root, def.dir, 'dist', 'cli.js'), env: passed };
  });
}
