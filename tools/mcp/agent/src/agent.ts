import type { Redactor } from '@arthadhruva/mcp-kit';
import { Budget } from './budget.js';
import { neutralise, present } from './neutralise.js';
import { openingMessage, retryMessage, SYSTEM_PROMPT } from './prompt.js';
import type { Session } from './session.js';
import { ResultStore } from './store.js';
import { Trace } from './trace.js';
import type { Block, LimitName, Limits, Message, Model, ModelReply, Pricing, ToolHost, ToolResultBlock, ToolUseBlock } from './types.js';
import { ModelError } from './types.js';
import { describe, verify, type FigureCheck } from './verifier.js';

/**
 * One question, start to finish: a tool-use loop under hard limits, with the tools gated by the session, tool results shown to
 * the model neutralised and fenced, and the final answer checked figure by figure against the raw results. One retry if the check
 * fails; after that the answer is withheld and the offending figures are listed.
 *
 * It never throws for something that went wrong in the world (a tool error, a refusal, a limit, the model failing): those are
 * outcomes, because a partial answer with the reason it stopped is more useful than an exception.
 */
export type Status = 'verified' | 'could-not-verify' | 'stopped' | 'refused' | 'error';

export interface Finding {
  id: string;
  tool: string;
  summary: string;
}

export interface Outcome {
  status: Status;
  /** The verified answer; for `could-not-verify` and `stopped` the last draft, which is NOT verified. */
  answer: string | null;
  /** Set when status is `stopped`. */
  stoppedBy: LimitName | null;
  /** Why it ended, in a sentence. */
  reason: string;
  figures: FigureCheck[];
  offending: FigureCheck[];
  /** What the tools returned, id by id: what was gathered, whether or not an answer came of it. */
  findings: Finding[];
  /** The tools that reached a server, in order (a refused call is not here). */
  called: string[];
  /** The tools the model asked for that the session refused (not offered, run start without the flag, another tenant): none of these reached a server. */
  refused: string[];
  steps: number;
  tokens: number;
  costUsd: number | null;
  seconds: number;
  traceFile?: string;
}

export interface AgentDeps {
  model: Model;
  host: ToolHost;
  session: Session;
  limits: Limits;
  pricing: Pricing | null;
  redactor?: Redactor;
  now?: () => number;
  /** Where to write the trace; null for none. */
  traceDir?: string | null;
  /** How many corrections of an unverifiable answer to allow. The contract is one. */
  retries?: number;
}

const textOf = (blocks: Block[]) => blocks.filter((b): b is { type: 'text'; text: string } => b.type === 'text').map((b) => b.text).join('\n').trim();
const toolUses = (blocks: Block[]) => blocks.filter((b): b is ToolUseBlock => b.type === 'tool_use');

