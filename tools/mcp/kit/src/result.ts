import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { ToolError } from './errors.js';

/**
 * The one shape every tool returns: text for a person (or a model reading it as text), and the same facts as
 * `structuredContent` for a program. An error is a result with `isError` and a typed code, never a thrown
 * exception or a stack trace.
 */
export interface Envelope<T> {
  text: string;
  data: T;
}

export function ok<T extends Record<string, unknown>>(text: string, data: T): CallToolResult {
  return { content: [{ type: 'text', text }], structuredContent: data };
}

export function errorResult(error: ToolError): CallToolResult {
  const retry = error.retryAfterSec === undefined ? '' : ` Retry after ${error.retryAfterSec} s.`;
  return {
    isError: true,
    content: [{ type: 'text', text: `${error.code}: ${error.message}${retry}` }],
    structuredContent: {
      error: {
        code: error.code,
        message: error.message,
        ...(error.retryAfterSec === undefined ? {} : { retryAfterSec: error.retryAfterSec }),
        ...(error.details === undefined ? {} : { details: error.details }),
      },
    },
  };
}

/** The typed error carried by an error result, for clients and tests. */
export function errorOf(result: CallToolResult): { code: string; message: string; retryAfterSec?: number; details?: Record<string, unknown> } | undefined {
  if (!result.isError) return undefined;
  const sc = result.structuredContent as { error?: { code: string; message: string; retryAfterSec?: number } } | undefined;
  return sc?.error;
}
