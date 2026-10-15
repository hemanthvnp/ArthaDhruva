import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { z } from 'zod';
import {
  CommandRunner, REDACTED, Redactor, RegistrationError, ToolError, buildServer, connect, defineTool, errorOf, markMutating,
  type AnyTool,
} from '@arthadhruva/mcp-kit';

const SECRET = 'session-cookie-value-123';
const lines: string[] = [];
const sink = (line: string) => lines.push(line);

const adapter = markMutating({
  read: async (id: string) => ({ id, note: `seen with ${SECRET}` }),
  write: async () => undefined,
}, ['write']);

const echo = defineTool({
  name: 'echo', title: 'Echo', description: 'Returns its input.',
  input: { id: z.string() }, output: { id: z.string(), note: z.string() },
  annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
  uses: ['read'],
  handler: async ({ id }) => {
    const data = await adapter.read(id);
    return { text: `Loan ${data.id}: ${data.note}`, data };
  },
});

const failing = (error: unknown) => defineTool({
  name: 'failing', title: 'Failing', description: 'Always fails.',
  input: {}, output: { never: z.string() },
  annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
  uses: [],
  handler: async () => { throw error; },
});

const badOutput = defineTool({
  name: 'bad_output', title: 'Bad output', description: 'Returns the wrong shape.',
  input: {}, output: { count: z.number() },
  annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
  uses: [],
  handler: async () => ({ text: 'oops', data: { count: 'many' } as unknown as { count: number } }),
});

function server(tools: AnyTool[]) {
  return buildServer({ name: 'kit-test', version: '0.0.0', instructions: 'test', tools, adapters: [adapter], redactor: new Redactor().add(SECRET), sink });
}

afterEach(() => {
  lines.length = 0;
  vi.restoreAllMocks();
});

describe('result envelope through the protocol', () => {
  it('returns text and structuredContent that match the output schema, redacted', async () => {
    const stdout = vi.spyOn(process.stdout, 'write');
    const { client, close } = await connect(server([echo]));
    const result = (await client.callTool({ name: 'echo', arguments: { id: 'L-1' } })) as CallToolResult;
    await close();
    expect(result.isError).toBeFalsy();
    expect(result.structuredContent).toEqual({ id: 'L-1', note: `seen with ${REDACTED}` });
    expect(JSON.stringify(result)).not.toContain(SECRET);
    expect(result.content[0]).toMatchObject({ type: 'text', text: `Loan L-1: seen with ${REDACTED}` });
    expect(stdout).not.toHaveBeenCalled();
  });

  it('logs exactly one stderr line per call with tool, duration and outcome, and no payload', async () => {
    const { client, close } = await connect(server([echo]));
    await client.callTool({ name: 'echo', arguments: { id: 'L-42' } });
    await close();
    expect(lines).toHaveLength(1);
    expect(lines[0]).toMatch(/kit-test tool=echo ms=\d+ outcome=ok$/);
    expect(lines[0]).not.toContain('L-42');
  });

  it('turns a thrown ToolError into a typed error result, keeping retry-after', async () => {
    const { client, close } = await connect(server([failing(new ToolError('RateLimited', 'too many', { retryAfterSec: 20 }))]));
    const result = (await client.callTool({ name: 'failing', arguments: {} })) as CallToolResult;
    await close();
    expect(result.isError).toBe(true);
    expect(errorOf(result)).toEqual({ code: 'RateLimited', message: 'too many', retryAfterSec: 20 });
    expect(lines[0]).toContain('outcome=error:RateLimited');
  });

  it('never sends a stack trace or internal detail for an unexpected error', async () => {
    const { client, close } = await connect(server([failing(new Error(`connect ECONNREFUSED db.internal:5432 ${SECRET}`))]));
    const result = (await client.callTool({ name: 'failing', arguments: {} })) as CallToolResult;
    await close();
    expect(errorOf(result)?.code).toBe('Internal');
    const sent = JSON.stringify(result);
    expect(sent).not.toContain('db.internal');
    expect(sent).not.toContain('at ');
    expect(lines.join('\n')).toContain('db.internal');      // the detail stays in the server log...
    expect(lines.join('\n')).not.toContain(SECRET);         // ...redacted
  });

  it('refuses to send a result that does not match its declared schema', async () => {
    const { client, close } = await connect(server([badOutput]));
    const result = (await client.callTool({ name: 'bad_output', arguments: {} })) as CallToolResult;
    await close();
    expect(errorOf(result)?.code).toBe('Internal');
  });

  it('rejects arguments that do not match the input schema before the handler runs', async () => {
    const { client, close } = await connect(server([echo]));
    const result = (await client.callTool({ name: 'echo', arguments: { id: 7 } })) as CallToolResult;
    await close();
    expect(result.isError).toBe(true);
  });
});