export async function ask(question: string, deps: AgentDeps): Promise<Outcome> {
  const { model, host, session, limits } = deps;
  const budget = new Budget(limits, deps.pricing, deps.now);
  const store = new ResultStore();
  const trace = new Trace();
  const allowed = session.tools();
  const toolDefs = allowed.map((t) => ({ name: t.name, description: t.description, inputSchema: t.inputSchema }));
  const messages: Message[] = [{ role: 'user', content: openingMessage(session.tenant, question) }];
  let lastText = '';
  let retries = 0;
  let step = 0;

  const finish = (status: Status, reason: string, extra: Partial<Pick<Outcome, 'answer' | 'stoppedBy' | 'figures' | 'offending'>> = {}): Outcome => {
    const snap = budget.snapshot();
    const outcome: Outcome = {
      status, reason, answer: extra.answer ?? null, stoppedBy: extra.stoppedBy ?? null, figures: extra.figures ?? [], offending: extra.offending ?? [],
      findings: store.all().map((r) => ({ id: r.id, tool: r.tool, summary: summarise(r.outcome.text, r.outcome.isError) })),
      called: trace.events.flatMap((e) => (e.kind === 'tool' && e.outcome !== 'refused' ? [e.tool] : [])),
      refused: trace.events.flatMap((e) => (e.kind === 'tool' && e.outcome === 'refused' ? [e.tool] : [])),
      steps: snap.steps, tokens: snap.tokens, costUsd: snap.costUsd, seconds: snap.seconds,
    };
    trace.add({ kind: 'stop', limit: outcome.stoppedBy, reason });
    if (deps.traceDir) {
      const file = trace.write(deps.traceDir, {
        question, tenant: session.tenant, status, reason, steps: snap.steps, tokens: snap.tokens, costUsd: snap.costUsd, seconds: snap.seconds,
        toolsOffered: allowed.map((t) => t.name),
      }, deps.redactor);
      if (file) outcome.traceFile = file;
    }
    return outcome;
  };

  for (;;) {
    const limit = budget.exceeded();
    if (limit) return finish('stopped', `The ${LIMIT_WORDS[limit]} limit was reached before an answer was verified.`, { stoppedBy: limit, answer: lastText || null });

    let reply: ModelReply;
    try {
      reply = await model.complete({
        system: SYSTEM_PROMPT, messages, tools: toolDefs,
        signal: AbortSignal.timeout(Math.max(1000, budget.remainingSeconds() * 1000)),
      });
    } catch (e) {
      const why = e instanceof ModelError ? e.message : 'The model call failed.';
      return finish('error', why, { answer: lastText || null });
    }
    step++;
    budget.record(reply.usage);
    const uses = toolUses(reply.content);
    trace.add({ kind: 'model', step, stopReason: reply.stopReason, inputTokens: reply.usage.inputTokens, outputTokens: reply.usage.outputTokens, toolCalls: uses.length });
    messages.push({ role: 'assistant', content: reply.content });   // exactly as it came: thinking blocks must go back unchanged

    if (reply.stopReason === 'refusal') {
      const category = reply.stopDetails?.category ? ` (${reply.stopDetails.category})` : '';
      return finish('refused', `The model declined to answer${category}.${reply.stopDetails?.explanation ? ` ${reply.stopDetails.explanation}` : ''}`, { answer: lastText || null });
    }
    const text = textOf(reply.content);
    if (text) lastText = text;
    if (reply.stopReason === 'max_tokens') {
      return finish('stopped', 'The model reached its output limit before finishing.', { stoppedBy: 'output', answer: lastText || null });
    }

    if (uses.length > 0) {
      const results: ToolResultBlock[] = [];
      for (const use of uses) {
        const over = budget.exceeded(true);
        results.push(await runTool(use, over, deps, store, trace, step, budget));
      }
      messages.push({ role: 'user', content: results });
      continue;
    }

    // The model has answered.
    const check = verify(text, store);
    trace.add({ kind: 'verify', attempt: retries, verified: check.verified, figures: check.figures.length, offending: check.offending.map(describe) });
    if (check.verified) {
      return finish('verified', check.figures.length === 0
        ? 'The answer contains no figures to verify.'
        : `All ${check.figures.length} figure(s) are supported by the tool results they cite.`, { answer: text, figures: check.figures });
    }
    if (retries < (deps.retries ?? 1)) {
      retries++;
      messages.push({ role: 'user', content: retryMessage(check.offending.map(describe)) });
      continue;
    }
    return finish('could-not-verify', `${check.offending.length} figure(s) could not be verified after a correction was allowed.`,
      { answer: text, figures: check.figures, offending: check.offending });
  }
}

const LIMIT_WORDS: Record<LimitName, string> = { steps: 'step', tokens: 'token', time: 'time', cost: 'cost', output: 'output' };

async function runTool(
  use: ToolUseBlock, overLimit: LimitName | null, deps: AgentDeps, store: ResultStore, trace: Trace, step: number, budget: Budget,
): Promise<ToolResultBlock> {
  const { host, session } = deps;
  const refuse = (reason: string): ToolResultBlock => {
    const stored = store.add(use.name, {}, { isError: true, text: reason, structured: { error: { code: 'Refused', message: reason } }, errorCode: 'Refused' }, true);
    trace.add({ kind: 'tool', step, tool: use.name, args: {}, result: stored.id, outcome: 'refused', detail: reason, ms: 0 });
    return { type: 'tool_result', tool_use_id: use.id, content: present(stored), is_error: true };
  };
  if (overLimit) return refuse(`The ${LIMIT_WORDS[overLimit]} limit was reached, so this call was not made.`);
  const verdict = session.check(use.name, use.input);
  if (!verdict.ok) return refuse(verdict.reason);

  const started = Date.now();
  let outcome;
  try {
    outcome = await host.call(use.name, verdict.args, AbortSignal.timeout(Math.max(1000, budget.remainingSeconds() * 1000)));
  } catch (e) {
    outcome = { isError: true, text: 'The tool call failed.', structured: { error: { code: 'Internal', message: e instanceof Error ? e.message : 'The tool call failed.' } }, errorCode: 'Internal' };
  }
  const stored = store.add(use.name, verdict.args, outcome);
  trace.add({
    kind: 'tool', step, tool: use.name, args: verdict.args, result: stored.id, outcome: outcome.isError ? 'error' : 'ok',
    detail: outcome.isError ? (outcome.errorCode ?? 'error') : null, ms: Date.now() - started,
  });
  return { type: 'tool_result', tool_use_id: use.id, content: present(stored), ...(outcome.isError ? { is_error: true } : {}) };
}

function summarise(text: string, isError: boolean): string {
  const shown = String(neutralise(text.replace(/\s+/g, ' ').trim()).value);
  return `${isError ? 'error: ' : ''}${shown.length > 160 ? `${shown.slice(0, 160)}...` : shown}`;
}
