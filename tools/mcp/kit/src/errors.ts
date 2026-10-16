/**
 * The error vocabulary every server speaks. A tool never throws a stack trace at the client: whatever goes
 * wrong becomes one of these codes with a message a person can act on, and `Internal` (a bug in the tool
 * itself) says only that, with the detail left in the server's own stderr.
 */
export type ErrorCode =
  | 'NotFound'
  | 'Forbidden'
  | 'UpstreamDown'
  | 'Timeout'
  | 'RateLimited'
  | 'QuotaExceeded'
  | 'InvalidInput'
  | 'Conflict'
  | 'SessionExpired'
  | 'Internal';

export class ToolError extends Error {
  readonly code: ErrorCode;
  /** Seconds to wait before retrying, when the upstream said (RateLimited, QuotaExceeded, UpstreamDown). */
  readonly retryAfterSec: number | undefined;
  /** Extra structured facts for the caller (for Conflict: the run already in progress). */
  readonly details: Record<string, unknown> | undefined;

  constructor(code: ErrorCode, message: string, options: { retryAfterSec?: number; details?: Record<string, unknown> } = {}) {
    super(message);
    this.name = 'ToolError';
    this.code = code;
    this.retryAfterSec = options.retryAfterSec;
    this.details = options.details;
  }
}

export const notFound = (message: string) => new ToolError('NotFound', message);
export const forbidden = (message: string) => new ToolError('Forbidden', message);
export const invalidInput = (message: string) => new ToolError('InvalidInput', message);
export const upstreamDown = (message: string, retryAfterSec?: number) =>
  new ToolError('UpstreamDown', message, retryAfterSec === undefined ? {} : { retryAfterSec });
export const timeout = (message: string) => new ToolError('Timeout', message);

/** Parses an HTTP Retry-After header (seconds, or an HTTP date) into whole seconds; undefined when absent or unreadable. */
export function retryAfterSeconds(header: string | null | undefined, now: number = Date.now()): number | undefined {
  if (header == null || header.trim() === '') return undefined;
  const seconds = Number(header);
  if (Number.isFinite(seconds)) return Math.max(0, Math.ceil(seconds));
  const at = Date.parse(header);
  return Number.isNaN(at) ? undefined : Math.max(0, Math.ceil((at - now) / 1000));
}
