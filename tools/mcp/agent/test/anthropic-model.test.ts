import Anthropic from '@anthropic-ai/sdk';
import { describe, expect, it } from 'vitest';
import { AnthropicModel, toModelError } from '../src/anthropic-model.js';
import { ModelError } from '../src/types.js';

/**
 * The adapter's request shape, held to the current API guidance for claude-opus-5-5, with a fake client (no key, no network):
 * thinking omitted (it is always on, and `disabled` is a 400), no sampling parameters, no forced tool choice, explicit effort, the
 * refusal fallback on, caching on, and the assistant's thinking blocks passed back untouched.
 */
function fake(response: unknown) {
  const sent: Array<{ params: Record<string, unknown>; options: unknown }> = [];
  const client = { beta: { messages: { create: async (params: Record<string, unknown>, options: unknown) => { sent.push({ params, options }); return response; } } } };
  return { client: client as unknown as Anthropic, sent };
}

const REPLY = {
  content: [{ type: 'thinking', thinking: '', signature: 'sig' }, { type: 'text', text: 'ok' }],
  stop_reason: 'end_turn', stop_details: null, model: 'claude-opus-5-5',
  usage: { input_tokens: 1200, output_tokens: 300, cache_read_input_tokens: 5000, cache_creation_input_tokens: 800 },
};
const REQUEST = {
  system: 'sys', messages: [{ role: 'user' as const, content: 'hi' }],
  tools: [{ name: 't', description: 'd', inputSchema: { type: 'object', properties: { a: { type: 'string' } } } }],
};
const OPTIONS = { model: 'claude-opus-5-5', maxOutputTokens: 16000, effort: 'high' as const, fallback: true };

describe('request shape', () => {
  it('follows the guidance for claude-opus-5-5', async () => {
    const { client, sent } = fake(REPLY);
    await new AnthropicModel({ ...OPTIONS, client }).complete(REQUEST);
    const p = sent[0]!.params;
    expect(p).toMatchObject({ model: 'claude-opus-5-5', max_tokens: 16000, system: 'sys', output_config: { effort: 'high' }, cache_control: { type: 'ephemeral' } });
    expect(p.tools).toEqual([{ name: 't', description: 'd', input_schema: { type: 'object', properties: { a: { type: 'string' } } } }]);
    for (const forbidden of ['thinking', 'temperature', 'top_p', 'top_k', 'tool_choice']) expect(p, forbidden).not.toHaveProperty(forbidden);
  });

  it('turns the refusal fallback on by default, in its documented form, and off on request', async () => {
    const on = fake(REPLY);
    await new AnthropicModel({ ...OPTIONS, client: on.client }).complete(REQUEST);
    expect(on.sent[0]!.params).toMatchObject({ betas: ['server-side-fallback-2026-07-01'], fallbacks: 'default' });
    const off = fake(REPLY);
    await new AnthropicModel({ ...OPTIONS, fallback: false, client: off.client }).complete(REQUEST);
    expect(off.sent[0]!.params).not.toHaveProperty('fallbacks');
    expect(off.sent[0]!.params).not.toHaveProperty('betas');
  });

  it('passes the cancellation signal through', async () => {
    const { client, sent } = fake(REPLY);
    const signal = AbortSignal.timeout(5000);
    await new AnthropicModel({ ...OPTIONS, client }).complete({ ...REQUEST, signal });
    expect(sent[0]!.options).toEqual({ signal });
  });
});

describe('reply mapping', () => {
  it('keeps the content blocks as they came (thinking included) and counts every kind of token', async () => {
    const { client } = fake(REPLY);
    const r = await new AnthropicModel({ ...OPTIONS, client }).complete(REQUEST);
    expect(r.content).toEqual(REPLY.content);
    expect(r).toMatchObject({ stopReason: 'end_turn', model: 'claude-opus-5-5', stopDetails: null,
      usage: { inputTokens: 1200, outputTokens: 300, cacheReadTokens: 5000, cacheWriteTokens: 800 } });
  });

  it('carries a refusal\'s category', async () => {
    const { client } = fake({ ...REPLY, stop_reason: 'refusal', stop_details: { type: 'refusal', category: 'cyber', explanation: 'declined' } });
    const r = await new AnthropicModel({ ...OPTIONS, client }).complete(REQUEST);
    expect(r).toMatchObject({ stopReason: 'refusal', stopDetails: { category: 'cyber', explanation: 'declined' } });
  });

  it('treats missing cache counts as zero', async () => {
    const { client } = fake({ ...REPLY, usage: { input_tokens: 10, output_tokens: 5 } });
    expect((await new AnthropicModel({ ...OPTIONS, client }).complete(REQUEST)).usage).toEqual({ inputTokens: 10, outputTokens: 5, cacheReadTokens: 0, cacheWriteTokens: 0 });
  });
});

describe('errors', () => {
  const generate = (status: number, message = 'm') => Anthropic.APIError.generate(status, { error: { message } }, message, new Headers());

  it.each([
    [401, /rejected the credentials/, false], [403, /not permitted/, false], [404, /does not know this model/, false],
    [400, /refused the request/, false], [429, /rate limiting/, true], [500, /HTTP 500/, true], [529, /HTTP 529/, true],
  ])('HTTP %i becomes a typed ModelError', (status, message, retryable) => {
    const e = toModelError(generate(status));
    expect(e).toBeInstanceOf(ModelError);
    expect(e.message).toMatch(message);
    expect(e.retryable).toBe(retryable);
  });

  it('never repeats a secret that appears in the failure', () => {
    const e = toModelError(generate(401, 'bad key sk-ant-SECRETKEY'));
    expect(e.message).not.toContain('SECRETKEY');
  });

  it('an unknown failure is generic', () => {
    expect(toModelError(new Error('boom sk-ant-SECRETKEY')).message).toBe('The model call failed.');
  });

  it('a failing call throws the typed error', async () => {
    const client = { beta: { messages: { create: async () => { throw generate(429); } } } } as unknown as Anthropic;
    await expect(new AnthropicModel({ ...OPTIONS, client }).complete(REQUEST)).rejects.toMatchObject({ name: 'ModelError', retryable: true });
  });
});
