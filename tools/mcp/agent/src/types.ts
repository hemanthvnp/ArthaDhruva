/**
 * The agent's own small vocabulary. The loop, the verifier and the session never touch a provider SDK type: the model
 * and the tool servers are ports (`Model`, `ToolHost`), so every rule here is tested with scripted replies and
 * recorded tool results, with no network and no model cost.
 */
export interface ToolSpec {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  /** The server's own `readOnlyHint`. */
  readOnly: boolean;
  /** Which server offered it. */
  server: string;
}

export interface ToolOutcome {
  isError: boolean;
  /** The text content, as the server wrote it. */
  text: string;
  /** The structured content, when the server sent any. */
  structured: unknown;
  errorCode?: string;
}

export interface ToolHost {
  tools(): ToolSpec[];
  call(name: string, args: Record<string, unknown>, signal?: AbortSignal): Promise<ToolOutcome>;
  close(): Promise<void>;
}

export interface TextBlock { type: 'text'; text: string }
export interface ToolUseBlock { type: 'tool_use'; id: string; name: string; input: unknown }
/** Anything else a model may send (thinking, fallback markers): kept as it came and sent back unchanged. */
export interface OtherBlock { type: string; [key: string]: unknown }
export type Block = TextBlock | ToolUseBlock | OtherBlock;

export interface ToolResultBlock { type: 'tool_result'; tool_use_id: string; content: string; is_error?: boolean }

export interface Message {
  role: 'user' | 'assistant';
  content: string | Block[] | ToolResultBlock[];
}

export interface Usage {
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
}

export interface ModelRequest {
  system: string;
  messages: Message[];
  tools: Array<{ name: string; description: string; inputSchema: Record<string, unknown> }>;
  signal?: AbortSignal;
}

export interface ModelReply {
  content: Block[];
  stopReason: string | null;
  stopDetails?: { category: string | null; explanation?: string | null } | null;
  usage: Usage;
  model: string;
}

export interface Model {
  complete(request: ModelRequest): Promise<ModelReply>;
}

export class ModelError extends Error {
  constructor(message: string, readonly retryable: boolean) {
    super(message);
    this.name = 'ModelError';
  }
}

export type LimitName = 'steps' | 'tokens' | 'time' | 'cost' | 'output';

export interface Limits {
  maxSteps: number;
  /** Input plus output tokens, summed over the question. */
  maxTokens: number;
  maxSeconds: number;
  /** Needs pricing; null when no cost limit is set. */
  maxCostUsd: number | null;
}

export interface Pricing {
  inputPerMTok: number;
  outputPerMTok: number;
  cacheReadPerMTok: number;
  cacheWritePerMTok: number;
}
