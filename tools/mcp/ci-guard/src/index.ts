import { execFileSync } from 'node:child_process';
import { CommandRunner, Redactor, buildServer } from '@arthadhruva/mcp-kit';
import { gitRepo, shellScripts } from './git.js';
import { ghHistory } from './history.js';
import { allowlist, JOBS } from './model.js';
import { Preflight } from './preflight.js';
import { tools } from './tools.js';

/**
 * ci-guard over stdio. Run from anywhere inside the repository (or set CI_GUARD_REPO). It never pushes,
 * commits or edits tracked files; its own state lives in .git/ci-guard.
 */
export function createServer(root: string) {
  const redactor = new Redactor();
  const repo = gitRepo(root);
  const runner = new CommandRunner(root, allowlist({
    windows: process.platform === 'win32',
    rootForDocker: root.replace(/\\/g, '/'),
    shellScripts: shellScripts(root),
  }));
  const preflight = new Preflight(repo, runner, JOBS, {
    dockerUp: () => {
      try {
        execFileSync('docker', ['info'], { stdio: 'ignore', timeout: 20_000, windowsHide: true });
        return true;
      } catch {
        return false;
      }
    },
  });
  const github = ghHistory(root, repo.stateDir());
  return buildServer({
    name: 'ci-guard',
    version: '0.1.0',
    instructions: 'Runs this repository\'s CI checks locally before a push and reports typical CI time. Start with static_checks '
      + '(seconds), then preflight. A skipped step is not a pass.',
    tools: tools({ repo, preflight, github }),
    adapters: [preflight, repo, github],
    redactor,
  });
}
