import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { ConfigError, REDACTED, Redactor, ToolError, guard, loadConfig, markMutating, retryAfterSeconds } from '@arthadhruva/mcp-kit';

describe('typed errors', () => {
  it('reads Retry-After as seconds or as a date', () => {
    expect(retryAfterSeconds('30')).toBe(30);
    expect(retryAfterSeconds('1.2')).toBe(2);
    expect(retryAfterSeconds(new Date(Date.now() + 9_500).toUTCString(), Date.now())).toBeGreaterThanOrEqual(8);
    expect(retryAfterSeconds(null)).toBeUndefined();
    expect(retryAfterSeconds('soon')).toBeUndefined();
  });

  it('carries a code, a retry-after and details', () => {
    const e = new ToolError('RateLimited', 'slow down', { retryAfterSec: 12, details: { a: 1 } });
    expect(e.code).toBe('RateLimited');
    expect(e.retryAfterSec).toBe(12);
    expect(e.details).toEqual({ a: 1 });
  });
});

describe('redaction', () => {
  it('replaces a configured secret wherever it appears, including an upstream error body', () => {
    const cookie = 'eyJhbGciOi.session-value-123456';
    const r = new Redactor().add(cookie);
    expect(r.text(`401 from upstream: token ${cookie} expired`)).toBe(`401 from upstream: token ${REDACTED} expired`);
  });

  it('catches credentials it was not told about', () => {
    const r = new Redactor();
    expect(r.text('Cookie: ad_session=abc.def.ghi; XSRF-TOKEN=1234')).toBe(`Cookie: ad_session=${REDACTED}; XSRF-TOKEN=${REDACTED}`);
    expect(r.text('Authorization: Bearer abcdef123456')).toBe(`Authorization: Bearer ${REDACTED}`);
    expect(r.text('X-API-Key: xxxxxxxxxx')).toBe(`X-API-Key: ${REDACTED}`);
    expect(r.text('jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbmEifQ.c2lnbmF0dXJlLXZhbHVl here')).toBe(`jwt ${REDACTED} here`);
    expect(r.text('postgresql://app:hunter2secret@db:5432/x')).toBe(`postgresql://app:${REDACTED}@db:5432/x`);
    expect(r.text('key sk-ant-api03-abcdefghijk')).toBe(`key ${REDACTED}`);
  });

  it('redacts every string in a nested value and leaves numbers alone', () => {
    const r = new Redactor().add('secret-value');
    expect(r.value({ a: ['x secret-value'], n: 4, deep: { msg: 'secret-value' } })).toEqual({ a: [`x ${REDACTED}`], n: 4, deep: { msg: REDACTED } });
  });

  it('ignores values too short to redact safely', () => {
    expect(new Redactor().add('abc').text('abc abc')).toBe('abc abc');
  });
});

describe('configuration', () => {
  const spec = {
    API_URL: { description: 'backend base URL', required: true },
    SESSION: { description: 'session cookie', required: true, secret: true, check: (v: string) => (v.includes('.') ? undefined : 'not a token') },
    LIMIT: { description: 'row cap', default: '50' },
  };

  it('reads values, applies defaults and registers secrets with the redactor', () => {
    const redactor = new Redactor();
    const config = loadConfig(spec, { API_URL: 'http://localhost:8080', SESSION: 'abc.defghijk' }, redactor);
    expect(config).toEqual({ API_URL: 'http://localhost:8080', SESSION: 'abc.defghijk', LIMIT: '50' });
    expect(redactor.text('abc.defghijk')).toBe(REDACTED);
  });

  it('names a missing variable', () => {
    expect(() => loadConfig(spec, { SESSION: 'a.bcdefgh' })).toThrowError(/API_URL is not set \(backend base URL\)/);
  });

  it('never echoes a malformed secret', () => {
    try {
      loadConfig(spec, { API_URL: 'http://x', SESSION: 'plaintextsecret' });
      expect.unreachable();
    } catch (e) {
      expect(e).toBeInstanceOf(ConfigError);
      expect((e as Error).message).toContain('SESSION is malformed');
      expect((e as Error).message).not.toContain('plaintextsecret');
    }
  });

  it('exits non-zero at startup with the variable named and no secret printed', () => {
    const dist = fileURLToPath(new URL('../dist/index.js', import.meta.url)).replace(/\\/g, '/');
    const script = `import('file:///${dist.replace(/^\//, '')}').then((k) => { try { k.loadConfig({ NEEDED: { description: 'x', required: true }, TOKEN: { description: 't', secret: true, check: () => 'bad' } }, { TOKEN: 'super-secret-token' }); } catch (e) { k.exitOnConfigError(e); } });`;
    const run = spawnSync(process.execPath, ['--input-type=module', '-e', script], { encoding: 'utf8' });
    expect(run.status).toBe(1);
    expect(run.stderr).toContain('NEEDED is not set');
    expect(run.stderr).not.toContain('super-secret-token');
    expect(run.stdout).toBe('');
  });
});

describe('read-only guard', () => {
  it('refuses mutating methods without running them and lets reads through', async () => {
    let wrote = false;
    const adapter = markMutating({ read: async () => 'data', write: async () => { wrote = true; } }, ['write']);
    const guarded = guard(adapter, true);
    expect(await guarded.read()).toBe('data');
    expect(() => guarded.write()).toThrowError(ToolError);
    try {
      await guarded.write();
    } catch (e) {
      expect((e as ToolError).code).toBe('Forbidden');
    }
    expect(wrote).toBe(false);
    await guard(adapter, false).write();
    expect(wrote).toBe(true);
  });
});
