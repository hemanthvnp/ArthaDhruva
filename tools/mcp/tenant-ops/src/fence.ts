import { z } from 'zod';

/**
 * Text a tenant can write (a username, a webhook address, the error a webhook endpoint answered with, an audit
 * detail) reaches a model through this server, so it is treated as data on the way out and never as bare text:
 * structured output carries it as `{ untrusted: true, flagged, reasons, text }`, and text output wraps it in a
 * fence whose delimiters the content cannot forge. A phrase that reads like an instruction sets `flagged` and says
 * why. Flagging is a signal for the reader, not a filter: the text is still shown, inside the fence, because hiding
 * it would hide what the tenant actually wrote.
 */
export const fencedSchema = z.object({
  untrusted: z.literal(true),
  flagged: z.boolean(),
  reasons: z.array(z.string()),
  text: z.string(),
});
export type Fenced = z.infer<typeof fencedSchema>;

/** Longest tenant-written text returned in full; the rest is cut and marked. */
export const MAX_FIELD = 300;

const PATTERNS: ReadonlyArray<readonly [RegExp, string]> = [
  [/\b(ignore|disregard|forget|override)\b[^.\n]{0,40}\b(previous|prior|above|earlier|all|any|system)\b[^.\n]{0,30}\b(instruction|prompt|rule|message|context)s?\b/i, 'tries to override earlier instructions'],
  [/\b(system|developer)\s+(prompt|message|instruction)s?\b/i, 'refers to the system prompt'],
  [/\b(you are now|from now on|act as|pretend to be|new instructions?)\b/i, 'tries to assign a role or new instructions'],
  [/\b(call|run|invoke|execute|use)\b[^.\n]{0,25}\b(tool|function|command|shell|script)\b/i, 'asks for a tool call or command'],
  [/\b(portfolio_run_start|run_start|api[\s_-]?key|session\s+cookie|password|secret)\b/i, 'mentions a credential or a tool that costs allowance'],
  [/<\/?\s*(system|assistant|user|tool|instructions?)\b[^>]*>/i, 'imitates a message boundary'],
  [/\b(exfiltrate|send\b[^.\n]{0,30}\bto\b[^.\n]{0,30}(http|\bemail\b|@))/i, 'asks for data to be sent elsewhere'],
];

/** Returns null for an absent value, so a null column stays null instead of becoming an empty fenced string. */
export function fence(value: string | null | undefined): Fenced | null {
  if (value === null || value === undefined) return null;
  const reasons = PATTERNS.filter(([re]) => re.test(value)).map(([, why]) => why);
  const text = value.length > MAX_FIELD ? `${value.slice(0, MAX_FIELD)}... [${value.length - MAX_FIELD} more characters not shown]` : value;
  return { untrusted: true, flagged: reasons.length > 0, reasons, text };
}

const OPEN = '<<<UNTRUSTED DATA';
const CLOSE = '>>>END UNTRUSTED DATA';

/** The fenced form for text output. A fence marker inside the content is defused so the content cannot close it early. */
export function fenced(label: string, value: Fenced | null): string {
  if (value === null) return `${label}: (none)`;
  const body = value.text.replaceAll('<<<', '<​<<').replaceAll('>>>', '>​>>');
  const flag = value.flagged ? ` [FLAGGED: ${value.reasons.join('; ')}]` : '';
  return `${label}: ${OPEN} (${label})${flag}\n${body}\n${CLOSE}`;
}
