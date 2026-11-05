import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { invalidInput } from '@arthadhruva/mcp-kit';
import type { Reader } from './artifacts.js';

/**
 * Read-only git and filesystem access. Only `rev-parse --verify`, `diff --name-only` and `ls-files --others` are run:
 * nothing that writes to the index, the refs or the working tree. A ref comes from the caller, so it is checked against a
 * strict pattern (no leading dash, no spaces or shell characters) and must resolve to a commit before it is used.
 */
export interface Repo extends Reader {
  root: string;
  /** Files that differ from `base`, committed or not, plus untracked ones. */
  changedSince(base: string): string[];
}

const REF = /^[A-Za-z0-9][A-Za-z0-9._/@~^-]{0,99}$/;

export function gitRepo(root: string): Repo {
  const git = (args: string[]) => execFileSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 64 << 20, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  const lines = (s: string) => s.split('\n').map((l) => l.trim()).filter(Boolean);
  return {
    root,
    read(relative) {
      try {
        return fs.readFileSync(path.join(root, relative));
      } catch {
        return undefined;
      }
    },
    changedSince(base) {
      if (!REF.test(base) || base.includes('..')) throw invalidInput('base must be a branch, tag or commit name, for example origin/dev.');
      try {
        git(['rev-parse', '--verify', '--quiet', `${base}^{commit}`]);
      } catch {
        throw invalidInput(`base "${base}" is not a commit in this repository (try git fetch, or a name like origin/dev).`);
      }
      const files = new Set<string>();
      lines(git(['diff', '--name-only', base, '--'])).forEach((f) => files.add(f));
      lines(git(['ls-files', '--others', '--exclude-standard'])).forEach((f) => files.add(f));
      return [...files].sort();
    },
  };
}

export function findRoot(start: string = process.cwd()): string {
  return execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd: start, encoding: 'utf8', windowsHide: true }).trim();
}
