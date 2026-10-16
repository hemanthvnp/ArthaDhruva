import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Read-only git and filesystem access for ci-guard. Only `git rev-parse`, `diff --name-only`, `ls-files` and
 * `branch --show-current` are run: nothing that writes to the index, the refs or the working tree.
 */
export interface Repo {
  root: string;
  changedFiles(): { base: string; files: string[] };
  trackedFiles(pathspec?: string): string[];
  currentBranch(): string;
  read(relative: string): Buffer | undefined;
  stateDir(): string;
}

export function gitRepo(root: string): Repo {
  const git = (args: string[]) => execFileSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 64 << 20, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  const lines = (s: string) => s.split('\n').map((l) => l.trim()).filter(Boolean);
  const baseRef = () => {
    for (const ref of ['@{u}', 'origin/dev', 'origin/main']) {
      try {
        git(['rev-parse', '--verify', '--quiet', ref]);
        return ref;
      } catch {
        /* next */
      }
    }
    return 'HEAD';
  };
  return {
    root,
    changedFiles() {
      const base = baseRef();
      const set = new Set<string>();
      try {
        lines(git(['diff', '--name-only', `${base}...HEAD`])).forEach((f) => set.add(f));
      } catch {
        /* no merge base */
      }
      lines(git(['diff', '--name-only', 'HEAD'])).forEach((f) => set.add(f));
      lines(git(['ls-files', '--others', '--exclude-standard'])).forEach((f) => set.add(f));
      return { base, files: [...set].sort() };
    },
    trackedFiles(pathspec) {
      return lines(git(pathspec ? ['ls-files', '--', pathspec] : ['ls-files']));
    },
    currentBranch() {
      return git(['branch', '--show-current']).trim();
    },
    read(relative) {
      try {
        return fs.readFileSync(path.join(root, relative));
      } catch {
        return undefined;
      }
    },
    /** The only place ci-guard writes: inside .git, so it is never tracked or committed. */
    stateDir() {
      const dir = path.join(root, '.git', 'ci-guard');
      fs.mkdirSync(path.join(dir, 'runs'), { recursive: true });
      return dir;
    },
  };
}

export function findRoot(start: string = process.cwd()): string {
  return execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd: start, encoding: 'utf8', windowsHide: true }).trim();
}

/** Shell scripts shellcheck should see, as CI's globs `deploy/*.sh deploy/azure/*.sh scripts/*.sh` would expand. */
export function shellScripts(root: string): string[] {
  return ['deploy', 'deploy/azure', 'scripts'].flatMap((dir) => {
    try {
      return fs.readdirSync(path.join(root, dir)).filter((f) => f.endsWith('.sh')).sort().map((f) => `${dir}/${f}`);
    } catch {
      return [];
    }
  });
}