describe('tool annotations at registration', () => {
  it('fails a tool without annotations, naming it', () => {
    const missing = { ...echo, name: 'unannotated', annotations: undefined } as unknown as AnyTool;
    expect(() => server([missing])).toThrowError(new RegistrationError('Tool unannotated does not declare readOnlyHint.'));
  });

  it('fails a tool that claims to be read-only but uses a mutating adapter method', () => {
    const liar = { ...echo, name: 'liar', uses: ['read', 'write'] };
    expect(() => server([liar])).toThrowError(/liar declares readOnlyHint but uses mutating write/);
  });

  it('fails a tool that is both read-only and destructive, and a duplicate name', () => {
    expect(() => server([{ ...echo, annotations: { readOnlyHint: true, destructiveHint: true, idempotentHint: true } }])).toThrowError(/both read-only and destructive/);
    expect(() => server([echo, echo])).toThrowError(/registered twice/);
  });

  it('accepts a mutating tool that says so', () => {
    const writer = { ...echo, name: 'writer', uses: ['write'], annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true } };
    expect(() => server([writer])).not.toThrow();
  });
});

describe('progress and cancellation', () => {
  it('sends progress notifications with a monotonic value when the client asks for them', async () => {
    const slow = defineTool({
      name: 'slow', title: 'Slow', description: 'Three steps.',
      input: {}, output: { steps: z.number() },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
      uses: [],
      handler: async (_args, { progress }) => {
        for (let i = 1; i <= 3; i++) await progress.report(`step ${i}`, i, 3);
        return { text: 'done', data: { steps: 3 } };
      },
    });
    const { client, close } = await connect(server([slow]));
    const seen: Array<{ progress: number; message?: string }> = [];
    await client.callTool({ name: 'slow', arguments: {} }, undefined, { onprogress: (p) => seen.push(p) });
    await close();
    expect(seen.map((p) => p.progress)).toEqual([1, 2, 3]);
    expect(seen[2]?.message).toBe('step 3');
  });

  it('stops the work and its child processes when the client cancels', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcp-cancel-'));
    fs.writeFileSync(path.join(dir, 'tree.mjs'), `
import { spawn } from 'node:child_process';
const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
console.log('child ' + child.pid);
setInterval(() => {}, 1000);`);
    const runner = new CommandRunner(dir, [{ id: 'tree', command: process.execPath, args: ['tree.mjs'], timeoutMs: 60_000 }]);
    let childPid = 0;
    let started!: () => void;
    const childStarted = new Promise<void>((r) => { started = r; });
    let finished!: (cancelled: boolean) => void;
    const handlerDone = new Promise<boolean>((r) => { finished = r; });
    const long = defineTool({
      name: 'long', title: 'Long', description: 'Runs until cancelled.',
      input: {}, output: { cancelled: z.boolean() },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
      uses: [],
      handler: async (_args, { progress }) => {
        const r = await runner.run('tree', {
          signal: progress.signal,
          onLine: (l) => { const m = /child (\d+)/.exec(l); if (m) { childPid = Number(m[1]); started(); } },
        });
        finished(r.cancelled);
        return { text: 'stopped', data: { cancelled: r.cancelled } };
      },
    });
    const { client, close } = await connect(server([long]));
    const controller = new AbortController();
    const call = client.callTool({ name: 'long', arguments: {} }, undefined, { signal: controller.signal, timeout: 60_000 });
    await childStarted;
    controller.abort();
    await expect(call).rejects.toThrow();
    expect(await handlerDone).toBe(true);
    const until = Date.now() + 5000;
    let gone = false;
    while (!gone && Date.now() < until) {
      try { process.kill(childPid, 0); await new Promise((r) => setTimeout(r, 100)); } catch { gone = true; }
    }
    expect(gone).toBe(true);
    await close();
    fs.rmSync(dir, { recursive: true, force: true });
  });
});
