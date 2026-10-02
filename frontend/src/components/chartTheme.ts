/** Recharts props that put every chart on the design tokens: hairline grid, muted ticks, paper tooltip. */
export const AXIS = { stroke: 'var(--border-strong)', tick: { fill: 'var(--text-muted)', fontSize: 12 }, tickLine: false } as const;
export const GRID = { stroke: 'var(--border)', strokeDasharray: '3 3', vertical: false } as const;
export const TOOLTIP = {
  contentStyle: { background: 'var(--surface)', border: '1px solid var(--border-strong)', borderRadius: 8, color: 'var(--text)', fontSize: 13 },
  labelStyle: { color: 'var(--text-muted)', marginBottom: 4 },
} as const;
// itemSorter null: series are listed in the order they are drawn (baseline first), not alphabetically.
export const LEGEND = { verticalAlign: 'top', align: 'right', iconType: 'plainline', itemSorter: null, wrapperStyle: { paddingBottom: 8, color: 'var(--text-muted)', fontSize: 13 } } as const;

/** How each built-in scenario is drawn. Severity is risk, so the stress scenarios take the amber and
 * red of the risk spectrum; the baseline is ink; the two rate scenarios are neutral and dashed. */
export const SCENARIO_STYLE: Record<string, { label: string; color: string; dash?: string }> = {
  BASELINE: { label: 'Baseline', color: 'var(--text)' },
  ADVERSE: { label: 'Adverse', color: 'var(--sig-mid)' },
  SEVERELY_ADVERSE: { label: 'Severely adverse', color: 'var(--sig-high)' },
  RATES_UP_200: { label: 'Rates +200 bp', color: 'var(--hop-3)', dash: '6 4' },
  RATES_DOWN_200: { label: 'Rates -200 bp', color: 'var(--hop-4)', dash: '2 4' },
};

export function scenarioLabel(name: string): string {
  return SCENARIO_STYLE[name]?.label ?? name;
}

/** X-axis ticks for a monthly series: Januaries only, thinned so at most `max` labels are drawn. */
export function yearTicks(months: string[], max = 8): string[] {
  const januaries = months.filter((m) => m.endsWith('-01'));
  const step = Math.max(1, Math.ceil(januaries.length / max));
  return januaries.filter((_, i) => i % step === 0);
}

export const yearOf = (month: string): string => month.slice(0, 4);
