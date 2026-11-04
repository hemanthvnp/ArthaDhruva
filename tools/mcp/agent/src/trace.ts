import fs from 'node:fs';
import path from 'node:path';
import type { Redactor } from '@arthadhruva/mcp-kit';

/**
 * A local record of one question: each model step, each tool call (what was asked of which tool and how it ended, not what it
 * returned), tokens, estimated cost and the verifier's verdict. It exists so a surprising answer can be explained afterwards. It
 * holds no credentials (the session cookie never passes through the agent; the redactor removes any registered secret from
 * every string anyway) and no tool-result bodies, which may hold a customer's data and live in the servers' own audit trail.
 */
export type TraceEvent =
  | { kind: 'model'; step: number; stopReason: string | null; inputTokens: number; outputTokens: number; toolCalls: number }
  | { kind: 'tool'; step: number; tool: string; args: Record<string, unknown>; result: string | null; outcome: 'ok' | 'error' | 'refused'; detail: string | null; ms: number }
  | { kind: 'verify'; attempt: number; verified: boolean; figures: number; offending: string[] }
  | { kind: 'stop'; limit: string | null; reason: string };

export class Trace {
  readonly events: TraceEvent[] = [];

  add(event: TraceEvent): void {
    this.events.push(event);
  }

  /** Writes the trace as JSON under `dir` and returns the file name; best effort, a failure to write never fails the question. */
  write(dir: string, summary: Record<string, unknown>, redactor?: Redactor): string | undefined {
    try {
      fs.mkdirSync(dir, { recursive: true });
      const stamp = new Date().toISOString().replaceAll(':', '-');
      const file = path.join(dir, `${stamp}-${Math.random().toString(36).slice(2, 8)}.json`);
      const body = { ...summary, events: this.events };
      const safe = redactor ? redactor.value<Record<string, unknown>>(body) : body;
      fs.writeFileSync(file, JSON.stringify(safe, null, 2), 'utf8');
      return file;
    } catch {
      return undefined;
    }
  }
}
