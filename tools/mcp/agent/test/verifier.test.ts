import { describe, expect, it } from 'vitest';
import { describe as explain, extract, supports, verify } from '../src/verifier.js';
import { failed, storeOf } from './helpers.js';

/**
 * One group per verifier rule in specs/agent-evals ("exact match, rounding within tolerance, unit conversion, percentage versus
 * fraction, uncited figure, mismatch and retry exhaustion"; the last is in agent.test.ts, since it needs the loop). Each states the
 * rule as a person would, so a reader can check the rule and not just the code.
 */
const verdicts = (answer: string, ...results: unknown[]) => verify(answer, storeOf(...results)).figures.map((f) => f.verdict);

describe('exact match', () => {
  it('a figure that is in the cited result is supported, and says where', () => {
    const v = verify('The portfolio has 400 loans [r1].', storeOf({ portfolioLoans: 400 }));
    expect(v.verified).toBe(true);
    expect(v.figures[0]).toMatchObject({ verdict: 'supported', matched: { result: 'r1', path: 'portfolioLoans', value: 400 } });
  });

  it('currency with separators', () => {
    expect(verdicts('Exposure is $98,000,000 [r1].', { snapshots: [{ exposure: 98_000_000 }] })).toEqual(['supported']);
  });

  it('an answer with no figures at all has nothing to verify', () => {
    const v = verify('The VM is asleep, which is its normal idle state.', storeOf({}));
    expect(v).toMatchObject({ verified: true, figures: [] });
  });
});

describe('rounding within tolerance', () => {
  it('3.4% is supported by 0.03412', () => {
    expect(verdicts('Loss is 3.4% [r1].', { loss: 0.03412 })).toEqual(['supported']);
  });

  it('the precision written decides: 3.41% matches 0.03412, 3.5% and 3.46% do not', () => {
    expect(verdicts('It is 3.41% [r1].', { loss: 0.03412 })).toEqual(['supported']);
    expect(verdicts('It is 3.5% [r1].', { loss: 0.03412 })).toEqual(['mismatch']);
    expect(verdicts('It is 3.46% [r1].', { loss: 0.03412 })).toEqual(['mismatch']);
  });

  it('an integer figure must be the integer, not merely close', () => {
    expect(verdicts('There are 400 loans [r1].', { n: 400 })).toEqual(['supported']);
    expect(verdicts('There are 401 loans [r1].', { n: 400 })).toEqual(['mismatch']);
  });

  it('a figure may be supported by any number anywhere in the cited result', () => {
    expect(verdicts('The lifetime PD is 4.1% [r1].', PORTFOLIO_LIKE)).toEqual(['supported']);
  });
});

const PORTFOLIO_LIKE = { snapshots: [{ pd12m: 0.00312, pdLifetime: 0.0412 }] };

describe('percentage versus fraction', () => {
  it('a result held as a fraction supports the percentage, and one already in percent does too', () => {
    expect(verdicts('PD is 3.4% [r1].', { pd: 0.034 })).toEqual(['supported']);
    expect(verdicts('PD is 3.4% [r1].', { pd: 3.4 })).toEqual(['supported']);
  });

  it('but not the other quantity: 34% is not 3.4%', () => {
    expect(verdicts('PD is 34% [r1].', { pd: 0.034 })).toEqual(['mismatch']);
  });

  it('the word percent is a percent', () => {
    expect(verdicts('PD is 3.4 percent [r1].', { pd: 0.034 })).toEqual(['supported']);
  });
});

describe('unit conversion', () => {
  it('basis points against a fraction, a percentage, and basis points', () => {
    expect(verdicts('Spread is 34 bps [r1].', { s: 0.0034 })).toEqual(['supported']);
    expect(verdicts('Spread is 34 bps [r1].', { s: 0.34 })).toEqual(['supported']);
    expect(verdicts('Spread is 34 bps [r1].', { s: 34 })).toEqual(['supported']);
    expect(verdicts('Spread is 34 basis points [r1].', { s: 0.0034 })).toEqual(['supported']);
  });

  it('and the same quantity as a percentage', () => {
    expect(verdicts('Spread is 0.34% [r1].', { s: 0.0034 })).toEqual(['supported']);
  });

  it('a wrong conversion is a mismatch: 35 bps against 0.0034', () => {
    expect(verdicts('Spread is 35 bps [r1].', { s: 0.0034 })).toEqual(['mismatch']);
  });

  it('size words scale a currency figure, to the precision written', () => {
    expect(verdicts('Loss is $1.2m [r1].', { ecl: 1_234_567 })).toEqual(['supported']);
    expect(verdicts('Loss is $1.3m [r1].', { ecl: 1_234_567 })).toEqual(['mismatch']);
    expect(verdicts('Loss is 1.2 million [r1].', { ecl: 1_234_567 })).toEqual(['supported']);
    expect(verdicts('Exposure is $98bn [r1].', { e: 98_000_000_000 })).toEqual(['supported']);
  });
});

