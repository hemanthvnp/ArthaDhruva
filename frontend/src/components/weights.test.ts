import { describe, expect, it } from 'vitest';
import { ILLUSTRATIVE_PRESET, parseWeights, presetFor, weightsLabel } from './weights';

const label = (s: string) => ({ BASELINE: 'Baseline', ADVERSE: 'Adverse', SEVERELY_ADVERSE: 'Severely adverse' })[s] ?? s;

describe('presetFor', () => {
  it('fills the illustrative preset for the scenarios on screen and zero for the rest', () => {
    expect(presetFor(['BASELINE', 'ADVERSE', 'SEVERELY_ADVERSE', 'RATES_UP_200'])).toEqual({
      BASELINE: '50', ADVERSE: '30', SEVERELY_ADVERSE: '20', RATES_UP_200: '0',
    });
  });

  it('sums to 100 over the three stress scenarios', () => {
    expect(Object.values(ILLUSTRATIVE_PRESET).reduce((a, b) => a + b, 0)).toBe(100);
  });
});

describe('parseWeights', () => {
  it('turns percentages into fractions when they sum to 100', () => {
    const parsed = parseWeights({ BASELINE: '50', ADVERSE: '30', SEVERELY_ADVERSE: '20' });
    expect(parsed.problem).toBeNull();
    expect(parsed.weights).toEqual({ BASELINE: 0.5, ADVERSE: 0.3, SEVERELY_ADVERSE: 0.2 });
  });

  it('says what the weights sum to when they fall short', () => {
    const parsed = parseWeights({ BASELINE: '50', ADVERSE: '30', SEVERELY_ADVERSE: '10' });
    expect(parsed.weights).toBeNull();
    expect(parsed.sum).toBe(90);
    expect(parsed.problem).toBe('The weights sum to 90%; they must sum to 100%.');
  });

  it('counts a blank entry as zero', () => {
    expect(parseWeights({ BASELINE: '100', ADVERSE: '' }).weights).toEqual({ BASELINE: 1, ADVERSE: 0 });
  });

  it('accepts decimals that make 100 and rejects negatives and text', () => {
    expect(parseWeights({ BASELINE: '33.4', ADVERSE: '33.3', SEVERELY_ADVERSE: '33.3' }).problem).toBeNull();
    expect(parseWeights({ BASELINE: '120', ADVERSE: '-20' }).problem).toBe('The weight for ADVERSE must be a number of 0 or more.');
    expect(parseWeights({ BASELINE: 'half', ADVERSE: '50' }).problem).toContain('BASELINE');
  });
});

describe('weightsLabel', () => {
  it('names the weights in order and leaves out zeros', () => {
    expect(weightsLabel({ BASELINE: 0.5, ADVERSE: 0.3, SEVERELY_ADVERSE: 0.2, RATES_UP_200: 0 }, label))
      .toBe('50% baseline, 30% adverse, 20% severely adverse');
  });

  it('keeps one decimal for a fractional percentage', () => {
    expect(weightsLabel({ BASELINE: 0.334, ADVERSE: 0.666 }, label)).toBe('33.4% baseline, 66.6% adverse');
  });
});
