#!/usr/bin/env node
import { exitOnConfigError, Redactor } from '@arthadhruva/mcp-kit';
import { ask, type Outcome } from './agent.js';
import { AnthropicModel } from './anthropic-model.js';
import { DEFAULT_SERVERS, loadAgentConfig, OPERATOR_SERVERS, stdioServers } from './config.js';
import { McpToolHost } from './host.js';
import { Session } from './session.js';

/**
 *   analyst-agent ask "<question>" [--operator] [--allow-run-start] [--json]
 *
 * Exit status: 0 verified, 2 could not verify, 3 stopped (a limit), 4 refused, 1 error.
 */
const USAGE = 'usage: analyst-agent ask "<question>" [--operator] [--allow-run-start] [--json]';

async function main(): Promise<number> {
  const [command, ...rest] = process.argv.slice(2);
  const flags = new Set(rest.filter((a) => a.startsWith('--')));
  const question = rest.filter((a) => !a.startsWith('--')).join(' ').trim();
  if (command !== 'ask' || !question) {
    process.stderr.write(`${USAGE}\n`);
    return 1;
  }
  for (const f of flags) if (!['--operator', '--allow-run-start', '--json'].includes(f)) {
    process.stderr.write(`unknown option ${f}\n${USAGE}\n`);
    return 1;
  }

  const config = loadAgentConfig();
  const redactor = new Redactor();
  for (const name of ['RISK_ENGINE_SESSION', 'RISK_ENGINE_XSRF', 'TENANT_OPS_DATABASE_URL']) {
    const v = process.env[name];
    if (v) redactor.add(v);
  }
  const host = await McpToolHost.spawn(stdioServers(flags.has('--operator') ? OPERATOR_SERVERS : DEFAULT_SERVERS, config.tenant));
  try {
    const session = new Session({ tenant: config.tenant, allowRunStart: flags.has('--allow-run-start'), specs: host.tools() });
    const model = new AnthropicModel({ model: config.model, maxOutputTokens: config.maxOutputTokens, effort: config.effort, fallback: config.fallback });
    const outcome = await ask(question, { model, host, session, limits: config.limits, pricing: config.pricing, redactor, traceDir: config.traceDir });
    process.stdout.write(flags.has('--json') ? `${JSON.stringify(outcome, null, 2)}\n` : `${render(outcome)}\n`);
    return { verified: 0, 'could-not-verify': 2, stopped: 3, refused: 4, error: 1 }[outcome.status];
  } finally {
    await host.close();
  }
}

export function render(o: Outcome): string {
  const lines: string[] = [];
  if (o.status === 'verified') lines.push(o.answer ?? '', '', `Verified: ${o.reason}`);
  else if (o.status === 'could-not-verify') {
    lines.push('COULD NOT VERIFY this answer. It is shown for reference only; do not rely on its figures.', '', o.answer ?? '', '', 'Figures that could not be verified:');
    for (const f of o.offending) lines.push(`  - ${f.text} (${f.verdict})`);
  } else if (o.status === 'stopped') {
    lines.push(`STOPPED: ${o.reason}`, '');
    if (o.answer) lines.push('Last draft (NOT verified):', o.answer, '');
    lines.push('Gathered so far:', ...o.findings.map((f) => `  [${f.id}] ${f.tool}: ${f.summary}`));
  } else lines.push(`${o.status.toUpperCase()}: ${o.reason}`);
  lines.push('', `steps ${o.steps}, tokens ${o.tokens}, ${o.costUsd === null ? 'cost unknown' : `about $${o.costUsd.toFixed(4)}`}, ${o.seconds.toFixed(1)} s${o.traceFile ? `, trace ${o.traceFile}` : ''}`);
  return lines.join('\n');
}

main().then((code) => { process.exitCode = code; }, (e: unknown) => exitOnConfigError(e));