describe('uncited figures', () => {
  it('a number with no citation is unsupported', () => {
    const v = verify('The PD is 0.31%.', storeOf({ pd: 0.0031 }));
    expect(v.verified).toBe(false);
    expect(v.figures[0]).toMatchObject({ verdict: 'uncited' });
    expect(explain(v.figures[0]!)).toContain('no citation');
  });

  it('the citation may follow the noun that completes the figure, but not a longer stretch', () => {
    expect(verdicts('The PD is 0.31% of exposure [r1].', { pd: 0.0031 })).toEqual(['supported']);
    expect(verdicts('The PD is 0.31% of total portfolio exposure [r1].', { pd: 0.0031 })).toEqual(['uncited']);
    expect(verdicts('The PD is 0.31% and the loss is 3 [r1].', { pd: 0.0031, n: 3 })).toEqual(['uncited', 'supported']);
  });

  it('a citation of a result that does not exist supports nothing', () => {
    expect(verdicts('The PD is 0.31% [r9].', { pd: 0.0031 })).toEqual(['unknown-result']);
  });

  it('a citation of an error result supports nothing', () => {
    expect(verdicts('The PD is 0.31% [r1].', failed('NotFound', 'No such run'))).toEqual(['error-result']);
  });

  it('only some figures uncited: the answer is not verified and names which', () => {
    const v = verify('There are 400 loans [r1] and the PD is 0.31%.', storeOf({ n: 400 }));
    expect(v.verified).toBe(false);
    expect(v.offending.map((f) => f.text)).toEqual(['0.31%']);
  });
});

describe('mismatch', () => {
  it('4.1% against a result of 3.4% fails, and names the nearest number the result holds', () => {
    const v = verify('Loss is 4.1% [r1].', storeOf({ summary: { loss: 0.034 } }));
    expect(v.verified).toBe(false);
    expect(v.figures[0]).toMatchObject({ verdict: 'mismatch', nearest: { result: 'r1', path: 'summary.loss', value: 0.034 } });
    expect(explain(v.figures[0]!)).toContain('summary.loss');
  });

  it('a figure is checked only against the results it cites', () => {
    expect(verdicts('PD is 0.31% [r2].', { pd: 0.0031 }, { pd: 0.5 })).toEqual(['mismatch']);
    expect(verdicts('PD is 0.31% [r1, r2].', { pd: 0.0031 }, { pd: 0.5 })).toEqual(['supported']);
  });
});

describe('what is not a figure', () => {
  const found = (s: string) => extract(s).map((f) => f.text);

  it('identifiers, hyphenated labels, dates, years and list markers are not', () => {
    expect(found('The pd_24m model, loan L-104 and the 12-month PD for 2026-11-04 in 2026.')).toEqual([]);
    expect(found('1. First point\n2) Second point')).toEqual([]);
    expect(found('A 30-year loan with a 24m horizon')).toEqual([]);
  });

  it('counts, durations and amounts are', () => {
    expect(found('400 loans, 82 months, $3, 0.5')).toEqual(['400', '82', '$3', '0.5']);
  });

  it('a citation\'s own digits are not figures', () => {
    expect(found('PD is low [r12, r3].')).toEqual([]);
  });
});

describe('supports()', () => {
  it('is symmetric about rounding: the half-unit boundary', () => {
    expect(supports('plain', 3.4, 1, 1, 3.449)).toBe(true);
    expect(supports('plain', 3.4, 1, 1, 3.451)).toBe(false);
    expect(supports('percent', 3.4, 1, 1, 0.03449)).toBe(true);
  });
});
