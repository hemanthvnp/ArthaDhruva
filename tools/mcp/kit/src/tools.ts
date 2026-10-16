import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { z } from 'zod';
import { ToolError } from './errors.js';
import type { CallLog } from './log.js';
import { progressFor, type Extra, type Progress } from './progress.js';
import type { Redactor } from './redact.js';
import { errorResult, ok } from './result.js';

/**
 * A tool as every server declares it. Registration checks the declaration before the server starts:
 * annotations must be present and must not contradict what the tool does (D14), so "read-only by
 * construction" is a property the code enforces, not a convention.
 */
export interface Annotations {
  readOnlyHint: boolean;
  destructiveHint: boolean;
  idempotentHint: boolean;
  openWorldHint?: boolean;
}

export interface ToolContext {
  progress: Progress;
}

export interface ToolResult<O> {
  text: string;
  data: O;
}

export interface ToolDefinition<I extends z.ZodRawShape, O extends z.ZodRawShape> {
  name: string;
  title: string;
  description: string;
  input: I;
  output: O;
  annotations: Annotations;
  /** The adapter methods the handler calls, checked against the adapters' mutating marks. */
  uses: readonly string[];
  handler: (args: z.infer<z.ZodObject<I>>, ctx: ToolContext) => Promise<ToolResult<z.infer<z.ZodObject<O>>>>;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type AnyTool = ToolDefinition<any, any>;

export function defineTool<I extends z.ZodRawShape, O extends z.ZodRawShape>(tool: ToolDefinition<I, O>): ToolDefinition<I, O> {
  return tool;
}

export class RegistrationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'RegistrationError';
  }
}

/** Throws when a tool's annotations are missing or contradict the adapter methods it uses. */
export function checkAnnotations(tool: AnyTool, mutating: ReadonlySet<string>): void {
  const a = tool.annotations as Partial<Annotations> | undefined;
  for (const key of ['readOnlyHint', 'destructiveHint', 'idempotentHint'] as const) {
    if (typeof a?.[key] !== 'boolean') throw new RegistrationError(`Tool ${tool.name} does not declare ${key}.`);
  }
  const mutatingUsed = tool.uses.filter((m) => mutating.has(m));
  if (a!.readOnlyHint && mutatingUsed.length > 0) {
    throw new RegistrationError(`Tool ${tool.name} declares readOnlyHint but uses mutating ${mutatingUsed.join(', ')}.`);
  }
  if (a!.readOnlyHint && a!.destructiveHint) {
    throw new RegistrationError(`Tool ${tool.name} cannot be both read-only and destructive.`);
  }
}

export interface RegisterOptions {
  redactor: Redactor;
  log: CallLog;
  /** Every mutating method across the server's adapters. */
  mutating: ReadonlySet<string>;
}

/**
 * Registers tools on a server, each wrapped so that: arguments are validated by the SDK against the input
 * schema; the result is checked against the output schema before it leaves; any thrown error becomes a
 * typed error result; every string in the result is redacted; and one line is logged per call.
 */
export function registerTools(server: McpServer, tools: readonly AnyTool[], options: RegisterOptions): void {
  const names = new Set<string>();
  for (const tool of tools) {
    if (names.has(tool.name)) throw new RegistrationError(`Tool ${tool.name} is registered twice.`);
    names.add(tool.name);
    checkAnnotations(tool, options.mutating);
    const outputSchema = z.object(tool.output);
    server.registerTool(
      tool.name,
      {
        title: tool.title,
        description: tool.description,
        inputSchema: tool.input,
        outputSchema: tool.output,
        annotations: { title: tool.title, ...tool.annotations },
      },
      (async (args: unknown, extra: Extra) => invoke(tool, outputSchema, args, extra, options)) as never,
    );
  }
}

async function invoke(tool: AnyTool, outputSchema: z.ZodObject, args: unknown, extra: Extra, options: RegisterOptions): Promise<CallToolResult> {
  const started = performance.now();
  let result: CallToolResult;
  let outcome = 'ok';
  try {
    const { text, data } = await tool.handler(args as never, { progress: progressFor(extra) });
    const checked = outputSchema.safeParse(data);
    if (!checked.success) {
      options.log.internal(tool.name, new Error(`output does not match its schema: ${checked.error.message}`));
      throw new ToolError('Internal', `${tool.name} produced a result that does not match its declared schema.`);
    }
    result = ok(options.redactor.text(text), options.redactor.value(checked.data as Record<string, unknown>));
  } catch (e) {
    const error = e instanceof ToolError ? e : internal(tool.name, e, options.log);
    outcome = `error:${error.code}`;
    result = errorResult(new ToolError(error.code, options.redactor.text(error.message), {
      ...(error.retryAfterSec === undefined ? {} : { retryAfterSec: error.retryAfterSec }),
      ...(error.details === undefined ? {} : { details: options.redactor.value<Record<string, unknown>>(error.details) }),
    }));
  }
  options.log.call(tool.name, performance.now() - started, extra.signal.aborted ? 'cancelled' : outcome);
  return result;
}

function internal(tool: string, e: unknown, log: CallLog): ToolError {
  log.internal(tool, e);
  return new ToolError('Internal', `Unexpected error in ${tool}; the server's log has the detail.`);
}
