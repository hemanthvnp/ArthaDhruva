import { describe, expect, it } from 'vitest';
import { bandColor, bandPosition, fileSize, initials } from './borrower';

describe('borrower view helpers', () => {
  it('places each band in the middle of its own third', () => {
    expect(bandPosition('LOW')).toBeLessThan(33.3);
    expect(bandPosition('MEDIUM')).toBeGreaterThan(33.3);
    expect(bandPosition('MEDIUM')).toBeLessThan(66.6);
    expect(bandPosition('HIGH')).toBeGreaterThan(66.6);
  });

  it('colours bands with the status tokens', () => {
    expect(bandColor('LOW')).toBe('var(--ok)');
    expect(bandColor('MEDIUM')).toBe('var(--warn)');
    expect(bandColor('HIGH')).toBe('var(--danger)');
  });

  it('makes avatar initials from names and usernames', () => {
    expect(initials('Priya Nair')).toBe('PN');
    expect(initials('loan.care')).toBe('LC');
    expect(initials('ana')).toBe('AN');
    expect(initials('  ')).toBe('?');
  });

  it('formats file sizes for people', () => {
    expect(fileSize(512)).toBe('512 B');
    expect(fileSize(2048)).toBe('2 KB');
    expect(fileSize(3 * 1024 * 1024)).toBe('3.0 MB');
  });
});
