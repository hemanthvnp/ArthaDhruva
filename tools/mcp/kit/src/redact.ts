/**
 * Strips credentials from anything that leaves a server: tool results, error messages and log lines. Two
 * layers: the exact secret values the server was configured with (whatever they look like), and patterns for
 * credentials that may arrive from elsewhere (an upstream echoing a cookie, a token in an error body).
 */
export const REDACTED = '[redacted]';

const PATTERNS: Array<{ re: RegExp; to: string }> = [
  { re: /\b(ad_session|XSRF-TOKEN)=[^;\s"',]+/gi, to: `$1=${REDACTED}` },               // session and CSRF cookies
  { re: /\bBearer\s+[A-Za-z0-9._~+/-]+=*/gi, to: `Bearer ${REDACTED}` },                // bearer tokens
  { re: /\b(x-api-key|api[_-]?key)(["']?\s*[:=]\s*["']?)[A-Za-z0-9._-]{8,}/gi, to: `$1$2${REDACTED}` },
  { re: /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/g, to: REDACTED },  // JWTs
  { re: /\bsk-ant-[A-Za-z0-9_-]{10,}/g, to: REDACTED },                                   // Anthropic API keys
  { re: /(postgres(?:ql)?:\/\/[^:\s/]+:)[^@\s]+@/gi, to: `$1${REDACTED}@` },               // connection-string passwords
];

export class Redactor {
  private readonly secrets = new Set<string>();

  /** Registers configured secret values; anything shorter than 6 characters is ignored (it would mangle text). */
  add(...values: Array<string | undefined>): this {
    for (const v of values) {
      if (v && v.length >= 6) this.secrets.add(v);
    }
    return this;
  }

  text(input: string): string {
    let out = input;
    for (const secret of this.secrets) out = out.split(secret).join(REDACTED);
    for (const { re, to } of PATTERNS) out = out.replace(re, to);
    return out;
  }

  /** Deep copy of a JSON-like value with every string redacted (keys included). */
  value<T>(input: T): T {
    return this.walk(input) as T;
  }

  private walk(v: unknown): unknown {
    if (typeof v === 'string') return this.text(v);
    if (Array.isArray(v)) return v.map((x) => this.walk(x));
    if (v && typeof v === 'object') {
      const out: Record<string, unknown> = {};
      for (const [k, x] of Object.entries(v as Record<string, unknown>)) out[this.text(k)] = this.walk(x);
      return out;
    }
    return v;
  }
}
