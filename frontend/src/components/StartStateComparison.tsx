import { useQueries } from '@tanstack/react-query';
import { termStructure } from '../api/client';
import type { DelinquencyHistory, LifetimeRiskInput } from '../api/types';
import ErrorBanner from './ErrorBanner';
import IntervalRange from './IntervalRange';
import { CLEAN_HISTORY, STATE_DAYS, comparisonRows, historyFor } from './delinquency';
import { compactMoney, pct } from '../format';

interface Props {
  input: LifetimeRiskInput;
  scenario: string;
  history: DelinquencyHistory | undefined;
}

/**
 * The same loan from current, 30 and 60 days past due: its projected lifetime PD and expected credit loss, each with its
 * range. Each status uses the history given, adjusted so it is consistent with that status.
 */
export default function StartStateComparison({ input, scenario, history }: Props) {
  const base = history ?? CLEAN_HISTORY;
  const queries = useQueries({
    queries: ([0, 1, 2] as const).map((state) => ({
      queryKey: ['start-state', JSON.stringify(input), scenario, state, JSON.stringify(base)],
      queryFn: () => termStructure({ ...input, daysPastDue: STATE_DAYS[state], delinquency: historyFor(state, base), intervals: {} }, scenario),
      retry: false,
    })),
  });
  const rows = comparisonRows(queries.map((q) => q.data));
  const failed = queries.find((q) => q.error)?.error ?? null;
  return (
    <div className="card" data-testid="start-state-comparison">
      <div className="card-head">
        <h3>The same loan from each status</h3>
        <span className="badge">{scenario.replace(/_/g, ' ').toLowerCase()}</span>
      </div>
      <ErrorBanner error={failed} />
      <table>
        <thead>
          <tr>
            <th>Starting status</th>
            <th className="num">12-month PD</th>
            <th className="num">Lifetime PD, with range</th>
            <th className="num">Lifetime expected loss, with range</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(({ state, label, response }) => {
            const s = response?.termStructure.summary;
            const ranges = response?.intervals;
            return (
              <tr key={state}>
                <td>{label}</td>
                <td className="num">{s ? pct(s.pd12m) : '...'}</td>
                <td className="num">
                  {s ? pct(s.pdLifetime) : '...'}
                  {ranges?.pdLifetime && <IntervalRange label={`Lifetime PD from ${label}`} interval={ranges.pdLifetime} intervals={ranges} format={(n) => pct(n)} />}
                </td>
                <td className="num">
                  {response ? compactMoney(response.termStructure.ecl.eclLifetime) : '...'}
                  {ranges?.eclLifetime && <IntervalRange label={`Lifetime expected loss from ${label}`} interval={ranges.eclLifetime} intervals={ranges} format={compactMoney} />}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <p className="chart-caption">
        Each status keeps the history above, adjusted so it is consistent with it: a late loan has at least one spell and a worst
        status at least as bad as today&apos;s. The delinquency model projects how a late loan cures, rolls or defaults;
        {' '}{rows.every((r) => r.response?.modelUsed.startsWith('delinquency@')) ? 'it ran for all three.' : 'where it did not run, the result says so in the response.'}
      </p>
    </div>
  );
}
