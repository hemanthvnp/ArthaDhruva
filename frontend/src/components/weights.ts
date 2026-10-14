/**
 * Scenario weights as the console handles them: percentages typed per scenario, checked here, and sent to
 * the API as fractions. The API checks them again; this only keeps the apply button honest.
 */

/** An illustration, not the platform's view of the economy: offered as a starting point and labelled so. */
export const ILLUSTRATIVE_PRESET: Record<string, number> = { BASELINE: 50, ADVERSE: 30, SEVERELY_ADVERSE: 20 };

/** The preset restricted to the scenarios on screen; any other scenario starts at 0. */
export function presetFor(scenarios: string[]): Record<string, string> {
  return Object.fromEntries(scenarios.map((s) => [s, String(ILLUSTRATIVE_PRESET[s] ?? 0)]));
}

export interface ParsedWeights {
  /** fractions by scenario, only when the entries are valid and sum to 100% */
  weights: Record<string, number> | null;
  /** the sum of the valid entries, in percent */
  sum: number;
  /** why the weights cannot be applied; null when they can */
  problem: string | null;
}

/** Reads percentages typed per scenario; a blank entry counts as 0. */
export function parseWeights(entries: Record<string, string>): ParsedWeights {
  let sum = 0;
  const weights: Record<string, number> = {};
  for (const [scenario, raw] of Object.entries(entries)) {
    const text = raw.trim();
    const value = text === '' ? 0 : Number(text);
    if (!Number.isFinite(value) || value < 0) {
      return { weights: null, sum, problem: `The weight for ${scenario} must be a number of 0 or more.` };
    }
    sum += value;
    weights[scenario] = value / 100;
  }
  const rounded = Math.round(sum * 1e6) / 1e6;
  if (Math.abs(rounded - 100) > 1e-4) {
    return { weights: null, sum: rounded, problem: `The weights sum to ${formatPercent(rounded)}; they must sum to 100%.` };
  }
  return { weights, sum: rounded, problem: null };
}

/** "50% baseline, 30% adverse, 20% severely adverse": the weights used, in the order given, zeros left out. */
export function weightsLabel(weights: Record<string, number>, label: (scenario: string) => string): string {
  return Object.entries(weights)
    .filter(([, w]) => w > 0)
    .map(([s, w]) => `${formatPercent(w * 100)} ${label(s).toLowerCase()}`)
    .join(', ');
}

function formatPercent(value: number): string {
  return `${Number.isInteger(value) ? value : value.toFixed(1)}%`;
}

/** Stated beside every weighted figure: what the weighting does and does not do. */
export const WEIGHTED_METHOD_NOTE =
  'Each scenario is staged on its own path and its allowance weighted by the probability given. The weights are yours, not an estimate, and the stress scenarios are illustrative.';
