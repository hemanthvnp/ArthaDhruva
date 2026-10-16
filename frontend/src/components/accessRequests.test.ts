import { describe, expect, it } from 'vitest';
import { slugFrom, slugProblem, usernameFrom } from './accessRequests';

describe('access request review helpers', () => {
  it('suggests a slug from a company name', () => {
    expect(slugFrom('Delta Credit Union, Inc.')).toBe('delta-credit-union');
    expect(slugFrom('  Crédit  Agricole  ')).toBe('credit-agricole');
    expect(slugFrom('A&B Lending LLC')).toBe('a-b-lending');
    expect(slugFrom('x'.repeat(60))).toHaveLength(39);
  });

  it('flags slugs the backend would refuse', () => {
    expect(slugProblem('delta-credit-union')).toBeNull();
    expect(slugProblem('ab')).not.toBeNull();
    expect(slugProblem('-delta')).not.toBeNull();
    expect(slugProblem('Delta')).not.toBeNull();
    expect(slugProblem('platform')).toBe('That name is reserved.');
  });

  it('suggests a username from a work email', () => {
    expect(usernameFrom('Dee.Smith@delta.test')).toBe('dee.smith');
    expect(usernameFrom('o+ps@x.test')).toBe('ops');
  });
});
