#!/usr/bin/env node
import { exitOnConfigError, loadConfig, serveStdio } from '@arthadhruva/mcp-kit';
import { findRoot } from './git.js';
import { createServer } from './index.js';

let config;
try {
  config = loadConfig({ CI_GUARD_REPO: { description: 'repository root (default: the git root of the working directory)' } });
} catch (e) {
  exitOnConfigError(e);
}
serveStdio(createServer(config.CI_GUARD_REPO ?? findRoot())).catch((e: unknown) => {
  process.stderr.write(`ci-guard failed to start: ${e instanceof Error ? e.message : String(e)}
`);
  process.exit(1);
});
