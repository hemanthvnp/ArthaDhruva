import { describe, expect, it } from 'vitest';
import { axisMoney, bps, compactMoney, count, duration, money, monthLabel, pct } from './format';
import { featureLabel } from './labels';
import { yearTicks } from './components/chartTheme';
import { meterPosition, riskBand } from './components/risk';

describe('pct', () => {
  it('keeps enough digits to tell small probabilities apart', () => {
    expect(pct(0.00087)).toBe('0.087%');
    expect(pct(0.0079)).toBe('0.79%');
    expect(pct(0.123)).toBe('12.3%');
  });
  it('uses the digits it is given', () => {
    expect(pct(0.5, 0)).toBe('50%');
    expect(pct(0.999, 1)).toBe('99.9%');
  });
});

describe('money', () => {
  // The figures are US dollars: they are grouped the same way whatever the reader's locale is.
  it('groups by thousands and drops cents on larger amounts', () => {
    expect(money(1234567)).toBe('$1,234,567');
    expect(money(157284.4)).toBe('$157,284');
  });
  it('keeps cents below a hundred', () => {
    expect(money(89.5)).toBe('$89.50');
    expect(money(4.164)).toBe('$4.16');
  });
  it('shortens headline figures', () => {
    expect(compactMoney(112_880_000)).toBe('$112.88M');
    expect(compactMoney(36_338)).toBe('$36K');
    expect(compactMoney(7_630)).toBe('$7.6K');
    expect(compactMoney(89.5)).toBe('$89.50');
  });
  it('labels a chart axis as briefly as it can', () => {
    expect(axisMoney(120e6)).toBe('$120M');
    expect(axisMoney(1.5e6)).toBe('$1.5M');
    expect(axisMoney(45_000)).toBe('$45K');
    expect(axisMoney(0)).toBe('$0');
  });
});

describe('other figures', () => {
  it('writes a loss allowance in basis points of exposure', () => {
    expect(bps(0.0000676)).toBe('0.7 bp');
    expect(bps(0.0025)).toBe('25 bp');
  });
  it('writes long durations in years', () => {
    expect(duration(18)).toBe('18 mo');
    expect(duration(112.8)).toBe('9.4 yr');
  });
  it('names a month', () => {
    expect(monthLabel('2027-03')).toBe('Mar 2027');
    expect(monthLabel('2026-12')).toBe('Dec 2026');
  });
  it('counts with separators', () => {
    expect(count(1234567)).toBe('1,234,567');
    expect(count(301.24, 1)).toBe('301.2');
  });
});

describe('labels and scales', () => {
  it('names model inputs in plain words and degrades gracefully for unknown ones', () => {
    expect(featureLabel('credit_score')).toBe('Credit score');
    expect(featureLabel('some_new_input')).toBe('some new input');
  });
  it('puts one tick on each January, thinned to the number asked for', () => {
    const months = Array.from({ length: 120 }, (_, i) => `${2026 + Math.floor((i + 8) / 12)}-${String(((i + 8) % 12) + 1).padStart(2, '0')}`);
    const ticks = yearTicks(months, 5);
    expect(ticks.every((t) => t.endsWith('-01'))).toBe(true);
    expect(ticks.length).toBeLessThanOrEqual(5);
    expect(ticks[0]).toBe('2027-01');
  });
  it('bands risk at 2% and 10%, and places the meter pin by band', () => {
    expect(riskBand(0.0199)).toBe('LOW');
    expect(riskBand(0.02)).toBe('MEDIUM');
    expect(riskBand(0.0999)).toBe('MEDIUM');
    expect(riskBand(0.1)).toBe('HIGH');
    expect(meterPosition(0)).toBe(0);
    expect(meterPosition(0.02)).toBeCloseTo(33.3, 5);
    expect(meterPosition(0.1)).toBeCloseTo(66.6, 5);
    expect(meterPosition(0.9)).toBe(100);
    // strictly increasing up to the cap: a riskier loan never sits to the left of a safer one
    let previous = -1;
    for (let p = 0; p <= 0.25; p += 0.005) {
      const position = meterPosition(p);
      expect(position).toBeGreaterThan(previous);
      previous = position;
    }
  });
});
