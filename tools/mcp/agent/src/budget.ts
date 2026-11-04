import type { LimitName, Limits, Pricing, Usage } from './types.js';

/**
 * The hard limits on one question: model calls, tokens, wall-clock time and estimated cost. Checked before every model call
 * and every tool call; the first one reached stops the question and is reported by name, with what was found so far.
 *
 * Cost is an estimate from token counts and the prices given to it (the SDK reports tokens, not dollars). It is exact only if the
 * prices are right, so they are configuration, and a cost limit without prices is refused at start-up instead of being quietly
 * unenforced. The token limit needs no prices and is the dependable backstop.
 */
export class Budget {
  private steps = 0;
  private tokens = 0;
  private cost = 0;
  private readonly started: number;

  constructor(
    readonly limits: Limits,
    private readonly pricing: Pricing | null,
    private readonly now: () => number = Date.now,
  ) {
    if (limits.maxCostUsd !== null && pricing === null) {
      throw new Error('A cost limit needs prices: set AGENT_PRICE_INPUT_PER_MTOK and AGENT_PRICE_OUTPUT_PER_MTOK (or use a model with known prices).');
    }
    this.started = now();
  }

  /** Records one model call. */
  record(usage: Usage): void {
    this.steps++;
    this.tokens += usage.inputTokens + usage.outputTokens + usage.cacheReadTokens + usage.cacheWriteTokens;
    if (this.pricing) {
      this.cost += (usage.inputTokens * this.pricing.inputPerMTok + usage.outputTokens * this.pricing.outputPerMTok
        + usage.cacheReadTokens * this.pricing.cacheReadPerMTok + usage.cacheWriteTokens * this.pricing.cacheWritePerMTok) / 1e6;
    }
  }

  /**
   * The limit that has been reached, or null. The step limit means "no more model calls": once the maxSteps-th has been made, the
   * tools that call asked for may still run (`ignoreSteps`), so what it asked for is gathered and reported, but no further call is made.
   */
  exceeded(ignoreSteps = false): LimitName | null {
    if (!ignoreSteps && this.steps >= this.limits.maxSteps) return 'steps';
    if (this.tokens >= this.limits.maxTokens) return 'tokens';
    if (this.elapsedSeconds() >= this.limits.maxSeconds) return 'time';
    if (this.limits.maxCostUsd !== null && this.cost >= this.limits.maxCostUsd) return 'cost';
    return null;
  }

  elapsedSeconds(): number {
    return (this.now() - this.started) / 1000;
  }

  /** Seconds left, for the timeout of a call that is about to be made. */
  remainingSeconds(): number {
    return Math.max(0, this.limits.maxSeconds - this.elapsedSeconds());
  }

  snapshot(): { steps: number; tokens: number; costUsd: number | null; seconds: number } {
    return { steps: this.steps, tokens: this.tokens, costUsd: this.pricing ? this.cost : null, seconds: this.elapsedSeconds() };
  }
}

/** Prices per million tokens, from the model's published rates; used only when the caller did not give any. */
export const KNOWN_PRICES: Record<string, Pricing> = {
  // Claude Opus 5.5: $4 / $20 per MTok, cache reads $0.20, cache writes at 1.25x input (5-minute).
  'claude-opus-5-5': { inputPerMTok: 4, outputPerMTok: 20, cacheReadPerMTok: 0.2, cacheWritePerMTok: 5 },
};
