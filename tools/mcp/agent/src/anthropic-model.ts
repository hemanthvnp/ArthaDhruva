import Anthropic from '@anthropic-ai/sdk';
import { ModelError, type Block, type Model, type ModelReply, type ModelRequest } from './types.js';

/**
 * The Claude API behind the `Model` port, through the official SDK (Messages API, manual tool loop: the agent owns its budgets and
 * its tool gating, which the SDK's tool runner would hide).
 *
 * Choices, each for a reason in the current API guidance:
 *  - Default model `claude-opus-5-5`. Thinking is always on for it (the parameter is omitted; `disabled` or a budget is a 400), so depth
 *    is set with `output_config.effort`, whose default on this model is `medium`: set explicitly here, `high` by default, because an
 *    analyst answer that must be exactly right earns the extra reasoning (configurable).
 *  - Forced `tool_choice` is a 400 on this model; the default `auto` is used and the system prompt steers.
 *  - Refusal fallbacks are on by default (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`): a safety decline is re-run
 *    on a fallback inside the same call instead of ending the question. `AGENT_REFUSAL_FALLBACK=off` removes it.
 *  - The assistant turn goes back exactly as received (thinking blocks included) and history is only ever appended to, as the
 *    preserved-thinking check requires.
 *  - Top-level `cache_control` caches the stable prefix (tools and system prompt) across the loop's steps.
 *  - Non-streaming with `max_tokens` 16000, the documented size that stays inside the SDK's request timeout.
 */
export type Effort = 'low' | 'medium' | 'high' | 'xhigh' | 'max';

export interface AnthropicOptions {
  model: string;
  maxOutputTokens: number;
  effort: Effort;
  fallback: boolean;
  /** For tests. Default: a client that reads ANTHROPIC_API_KEY (or an `ant auth login` profile) from the environment. */
  client?: Anthropic;
}

export class AnthropicModel implements Model {
  private readonly client: Anthropic;

  constructor(private readonly options: AnthropicOptions) {
    this.client = options.client ?? new Anthropic();
  }

  async complete(request: ModelRequest): Promise<ModelReply> {
    try {
      const response = await this.client.beta.messages.create({
        model: this.options.model,
        max_tokens: this.options.maxOutputTokens,
        system: request.system,
        tools: request.tools.map((t) => ({ name: t.name, description: t.description, input_schema: t.inputSchema as Anthropic.Tool.InputSchema })),
        // The agent's own block types are the SDK's wire shapes; this is the one place the two meet.
        messages: request.messages as unknown as Anthropic.Beta.BetaMessageParam[],
        cache_control: { type: 'ephemeral' },
        output_config: { effort: this.options.effort },
        ...(this.options.fallback ? { betas: ['server-side-fallback-2026-07-01'], fallbacks: 'default' as const } : {}),
      }, request.signal ? { signal: request.signal } : {});
      return {
        content: response.content as unknown as Block[],
        stopReason: response.stop_reason,
        stopDetails: response.stop_details ? { category: response.stop_details.category ?? null, explanation: response.stop_details.explanation ?? null } : null,
        usage: {
          inputTokens: response.usage.input_tokens,
          outputTokens: response.usage.output_tokens,
          cacheReadTokens: response.usage.cache_read_input_tokens ?? 0,
          cacheWriteTokens: response.usage.cache_creation_input_tokens ?? 0,
        },
        model: response.model,
      };
    } catch (e) {
      throw toModelError(e);
    }
  }
}

/** Typed SDK errors to the port's error, most specific first. Messages never include request bodies or keys. */
export function toModelError(e: unknown): ModelError {
  if (e instanceof Anthropic.AuthenticationError) return new ModelError('The Claude API rejected the credentials (check ANTHROPIC_API_KEY or run `ant auth login`).', false);
  if (e instanceof Anthropic.PermissionDeniedError) return new ModelError('The Claude API credentials are not permitted to use this model.', false);
  if (e instanceof Anthropic.NotFoundError) return new ModelError('The Claude API does not know this model name (AGENT_MODEL).', false);
  if (e instanceof Anthropic.BadRequestError) return new ModelError(`The Claude API refused the request: ${e.message.slice(0, 300)}`, false);
  if (e instanceof Anthropic.RateLimitError) return new ModelError('The Claude API is rate limiting this caller; try again shortly.', true);
  if (e instanceof Anthropic.APIConnectionTimeoutError) return new ModelError('The Claude API did not answer in time.', true);
  if (e instanceof Anthropic.APIConnectionError) return new ModelError('The Claude API is not reachable.', true);
  if (e instanceof Anthropic.APIError) return new ModelError(`The Claude API failed (HTTP ${e.status ?? 'unknown'}).`, (e.status ?? 0) >= 500);
  return new ModelError('The model call failed.', false);
}
