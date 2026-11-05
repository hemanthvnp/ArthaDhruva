import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { ToolError } from './errors.js';

/**
 * The one shape every tool returns: text for a person (or a model reading it as text), and the same facts as
 * `structuredContent` for a program. An error is a result with `isError` and a typed code (in `_meta`), never a thrown
 * exception or a stack trace.
 */
export interface Envelope<T> {
  text: string;
  data: T;
}

export function ok<T extends Record<string, unknown>>(text: string, data: T): CallToolResult {
  return { content: [{ type: 'text', text }], structuredContent: data };
}

/**
 * Where a typed error rides on an error result. NOT in `structuredContent`: a client that has listed the tools validates a result's
 * `structuredContent` against the tool's output schema (the MCP SDK does so even when `isError` is set), so an error object there
 * made every real client fail with a protocol error the moment any tool failed. The MCP specification does not require an error
 * result to match the output schema; `_meta` is the place for extra facts about a result.
 */
export const ERROR_META = 'io.arthadhruva/error';

export interface TypedError {
  code: string;
  message: string;
  retryAfterSec?: number;
  details?: Record<string, unknown>;
}

export function errorResult(error: ToolError): CallToolResult {
  const retry = error.retryAfterSec === undefined ? '' : ` Retry after ${error.retryAfterSec} s.`;
  const typed: TypedError = {
    code: error.code,
    message: error.message,
    ...(error.retryAfterSec === undefined ? {} : { retryAfterSec: error.retryAfterSec }),
    ...(error.details === undefined ? {} : { details: error.details }),
  };
  return {
    isError: true,
    content: [{ type: 'text', text: `${error.code}: ${error.message}${retry}` }],
    _meta: { [ERROR_META]: typed },
  };
}

/** The typed error carried by an error result, for clients and tests; undefined for a result that is not an error. */
export function errorOf(result: CallToolResult): TypedError | undefined {
  if (!result.isError) return undefined;
  const meta = result._meta?.[ERROR_META] as TypedError | undefined;
  if (meta) return meta;
  // Results from before the error moved to _meta carried it in structuredContent.
  return (result.structuredContent as { error?: TypedError } | undefined)?.error;
}
