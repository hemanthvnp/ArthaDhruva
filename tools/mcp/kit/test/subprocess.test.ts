import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { CommandRunner, ToolError } from '@arthadhruva/mcp-kit';

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcp-kit-'));
// A parent that starts a long-lived child and prints the child's pid: killing only the parent would leave it.
fs.writeFileSync(path.join(dir, 'tree.mjs'), `
import { spawn } from 'node:child_process';
const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
console.log('child ' + child.pid);
setInterval(() => {}, 1000);
`);
fs.writeFileSync(path.join(dir, 'hello.mjs'), `console.log('one'); console.log('two'); console.error('warn');`);
const marker = path.join(dir, 'should-not-exist');

const runner = new CommandRunner(dir, [
  { id: 'hello', command: process.execPath, args: ['hello.mjs'] },
  { id: 'tree', command: process.execPath, args: ['tree.mjs'], timeoutMs: 1500 },
  { id: 'tree-long', command: process.execPath, args: ['tree.mjs'], timeoutMs: 60_000 },
]);

const alive = (pid: number) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};

async function eventually(check: () => boolean, ms = 5000) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    if (check()) return true;
    await new Promise((r) => setTimeout(r, 100));
  }
  return check();
}

afterAll(() => fs.rmSync(dir, { recursive: true, force: true }));

describe('subprocess runner', () => {
  it('runs an allowlisted command and streams its lines', async () => {
    const lines: string[] = [];
    const r = await runner.run('hello', { onLine: (l) => lines.push(l) });
    expect(r.code).toBe(0);
    expect(r.stdout).toContain('two');
    expect(r.stderr).toContain('warn');
    expect(lines).toEqual(expect.arrayContaining(['one', 'two', 'warn']));
  });

  it('refuses a command that is not on the allowlist without spawning anything', async () => {
    await expect(runner.run(`touch ${marker}`)).rejects.toMatchObject({ code: 'Forbidden' });
    await expect(runner.run('rm')).rejects.toBeInstanceOf(ToolError);
    expect(fs.existsSync(marker)).toBe(false);
  });

  it('kills the whole process tree on timeout', async () => {
    let childPid = 0;
    const r = await runner.run('tree', { onLine: (l) => { const m = /child (\d+)/.exec(l); if (m) childPid = Number(m[1]); } });
    expect(r.timedOut).toBe(true);
    expect(childPid).toBeGreaterThan(0);
    expect(await eventually(() => !alive(childPid))).toBe(true);
  });

  it('kills the whole process tree when the caller cancels', async () => {
    const controller = new AbortController();
    let childPid = 0;
    const running = runner.run('tree-long', {
      signal: controller.signal,
      onLine: (l) => {
        const m = /child (\d+)/.exec(l);
        if (m) {
          childPid = Number(m[1]);
          controller.abort();
        }
      },
    });
    const r = await running;
    expect(r.cancelled).toBe(true);
    expect(r.timedOut).toBe(false);
    expect(await eventually(() => childPid > 0 && !alive(childPid))).toBe(true);
  });

  it('does not start a run that was already cancelled', async () => {
    const controller = new AbortController();
    controller.abort();
    const r = await runner.run('hello', { signal: controller.signal });
    expect(r.cancelled).toBe(true);
    expect(r.stdout).toBe('');
  });
});
