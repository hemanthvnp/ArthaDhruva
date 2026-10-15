#!/usr/bin/env node
import { exitOnConfigError, serveStdio } from '@arthadhruva/mcp-kit';
import { createServer } from './index.js';

let server;
try {
  server = createServer();
} catch (e) {
  exitOnConfigError(e);
}
serveStdio(server).catch((e: unknown) => {
  process.stderr.write(`risk-engine failed to start: ${e instanceof Error ? e.message : String(e)}\n`);
  process.exit(1);
});
