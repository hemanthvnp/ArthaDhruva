import type { Redactor } from './redact.js';

/**
 * One line per tool call on stderr: when, which tool, how long, and how it ended. Never the arguments or the
 * result, and never stdout, which belongs to the MCP protocol on a stdio server.
 */
export type Sink = (line: string) => void;

export const stderrSink: Sink = (line) => {
  process.stderr.write(`${line}\n`);
};

export function callLine(server: string, tool: string, durationMs: number, outcome: string): string {
  return `${new Date().toISOString()} ${server} tool=${tool} ms=${Math.round(durationMs)} outcome=${outcome}`;
}

export class CallLog {
  constructor(private readonly server: string, private readonly redactor: Redactor, private readonly sink: Sink = stderrSink) {}

  call(tool: string, durationMs: number, outcome: string): void {
    this.sink(this.redactor.text(callLine(this.server, tool, durationMs, outcome)));
  }

  /** An unexpected failure inside a tool: the stack goes to stderr (redacted), never to the client. */
  internal(tool: string, error: unknown): void {
    const detail = error instanceof Error ? (error.stack ?? error.message) : String(error);
    this.sink(this.redactor.text(`${new Date().toISOString()} ${this.server} tool=${tool} internal-error ${detail}`));
  }
}
