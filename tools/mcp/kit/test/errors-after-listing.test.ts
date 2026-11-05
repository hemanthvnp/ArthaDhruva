import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { describe, expect, it } from 'vitest';
import { z } from 'zod';
import { Redactor, ToolError, buildServer, connect, defineTool, errorOf } from '@arthadhruva/mcp-kit';

/**
 * Regression: a tool error must reach a client that has LISTED the tools.
 *
 * The MCP SDK's client remembers each tool's output schema from listTools and checks every later result's `structuredContent`
 * against it, error results included. The kit used to carry a typed error as `structuredContent: { error }`, which never matches a
 * tool's output schema, so every real client (which always lists tools first) failed with a protocol error the moment a tool failed,
 * instead of getting the typed error. The tests that call a tool without listing first never saw it. This one lists first.
 */
const failing = defineTool({
  name: 'failing', title: 'Failing', description: 'Always fails.',
  input: {}, output: { value: z.number() },
  annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
  uses: [],
  handler: async () => { throw new ToolError('RateLimited', 'slow down', { retryAfterSec: 12, details: { limit: 5 } }); },
});

const working = defineTool({
  name: 'working', title: 'Working', description: 'Works.',
  input: {}, output: { value: z.number() },
  annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
  uses: [],
  handler: async () => ({ text: 'ok', data: { value: 3 } }),
});

async function listedClient() {
  const { client, close } = await connect(buildServer({
    name: 't', version: '0', instructions: '', tools: [failing, working], adapters: [], redactor: new Redactor(), sink: () => undefined,
  }));
  await client.listTools();   // this is what makes the SDK validate later results
  return { client, close };
}

describe('errors after the tools have been listed', () => {
  it('a failing tool returns its typed error instead of a protocol error', async () => {
    const { client, close } = await listedClient();
    const r = (await client.callTool({ name: 'failing', arguments: {} })) as CallToolResult;
    expect(r.isError).toBe(true);
    expect(errorOf(r)).toEqual({ code: 'RateLimited', message: 'slow down', retryAfterSec: 12, details: { limit: 5 } });
    expect(r.content[0]).toMatchObject({ type: 'text', text: expect.stringContaining('RateLimited: slow down') });
    await close();
  });

  it('an error result carries no structuredContent, which is what keeps it out of the schema check', async () => {
    const { client, close } = await listedClient();
    const r = (await client.callTool({ name: 'failing', arguments: {} })) as CallToolResult;
    expect(r.structuredContent).toBeUndefined();
    await close();
  });

  it('a result that is not an error has no error to read, and a good call still validates', async () => {
    const { client, close } = await listedClient();
    const r = (await client.callTool({ name: 'working', arguments: {} })) as CallToolResult;
    expect(errorOf(r)).toBeUndefined();
    expect(r.structuredContent).toEqual({ value: 3 });
    await close();
  });

  it('an invalid argument is refused with a protocol-level error the SDK raises, as before', async () => {
    const { client, close } = await listedClient();
    const r = (await client.callTool({ name: 'working', arguments: { unexpected: 1 } })) as CallToolResult;
    expect(r.isError === true || r.structuredContent !== undefined).toBe(true);
    await close();
  });
});
