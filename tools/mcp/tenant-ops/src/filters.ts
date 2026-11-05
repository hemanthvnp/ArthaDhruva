import { invalidInput } from '@arthadhruva/mcp-kit';

/**
 * Every filter a caller can name is checked here against a fixed list, before any query is built or run, and a
 * value that is not on the list is InvalidInput. Nothing a caller sends is ever put into SQL text: the SQL is
 * fixed in store.ts, a filter value is a bound parameter, and a column to sort by is chosen from the map below
 * by key, never from the caller's string.
 */
export const RUN_STATUSES = ['QUEUED', 'RUNNING', 'COMPLETED', 'FAILED'] as const;
export const OUTBOX_STATUSES = ['PENDING', 'IN_FLIGHT', 'DELIVERED', 'FAILED'] as const;
export const RUN_ORDER = { requestedAt: 'requested_at', startedAt: 'started_at', finishedAt: 'finished_at' } as const;

export const MAX_RUNS = 50;
export const MAX_OUTBOX_ROWS = 100;

export function oneOf<T extends string>(name: string, value: string | undefined, allowed: readonly T[]): T | undefined {
  if (value === undefined) return undefined;
  const found = allowed.find((a) => a === value.toUpperCase());
  if (!found) throw invalidInput(`${name} "${value.slice(0, 40)}" is not one of ${allowed.join(', ')}.`);
  return found;
}

export function orderColumn(value: string | undefined): (typeof RUN_ORDER)[keyof typeof RUN_ORDER] {
  if (value === undefined) return RUN_ORDER.requestedAt;
  if (!Object.hasOwn(RUN_ORDER, value)) {
    throw invalidInput(`orderBy "${value.slice(0, 40)}" is not one of ${Object.keys(RUN_ORDER).join(', ')}.`);
  }
  return RUN_ORDER[value as keyof typeof RUN_ORDER];
}

export function bounded(name: string, value: number | undefined, fallback: number, max: number): number {
  if (value === undefined) return fallback;
  if (!Number.isInteger(value) || value < 1 || value > max) throw invalidInput(`${name} must be a whole number from 1 to ${max}.`);
  return value;
}
