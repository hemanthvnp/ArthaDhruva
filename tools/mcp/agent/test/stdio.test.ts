import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { McpToolHost } from '../src/host.js';
import { Session } from '../src/session.js';

/**
 * The real transport: the agent's host starts an actual server process, lists its tools, calls one, and gets a typed error back after
 * listing (the path the kit's error-shape bug used to break). model-validation is used because it needs no credentials and no
 * network. It runs the compiled server, so `tsc -b` must have been run (CI does); without a build the test is skipped, loudly.
 */
const MCP = path.resolve(fileURLToPath(new URL('../..', import.meta.url)));
const REPO = path.resolve(MCP, '../..');
const ENTRY = path.join(MCP, 'model-validation', 'dist', 'cli.js');
const built = fs.existsSync(ENTRY);
if (!built) process.stderr.write('\n*** agent stdio.test.ts SKIPPED: tools/mcp/model-validation is not built (run `npx tsc -b`). ***\n\n');

describe.skipIf(!built)('McpToolHost over a real stdio server', () => {
  const spawn = () => McpToolHost.spawn([{ name: 'model-validation', entry: ENTRY, env: { MODEL_VALIDATION_REPO: REPO } }]);

  it('lists the tools, with the server\'s read-only annotations, and the session gates on them', async () => {
    const host = await spawn();
    try {
      const specs = host.tools();
      expect(specs.map((s) => s.name).sort()).toEqual(['drift_report', 'rerun_advisory', 'run_golden_test', 'survival_backtest', 'verify_artifacts']);
      const offered = new Session({ tenant: '7', allowRunStart: false, specs }).tools().map((t) => t.name);
      expect(offered).not.toContain('run_golden_test');   // it runs Maven and writes build output: not read-only, so not offered
      expect(offered).toContain('verify_artifacts');
    } finally {
      await host.close();
    }
  }, 60_000);

  it('a call returns the server\'s structured result', async () => {
    const host = await spawn();
    try {
      const r = await host.call('verify_artifacts', {});
      expect(r.isError).toBe(false);
      expect(r.structured).toMatchObject({ passed: true });
    } finally {
      await host.close();
    }
  }, 60_000);

  it('an error after listing is a typed result, not a protocol failure', async () => {
    const host = await spawn();
    try {
      const r = await host.call('rerun_advisory', { base: '--output=/tmp/x' });
      expect(r).toMatchObject({ isError: true, errorCode: 'InvalidInput' });
      expect(r.text).toContain('InvalidInput');
    } finally {
      await host.close();
    }
  }, 60_000);
});
