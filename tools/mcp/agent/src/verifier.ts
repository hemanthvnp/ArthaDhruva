import type { ResultStore, StoredResult } from './store.js';

/**
 * The grounding verifier: does every number in the answer come from a tool result it cites?
 *
 * **What is a figure.** Any number in the answer, except: part of an identifier (`pd_24m`, `L-12`), a hyphenated label (`12-month`,
 * `30-year`), part of a date or range (`2026-11-04`, `3-5`), a list marker (`1.`, `2)` at the start of a line), a four-digit year
 * written alone, or a size suffix that is not a size (`24m` without a `$`). Everything else needs a citation. A horizon written as
 * `24 months` is a figure; write `24-month` for a label.
 *
 * **How a citation attaches.** `[r3]` or `[r3, r5]` after the figure, with at most two words between them so the noun that completes
 * a count can sit there (`400 loans [r3]`, `82 months [r2]`, `0.31% of exposure [r1]`) and a closing parenthesis or spaces may too. A digit
 * or a longer stretch between them breaks the link, so a citation at the end of a long sentence covers only the figure just before it.
 * No citation, no support: `uncited`. A citation of a result that does not exist, or that was an error, supports nothing.
 *
 * **How a figure is compared.** With every number the cited results hold, by the figure's unit, to the precision it is written at:
 *   - `%`: the result is the fraction (0.03412 supports 3.4%) or already in percent (3.412 supports 3.4%).
 *   - `bps`: the result is a fraction (0.0034 supports 34 bps), in percent (0.34), or already in basis points.
 *   - `$`, `x`, a plain number: the number itself; `k`, `m`, `bn` (with `$`) and `thousand`, `million`, `billion` scale it.
 *   A figure written with d decimals matches a value that rounds to it: 3.4% matches 3.412 but not 3.46. `4.1%` against a result of
 *   3.4% is a `mismatch`, and the verdict names the nearest figure the result does hold.
 *
 * Only numbers are checked. A claim in words ("the VM is asleep") is not, and the answer is not "verified" in that sense; the
 * contract is that the figures are real, not that the prose is true. An opt-in judge for prose belongs to the live suite.
 */
export type Unit = 'percent' | 'bps' | 'currency' | 'plain';
export type Verdict = 'supported' | 'uncited' | 'unknown-result' | 'error-result' | 'mismatch';

export interface FigureCheck {
  text: string;
  value: number;
  unit: Unit;
  citations: string[];
  verdict: Verdict;
  matched?: { result: string; path: string; value: number };
  nearest?: { result: string; path: string; value: number };
  at: number;
}

export interface Verification {
  verified: boolean;
  figures: FigureCheck[];
  offending: FigureCheck[];
}

