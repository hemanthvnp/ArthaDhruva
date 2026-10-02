/** Number formatting shared by the risk pages, so the same quantity reads the same everywhere. */

/** One way of writing numbers for every reader. The figures are US dollars and US loan counts; left to
 * the browser, a reader elsewhere would see them grouped by another convention beside a dollar sign. */
const LOCALE = 'en-US';

/** A count or other plain number, with thousands separators. */
export function count(n: number, maximumFractionDigits = 0): string {
  return n.toLocaleString(LOCALE, { maximumFractionDigits });
}

/** A probability as a percentage; small probabilities keep enough digits to be told apart. */
export function pct(p: number, digits?: number): string {
  const value = p * 100;
  const d = digits ?? (value < 0.1 ? 3 : value < 10 ? 2 : 1);
  return `${value.toFixed(d)}%`;
}

/** Whole currency for anything worth rounding, cents below a hundred. */
export function money(n: number): string {
  const abs = Math.abs(n);
  return `$${n.toLocaleString(LOCALE, { minimumFractionDigits: abs < 100 ? 2 : 0, maximumFractionDigits: abs < 100 ? 2 : 0 })}`;
}

/** Currency for headline figures and chart axes: $1.24M, $318K. */
export function compactMoney(n: number): string {
  const abs = Math.abs(n);
  if (abs >= 1e9) return `$${(n / 1e9).toFixed(2)}B`;
  if (abs >= 1e6) return `$${(n / 1e6).toFixed(2)}M`;
  if (abs >= 1e4) return `$${(n / 1e3).toFixed(0)}K`;
  if (abs >= 1e3) return `$${(n / 1e3).toFixed(1)}K`;
  return money(n);
}

/** Currency for a chart axis: as short as it can be while two ticks still read differently ($120M, $1.5M, $0). */
export function axisMoney(n: number): string {
  const abs = Math.abs(n);
  const short = (value: number) => String(Number(value.toFixed(1)));
  if (abs >= 1e9) return `$${short(n / 1e9)}B`;
  if (abs >= 1e6) return `$${short(n / 1e6)}M`;
  if (abs >= 1e3) return `$${short(n / 1e3)}K`;
  return `$${short(n)}`;
}

/** Basis points of exposure, the usual unit for a loss allowance. */
export function bps(fraction: number): string {
  return `${(fraction * 1e4).toFixed(fraction * 1e4 < 10 ? 1 : 0)} bp`;
}

/** Months as years once they are long enough to read better that way. */
export function duration(months: number): string {
  return months >= 24 ? `${(months / 12).toFixed(1)} yr` : `${months.toFixed(0)} mo`;
}

/** "2027-03" as "Mar 2027". */
export function monthLabel(yearMonth: string): string {
  const [y, m] = yearMonth.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, 1)).toLocaleDateString(LOCALE, { month: 'short', year: 'numeric', timeZone: 'UTC' });
}

/** A moment, in the reader's own time zone and written so that day and month cannot be mistaken for
 * each other ("1 Oct 2026, 18:43", never 1/10/2026). */
export function dateTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

export function dateOnly(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { dateStyle: 'medium' });
}
