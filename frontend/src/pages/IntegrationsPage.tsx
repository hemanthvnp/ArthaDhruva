import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  clearPortfolio, createApiKey, createWebhook, deleteWebhook, getPortfolioStatus, getSsoConfig, getUsage, listApiKeys,
  listWebhookDeliveries, listWebhooks, revokeApiKey, saveSsoConfig, ssoCallbackUrl, uploadPortfolio,
  type PortfolioUploadResult,
} from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import type { WebhookEvent } from '../api/types';
import { count, dateOnly, dateTime } from '../format';

const EVENTS: [WebhookEvent, string][] = [['LOAN_SCORED', 'Loan scored'], ['CASE_FLAGGED', 'Case flagged'], ['CASE_ASSIGNED', 'Case assigned']];
const METRIC: Record<string, string> = {
  SCORE_CALL: 'Scores',
  EXPECTED_LOSS: 'Expected-loss calculations',
  TERM_STRUCTURE: 'Lifetime projections',
  PORTFOLIO_RUN: 'Portfolio runs',
  CVAR_RUN: 'Loss simulations',
  ASSISTANT_CALL: 'Assistant questions',
};
const DELIVERY: Record<string, { label: string; badge: string }> = {
  DELIVERED: { label: 'Delivered', badge: 'badge-low' },
  PENDING: { label: 'Waiting to send', badge: 'badge-medium' },
  IN_FLIGHT: { label: 'Sending', badge: 'badge-medium' },
  FAILED: { label: 'Gave up', badge: 'badge-high' },
};

function SingleSignOn() {
  const queryClient = useQueryClient();
  const config = useQuery({ queryKey: ['sso-config'], queryFn: getSsoConfig });
  const [issuer, setIssuer] = useState('');
  const [clientId, setClientId] = useState('');
  const [clientSecret, setClientSecret] = useState('');
  const [enabled, setEnabled] = useState(true);
  const [enforced, setEnforced] = useState(false);

  // The form starts from what is saved, and starts over from it whenever that changes.
  const [loaded, setLoaded] = useState(config.data);
  if (config.data !== loaded) {
    setLoaded(config.data);
    if (config.data?.configured) {
      setIssuer(config.data.issuer ?? '');
      setClientId(config.data.clientId ?? '');
      setEnabled(config.data.enabled ?? true);
      setEnforced(config.data.enforced ?? false);
    }
  }

  const save = useMutation({
    mutationFn: () => saveSsoConfig({ issuer: issuer.trim(), clientId: clientId.trim(), clientSecret: clientSecret || undefined, enabled, enforced }),
    onSuccess: () => {
      setClientSecret('');
      void queryClient.invalidateQueries({ queryKey: ['sso-config'] });
    },
  });
  const configured = config.data?.configured === true;

  return (
    <div className="card">
      <div className="card-head">
        <h3>Single sign-on</h3>
        <span className={`badge ${configured && enabled ? 'badge-low' : ''}`}>{configured ? (enabled ? 'On' : 'Configured, off') : 'Not configured'}</span>
      </div>
      <p className="page-subtitle">
        OpenID Connect with your identity provider. Register <code>{ssoCallbackUrl()}</code> there as the redirect URI. The
        issuer is checked against its own discovery document before it is saved.
      </p>
      <div className="field-grid">
        <div className="field">
          <label htmlFor="sso-issuer">Issuer URL</label>
          <input id="sso-issuer" value={issuer} onChange={(e) => setIssuer(e.target.value)} placeholder="https://login.example.com" />
        </div>
        <div className="field">
          <label htmlFor="sso-client">Client ID</label>
          <input id="sso-client" value={clientId} onChange={(e) => setClientId(e.target.value)} autoComplete="off" />
        </div>
        <div className="field">
          <label htmlFor="sso-secret">Client secret</label>
          <input id="sso-secret" type="password" value={clientSecret} onChange={(e) => setClientSecret(e.target.value)}
            placeholder={configured ? 'unchanged unless you enter a new one' : ''} autoComplete="new-password" />
        </div>
        <div className="field" style={{ justifyContent: 'flex-end', gap: '0.5rem' }}>
          <label style={{ fontWeight: 400, color: 'var(--text)' }}>
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> Offer single sign-on at sign-in
          </label>
          <label style={{ fontWeight: 400, color: 'var(--text)' }}>
            <input type="checkbox" checked={enforced} onChange={(e) => setEnforced(e.target.checked)} /> Require it (passwords no longer accepted)
          </label>
        </div>
      </div>
      <div className="actions">
        <button onClick={() => save.mutate()} disabled={save.isPending || !issuer.trim() || !clientId.trim() || (!configured && !clientSecret)}>
          {save.isPending ? 'Checking the provider...' : 'Save'}
        </button>
        {save.isSuccess && <span className="sub" role="status">Saved.</span>}
      </div>
      <ErrorBanner error={config.error ?? save.error} />
    </div>
  );
}