const NUMBER = /(?<![\w.\-/])([+-]?)(\$?)(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?(?:\s?(%|percent|bps|bp|basis points?|x|k|mm|m|bn|b|million|billion|thousand)(?![A-Za-z]))?/gi;
const CITATION = /^[\s)\]]*(?:[A-Za-z][A-Za-z'-]*[\s,]*){0,2}\[\s*(r\d+(?:\s*,\s*r\d+)*)\s*\]/;
const SCALE_WITH_DOLLAR: Record<string, number> = { k: 1e3, m: 1e6, mm: 1e6, bn: 1e9, b: 1e9 };
const SCALE_WORD: Record<string, number> = { thousand: 1e3, million: 1e6, billion: 1e9 };

interface Extracted {
  text: string;
  value: number;
  decimals: number;
  unit: Unit;
  scale: number;
  citations: string[];
  at: number;
}

export function extract(answer: string): Extracted[] {
  const out: Extracted[] = [];
  for (const m of answer.matchAll(NUMBER)) {
    const [whole, sign = '', dollar = '', integer = '', fraction, suffixRaw] = m;
    const at = m.index ?? 0;
    const end = at + whole.length;
    const after = answer.slice(end);
    if (/^-[A-Za-z0-9]/.test(after)) continue;                  // 12-month, 2026-11, 3-5
    if (/^[A-Za-z_]/.test(after)) continue;                     // 24m, 3x4: the letters make it part of a word
    const lineStart = answer.lastIndexOf('\n', at) + 1;
    const alone = /^\s*$/.test(answer.slice(lineStart, at));
    if (alone && !fraction && !suffixRaw && /^[.)]\s/.test(after)) continue;   // a list marker: "1. ", "2) "
    const suffix = suffixRaw?.toLowerCase().replace(/\s+/g, ' ');
    const noSuffix = suffix === undefined;
    if (noSuffix && !dollar && !fraction && !sign && /^(19|20|21)\d\d$/.test(integer)) continue;   // a year written alone
    let unit: Unit = dollar ? 'currency' : 'plain';
    let scale = 1;
    if (suffix === '%' || suffix === 'percent') unit = 'percent';
    else if (suffix === 'bps' || suffix === 'bp' || suffix?.startsWith('basis point')) unit = 'bps';
    else if (suffix === 'x') unit = 'plain';
    else if (suffix !== undefined && suffix in SCALE_WORD) scale = SCALE_WORD[suffix]!;
    else if (suffix !== undefined && suffix in SCALE_WITH_DOLLAR) {
      if (!dollar) continue;                                    // "24m" or "5b" with no currency sign is not a size
      scale = SCALE_WITH_DOLLAR[suffix]!;
    }
    const value = Number(`${sign === '-' ? '-' : ''}${integer.replaceAll(',', '')}${fraction ? `.${fraction}` : ''}`);
    const cited = CITATION.exec(after);
    out.push({
      text: whole.trim(), value, decimals: fraction?.length ?? 0, unit, scale,
      citations: cited ? cited[1]!.split(',').map((c) => c.trim()) : [], at,
    });
  }
  return out;
}

interface Leaf {
  path: string;
  value: number;
}

function leaves(value: unknown, prefix = '', out: Leaf[] = []): Leaf[] {
  if (typeof value === 'number' && Number.isFinite(value)) out.push({ path: prefix || '(value)', value });
  else if (Array.isArray(value)) value.forEach((v, i) => leaves(v, `${prefix}[${i}]`, out));
  else if (typeof value === 'object' && value !== null) {
    for (const [k, v] of Object.entries(value)) leaves(v, prefix ? `${prefix}.${k}` : k, out);
  }
  return out;
}

/** Does a result's number `v` support a figure written as `n` with `decimals` decimals, in `unit`? */
export function supports(unit: Unit, n: number, decimals: number, scale: number, v: number): boolean {
  const half = 0.5 * 10 ** -decimals;
  const within = (a: number, b: number, tolerance: number) => Math.abs(a - b) <= tolerance + 1e-9 * Math.max(1, Math.abs(b));
  switch (unit) {
    case 'percent':
      return within(v * 100, n, half) || within(v, n, half);
    case 'bps':
      return within(v * 10_000, n, half) || within(v * 100, n, half) || within(v, n, half);
    default:
      return within(v, n * scale, half * scale);
  }
}

/** The value of `v` in the figure's own terms, only to rank candidates for the "nearest" report. */
function inTermsOf(unit: Unit, n: number, v: number): number {
  if (unit === 'percent') return Math.abs(v) <= 1.5 && Math.abs(n) > 1.5 ? v * 100 : v;
  if (unit === 'bps') return Math.abs(v) <= 1.5 && Math.abs(n) > 1.5 ? v * 10_000 : v;
  return v;
}

export function verify(answer: string, store: ResultStore): Verification {
  const figures: FigureCheck[] = extract(answer).map((f) => {
    const base = { text: f.text, value: f.value, unit: f.unit, citations: f.citations, at: f.at };
    if (f.citations.length === 0) return { ...base, verdict: 'uncited' as const };
    const cited: StoredResult[] = [];
    let unknown = false;
    for (const id of f.citations) {
      const r = store.get(id);
      if (r) cited.push(r);
      else unknown = true;
    }
    const usable = cited.filter((r) => !r.outcome.isError);
    if (usable.length === 0) return { ...base, verdict: unknown || cited.length === 0 ? ('unknown-result' as const) : ('error-result' as const) };
    let nearest: FigureCheck['nearest'];
    let best = Infinity;
    for (const r of usable) {
      for (const leaf of leaves(r.outcome.structured)) {
        if (supports(f.unit, f.value, f.decimals, f.scale, leaf.value)) {
          return { ...base, verdict: 'supported' as const, matched: { result: r.id, path: leaf.path, value: leaf.value } };
        }
        const distance = Math.abs(inTermsOf(f.unit, f.value, leaf.value) - f.value * f.scale);
        if (distance < best) {
          best = distance;
          nearest = { result: r.id, path: leaf.path, value: leaf.value };
        }
      }
    }
    return { ...base, verdict: 'mismatch' as const, ...(nearest ? { nearest } : {}) };
  });
  const offending = figures.filter((f) => f.verdict !== 'supported');
  return { verified: offending.length === 0, figures, offending };
}

/** One line per offending figure, for the retry message and the "could not verify" report. */
export function describe(f: FigureCheck): string {
  const where = f.citations.length ? ` citing ${f.citations.join(', ')}` : '';
  switch (f.verdict) {
    case 'uncited': return `"${f.text}" has no citation (put [rN] right after it)`;
    case 'unknown-result': return `"${f.text}"${where}: no such tool result`;
    case 'error-result': return `"${f.text}"${where}: that result was an error and supports no figure`;
    case 'mismatch': return `"${f.text}"${where}: the cited result does not hold it${f.nearest ? `; its nearest number is ${f.nearest.value} at ${f.nearest.path} in ${f.nearest.result}` : ''}`;
    default: return `"${f.text}" is supported`;
  }
}
