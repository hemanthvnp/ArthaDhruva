import { describe, expect, it } from 'vitest';
import { STATE_TILES, TILE_COLUMNS, TILE_ROWS } from './segmentTiles';

describe('STATE_TILES', () => {
  it('places the 50 states and DC once each, inside the grid, on distinct tiles', () => {
    const codes = Object.keys(STATE_TILES);
    expect(codes).toHaveLength(51);
    expect(codes).toContain('DC');
    const cells = new Set(Object.values(STATE_TILES).map(([c, r]) => `${c},${r}`));
    expect(cells.size).toBe(51);
    for (const [c, r] of Object.values(STATE_TILES)) {
      expect(c).toBeGreaterThanOrEqual(0);
      expect(c).toBeLessThan(TILE_COLUMNS);
      expect(r).toBeGreaterThanOrEqual(0);
      expect(r).toBeLessThan(TILE_ROWS);
    }
  });
});
