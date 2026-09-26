/** Colour-scheme preset (accent + sidebar). Light/dark is handled separately in Layout. */
export type Palette = 'teal' | 'brass' | 'oxblood' | 'emerald' | 'mono';

export const PALETTES: { id: Palette; label: string; swatch: string }[] = [
  { id: 'teal', label: 'Petrol teal', swatch: '#0e7c86' },
  { id: 'brass', label: 'Ink & brass', swatch: '#b8842a' },
  { id: 'oxblood', label: 'Oxblood', swatch: '#9b1c34' },
  { id: 'emerald', label: 'Emerald', swatch: '#146c43' },
  { id: 'mono', label: 'Monochrome', swatch: '#1c1917' },
];

export function readPalette(): Palette {
  try {
    const p = localStorage.getItem('palette');
    return PALETTES.some((x) => x.id === p) ? (p as Palette) : 'teal';
  } catch {
    return 'teal';
  }
}

export function applyPalette(p: Palette) {
  if (p === 'teal') document.documentElement.removeAttribute('data-palette');
  else document.documentElement.setAttribute('data-palette', p);
  try {
    if (p === 'teal') localStorage.removeItem('palette');
    else localStorage.setItem('palette', p);
  } catch {
    /* storage unavailable: the choice just won't persist */
  }
}

// Apply before first paint so the login page and a reload never flash the default palette.
applyPalette(readPalette());
