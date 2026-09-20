import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  clearPortfolio, createApiKey, createWebhook, deleteWebhook, getPortfolioStatus, getUsage, listApiKeys,
  listWebhookDeliveries, listWebhooks, revokeApiKey, uploadPortfolio,
} from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import type { WebhookEvent } from '../api/types';

const EVENTS: WebhookEvent[] = ['LOAN_SCORED', 'CASE_FLAGGED', 'CASE_ASSIGNED'];

export default function IntegrationsPage() {
  const qc = useQueryClient();
  const usage = useQuery({ queryKey: ['usage'], queryFn: getUsage });
  const keys = useQuery({ queryKey: ['api-keys'], queryFn: listApiKeys });
  const hooks = useQuery({ queryKey: ['webhooks'], queryFn: listWebhooks });
  const deliveries = useQuery({ queryKey: ['webhook-deliveries'], queryFn: listWebhookDeliveries, refetchInterval: 10_000 });
  const portfolio = useQuery({ queryKey: ['portfolio'], queryFn: getPortfolioStatus });

  const [keyName, setKeyName] = useState('');
  const [newKey, setNewKey] = useState<string | null>(null);
  const [url, setUrl] = useState('');
  const [events, setEvents] = useState<WebhookEvent[]>(['CASE_FLAGGED']);
  const [newSecret, setNewSecret] = useState<string | null>(null);
  const [uploadMsg, setUploadMsg] = useState<string | null>(null);

  const inv = (k: string) => () => qc.invalidateQueries({ queryKey: [k] });
  const addKey = useMutation({ mutationFn: () => createApiKey(keyName), onSuccess: (k) => { setNewKey(k.key); setKeyName(''); inv('api-keys')(); } });
  const revoke = useMutation({ mutationFn: revokeApiKey, onSuccess: inv('api-keys') });
  const addHook = useMutation({ mutationFn: () => createWebhook(url, events), onSuccess: (h) => { setNewSecret(h.secret ?? null); setUrl(''); inv('webhooks')(); } });
  const delHook = useMutation({ mutationFn: deleteWebhook, onSuccess: inv('webhooks') });
  const clear = useMutation({ mutationFn: clearPortfolio, onSuccess: () => { setUploadMsg('Reverted to the shared demo catalog.'); inv('portfolio')(); } });
  const upload = useMutation({
    mutationFn: async (file: File) => {
      const parsed = JSON.parse(await file.text());
      return uploadPortfolio(Array.isArray(parsed) ? parsed : parsed.loans);
    },
    onSuccess: (r) => { setUploadMsg(`${r.accepted} loans accepted, ${r.rejected} rejected.`); inv('portfolio')(); },
  });

  const toggleEvent = (e: WebhookEvent) => setEvents(events.includes(e) ? events.filter((x) => x !== e) : [...events, e]);
  const err = usage.error ?? keys.error ?? hooks.error ?? addKey.error ?? revoke.error ?? addHook.error ?? delHook.error ?? upload.error ?? clear.error;

  return (
    <div>
      <h2>Integrations &amp; Plan</h2>
      <ErrorBanner error={err} />

      {usage.data && (
        <div className="card">
          <h3>Plan: {usage.data.plan}</h3>
          <p>Seats {usage.data.seatsUsed} / {usage.data.seatLimit} &middot; rate limit {usage.data.rateLimit.perSecond}/s (burst {usage.data.rateLimit.capacity})</p>
          <p>Usage {usage.data.period}: {Object.entries(usage.data.usage).map(([k, v]) => `${k} ${v}`).join(', ') || 'none yet'}</p>
        </div>
      )}

      <div className="card">
        <h3>Loan portfolio</h3>
        <p>{portfolio.data?.usingOwnPortfolio ? `Using your own portfolio (${portfolio.data.loanCount} loans).` : 'Using the shared demo catalog.'}</p>
        <input type="file" accept="application/json" aria-label="portfolio file" onChange={(e) => e.target.files?.[0] && upload.mutate(e.target.files[0])} />
        {portfolio.data?.usingOwnPortfolio && <button onClick={() => clear.mutate()}>Revert to demo catalog</button>}
        {uploadMsg && <p role="status">{uploadMsg}</p>}
      </div>

      <div className="card">
        <h3>API keys</h3>
        <p className="page-subtitle">For programmatic batch loading at <code>POST /v1/ingest/loans</code> (header <code>X-API-Key</code>). A key can do nothing else.</p>
        <div style={{ display: 'flex', gap: '0.6rem' }}>
          <input placeholder="Key name" value={keyName} onChange={(e) => setKeyName(e.target.value)} />
          <button onClick={() => addKey.mutate()} disabled={!keyName}>Create key</button>
        </div>
        {newKey && <p role="status">Copy this now, it is shown once: <code>{newKey}</code></p>}
        <table>
          <thead><tr><th>Name</th><th>Prefix</th><th>Last used</th><th>Status</th><th /></tr></thead>
          <tbody>
            {keys.data?.map((k) => (
              <tr key={k.id}>
                <td>{k.name}</td><td>{k.prefix}</td><td>{k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleString() : 'never'}</td>
                <td>{k.revokedAt ? 'revoked' : 'active'}</td>
                <td>{!k.revokedAt && <button onClick={() => revoke.mutate(k.id)}>Revoke</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="card">
        <h3>Webhooks</h3>
        <div style={{ display: 'flex', gap: '0.6rem', flexWrap: 'wrap', alignItems: 'center' }}>
          <input placeholder="https://example.com/hook" value={url} onChange={(e) => setUrl(e.target.value)} style={{ minWidth: 280 }} />
          {EVENTS.map((e) => (
            <label key={e}><input type="checkbox" checked={events.includes(e)} onChange={() => toggleEvent(e)} /> {e}</label>
          ))}
          <button onClick={() => addHook.mutate()} disabled={!url || events.length === 0}>Add</button>
        </div>
        {newSecret && <p role="status">Signing secret (shown once): <code>{newSecret}</code></p>}
        <ul>
          {hooks.data?.map((h) => (
            <li key={h.id}>{h.url} &middot; {h.events.join(', ')} <button onClick={() => delHook.mutate(h.id)}>Remove</button></li>
          ))}
        </ul>
        <h4>Recent deliveries</h4>
        <table>
          <thead><tr><th>Event</th><th>Status</th><th>Attempts</th><th>Error</th></tr></thead>
          <tbody>
            {deliveries.data?.items.map((d) => (
              <tr key={d.id}><td>{d.eventType}</td><td>{d.status}</td><td>{d.attempts}</td><td>{d.lastError ?? ''}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
