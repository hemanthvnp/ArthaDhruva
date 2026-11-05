import { describe, expect, it } from 'vitest';
import { Session } from '../src/session.js';
import { SPECS, spec } from './helpers.js';

const session = (over: Partial<ConstructorParameters<typeof Session>[0]> = {}) => new Session({ tenant: '7', allowRunStart: false, specs: SPECS, ...over });

describe('no side effects by default', () => {
  it('offers only the tools their servers mark read-only, and never the run start unless the session allows it', () => {
    const names = session().tools().map((t) => t.name);
    expect(names).toEqual(['portfolio_summary', 'portfolio_loans', 'expected_loss', 'audit_query', 'run_detail', 'webhook_outbox']);
    expect(names).not.toContain('portfolio_run_start');   // its server marks it read-only; the name decides
    expect(names).not.toContain('wake');
    expect(names).not.toContain('run_golden_test');
  });

  it('the allow flag adds the run start, and only it', () => {
    const names = session({ allowRunStart: true }).tools().map((t) => t.name);
    expect(names).toContain('portfolio_run_start');
    expect(names).not.toContain('wake');
  });

  it('a refused run start says why', () => {
    const v = session().check('portfolio_run_start', {});
    expect(v).toMatchObject({ ok: false });
    expect((v as { reason: string }).reason).toContain('does not allow it');
  });

  it('a tool that was never offered is refused whatever the model calls it', () => {
    for (const name of ['wake', 'run_golden_test', 'drop_database', '', 'PORTFOLIO_SUMMARY']) {
      expect(session().check(name, {}), name).toMatchObject({ ok: false });
    }
  });
});

describe('permissions never widen', () => {
  it('the set is decided at construction: later changes to the specs or to the returned list do not matter', () => {
    const specs = [spec('a'), spec('b', 'x', false)];
    const s = new Session({ tenant: '7', allowRunStart: false, specs });
    specs.push(spec('c'));
    specs[1]!.readOnly = true;
    s.tools().push(spec('d'));
    expect(s.tools().map((t) => t.name)).toEqual(['a']);
    expect(s.has('c')).toBe(false);
    expect(s.has('b')).toBe(false);
  });

  it('the session cannot be altered', () => {
    const s = session();
    expect(() => { (s as { tenant: string }).tenant = '8'; }).toThrow();
    expect(() => { (s as { allowRunStart: boolean }).allowRunStart = true; }).toThrow();
    expect(s.check('portfolio_run_start', {})).toMatchObject({ ok: false });
  });

  it('two servers offering one name is an error, not a guess', () => {
    expect(() => new Session({ tenant: '7', allowRunStart: false, specs: [spec('x', 'a'), spec('x', 'b')] })).toThrow(/both|Two servers/);
  });

  it('a session needs a tenant', () => {
    expect(() => session({ tenant: '  ' })).toThrow(/tenant/);
  });
});

describe('fixed tenant', () => {
  it('a call naming another tenant is refused, wherever the name is', () => {
    for (const input of [
      { tenantId: '8' }, { tenant: '8' }, { organization: 'other' }, { orgSlug: 'rival' }, { organisation_id: '9' },
      { filter: { tenant_id: '8' } }, { items: [{ ok: 1 }, { organizationId: '8' }] },
    ]) {
      const v = session().check('audit_query', input);
      expect(v, JSON.stringify(input)).toMatchObject({ ok: false });
      expect((v as { reason: string }).reason).toContain('fixed for this session');
    }
  });

  it('the session\'s own tenant is stripped, not passed on', () => {
    const v = session().check('audit_query', { tenantId: '7', limit: 20 });
    expect(v).toEqual({ ok: true, args: { limit: 20 } });
    expect(session().check('audit_query', { filter: { tenant: ' 7 ' }, limit: 5 })).toEqual({ ok: true, args: { filter: {}, limit: 5 } });
  });

  it('a number is not the tenant string: tenantId 7 as a number is refused rather than guessed at', () => {
    expect(session().check('audit_query', { tenantId: 7 })).toMatchObject({ ok: false });
  });

  it('ordinary arguments pass, unchanged, and the input is not mutated', () => {
    const input = { limit: 20, actor: 'dana', nested: { a: [1, 2] } };
    const v = session().check('audit_query', input);
    expect(v).toEqual({ ok: true, args: input });
    expect(input).toEqual({ limit: 20, actor: 'dana', nested: { a: [1, 2] } });
  });

  it('arguments that are not an object are refused', () => {
    for (const input of [null, 'x', 3, [1]]) expect(session().check('audit_query', input)).toMatchObject({ ok: false });
  });

  it('words that merely contain "org" are not tenants', () => {
    expect(session().check('audit_query', { organizer: 'x', origin: 'y', actor: 'z' })).toMatchObject({ ok: true });
  });
});
