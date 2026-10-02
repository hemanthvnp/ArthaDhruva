import { Fragment, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { auditLog } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import type { AuditLogEntry } from '../api/types';
import { count, dateTime } from '../format';

const LIMITS = [50, 100, 250, 500];

function outcome(e: AuditLogEntry): { label: string; badge: string } {
  if (e.statusCode == null) return e.success ? { label: 'OK', badge: 'badge-low' } : { label: 'Failed', badge: 'badge-high' };
  const badge = e.statusCode < 400 ? 'badge-low' : e.statusCode < 500 ? 'badge-medium' : 'badge-high';
  return { label: String(e.statusCode), badge };
}

/** Stored payloads are JSON with credentials already redacted on the server. */
function pretty(json: string | null, missing: string): string {
  if (!json) return missing;
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}

export default function AuditLogPage() {
  const [limit, setLimit] = useState(50);
  const [filter, setFilter] = useState('');
  const [open, setOpen] = useState<number | null>(null);
  const log = useQuery({ queryKey: ['audit-log', limit], queryFn: () => auditLog(limit) });

  const needle = filter.trim().toLowerCase();
  const entries = (log.data ?? []).filter((e) =>
    !needle || [e.actor, e.path, e.endpoint, e.modelVersion, e.httpMethod, e.statusCode?.toString()].some((v) => v?.toLowerCase().includes(needle)));

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Audit log</h2>
          <p className="page-subtitle">
            Every call to the API in your organization: who made it, what was sent, how it ended, and which model version
            answered. Changes also record what came back. Entries are append-only: the application's own database role
            cannot update or delete them.
          </p>
        </div>
      </div>

      <div className="card">
        <div className="toolbar">
          <div className="form-row" style={{ margin: 0 }}>
            <input aria-label="Filter" placeholder="Filter by user, path, status or model version" value={filter} onChange={(e) => setFilter(e.target.value)} />
            <select aria-label="How many entries" value={limit} onChange={(e) => setLimit(Number(e.target.value))}>
              {LIMITS.map((n) => <option key={n} value={n}>Latest {n}</option>)}
            </select>
          </div>
          <button className="secondary" onClick={() => log.refetch()} disabled={log.isFetching}>
            {log.isFetching ? 'Refreshing...' : 'Refresh'}
          </button>
        </div>
        <ErrorBanner error={log.error} />
        {log.isPending && <div className="skeleton" style={{ height: 160 }} />}
        {log.data && entries.length === 0 && <p className="empty">{needle ? 'No entries match the filter.' : 'No audit entries yet.'}</p>}
        {entries.length > 0 && (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>When</th>
                  <th>Who</th>
                  <th>Request</th>
                  <th>Result</th>
                  <th>Model</th>
                  <th className="num">Latency</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((e) => {
                  const result = outcome(e);
                  const expanded = open === e.id;
                  return (
                    <Fragment key={e.id}>
                      <tr className={expanded ? 'selected' : ''} onClick={() => setOpen(expanded ? null : e.id)} style={{ cursor: 'pointer' }}>
                        <td style={{ whiteSpace: 'nowrap' }}>
                          <button type="button" className="plain-text" aria-expanded={expanded}>{dateTime(e.occurredAt)}</button>
                        </td>
                        <td>{e.actor ?? <span className="sub">system</span>}</td>
                        <td>
                          <span className="mono">{e.httpMethod ? `${e.httpMethod} ${e.path ?? ''}` : e.endpoint}</span>
                          {e.httpMethod && <div className="sub">{e.endpoint}</div>}
                        </td>
                        <td><span className={`badge ${result.badge}`}>{result.label}</span></td>
                        <td className="mono">{e.modelVersion ?? ''}</td>
                        <td className="num">{count(e.latencyMs)} ms</td>
                      </tr>
                      {expanded && (
                        <tr className="detail-row">
                          <td colSpan={6}>
                            {e.errorMessage && <p className="error-banner" style={{ marginTop: 0 }}>{e.errorMessage}</p>}
                            <div className="widget-grid">
                              <div>
                                <div className="section-label">Request</div>
                                <pre className="code-block">{pretty(e.requestJson, '(no arguments)')}</pre>
                              </div>
                              <div>
                                <div className="section-label">Response</div>
                                <pre className="code-block">
                                  {pretty(e.responseJson, e.httpMethod === 'GET' ? 'A read is recorded as having happened; the data it returned is not copied into the trail.' : '(none)')}
                                </pre>
                              </div>
                            </div>
                            <p className="chart-caption">{e.clientIp ? `From ${e.clientIp}. ` : ''}Credentials are redacted before an entry is stored.</p>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {log.data && (
          <p className="chart-caption">
            Showing {count(entries.length)} of the latest {count(log.data.length)} entries. Select a row to see what was sent and returned.
          </p>
        )}
      </div>
    </div>
  );
}
