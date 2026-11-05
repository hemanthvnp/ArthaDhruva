#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Redactor } from '@arthadhruva/mcp-kit';
import type { AgentDeps } from './agent.js';
import { AnthropicModel } from './anthropic-model.js';
import { DEFAULT_SERVERS, loadAgentConfig, OPERATOR_SERVERS, stdioServers } from './config.js';
import { runInjectionSuite } from './evals/injection.js';
import { previousReport, runSuite, withComparison, writeReport } from './evals/runner.js';
import type { Question } from './evals/scoring.js';
import { McpToolHost } from './host.js';
import { Session } from './session.js';

/**
 *   analyst-eval live        ~30 questions against the live stack: tool-call accuracy and answer correctness
 *   analyst-eval selection   the same questions scored only on which tools were chosen, per tool and per server, compared with the last report
 *   analyst-eval injection   the 30 adversarial cases against the real model
 *
 * All three cost tokens and REFUSE to start without AGENT_EVAL_CAP_USD (dollars). They are never part of CI. `live` and `selection`
 * need the stack running (the API session and, for operator questions, the other servers' settings); `injection` needs only a key.
 * Reports are written to evals/reports and, for `selection`, compared with the previous completed one.
 */
const HERE = path.dirname(fileURLToPath(import.meta.url));
const PACKAGE = path.resolve(HERE, '..');

async function main(): Promise<number> {
  const [suite] = process.argv.slice(2);
  if (!['live', 'selection', 'injection'].includes(suite ?? '')) {
    process.stderr.write('usage: analyst-eval live|selection|injection   (needs AGENT_EVAL_CAP_USD)\n');
    return 1;
  }
  const config = loadAgentConfig();
  const capUsd = process.env.AGENT_EVAL_CAP_USD === undefined ? undefined : Number(process.env.AGENT_EVAL_CAP_USD);
  const model = new AnthropicModel({ model: config.model, maxOutputTokens: config.maxOutputTokens, effort: config.effort, fallback: config.fallback });
  const reports = process.env.AGENT_EVAL_REPORTS ?? path.join(PACKAGE, 'evals', 'reports');

  if (suite === 'injection') {
    const report = await runInjectionSuite({ model, modelName: config.model, capUsd, pricing: config.pricing, limits: { ...config.limits, maxSteps: 6 } });
    const file = writeReport(report as never, reports);
    process.stdout.write(`injection: ${report.passed} of ${report.casesRun} cases passed${report.completed ? '' : ` (INCOMPLETE: ${report.stoppedBecause})`}; cost about $${report.costUsd.toFixed(2)}; ${file}\n`);
    return report.completed && report.passed === report.casesTotal ? 0 : 2;
  }

  const questions = (JSON.parse(fs.readFileSync(path.join(PACKAGE, 'evals', 'questions.json'), 'utf8')) as Question[]);
  const redactor = new Redactor();
  for (const name of ['RISK_ENGINE_SESSION', 'RISK_ENGINE_XSRF', 'TENANT_OPS_DATABASE_URL']) if (process.env[name]) redactor.add(process.env[name]!);
  const host = await McpToolHost.spawn(stdioServers(OPERATOR_SERVERS, config.tenant));
  try {
    const serverOf = (tool: string) => host.tools().find((t) => t.name === tool)?.server ?? 'unknown';
    const known = new Set(host.tools().map((t) => t.name));
    const askable = questions.filter((q) => q.expectedTools.every((t) => known.has(t)));
    if (askable.length < questions.length) process.stderr.write(`skipping ${questions.length - askable.length} question(s) whose tools are not offered by the running servers\n`);
    const options = {
      suite: suite!, model: config.model, questions: askable, capUsd, baseLimits: suite === 'selection' ? { ...config.limits, maxSteps: 6 } : config.limits, serverOf,
      makeDeps: (q: Question, limits: typeof config.limits): AgentDeps => ({
        model, host, session: new Session({ tenant: config.tenant, allowRunStart: false, specs: host.tools().filter((t) => q.operator ? true : (DEFAULT_SERVERS as readonly string[]).includes(t.server)) }),
        limits, pricing: config.pricing, redactor,
      }),
      onQuestion: (s: { id: string; answer: { correct: boolean }; selection: { missed: string[]; unexpected: string[] } }, spent: number) =>
        process.stdout.write(`${s.id}: ${s.answer.correct ? 'correct' : 'not correct'}${s.selection.missed.length ? `, missed ${s.selection.missed.join(',')}` : ''}${s.selection.unexpected.length ? `, unexpected ${s.selection.unexpected.join(',')}` : ''} ($${spent.toFixed(2)} so far)\n`),
    };
    let report = await runSuite(options, config.pricing !== null);
    report = withComparison(report, previousReport(reports, suite!, report.startedAt));
    const file = writeReport(report, reports);
    const s = report.summary;
    process.stdout.write(`${suite}: ${report.questionsRun} of ${report.questionsTotal} questions${report.completed ? '' : ` (INCOMPLETE: ${report.stoppedBecause})`}; `
      + (s ? `tool-call accuracy ${(s.toolCallAccuracy * 100).toFixed(1)}%, answer correctness ${(s.answerCorrectness * 100).toFixed(1)}%; ` : '')
      + `cost about $${report.costUsd.toFixed(2)}; ${file}\n`);
    if (report.comparedWith) process.stdout.write(`vs ${report.comparedWith.report}:\n  ${report.comparedWith.changes.join('\n  ')}\n`);
    return report.completed ? 0 : 2;
  } finally {
    await host.close();
  }
}

main().then((code) => { process.exitCode = code; }, (e: unknown) => { process.stderr.write(`${e instanceof Error ? e.message : String(e)}\n`); process.exitCode = 1; });
