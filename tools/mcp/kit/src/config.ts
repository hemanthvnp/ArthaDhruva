import type { Redactor } from './redact.js';

/**
 * Configuration comes from environment variables only, is validated once at startup, and a missing or
 * malformed variable stops the server with a message that names it and never prints a secret's value.
 */
export interface VarSpec {
  /** What it is for, shown when it is missing. */
  description: string;
  required?: boolean;
  /** A secret is registered with the redactor and never echoed, even when malformed. */
  secret?: boolean;
  default?: string;
  /** Returns an error message for a bad value, or undefined when it is fine. */
  check?: (value: string) => string | undefined;
}

export type ConfigSpec = Record<string, VarSpec>;

export class ConfigError extends Error {
  constructor(readonly problems: string[]) {
    super(`Configuration error:\n${problems.map((p) => `  - ${p}`).join('\n')}`);
    this.name = 'ConfigError';
  }
}

export function loadConfig<S extends ConfigSpec>(
  spec: S,
  env: Record<string, string | undefined> = process.env,
  redactor?: Redactor,
): { [K in keyof S]: string | undefined } {
  const problems: string[] = [];
  const out: Record<string, string | undefined> = {};
  for (const [name, v] of Object.entries(spec)) {
    const raw = env[name];
    const value = raw === undefined || raw === '' ? v.default : raw;
    if (value === undefined) {
      if (v.required) problems.push(`${name} is not set (${v.description})`);
      out[name] = undefined;
      continue;
    }
    const issue = v.check?.(value);
    if (issue) problems.push(v.secret ? `${name} is malformed (${issue}); its value is not shown` : `${name}=${value} is invalid: ${issue}`);
    if (v.secret) redactor?.add(value);
    out[name] = value;
  }
  if (problems.length) throw new ConfigError(problems);
  return out as { [K in keyof S]: string | undefined };
}

/** For a server's entry point: print the problem to stderr and exit non-zero. */
export function exitOnConfigError(error: unknown): never {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
}

export const isUrl = (v: string) => {
  try {
    const u = new URL(v);
    return u.protocol === 'http:' || u.protocol === 'https:' ? undefined : 'must be an http(s) URL';
  } catch {
    return 'must be a URL';
  }
};

export const isPositiveInt = (v: string) => (/^\d+$/.test(v) && Number(v) > 0 ? undefined : 'must be a positive integer');
