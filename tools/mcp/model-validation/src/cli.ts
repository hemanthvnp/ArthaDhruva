#!/usr/bin/env node
import { exitOnConfigError, loadConfig, serveStdio } from '@arthadhruva/mcp-kit';
import { findRoot } from './git.js';
import { createServer } from './index.js';

let server;
try {
  const config = loadConfig({ MODEL_VALIDATION_REPO: { description: 'repository root (default: the git root of the working directory)' } });
  server = createServer(config.MODEL_VALIDATION_REPO ?? findRoot());
} catch (e) {
  exitOnConfigError(e);
}
serveStdio(server).catch((e: unknown) => {
  process.stderr.write(`model-validation failed to start: ${e instanceof Error ? e.message : String(e)}\n`);
  process.exit(1);
});
