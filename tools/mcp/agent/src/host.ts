import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { errorOf } from '@arthadhruva/mcp-kit';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import type { ToolHost, ToolOutcome, ToolSpec } from './types.js';

/**
 * The agent's door to the MCP servers: an MCP client for each, with their tools listed once when the host is made. It forwards a
 * call to the server that offered the tool and returns the server's own typed result. It decides nothing about permissions (the
 * `Session` does, before a call gets here) and it never adds or removes a tool afterwards.
 */
export interface Connected {
  server: string;
  client: Client;
  /** Closes the connection (and the server process, for a stdio one). */
  close: () => Promise<void>;
}

export class McpToolHost implements ToolHost {
  private constructor(private readonly specs: ToolSpec[], private readonly owner: Map<string, Connected>, private readonly connections: Connected[]) {}

  /** Lists the tools of already-connected clients (used directly by tests, which connect in-process). */
  static async fromConnected(connections: Connected[]): Promise<McpToolHost> {
    const specs: ToolSpec[] = [];
    const owner = new Map<string, Connected>();
    for (const c of connections) {
      const { tools } = await c.client.listTools();
      for (const t of tools) {
        if (owner.has(t.name)) throw new Error(`Servers ${owner.get(t.name)!.server} and ${c.server} both offer a tool named ${t.name}.`);
        owner.set(t.name, c);
        specs.push({
          name: t.name, description: t.description ?? t.title ?? t.name, inputSchema: t.inputSchema as Record<string, unknown>,
          readOnly: t.annotations?.readOnlyHint === true, server: c.server,
        });
      }
    }
    return new McpToolHost(specs, owner, connections);
  }

  /** Starts each server as a child process over stdio and connects to it. */
  static async spawn(servers: StdioServer[]): Promise<McpToolHost> {
    const connections: Connected[] = [];
    try {
      for (const s of servers) {
        const transport = new StdioClientTransport({ command: process.execPath, args: [s.entry], env: s.env, stderr: 'inherit' });
        const client = new Client({ name: 'analyst-agent', version: '0.1.0' });
        await client.connect(transport);
        connections.push({ server: s.name, client, close: () => client.close() });
      }
      return await McpToolHost.fromConnected(connections);
    } catch (e) {
      await Promise.allSettled(connections.map((c) => c.close()));
      throw e;
    }
  }

  tools(): ToolSpec[] {
    return this.specs.map((s) => ({ ...s }));
  }

  async call(name: string, args: Record<string, unknown>, signal?: AbortSignal): Promise<ToolOutcome> {
    const target = this.owner.get(name);
    if (!target) return { isError: true, text: `No server offers ${name}.`, structured: { error: { code: 'NotFound', message: `No server offers ${name}.` } }, errorCode: 'NotFound' };
    const result = await target.client.callTool({ name, arguments: args }, undefined, signal ? { signal } : {});
    const content = Array.isArray(result.content) ? result.content : [];
    const text = content.filter((c): c is { type: 'text'; text: string } => c.type === 'text' && typeof c.text === 'string').map((c) => c.text).join('\n');
    const error = errorOf(result as CallToolResult);
    return { isError: result.isError === true, text, structured: result.structuredContent as unknown, ...(error?.code ? { errorCode: error.code } : {}) };
  }

  async close(): Promise<void> {
    await Promise.allSettled(this.connections.map((c) => c.close()));
  }
}

export interface StdioServer {
  name: string;
  /** Path of the server's compiled entry point. */
  entry: string;
  env: Record<string, string>;
}
