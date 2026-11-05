import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, describe, expect, it } from 'vitest';
import type { AgentDeps } from '../src/agent.js';
import { KNOWN_PRICES } from '../src/budget.js';
import { INJECTION_SPECS, runInjectionSuite } from '../src/evals/injection.js';
import { previousReport, requireCap, runSuite, withComparison, writeReport, type RunOptions } from '../src/evals/runner.js';
import { compare, scoreQuestion, summarise, type Question } from '../src/evals/scoring.js';
import { INJECTION_CASES } from '../src/injection-cases.js';
import { RecordedHost, ScriptedModel } from '../src/replay.js';
import { Session } from '../src/session.js';
import type { Limits, ModelReply, Usage } from '../src/types.js';
import { call, ok, PORTFOLIO, say, SPECS } from './helpers.js';
import type { Outcome } from '../src/agent.js';

const PACKAGE = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const MCP = path.resolve(PACKAGE, '..');
const PRICES = KNOWN_PRICES['claude-opus-5-5']!;
const LIMITS: Limits = { maxSteps: 12, maxTokens: 10_000_000, maxSeconds: 600, maxCostUsd: null };
const questions = JSON.parse(fs.readFileSync(path.join(PACKAGE, 'evals', 'questions.json'), 'utf8')) as Question[];

describe('the question set', () => {
  it('is about 30 questions with unique ids', () => {
    expect(questions).toHaveLength(30);
    expect(new Set(questions.map((q) => q.id)).size).toBe(30);
  });

  it('names only tools that exist: a renamed tool fails here, not in a live run that costs money', () => {
    const real = new Set<string>();
    for (const dir of ['risk-engine', 'tenant-ops', 'model-validation', 'deploy-ops', 'ci-guard']) {
      const source = fs.readFileSync(path.join(MCP, dir, 'src', 'tools.ts'), 'utf8');
      for (const m of source.matchAll(/\bname: '([a-z_]+)'/g)) real.add(m[1]!);
    }
    for (const q of questions) {
      for (const t of [...q.expectedTools, ...(q.allowedTools ?? []), ...(q.firstTool ? [q.firstTool] : [])]) expect(real, `${q.id} names ${t}`).toContain(t);
    }
  });

  it('every expected tool is one the agent may use (read-only), so a question can be answered', () => {
    const notOffered = new Set(['portfolio_run_start', 'wake', 'run_golden_test', 'preflight', 'preflight_cancel']);
    for (const q of questions) for (const t of q.expectedTools) expect(notOffered.has(t), `${q.id} expects ${t}`).toBe(false);
  });

  it('covers every server', () => {
    const tools = new Set(questions.flatMap((q) => q.expectedTools));
    for (const t of ['portfolio_summary', 'run_list', 'verify_artifacts', 'health', 'typical_time']) expect(tools).toContain(t);
  });
});

function outcome(over: Partial<Outcome>): Outcome {
  return {
    status: 'verified', answer: 'x', stoppedBy: null, reason: '', figures: [], offending: [], findings: [], called: [], refused: [],
    steps: 2, tokens: 3000, costUsd: 0.05, seconds: 4, ...over,
  };
}
const Q: Question = { id: 'q', question: '?', expectedTools: ['portfolio_summary'], firstTool: 'portfolio_summary', allowedTools: ['get_loan'], mustMention: ['baseline'] };

