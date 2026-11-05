import fs from 'node:fs';
import path from 'node:path';
import { ask, type AgentDeps, type Outcome } from '../agent.js';
import type { Limits } from '../types.js';
import { compare, scoreQuestion, summarise, type Question, type QuestionScore, type Summary } from './scoring.js';

/**
 * The live suites cost real money, so the runner is built around a spending cap that is mandatory: it will not start without one
 * (and without prices to turn tokens into dollars), it gives each question only what is left of the cap as its own cost limit, and
 * when the cap is reached it stops, reports the questions it finished, and marks the report incomplete. A report that is incomplete
 * says so in its first field; a score from a partial run is never presented as the whole.
 */
export interface Report {
  suite: string;
  startedAt: string;
  model: string;
  /** False when the run stopped early (the cap) or a question could not be run. */
  completed: boolean;
  stoppedBecause: string | null;
  capUsd: number;
  costUsd: number;
  questionsTotal: number;
  questionsRun: number;
  summary: Summary | null;
  perQuestion: QuestionScore[];
  /** How this run differs from the previous report, when one was given. */
  comparedWith?: { report: string; changes: string[] };
}

export interface RunOptions {
  suite: string;
  model: string;
  questions: Question[];
  capUsd: number | undefined;
  baseLimits: Limits;
  /** Builds the agent for one question under the limits it may use. */
  makeDeps: (question: Question, limits: Limits) => AgentDeps;
  serverOf: (tool: string) => string;
  now?: () => Date;
  /** Called after each question, for progress. */
  onQuestion?: (score: QuestionScore, spent: number) => void;
}

export function requireCap(capUsd: number | undefined, pricingKnown: boolean): number {
  if (capUsd === undefined || !Number.isFinite(capUsd) || capUsd <= 0) {
    throw new Error('Refusing to start: the live suites cost money and need a spending cap. Set AGENT_EVAL_CAP_USD to a positive number of dollars.');
  }
  if (!pricingKnown) throw new Error('Refusing to start: the spending cap is in dollars, so the model\'s prices are needed (AGENT_PRICE_INPUT_PER_MTOK and AGENT_PRICE_OUTPUT_PER_MTOK).');
  return capUsd;
}

export async function runSuite(options: RunOptions, pricingKnown = true): Promise<Report> {
  const cap = requireCap(options.capUsd, pricingKnown);
  const now = options.now ?? (() => new Date());
  const startedAt = now().toISOString();
  const scores: QuestionScore[] = [];
  let spent = 0;
  let stoppedBecause: string | null = null;

  for (const question of options.questions) {
    const remaining = cap - spent;
    if (remaining <= 0) {
      stoppedBecause = `the spending cap of $${cap} was reached after ${scores.length} of ${options.questions.length} questions`;
      break;
    }
    const limits: Limits = { ...options.baseLimits, maxCostUsd: Math.min(options.baseLimits.maxCostUsd ?? Infinity, remaining) };
    let outcome: Outcome;
    try {
      outcome = await ask(question.question, options.makeDeps(question, limits));
    } catch (e) {
      stoppedBecause = `question ${question.id} could not be run: ${e instanceof Error ? e.message : 'unknown error'}`;
      break;
    }
    spent += outcome.costUsd ?? 0;
    const score = scoreQuestion(question, outcome);
    scores.push(score);
    options.onQuestion?.(score, spent);
    if (outcome.stoppedBy === 'cost' && spent >= cap && scores.length < options.questions.length) {
      stoppedBecause = `the spending cap of $${cap} was reached during question ${question.id}; ${options.questions.length - scores.length} question(s) were not run`;
      break;
    }
  }

  const completed = stoppedBecause === null && scores.length === options.questions.length;
  const ran = options.questions.filter((q) => scores.some((s) => s.id === q.id));
  return {
    suite: options.suite, startedAt, model: options.model, completed, stoppedBecause, capUsd: cap, costUsd: spent,
    questionsTotal: options.questions.length, questionsRun: scores.length,
    summary: scores.length ? summarise(ran, scores, options.serverOf) : null, perQuestion: scores,
  };
}

/** Writes a report as JSON and returns its path. Names sort by time, so "the previous report" is the last one before it. */
export function writeReport(report: Report, dir: string): string {
  fs.mkdirSync(dir, { recursive: true });
  const stamp = report.startedAt.replaceAll(':', '-');
  const file = path.join(dir, `${report.suite}-${stamp}${report.completed ? '' : '-INCOMPLETE'}.json`);
  fs.writeFileSync(file, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  return file;
}

/** The newest completed report of this suite in `dir` that is older than `before`, if any. */
export function previousReport(dir: string, suite: string, before: string): { file: string; report: Report } | undefined {
  if (!fs.existsSync(dir)) return undefined;
  const files = fs.readdirSync(dir).filter((f) => f.startsWith(`${suite}-`) && f.endsWith('.json') && !f.includes('INCOMPLETE')).sort();
  for (const f of files.reverse()) {
    const report = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8')) as Report;
    if (report.startedAt < before && report.summary) return { file: f, report };
  }
  return undefined;
}

export function withComparison(report: Report, previous: { file: string; report: Report } | undefined): Report {
  if (!previous?.report.summary || !report.summary) return report;
  return { ...report, comparedWith: { report: previous.file, changes: compare(previous.report.summary, report.summary) } };
}
