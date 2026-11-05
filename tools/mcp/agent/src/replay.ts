import type { Redactor } from '@arthadhruva/mcp-kit';
import type { Limits, Model, ModelReply, ModelRequest, ToolHost, ToolOutcome, ToolSpec } from './types.js';

/**
 * Replay and record. A real run is a conversation between the loop and two collaborators, the model and the tool servers; recorded,
 * both sides can be served from a file. Replayed, the loop, the session, the verifier and the budgets run exactly as in a live run
 * with no network, no model and no cost, which is what lets the deterministic suite run in CI and fixtures made from real runs
 * keep it growing.
 *
 * A fixture is data only. It carries no credential: recording redacts every string with the redactor it is given.
 */
export interface RecordedToolCall {
  name: string;
  args: Record<string, unknown>;
  outcome: ToolOutcome;
}

export interface Fixture {
  name: string;
  description?: string;
  question: string;
  session: { tenant: string; allowRunStart: boolean };
  tools: ToolSpec[];
  limits?: Partial<Limits>;
  /** The model's replies, in order. */
  model: ModelReply[];
  /** What the tools returned, served by name and arguments (the same call twice is served in order). */
  toolResults: RecordedToolCall[];
  expect: {
    status: string;
    stoppedBy?: string | null;
    /** The verdict of each figure in the final answer, in order. */
    verdicts?: string[];
    /** Tool calls the host must have received, in order (the ones that got past the session). */
    toolCalls?: string[];
    /** Tool calls the session must have refused. */
    refused?: string[];
    steps?: number;
    answerContains?: string[];
    answerExcludes?: string[];
  };
}

/** Serves a script of replies; asking for one more than the script holds is a test failure, not a hang. */
export class ScriptedModel implements Model {
  readonly requests: ModelRequest[] = [];

  constructor(private readonly replies: readonly ModelReply[]) {}

  async complete(request: ModelRequest): Promise<ModelReply> {
    const { signal: _signal, ...rest } = request;
    this.requests.push(structuredClone(rest));
    const reply = this.replies[this.requests.length - 1];
    if (!reply) throw new Error(`The script has ${this.replies.length} reply(ies) and the agent asked for call ${this.requests.length}.`);
    return structuredClone(reply);
  }
}

const canonical = (v: unknown): string => JSON.stringify(v, (_k, x: unknown) =>
  x && typeof x === 'object' && !Array.isArray(x) ? Object.fromEntries(Object.entries(x as Record<string, unknown>).sort(([a], [b]) => a.localeCompare(b))) : x);

/** Serves recorded tool results; records what it was actually asked. */
export class RecordedHost implements ToolHost {
  readonly received: Array<{ name: string; args: Record<string, unknown> }> = [];
  private readonly used = new Set<number>();

  constructor(private readonly specs: readonly ToolSpec[], private readonly results: readonly RecordedToolCall[]) {}

  tools(): ToolSpec[] {
    return this.specs.map((s) => ({ ...s }));
  }

  async call(name: string, args: Record<string, unknown>): Promise<ToolOutcome> {
    this.received.push({ name, args: structuredClone(args) });
    const key = canonical(args);
    const index = this.results.findIndex((r, i) => !this.used.has(i) && r.name === name && canonical(r.args) === key);
    if (index < 0) {
      const text = `No recorded result for ${name}(${key}).`;
      return { isError: true, text, structured: { error: { code: 'NotFound', message: text } }, errorCode: 'NotFound' };
    }
    this.used.add(index);
    return structuredClone(this.results[index]!.outcome);
  }

  async close(): Promise<void> {}
}

/** Wraps a model and keeps what it said. */
export class RecordingModel implements Model {
  readonly replies: ModelReply[] = [];

  constructor(private readonly inner: Model) {}

  async complete(request: ModelRequest): Promise<ModelReply> {
    const reply = await this.inner.complete(request);
    this.replies.push(structuredClone(reply));
    return reply;
  }
}

/** Wraps a host and keeps what its tools returned. */
export class RecordingHost implements ToolHost {
  readonly calls: RecordedToolCall[] = [];

  constructor(private readonly inner: ToolHost) {}

  tools(): ToolSpec[] {
    return this.inner.tools();
  }

  async call(name: string, args: Record<string, unknown>, signal?: AbortSignal): Promise<ToolOutcome> {
    const outcome = await this.inner.call(name, args, signal);
    this.calls.push({ name, args: structuredClone(args), outcome: structuredClone(outcome) });
    return outcome;
  }

  close(): Promise<void> {
    return this.inner.close();
  }
}

/** A fixture from a recorded run, with every string redacted. The expectation is left for the person recording to state. */
export function toFixture(
  name: string, question: string, session: Fixture['session'], model: RecordingModel, host: RecordingHost, expect: Fixture['expect'], redactor?: Redactor,
): Fixture {
  const fixture: Fixture = {
    name, question, session, tools: host.tools(), model: model.replies, toolResults: host.calls, expect,
  };
  return redactor ? redactor.value(fixture) : fixture;
}
