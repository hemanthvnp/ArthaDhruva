import type { Outcome } from '../agent.js';

/**
 * Scoring for the live suites. Two scores, kept apart on purpose.
 *
 * **Tool selection** asks only "did it reach for the right tools?", independent of the answer: was the first tool the expected
 * one, and were all the expected tools called. Tools named `allowed` (a sensible follow-up) are neither expected nor wrong; any
 * other tool is `unexpected`.
 *
 * **Answer correctness** is stricter than "the figures are real": the answer must be verified (every figure cited and supported),
 * grounded in the right place (at least one supported figure comes from an expected tool, when the answer has figures), and say
 * what the question needs (`mustMention`, each a case-insensitive pattern). It deliberately does not compare against a stored
 * number: the live stack's data changes, so a fixed expected value would measure the data, not the agent. The grounding check is
 * what makes a number right.
 */
export interface Question {
  id: string;
  question: string;
  /** Tools a good answer calls. */
  expectedTools: string[];
  /** The tool a good agent reaches for first, when one is clearly right. */
  firstTool?: string;
  /** Tools that are a fine follow-up but not required. */
  allowedTools?: string[];
  /** Patterns the answer must match (case-insensitive). */
  mustMention?: string[];
  /** Needs operator mode (servers 3 to 5). */
  operator?: boolean;
}

export interface Selection {
  firstOk: boolean | null;
  hit: string[];
  missed: string[];
  unexpected: string[];
}

export interface AnswerScore {
  status: string;
  verified: boolean;
  grounded: boolean;
  mentions: boolean;
  correct: boolean;
}

export interface QuestionScore {
  id: string;
  called: string[];
  selection: Selection;
  answer: AnswerScore;
  costUsd: number | null;
  steps: number;
  stoppedBy: string | null;
}

export function scoreQuestion(q: Question, outcome: Outcome): QuestionScore {
  const called = outcome.called;
  const allowed = new Set([...q.expectedTools, ...(q.allowedTools ?? [])]);
  const selection: Selection = {
    firstOk: q.firstTool === undefined ? null : called[0] === q.firstTool,
    hit: q.expectedTools.filter((t) => called.includes(t)),
    missed: q.expectedTools.filter((t) => !called.includes(t)),
    unexpected: [...new Set(called.filter((t) => !allowed.has(t)))],
  };
  const expectedIds = new Set(outcome.findings.filter((f) => q.expectedTools.includes(f.tool)).map((f) => f.id));
  const supported = outcome.figures.filter((f) => f.verdict === 'supported');
  const verified = outcome.status === 'verified';
  const grounded = outcome.figures.length === 0 || supported.some((f) => f.matched && expectedIds.has(f.matched.result));
  const mentions = (q.mustMention ?? []).every((p) => new RegExp(p, 'i').test(outcome.answer ?? ''));
  return {
    id: q.id, called, selection,
    answer: { status: outcome.status, verified, grounded, mentions, correct: verified && grounded && mentions && selection.missed.length === 0 },
    costUsd: outcome.costUsd, steps: outcome.steps, stoppedBy: outcome.stoppedBy,
  };
}

export interface Summary {
  questions: number;
  /** Share of questions whose expected tools were all called and none unexpected. */
  toolCallAccuracy: number;
  /** Share whose first tool was the expected one, among those that name one. */
  firstToolAccuracy: number | null;
  answerCorrectness: number;
  byTool: Record<string, { expected: number; hit: number; accuracy: number }>;
  byServer: Record<string, { expected: number; hit: number; accuracy: number }>;
  /** Tools called that no question expected or allowed, with how often. */
  unexpectedCalls: Record<string, number>;
}

const rate = (n: number, d: number) => (d === 0 ? 0 : n / d);

export function summarise(questions: Question[], scores: QuestionScore[], serverOf: (tool: string) => string): Summary {
  const byQuestion = new Map(questions.map((q) => [q.id, q]));
  const byTool: Summary['byTool'] = {};
  const byServer: Summary['byServer'] = {};
  const unexpectedCalls: Record<string, number> = {};
  let toolOk = 0;
  let firstAsked = 0;
  let firstOk = 0;
  for (const s of scores) {
    const q = byQuestion.get(s.id)!;
    if (s.selection.missed.length === 0 && s.selection.unexpected.length === 0) toolOk++;
    if (s.selection.firstOk !== null) {
      firstAsked++;
      if (s.selection.firstOk) firstOk++;
    }
    for (const t of q.expectedTools) {
      const hit = s.selection.hit.includes(t) ? 1 : 0;
      const srv = serverOf(t);
      for (const [map, key] of [[byTool, t], [byServer, srv]] as const) {
        const e = (map[key] ??= { expected: 0, hit: 0, accuracy: 0 });
        e.expected++;
        e.hit += hit;
      }
    }
    for (const t of s.called) if (!(q.expectedTools.includes(t) || (q.allowedTools ?? []).includes(t))) unexpectedCalls[t] = (unexpectedCalls[t] ?? 0) + 1;
  }
  for (const map of [byTool, byServer]) for (const e of Object.values(map)) e.accuracy = rate(e.hit, e.expected);
  return {
    questions: scores.length, toolCallAccuracy: rate(toolOk, scores.length), firstToolAccuracy: firstAsked ? firstOk / firstAsked : null,
    answerCorrectness: rate(scores.filter((s) => s.answer.correct).length, scores.length), byTool, byServer, unexpectedCalls,
  };
}

/** What changed between two summaries: overall scores and any tool whose selection accuracy moved. */
export function compare(previous: Summary, current: Summary): string[] {
  const lines: string[] = [];
  const pct = (x: number) => `${(x * 100).toFixed(1)}%`;
  const delta = (name: string, a: number, b: number) => { if (Math.abs(a - b) > 1e-9) lines.push(`${name}: ${pct(a)} -> ${pct(b)} (${b > a ? '+' : ''}${((b - a) * 100).toFixed(1)} points)`); };
  delta('tool-call accuracy', previous.toolCallAccuracy, current.toolCallAccuracy);
  delta('answer correctness', previous.answerCorrectness, current.answerCorrectness);
  if (previous.firstToolAccuracy !== null && current.firstToolAccuracy !== null) delta('first-tool accuracy', previous.firstToolAccuracy, current.firstToolAccuracy);
  for (const tool of new Set([...Object.keys(previous.byTool), ...Object.keys(current.byTool)])) {
    const a = previous.byTool[tool];
    const b = current.byTool[tool];
    if (!a) lines.push(`${tool}: new in this report (${pct(b!.accuracy)})`);
    else if (!b) lines.push(`${tool}: no longer asked for`);
    else delta(`tool ${tool}`, a.accuracy, b.accuracy);
  }
  return lines.length ? lines : ['no change in any score'];
}