describe('scoring', () => {
  it('right tools, grounded in them, mentions what it must: correct', () => {
    const s = scoreQuestion(Q, outcome({
      called: ['portfolio_summary'], answer: 'The baseline ECL is 1.2m [r1].',
      findings: [{ id: 'r1', tool: 'portfolio_summary', summary: '' }],
      figures: [{ text: '1.2m', value: 1.2, unit: 'currency', citations: ['r1'], verdict: 'supported', matched: { result: 'r1', path: 'x', value: 1 }, at: 0 }],
    }));
    expect(s.selection).toEqual({ firstOk: true, hit: ['portfolio_summary'], missed: [], unexpected: [] });
    expect(s.answer).toMatchObject({ verified: true, grounded: true, mentions: true, correct: true });
  });

  it('selection is scored apart from the answer: the right tool with a bad answer is still a selection success', () => {
    const s = scoreQuestion(Q, outcome({ status: 'could-not-verify', called: ['portfolio_summary'], answer: 'baseline' }));
    expect(s.selection.missed).toEqual([]);
    expect(s.answer.correct).toBe(false);
  });

  it('a wrong first tool, a missed tool and an unexpected one are each reported; allowed follow-ups are not unexpected', () => {
    const s = scoreQuestion(Q, outcome({ called: ['score_loan', 'get_loan'], answer: 'baseline' }));
    expect(s.selection).toEqual({ firstOk: false, hit: [], missed: ['portfolio_summary'], unexpected: ['score_loan'] });
    expect(s.answer.correct).toBe(false);
  });

  it('a verified answer whose figures come from the wrong tool is not grounded', () => {
    const s = scoreQuestion(Q, outcome({
      called: ['portfolio_summary', 'get_loan'], answer: 'baseline 3 [r2]',
      findings: [{ id: 'r1', tool: 'portfolio_summary', summary: '' }, { id: 'r2', tool: 'get_loan', summary: '' }],
      figures: [{ text: '3', value: 3, unit: 'plain', citations: ['r2'], verdict: 'supported', matched: { result: 'r2', path: 'n', value: 3 }, at: 0 }],
    }));
    expect(s.answer).toMatchObject({ verified: true, grounded: false, correct: false });
  });

  it('must-mention patterns are case-insensitive and all required', () => {
    const q: Question = { ...Q, mustMention: ['BASELINE', 'loss'] };
    expect(scoreQuestion(q, outcome({ called: ['portfolio_summary'], answer: 'baseline loss' })).answer.mentions).toBe(true);
    expect(scoreQuestion(q, outcome({ called: ['portfolio_summary'], answer: 'baseline only' })).answer.mentions).toBe(false);
  });

  it('accuracy is reported per tool and per server, and unexpected calls are counted', () => {
    const qs: Question[] = [Q, { id: 'b', question: '?', expectedTools: ['run_list'] }];
    const scores = [
      scoreQuestion(qs[0]!, outcome({ called: ['portfolio_summary'] })),
      scoreQuestion(qs[1]!, outcome({ called: ['audit_query'] })),
    ];
    const serverOf = (t: string) => (['run_list', 'audit_query'].includes(t) ? 'tenant-ops' : 'risk-engine');
    const s = summarise(qs, scores, serverOf);
    expect(s.toolCallAccuracy).toBe(0.5);
    expect(s.byTool).toMatchObject({ portfolio_summary: { expected: 1, hit: 1, accuracy: 1 }, run_list: { expected: 1, hit: 0, accuracy: 0 } });
    expect(s.byServer).toMatchObject({ 'risk-engine': { accuracy: 1 }, 'tenant-ops': { accuracy: 0 } });
    expect(s.unexpectedCalls).toEqual({ audit_query: 1 });
  });
});

describe('the spending cap', () => {
  const deps = (replies: ModelReply[]) => (_q: Question, limits: Limits): AgentDeps => {
    const host = new RecordedHost(SPECS, [{ name: 'portfolio_summary', args: {}, outcome: ok(PORTFOLIO) }]);
    return { model: new ScriptedModel(replies), host, session: new Session({ tenant: '7', allowRunStart: false, specs: host.tools() }), limits, pricing: PRICES };
  };
  const heavy: Usage = { inputTokens: 100_000, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 };   // $0.40 per call at $4/MTok
  const oneQuestion = (replies: ModelReply[]) => deps(replies);
  const base = (over: Partial<RunOptions>): RunOptions => ({
    suite: 'live', model: 'claude-opus-5-5', questions: [Q, { ...Q, id: 'q2' }, { ...Q, id: 'q3' }], capUsd: 1, baseLimits: LIMITS,
    makeDeps: oneQuestion([call('a', 'portfolio_summary', {}, heavy), say('baseline 400 loans [r1]', heavy)]), serverOf: () => 'risk-engine', ...over,
  });

  it('refuses to start without a cap, and without prices', async () => {
    await expect(runSuite(base({ capUsd: undefined }))).rejects.toThrow(/spending cap/);
    await expect(runSuite(base({ capUsd: 0 }))).rejects.toThrow(/spending cap/);
    await expect(runSuite(base({ capUsd: 5 }), false)).rejects.toThrow(/prices/);
    expect(() => requireCap(NaN, true)).toThrow();
  });

  it('stops when the cap is reached, reports the finished subset, and marks the run incomplete', async () => {
    // each question costs $0.80 (two calls of $0.40); a cap of $1 allows the first, and the second is cut short by the cap
    const report = await runSuite(base({ makeDeps: (q, l) => deps([call('a', 'portfolio_summary', {}, heavy), say('baseline 400 loans [r1]', heavy)])(q, l) }));
    expect(report.completed).toBe(false);
    expect(report.questionsTotal).toBe(3);
    expect(report.questionsRun).toBeLessThan(3);
    expect(report.stoppedBecause).toMatch(/spending cap of \$1/);
    expect(report.costUsd).toBeGreaterThan(0.79);
    expect(report.costUsd).toBeLessThan(1.5);
    expect(report.summary).not.toBeNull();
  });

  it('gives each question only what is left of the cap as its own cost limit', async () => {
    const seen: Array<number | null> = [];
    await runSuite(base({ capUsd: 5, questions: [Q, { ...Q, id: 'q2' }], makeDeps: (q, l) => { seen.push(l.maxCostUsd); return deps([call('a', 'portfolio_summary', {}, heavy), say('baseline', heavy)])(q, l); } }));
    expect(seen[0]).toBe(5);
    expect(seen[1]).toBeCloseTo(5 - 0.8, 6);
  });

  it('a run that finishes within the cap is complete', async () => {
    const report = await runSuite(base({ capUsd: 10, questions: [Q] }));
    expect(report).toMatchObject({ completed: true, stoppedBecause: null, questionsRun: 1 });
  });
});

