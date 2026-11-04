import type { StoredResult } from './store.js';

/**
 * What the model is shown of a tool result, and how.
 *
 * Layer 1, fencing: the result is wrapped in a tagged block and described as untrusted data.
 * Layer 2, neutralising: any string that reads like an instruction ("ignore previous instructions", "call the
 * portfolio_run_start tool", a fake message boundary) is replaced by a marker that says it was withheld and why, before the model
 * sees it. A user-written field (an audit actor, a webhook address, a note) is exactly where an attacker's text arrives.
 * The marker keeps the fact that something was there, which is itself worth reporting; it does not keep the words.
 * Layers 3 to 5 are the system prompt (prompt.ts), the session's frozen read-only tool set (session.ts), and the verifier.
 *
 * The raw result stays in the store, and figures are verified against that, so withholding text never changes what a figure
 * is checked against.
 *
 * The phrase list is the same one tenant-ops flags with (tools/mcp/tenant-ops/src/fence.ts); it is repeated here rather than
 * shared because the two packages are separate and the agent must not trust a server to have flagged anything.
 */
const PATTERNS: ReadonlyArray<readonly [RegExp, string]> = [
  [/\b(ignore|disregard|forget|override)\b[^.\n]{0,40}\b(previous|prior|above|earlier|all|any|system)\b[^.\n]{0,30}\b(instruction|prompt|rule|message|context)s?\b/i, 'tries to override earlier instructions'],
  [/\b(system|developer)\s+(prompt|message|instruction)s?\b/i, 'refers to the system prompt'],
  [/\b(you are now|from now on|act as|pretend to be|new instructions?)\b/i, 'tries to assign a role or new instructions'],
  [/\b(call|run|invoke|execute|use)\b[^.\n]{0,25}\b(tool|function|command|shell|script)\b/i, 'asks for a tool call or command'],
  [/\b(portfolio_run_start|run_start|api[\s_-]?key|session\s+cookie|password|secret)\b/i, 'mentions a credential, an action or a tool that costs allowance'],
  [/<\/?\s*(system|assistant|user|tool|tool_result|instructions?)\b[^>]*>/i, 'imitates a message boundary'],
  [/\b(exfiltrate|send\b[^.\n]{0,30}\bto\b[^.\n]{0,30}(http|\bemail\b|@))/i, 'asks for data to be sent elsewhere'],
  [/\b(other|another|different)\s+(tenant|organi[sz]ation|customer)'?s?\b/i, 'refers to another organization'],
];

export interface Neutralised {
  value: unknown;
  /** How many strings were withheld. */
  withheld: number;
  reasons: string[];
}

export function neutralise(value: unknown): Neutralised {
  const reasons = new Set<string>();
  let withheld = 0;
  const visit = (v: unknown): unknown => {
    if (typeof v === 'string') {
      const hit = PATTERNS.filter(([re]) => re.test(v)).map(([, why]) => why);
      if (hit.length === 0) return v;
      withheld++;
      hit.forEach((h) => reasons.add(h));
      return `[text withheld: it reads like an instruction (${hit.join('; ')}); ${v.length} characters]`;
    }
    if (Array.isArray(v)) return v.map(visit);
    if (typeof v === 'object' && v !== null) {
      return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, visit(x)]));
    }
    return v;
  };
  return { value: visit(value), withheld, reasons: [...reasons] };
}

/** Longest tool result shown to the model; the stored result is never cut. */
export const MAX_SHOWN = 30_000;

/**
 * The block the model receives for one result. Its id is what the model cites. For an error the model gets the typed code and
 * message (neutralised too: an error message can echo user text).
 */
export function present(result: StoredResult): string {
  const { outcome } = result;
  const source = outcome.structured ?? outcome.text;
  // What the agent itself wrote (a refusal) is not tool output and may name tools and tenants; only tool output is neutralised.
  const shown = result.trusted ? { value: source, withheld: 0 } : neutralise(source);
  let body = typeof shown.value === 'string' ? shown.value : JSON.stringify(shown.value);
  let truncated = false;
  if (body.length > MAX_SHOWN) {
    body = body.slice(0, MAX_SHOWN);
    truncated = true;
  }
  const notes = [
    ...(shown.withheld ? [`${shown.withheld} text field(s) withheld as instruction-like`] : []),
    ...(truncated ? [`cut at ${MAX_SHOWN} characters; the full result is held and can be cited`] : []),
  ];
  return `<tool_result id="${result.id}" tool="${result.tool}" status="${outcome.isError ? 'error' : 'ok'}" trust="untrusted-data">\n`
    + `${body}\n`
    + `${notes.length ? `[notes: ${notes.join('; ')}]\n` : ''}</tool_result>`;
}