function UploadOutcome({ result }: { result: PortfolioUploadResult }) {
  const rejected = result.results.filter((r) => !r.ok);
  return (
    <div role="status" style={{ marginTop: '0.9rem' }}>
      <p style={{ margin: 0 }}>
        <b>{count(result.accepted)}</b> loans accepted{result.rejected > 0 && <>, <b>{count(result.rejected)}</b> rejected</>}.
      </p>
      {rejected.length > 0 && (
        <>
          <div className="table-scroll" style={{ marginTop: '0.6rem' }}>
            <table>
              <thead><tr><th className="num">Row</th><th>Loan</th><th>Why it was rejected</th></tr></thead>
              <tbody>
                {rejected.slice(0, 25).map((r) => (
                  <tr key={r.index}>
                    <td className="num">{r.index + 1}</td>
                    <td className="mono">{r.loanId ?? ''}</td>
                    <td style={{ whiteSpace: 'normal' }}>{r.error}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {rejected.length > 25 && <p className="chart-caption">And {count(rejected.length - 25)} more.</p>}
        </>
      )}
    </div>
  );
}

export default function IntegrationsPage() {
  const qc = useQueryClient();
  const usage = useQuery({ queryKey: ['usage'], queryFn: getUsage });
  const keys = useQuery({ queryKey: ['api-keys'], queryFn: listApiKeys });
  const hooks = useQuery({ queryKey: ['webhooks'], queryFn: listWebhooks });
  const deliveries = useQuery({ queryKey: ['webhook-deliveries'], queryFn: listWebhookDeliveries, refetchInterval: 15_000, refetchIntervalInBackground: false });
  const portfolio = useQuery({ queryKey: ['portfolio'], queryFn: getPortfolioStatus });

  const [keyName, setKeyName] = useState('');
  const [newKey, setNewKey] = useState<string | null>(null);
  const [url, setUrl] = useState('');
  const [events, setEvents] = useState<WebhookEvent[]>(['CASE_FLAGGED']);
  const [newSecret, setNewSecret] = useState<string | null>(null);
  const [uploaded, setUploaded] = useState<PortfolioUploadResult | null>(null);
  const [portfolioNote, setPortfolioNote] = useState<string | null>(null);

  const inv = (k: string) => () => qc.invalidateQueries({ queryKey: [k] });
  // Every view built on the loan catalog is stale once the portfolio changes.
  const portfolioChanged = () => {
    for (const key of ['portfolio', 'loan', 'loan-suggestions', 'portfolio-risk', 'portfolio-risk-history', 'pd-drift']) {
      void qc.invalidateQueries({ queryKey: [key] });
    }
  };
  const addKey = useMutation({ mutationFn: () => createApiKey(keyName.trim()), onSuccess: (k) => { setNewKey(k.key); setKeyName(''); inv('api-keys')(); } });
  const revoke = useMutation({ mutationFn: revokeApiKey, onSuccess: inv('api-keys') });
  const addHook = useMutation({ mutationFn: () => createWebhook(url.trim(), events), onSuccess: (h) => { setNewSecret(h.secret ?? null); setUrl(''); inv('webhooks')(); } });
  const delHook = useMutation({ mutationFn: deleteWebhook, onSuccess: inv('webhooks') });
  const clear = useMutation({
    mutationFn: clearPortfolio,
    onSuccess: (r) => { setUploaded(null); setPortfolioNote(`${count(r.removed)} loans removed. The demo catalog is in use again.`); portfolioChanged(); },
  });
  const upload = useMutation({
    mutationFn: async (file: File) => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(await file.text());
      } catch {
        throw new Error('That file is not valid JSON.');
      }
      const loans = Array.isArray(parsed) ? parsed : (parsed as { loans?: unknown })?.loans;
      if (!Array.isArray(loans)) throw new Error('Expected a JSON array of loans, or an object with a "loans" array.');
      return uploadPortfolio(loans);
    },
    onSuccess: (r) => { setPortfolioNote(null); setUploaded(r); portfolioChanged(); },
  });

  const toggleEvent = (e: WebhookEvent) => setEvents(events.includes(e) ? events.filter((x) => x !== e) : [...events, e]);
  const err = usage.error ?? keys.error ?? hooks.error ?? portfolio.error ?? addKey.error ?? revoke.error ?? addHook.error ?? delHook.error ?? upload.error ?? clear.error;
  const metered = Object.entries(usage.data?.usage ?? {});

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Integrations and plan</h2>
          <p className="page-subtitle">Your plan and this month's usage, the loan portfolio the models run on, and the ways other systems connect.</p>
        </div>
      </div>
      <ErrorBanner error={err} />

      {usage.data && (
        <>
          <div className="kpi-row" style={{ marginTop: '1.25rem' }}>
            <div className="kpi">
              <div className="label">Plan</div>
              <div className="value" style={{ textTransform: 'capitalize' }}>{usage.data.plan.toLowerCase()}</div>
              <div className="hint">billing period {usage.data.period}</div>
            </div>
            <div className="kpi">
              <div className="label">Seats</div>
              <div className="value">{usage.data.seatsUsed} <span className="sub">of {usage.data.seatLimit}</span></div>
              <div className="progress" aria-hidden="true" style={{ marginTop: '0.5rem' }}>
                <span style={{ width: `${Math.min(100, (usage.data.seatsUsed / Math.max(1, usage.data.seatLimit)) * 100)}%` }} />
              </div>
            </div>
            <div className="kpi">
              <div className="label">Request budget</div>
              <div className="value">{usage.data.rateLimit.perSecond}<span className="sub"> per second</span></div>
              <div className="hint">bursts up to {usage.data.rateLimit.capacity}; heavy calls cost more</div>
            </div>
          </div>
          <div className="card">
            <div className="card-head"><h3>Usage this month</h3></div>
            {metered.length === 0 ? <p className="empty">Nothing metered yet this month.</p> : (
              <ul className="list">
                {metered.map(([metric, quantity]) => (
                  <li key={metric} className="list-row">
                    <span className="grow">{METRIC[metric] ?? metric}</span>
                    <span className="num">{count(quantity)}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </>
      )}

      <div className="card">
        <div className="card-head">
          <h3>Loan portfolio</h3>
          {portfolio.data && <span className="sub">{portfolio.data.usingOwnPortfolio ? `${count(portfolio.data.loanCount)} of your own loans` : 'demo catalog'}</span>}
        </div>
        <p className="page-subtitle">
          Upload your loans as a JSON array (or an object with a <code>loans</code> array) and every page works from them
          instead of the demo catalog. Each loan is validated on the way in; a loan that would be refused by the models
          is rejected here, with the reason, instead of failing later. Loading the same loan ID again replaces it.
        </p>
        <div className="form-row">
          <input type="file" accept="application/json,.json" aria-label="Portfolio file"
            onChange={(e) => { const file = e.target.files?.[0]; e.target.value = ''; if (file) upload.mutate(file); }} disabled={upload.isPending} />
          {upload.isPending && <span className="sub">Validating and loading...</span>}
          {portfolio.data?.usingOwnPortfolio && (
            <button className="danger-outline" disabled={clear.isPending}
              onClick={() => window.confirm('Remove every loan you uploaded and go back to the demo catalog?') && clear.mutate()}>
              Remove my portfolio
            </button>
          )}
        </div>
        {portfolioNote && <p role="status" className="sub">{portfolioNote}</p>}
        {uploaded && <UploadOutcome result={uploaded} />}
      </div>

      <div className="card">
        <div className="card-head"><h3>API keys</h3></div>
        <p className="page-subtitle">
          For a core system that pushes loans to <code>POST /v1/ingest/loans</code> with the header <code>X-API-Key</code>. A key can
          load loans and do nothing else. Only its hash is stored, so it is shown once.
        </p>
        <div className="form-row">
          <input aria-label="Key name" placeholder="Name, e.g. nightly loader" value={keyName} maxLength={80} onChange={(e) => setKeyName(e.target.value)} />
          <button onClick={() => addKey.mutate()} disabled={!keyName.trim() || addKey.isPending}>Create key</button>
        </div>
        {newKey && <p role="status" className="warn-banner">Copy this key now; it will not be shown again: <code>{newKey}</code></p>}
        {keys.data && keys.data.length > 0 && (
          <div className="table-scroll">
            <table>
              <thead><tr><th>Name</th><th>Key</th><th>Created</th><th>Last used</th><th>Status</th><th /></tr></thead>
              <tbody>
                {keys.data.map((k) => (
                  <tr key={k.id}>
                    <td>{k.name}</td>
                    <td className="mono">{k.prefix}…</td>
                    <td>{dateOnly(k.createdAt)} <span className="sub">by {k.createdBy}</span></td>
                    <td>{k.lastUsedAt ? dateTime(k.lastUsedAt) : <span className="sub">never</span>}</td>
                    <td><span className={`badge ${k.revokedAt ? '' : 'badge-low'}`}>{k.revokedAt ? 'Revoked' : 'Active'}</span></td>
                    <td className="num">{!k.revokedAt && <button className="danger-outline" onClick={() => revoke.mutate(k.id)} disabled={revoke.isPending}>Revoke</button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {keys.data?.length === 0 && <p className="empty">No keys yet.</p>}
      </div>

      <div className="card">
        <div className="card-head"><h3>Webhooks</h3></div>
        <p className="page-subtitle">
          Events are posted to your URL with an HMAC-SHA256 signature of the body, and retried with backoff if your endpoint
          is down. The URL must be public https; addresses inside a private network are refused.
        </p>
        <div className="form-row">
          <input aria-label="Webhook URL" placeholder="https://example.com/hooks/arthadhruva" value={url} onChange={(e) => setUrl(e.target.value)} style={{ minWidth: 280 }} />
          {EVENTS.map(([e, label]) => (
            <label key={e} className={`chip${events.includes(e) ? ' on' : ''}`}><input type="checkbox" checked={events.includes(e)} onChange={() => toggleEvent(e)} /> {label}</label>
          ))}
          <button onClick={() => addHook.mutate()} disabled={!url.trim() || events.length === 0 || addHook.isPending}>Add</button>
        </div>
        {newSecret && <p role="status" className="warn-banner">Signing secret, shown once: <code>{newSecret}</code></p>}
        {hooks.data && hooks.data.length > 0 && (
          <ul className="list">
            {hooks.data.map((h) => (
              <li key={h.id} className="list-row">
                <span className="grow">
                  <span className="mono">{h.url}</span>
                  <div className="sub">{h.events.map((e) => EVENTS.find(([key]) => key === e)?.[1] ?? e).join(', ')}{h.enabled ? '' : ' · disabled'}</div>
                </span>
                <button className="danger-outline" onClick={() => delHook.mutate(h.id)} disabled={delHook.isPending}>Remove</button>
              </li>
            ))}
          </ul>
        )}

        <div className="section-label" style={{ marginTop: '1.5rem' }}>Recent deliveries</div>
        {deliveries.data && deliveries.data.items.length === 0 && <p className="empty">Nothing has been delivered yet.</p>}
        {deliveries.data && deliveries.data.items.length > 0 && (
          <div className="table-scroll">
            <table>
              <thead><tr><th>When</th><th>Event</th><th>Status</th><th className="num">Attempts</th><th>Last error</th></tr></thead>
              <tbody>
                {deliveries.data.items.map((d) => (
                  <tr key={d.id}>
                    <td style={{ whiteSpace: 'nowrap' }}>{dateTime(d.createdAt)}</td>
                    <td>{d.eventType}</td>
                    <td><span className={`badge ${DELIVERY[d.status]?.badge ?? ''}`}>{DELIVERY[d.status]?.label ?? d.status}</span></td>
                    <td className="num">{d.attempts}</td>
                    <td style={{ whiteSpace: 'normal' }}>{d.lastError ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <SingleSignOn />
    </div>
  );
}
