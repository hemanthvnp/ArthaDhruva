import type { BorrowerStatus } from '../api/types';

type Band = BorrowerStatus['band'];

/** Where a borrower's pin sits on the spectrum: the middle of the band's third, the same for every loan in
 * it. The borrower view has no probability to place, and a position that moved within a band would let the
 * figure be read back off the screen. */
export function bandPosition(band: Band): number {
  return band === 'LOW' ? 16.5 : band === 'MEDIUM' ? 50 : 83.5;
}

/** Colour token for a band's status word; the word itself always carries the meaning. */
export function bandColor(band: Band): string {
  return band === 'LOW' ? 'var(--ok)' : band === 'MEDIUM' ? 'var(--warn)' : 'var(--danger)';
}

/** Up to two letters for an avatar: the initials of the first two words, or the first two letters. */
export function initials(name: string): string {
  const words = name.trim().split(/[\s._-]+/).filter(Boolean);
  if (words.length === 0) return '?';
  const letters = words.length > 1 ? words[0][0] + words[1][0] : words[0].slice(0, 2);
  return letters.toUpperCase();
}

export function fileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
