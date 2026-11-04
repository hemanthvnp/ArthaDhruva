import { ToolError } from '@arthadhruva/mcp-kit';
import { httpAttempt, withRetries, type Fetch, type RetryPolicy } from './retry.js';

/**
 * Prometheus, read-only. A caller picks a metric by NAME from this catalog; the query text is a constant here, never
 * built from input, so there is no way to ask for an expensive or arbitrary expression. The queries are the ones the
 * alert rules in observability/alerts.yml already use, so what is reported here is what would page.
 */
export interface MetricDef {
  description: string;
  unit: 'ratio' | 'seconds' | 'count' | 'per-second' | 'bool';
  query: string;
}

export const METRICS = {
  backend_up: { description: 'Is the risk-engine target being scraped successfully (1 = up)', unit: 'bool', query: 'min(up{job="risk-engine"})' },
  api_request_rate: { description: 'API requests per second over 5 minutes', unit: 'per-second', query: 'sum(rate(http_server_requests_seconds_count{job="risk-engine", uri=~"/v1/.*"}[5m]))' },
  api_error_ratio: {
    description: 'Share of API requests answered with a 5xx over 5 minutes (the alert fires above 0.01)', unit: 'ratio',
    query: 'sum(rate(http_server_requests_seconds_count{job="risk-engine", uri=~"/v1/.*", status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{job="risk-engine", uri=~"/v1/.*"}[5m]))',
  },
  score_p95_seconds: {
    description: 'p95 latency of POST /v1/score over 5 minutes (the alert fires above 0.4)', unit: 'seconds',
    query: 'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="risk-engine", uri="/v1/score"}[5m])))',
  },
  heap_after_gc: { description: 'JVM heap in use after garbage collection, as a share of the maximum (the alert fires above 0.9)', unit: 'ratio', query: 'max(jvm_memory_usage_after_gc{job="risk-engine", area="heap"})' },
  gc_overhead: { description: 'Share of CPU time spent in garbage collection (the alert fires above 0.25)', unit: 'ratio', query: 'max(jvm_gc_overhead{job="risk-engine"})' },
  db_pool_pending: { description: 'Threads waiting for a database connection (any above 0 is a problem)', unit: 'count', query: 'sum(hikaricp_connections_pending{job="risk-engine"})' },
  error_logs_10m: { description: 'ERROR log lines in the last 10 minutes', unit: 'count', query: 'sum(increase(logback_events_total{job="risk-engine", level="error"}[10m]))' },
} as const satisfies Record<string, MetricDef>;

export type MetricName = keyof typeof METRICS;
export const METRIC_NAMES = Object.keys(METRICS) as MetricName[];

export interface Sample {
  labels: Record<string, string>;
  value: number | null;
  /** When Prometheus evaluated it. */
  at: string;
}

export interface Alert {
  name: string;
  state: string;
  severity: string | null;
  labels: Record<string, string>;
  summary: string | null;
  /** When it began (to be pending, then firing). */
  activeSince: string;
}

interface QueryData { resultType: string; result: Array<{ metric: Record<string, string>; value: [number, string] }> }
interface AlertsData { alerts: Array<{ labels: Record<string, string>; annotations: Record<string, string>; state: string; activeAt: string }> }

export class Prometheus {
  constructor(private readonly baseUrl: string, private readonly policy: RetryPolicy, private readonly fetchImpl: Fetch = fetch) {}

  async instant(metric: MetricName): Promise<Sample[]> {
    const data = await this.get<QueryData>('/api/v1/query', { query: METRICS[metric].query });
    return data.result.map((r) => {
      const n = Number(r.value[1]);
      return { labels: r.metric, value: Number.isFinite(n) ? n : null, at: new Date(r.value[0] * 1000).toISOString() };
    });
  }

  async alerts(): Promise<Alert[]> {
    const data = await this.get<AlertsData>('/api/v1/alerts', {});
    return data.alerts.map((a) => ({
      name: a.labels.alertname ?? 'unknown', state: a.state, severity: a.labels.severity ?? null,
      labels: Object.fromEntries(Object.entries(a.labels).filter(([k]) => k !== 'alertname')),
      summary: a.annotations.summary ?? null, activeSince: a.activeAt,
    }));
  }

  private async get<T>(path: string, query: Record<string, string>): Promise<T> {
    const url = `${this.baseUrl}${path}${Object.keys(query).length ? `?${new URLSearchParams(query)}` : ''}`;
    const res = await withRetries(this.policy, () => httpAttempt(this.fetchImpl, url, { headers: { Accept: 'application/json' } }, this.policy.timeoutMs, 'Prometheus'));
    const text = await res.text();
    let body: { status?: string; data?: T; error?: string };
    try {
      body = JSON.parse(text);
    } catch {
      throw new ToolError('UpstreamDown', 'Prometheus answered something that is not JSON.');
    }
    if (!res.ok || body.status !== 'success' || body.data === undefined) {
      throw new ToolError('UpstreamDown', `Prometheus refused the query: ${String(body.error ?? res.statusText).slice(0, 200)}`);
    }
    return body.data;
  }
}