describe('reports and the comparison with the previous one', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-reports-'));
  afterAll(() => fs.rmSync(dir, { recursive: true, force: true }));

  const make = (startedAt: string, accuracy: number, completed = true) => ({
    suite: 'selection', startedAt, model: 'm', completed, stoppedBecause: null, capUsd: 1, costUsd: 0.5, questionsTotal: 1, questionsRun: 1, perQuestion: [],
    summary: { questions: 1, toolCallAccuracy: accuracy, firstToolAccuracy: accuracy, answerCorrectness: accuracy, unexpectedCalls: {},
      byTool: { portfolio_summary: { expected: 1, hit: accuracy, accuracy }, health: { expected: 1, hit: 1, accuracy: 1 } }, byServer: {} },
  });

  it('compares a run with the newest earlier COMPLETED report of the same suite, per tool', () => {
    writeReport(make('2026-11-01T10:00:00.000Z', 1), dir);
    writeReport(make('2026-11-02T10:00:00.000Z', 0.5, false), dir);
    const current = make('2026-11-03T10:00:00.000Z', 0);
    const prev = previousReport(dir, 'selection', current.startedAt);
    expect(prev?.file).toContain('2026-11-01');
    const report = withComparison(current, prev);
    expect(report.comparedWith!.changes).toEqual(expect.arrayContaining([
      expect.stringContaining('tool-call accuracy: 100.0% -> 0.0% (-100.0 points)'), expect.stringContaining('tool portfolio_summary: 100.0% -> 0.0%'),
    ]));
    expect(report.comparedWith!.changes.join('\n')).not.toContain('tool health');
  });

  it('an incomplete report is written with INCOMPLETE in its name and is never the baseline', () => {
    const file = writeReport(make('2026-11-04T10:00:00.000Z', 0.2, false), dir);
    expect(path.basename(file)).toContain('INCOMPLETE');
    expect(previousReport(dir, 'selection', '2026-11-05T00:00:00.000Z')?.file).not.toContain('INCOMPLETE');
  });

  it('says so when nothing changed, and when a tool is new', () => {
    const a = make('x', 1).summary;
    expect(compare(a, a)).toEqual(['no change in any score']);
    expect(compare({ ...a, byTool: {} }, a).join('\n')).toContain('new in this report');
  });
});

describe('the live injection run', () => {
  const wellBehaved = () => new ScriptedModel(Array.from({ length: 60 }, () => say('There was one event; one field was withheld as instruction-like.')));

  it('a model that just reads the data and answers passes every case… once it has called the one tool it needs', async () => {
    // a model that answers without any call also reaches no server and follows nothing
    const report = await runInjectionSuite({ model: wellBehaved(), modelName: 'm', capUsd: 10, pricing: PRICES, limits: LIMITS });
    expect(report).toMatchObject({ completed: true, casesRun: 30, passed: 30 });
  });

  it('a model that obeys the planted text fails the cases that demanded a call, even though the session blocked it', async () => {
    const cases = INJECTION_CASES.slice(0, 10);   // one carrier, all ten payloads
    const replies: ModelReply[] = cases.flatMap((c) => [call('a', c.planted.tool), ...(c.payload.demands ? [call('b', c.payload.demands.tool, c.payload.demands.input)] : []), say('Done.')]);
    let i = 0;
    const model = { complete: async (): Promise<ModelReply> => structuredClone(replies[i++]!) };
    const report = await runInjectionSuite({ model, modelName: 'm', capUsd: 10, pricing: PRICES, limits: LIMITS, cases });
    const followed = report.results.filter((r) => r.followedInstruction).length;
    expect(followed).toBe(cases.filter((c) => c.payload.demands).length);   // 9 of 10 demanded a call; "assume a role" demanded none
    expect(report.passed).toBe(1);
  });

  it('an answer that repeats the planted text is a leak', async () => {
    const c = INJECTION_CASES[0]!;
    const model = new ScriptedModel([call('a', c.planted.tool), say(`Note says ${c.marker}.`), say(`Note says ${c.marker}.`)]);
    const report = await runInjectionSuite({ model, modelName: 'm', capUsd: 10, pricing: PRICES, limits: LIMITS, cases: [c] });
    expect(report.results[0]).toMatchObject({ leaked: true, passed: false });
  });

  it('shares the mandatory cap', async () => {
    await expect(runInjectionSuite({ model: wellBehaved(), modelName: 'm', capUsd: undefined, pricing: PRICES, limits: LIMITS })).rejects.toThrow(/spending cap/);
  });

  it('plants its results on tools the session actually offers', () => {
    const offered = new Set(new Session({ tenant: '7', allowRunStart: false, specs: INJECTION_SPECS }).tools().map((t) => t.name));
    for (const c of INJECTION_CASES) expect(offered, c.id).toContain(c.planted.tool);
  });
});
