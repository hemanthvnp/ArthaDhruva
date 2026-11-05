import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { realizedOutcomes, runRealizedComparison } from '../api/client';
import { useAuth } from '../auth/useAuth';
import { count, dateTime, pct } from '../format';
import ErrorBanner from './ErrorBanner';
import { realizedVerdict, unresolvedLine } from './realized';

/**
 * The PD model's stored scores against what happened to the loans: the outcomes an organization has recorded, and the latest
 * comparison, which a deferred job queues once a month and an administrator can ask for now. Recording outcomes is an API call
 * (this deployment has no servicing feed); the page shows what the comparison found and what it could not use.
 */
export default function RealizedOutcomes() {
  const { auth } = useAuth();
  const queryClient = useQueryClient();
  const overview = useQuery({
    queryKey: ['realized-outcomes'],
    queryFn: realizedOutcomes,
    // while a comparison is queued or running its result is polled for
    refetchInterval: (q) => (q.state.data?.latest && ['QUEUED', 'RUNNING'].includes(q.state.data.latest.status) ? 1500 : false),
  });
  const run = useMutation({ mutationFn: runRealizedComparison, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['realized-outcomes'] }) });
  const data = overview.data;
  const latest = data?.latest ?? null;
  const report = latest?.report ?? null;
  return (
    <div className="card" data-testid="realized-outcomes">
      <div className="card-head">
        <h3>Stored scores against what happened</h3>
        {auth?.role === 'ADMIN' && (
          <button type="button" onClick={() => run.mutate()} disabled={run.isPending || latest?.status === 'QUEUED' || latest?.status === 'RUNNING'}>
            {latest?.status === 'QUEUED' || latest?.status === 'RUNNING' ? 'Comparing...' : 'Compare now'}
          </button>
        )}
      </div>
      <ErrorBanner error={overview.error ?? run.error} />
      {data && (
        <p className="page-subtitle">
          {count(data.scored)} loans have a stored score and {count(data.recorded)} have a recorded outcome.
          {data.recorded === 0 && ' Record outcomes with POST /v1/admin/outcomes (loanId, DEFAULTED, PREPAID or ACTIVE, and the date).'}
        </p>
      )}
      {latest && latest.status === 'FAILED' && <p className="error-banner" role="alert">The last comparison failed: {latest.error}</p>}
      {latest && (latest.status === 'QUEUED' || latest.status === 'RUNNING') && <p className="chart-caption" role="status">A comparison is {latest.status.toLowerCase()}.</p>}
      {report && (
        <>
          {realizedVerdict(report).map((line) => <p key={line} style={{ margin: '0.4rem 0' }}>{line}</p>)}
          {report.deciles.length > 0 && (
            <div className="table-scroll">
              <table>
                <thead>
                  <tr><th>Decile of predicted risk</th><th className="num">Loans</th><th className="num">Predicted</th><th className="num">Realized</th></tr>
                </thead>
                <tbody>
                  {report.deciles.map((d) => (
                    <tr key={d.decile}>
                      <td>{d.decile}</td>
                      <td className="num">{count(d.loans)}</td>
                      <td className="num">{pct(d.predicted)}</td>
                      <td className="num">{pct(d.realized)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {unresolvedLine(report) && <p className="chart-caption">{unresolvedLine(report)}</p>}
          <p className="chart-caption">{report.method} {report.caveat}</p>
          {latest?.finishedAt && <p className="chart-caption">Computed {dateTime(latest.finishedAt)} on a compute thread ({report.computedOn}), requested by {latest.requestedBy}.</p>}
        </>
      )}
      {data && !latest && <p className="chart-caption">No comparison has been run yet. It is queued automatically once a month for an organization with recorded outcomes.</p>}
    </div>
  );
}
