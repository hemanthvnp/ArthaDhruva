import { ask, type Outcome } from './agent.js';
import { KNOWN_PRICES } from './budget.js';
import { RecordedHost, ScriptedModel, type Fixture } from './replay.js';
import { Session } from './session.js';
import type { Limits } from './types.js';

/**
 * Runs a fixture through the real loop and compares what happened with what the fixture says should. Used by the deterministic
 * suite in CI (every fixture in test/fixtures) and by the eval harness; nothing here touches a network or a model.
 */
export const FIXTURE_LIMITS: Limits = { maxSteps: 12, maxTokens: 10_000_000, maxSeconds: 600, maxCostUsd: null };

export interface FixtureRun {
  outcome: Outcome;
  host: RecordedHost;
  model: ScriptedModel;
}

export async function runFixture(f: Fixture): Promise<FixtureRun> {
  const host = new RecordedHost(f.tools, f.toolResults);
  const model = new ScriptedModel(f.model);
  const limits: Limits = { ...FIXTURE_LIMITS, ...f.limits };
  const outcome = await ask(f.question, {
    model, host, session: new Session({ tenant: f.session.tenant, allowRunStart: f.session.allowRunStart, specs: host.tools() }),
    limits, pricing: KNOWN_PRICES['claude-opus-5-5']!,
  });
  return { outcome, host, model };
}

/** What differs from the fixture's expectation; empty means it passes. */
export function judge(f: Fixture, run: FixtureRun): string[] {
  const problems: string[] = [];
  const { outcome, host } = run;
  const e = f.expect;
  if (outcome.status !== e.status) problems.push(`status was ${outcome.status}, expected ${e.status} (${outcome.reason})`);
  if (e.stoppedBy !== undefined && outcome.stoppedBy !== e.stoppedBy) problems.push(`stoppedBy was ${outcome.stoppedBy}, expected ${e.stoppedBy}`);
  if (e.verdicts) {
    const got = outcome.figures.map((x) => x.verdict);
    if (JSON.stringify(got) !== JSON.stringify(e.verdicts)) problems.push(`figure verdicts were ${JSON.stringify(got)}, expected ${JSON.stringify(e.verdicts)}`);
  }
  if (e.toolCalls) {
    const got = host.received.map((c) => c.name);
    if (JSON.stringify(got) !== JSON.stringify(e.toolCalls)) problems.push(`the tool servers received ${JSON.stringify(got)}, expected ${JSON.stringify(e.toolCalls)}`);
  }
  if (e.refused && JSON.stringify(outcome.refused) !== JSON.stringify(e.refused)) problems.push(`the session refused ${JSON.stringify(outcome.refused)}, expected ${JSON.stringify(e.refused)}`);
  if (e.steps !== undefined && outcome.steps !== e.steps) problems.push(`took ${outcome.steps} model call(s), expected ${e.steps}`);
  const answer = outcome.answer ?? '';
  for (const s of e.answerContains ?? []) if (!answer.includes(s)) problems.push(`the answer does not contain "${s}"`);
  for (const s of e.answerExcludes ?? []) if (answer.includes(s)) problems.push(`the answer contains "${s}"`);
  return problems;
}
