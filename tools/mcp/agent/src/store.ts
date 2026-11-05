import type { ToolOutcome } from './types.js';

/**
 * Every tool result the agent receives, kept whole under an id (`r1`, `r2`, ...). The model is shown a neutralised, fenced
 * copy (neutralise.ts); the verifier checks the figures in its answer against THIS copy, the raw one, so a figure the model
 * cites is held to what the server actually said, not to what the model was shown.
 */
export interface StoredResult {
  id: string;
  tool: string;
  args: Record<string, unknown>;
  outcome: ToolOutcome;
  /** True for a result the agent wrote itself (a refusal by the session), which is not tool output and is shown as written. */
  trusted?: boolean;
}

export class ResultStore {
  private readonly results = new Map<string, StoredResult>();

  add(tool: string, args: Record<string, unknown>, outcome: ToolOutcome, trusted = false): StoredResult {
    const id = `r${this.results.size + 1}`;
    const stored: StoredResult = { id, tool, args, outcome, ...(trusted ? { trusted } : {}) };
    this.results.set(id, stored);
    return stored;
  }

  get(id: string): StoredResult | undefined {
    return this.results.get(id);
  }

  all(): StoredResult[] {
    return [...this.results.values()];
  }
}
