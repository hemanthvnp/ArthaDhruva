// Drives the built ci-guard server over stdio, the way an MCP client would: starts a preflight on this
// repository, follows it with progress notifications and prints the outcome. Usage:
//   node scripts/preflight-live.mjs [job,job,...] [--cancel-after=SECONDS]
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const args = process.argv.slice(2);
const jobs = args.find((a) => !a.startsWith('--'))?.split(',');
const cancelAfter = Number(args.find((a) => a.startsWith('--cancel-after='))?.split('=')[1] ?? 0);

const transport = new StdioClientTransport({ command: process.execPath, args: [new URL('../ci-guard/dist/cli.js', import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1')], stderr: 'inherit' });
const client = new Client({ name: 'preflight-live', version: '0' });
await client.connect(transport);

const started = await client.callTool({ name: 'preflight', arguments: jobs ? { jobs } : {} });
const { runId } = started.structuredContent;
console.log(started.content[0].text);

if (cancelAfter > 0) {
  await new Promise((r) => setTimeout(r, cancelAfter * 1000));
  const cancelled = await client.callTool({ name: 'preflight_cancel', arguments: { runId } });
  console.log(cancelled.content[0].text);
} else {
  const done = await client.callTool({ name: 'preflight_wait', arguments: { runId, maxSeconds: 3600 } }, undefined, {
    timeout: 3_700_000,
    resetTimeoutOnProgress: true,
    onprogress: (p) => process.stderr.write(`  progress ${p.progress}/${p.total ?? '?'} ${p.message ?? ''}\n`),
  });
  console.log(done.content[0].text);
}
await client.close();
