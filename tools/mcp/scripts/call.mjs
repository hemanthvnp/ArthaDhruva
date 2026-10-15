// Calls one tool on a built server over stdio and prints its text: node scripts/call.mjs <server> <tool> [json-args]
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const [server, tool, json] = process.argv.slice(2);
const cli = new URL(`../${server}/dist/cli.js`, import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1');
const client = new Client({ name: 'call', version: '0' });
await client.connect(new StdioClientTransport({ command: process.execPath, args: [cli], stderr: 'inherit', env: process.env }));
const result = await client.callTool({ name: tool, arguments: json ? JSON.parse(json) : {} }, undefined, { timeout: 600_000 });
console.log(result.content.map((c) => c.text).join('\n'));
await client.close();
