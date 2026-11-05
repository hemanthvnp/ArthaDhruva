#!/usr/bin/env node
import { exitOnConfigError, serveStdio } from '@arthadhruva/mcp-kit';
import { createServer } from './index.js';

try {
  await serveStdio(await createServer());
} catch (e) {
  exitOnConfigError(e);
}
