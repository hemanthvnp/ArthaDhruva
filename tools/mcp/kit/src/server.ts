import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { CallLog, type Sink } from './log.js';
import { mutatingMethods } from './readonly.js';
import { Redactor } from './redact.js';
import { registerTools, type AnyTool } from './tools.js';

export interface ServerParts {
  name: string;
  version: string;
  instructions: string;
  tools: readonly AnyTool[];
  /** Every adapter the tools call; their mutating marks drive the annotation check. */
  adapters: readonly object[];
  redactor: Redactor;
  sink?: Sink;
}

/** Builds a server the same way for every package: tools registered through the kit's checks. */
export function buildServer(parts: ServerParts): McpServer {
  const server = new McpServer({ name: parts.name, version: parts.version }, { instructions: parts.instructions, capabilities: { logging: {} } });
  const mutating = new Set(parts.adapters.flatMap((a) => mutatingMethods(a)));
  registerTools(server, parts.tools, { redactor: parts.redactor, log: new CallLog(parts.name, parts.redactor, parts.sink), mutating });
  return server;
}

/** Serves over stdio. Nothing else may write to stdout. */
export async function serveStdio(server: McpServer): Promise<void> {
  await server.connect(new StdioServerTransport());
}
