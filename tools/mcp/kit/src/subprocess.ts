import { spawn, spawnSync } from 'node:child_process';
import path from 'node:path';
import { ToolError } from './errors.js';

/**
 * The only way a server starts a process. Every command is an entry on the calling server's allowlist,
 * looked up by id, with its arguments fixed in the entry: a handler cannot pass a command line, so a model
 * cannot talk a tool into running anything else. A run has a timeout, honours an AbortSignal, and on either
 * kills the whole process tree (Maven and npm start children that outlive their parent otherwise).
 */
export interface AllowedCommand {
  id: string;
  /** Executable name or path. On Windows a .cmd/.bat file is run through cmd.exe, as Node requires. */
  command: string;
  args: readonly string[];
  /** Working directory relative to the runner's root. */
  cwd?: string;
  env?: Record<string, string>;
  timeoutMs?: number;
}

export interface RunResult {
  id: string;
  code: number | null;
  stdout: string;
  stderr: string;
  durationMs: number;
  timedOut: boolean;
  cancelled: boolean;
}

export interface RunOptions {
  signal?: AbortSignal;
  /** Called with each output line, for progress reporting and logs. Never sent to the protocol. */
  onLine?: (line: string, stream: 'stdout' | 'stderr') => void;
  /** Overrides the entry's timeout downward only. */
  timeoutMs?: number;
}

const WIN = process.platform === 'win32';
const MAX_CAPTURE = 1 << 20;

export class CommandRunner {
  private readonly allowed: Map<string, AllowedCommand>;

  constructor(private readonly root: string, allowlist: readonly AllowedCommand[], private readonly defaultTimeoutMs = 120_000) {
    this.allowed = new Map(allowlist.map((c) => [c.id, c]));
  }

  has(id: string): boolean {
    return this.allowed.has(id);
  }

  ids(): string[] {
    return [...this.allowed.keys()];
  }

  /** Refuses an id not on the allowlist before anything is spawned. */
  run(id: string, options: RunOptions = {}): Promise<RunResult> {
    const entry = this.allowed.get(id);
    if (!entry) {
      return Promise.reject(new ToolError('Forbidden', `Command '${id}' is not on this server's allowlist.`));
    }
    if (options.signal?.aborted) {
      return Promise.resolve({ id, code: null, stdout: '', stderr: '', durationMs: 0, timedOut: false, cancelled: true });
    }
    const timeoutMs = Math.min(options.timeoutMs ?? Infinity, entry.timeoutMs ?? this.defaultTimeoutMs);
    const cwd = path.resolve(this.root, entry.cwd ?? '.');
    const [command, args] = launch(entry.command, entry.args);
    const started = Date.now();

    return new Promise((resolve) => {
      const child = spawn(command, args, {
        cwd,
        env: { ...process.env, ...entry.env },
        windowsHide: true,
        detached: !WIN, // its own process group, so the whole tree can be signalled on POSIX
        shell: false,
      });
      let stdout = '';
      let stderr = '';
      let timedOut = false;
      let cancelled = false;
      let settled = false;
      const lines = (stream: 'stdout' | 'stderr') => {
        let pending = '';
        return (chunk: Buffer) => {
          const text = chunk.toString('utf8');
          if (stream === 'stdout') stdout = cap(stdout + text);
          else stderr = cap(stderr + text);
          if (!options.onLine) return;
          pending += text;
          const parts = pending.split(/\r?\n/);
          pending = parts.pop() ?? '';
          for (const line of parts) options.onLine(line, stream);
        };
      };
      child.stdout?.on('data', lines('stdout'));
      child.stderr?.on('data', lines('stderr'));

      const timer = setTimeout(() => {
        timedOut = true;
        killTree(child.pid);
      }, timeoutMs);
      const onAbort = () => {
        cancelled = true;
        killTree(child.pid);
      };
      options.signal?.addEventListener('abort', onAbort, { once: true });

      const finish = (code: number | null, error?: Error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        options.signal?.removeEventListener('abort', onAbort);
        if (error) stderr = cap(`${stderr}spawn error: ${error.message}\n`);
        resolve({ id, code: error ? 127 : code, stdout, stderr, durationMs: Date.now() - started, timedOut, cancelled });
      };
      child.on('error', (e) => finish(null, e));
      child.on('close', (code) => finish(code));
    });
  }
}

function cap(s: string): string {
  return s.length > MAX_CAPTURE ? s.slice(s.length - MAX_CAPTURE) : s;
}

/** On Windows, a .cmd/.bat cannot be spawned directly (Node refuses since CVE-2024-27980); run it via cmd.exe. */
export function launch(command: string, args: readonly string[]): [string, string[]] {
  if (WIN && /\.(cmd|bat)$/i.test(command)) {
    return [process.env.ComSpec ?? 'cmd.exe', ['/d', '/s', '/c', [command, ...args].map(quoteCmd).join(' ')]];
  }
  return [command, [...args]];
}

/** Quotes an argument for cmd.exe. Allowlisted arguments are fixed strings, so this only has to be correct, not hostile-proof. */
function quoteCmd(arg: string): string {
  return /[\s"&|<>^()]/.test(arg) ? `"${arg.replace(/"/g, '""')}"` : arg;
}

/** Kills a process and everything it started: taskkill /t on Windows, the process group on POSIX. */
export function killTree(pid: number | undefined): void {
  if (pid === undefined) return;
  try {
    if (WIN) {
      spawnSync('taskkill', ['/pid', String(pid), '/t', '/f'], { stdio: 'ignore', windowsHide: true });
    } else {
      process.kill(-pid, 'SIGTERM');
      setTimeout(() => {
        try {
          process.kill(-pid, 'SIGKILL');
        } catch {
          /* already gone */
        }
      }, 2000).unref();
    }
  } catch {
    /* already gone */
  }
}
