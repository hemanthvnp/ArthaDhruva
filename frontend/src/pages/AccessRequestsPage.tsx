import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { approveAccessRequest, declineAccessRequest, listAccessRequests } from '../api/client';
import type { AccessRequestStatus, AccessRequestView, ApprovedAccessRequest, PlanCode } from '../api/types';
import ErrorBanner from '../components/ErrorBanner';
import { slugFrom, slugProblem, usernameFrom } from '../components/accessRequests';
import { dateTime } from '../format';

const FILTERS: { value: AccessRequestStatus | undefined; label: string }[] = [
  { value: 'PENDING', label: 'Pending' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'DECLINED', label: 'Declined' },
  { value: undefined, label: 'All' },
];
const BADGE: Record<AccessRequestStatus, string> = { PENDING: 'badge-medium', APPROVED: 'badge-low', DECLINED: '' };

/** Approving provisions the organization and invites its first admin; the invite is shown once. */
function ApproveForm({ request, onDone }: { request: AccessRequestView; onDone: (result: ApprovedAccessRequest) => void }) {
  const queryClient = useQueryClient();
  const [slug, setSlug] = useState(() => slugFrom(request.companyName));
  const [name, setName] = useState(request.companyName);
  const [username, setUsername] = useState(() => usernameFrom(request.workEmail));
  const [plan, setPlan] = useState<PlanCode>('TRIAL');
  const approve = useMutation({
    mutationFn: () => approveAccessRequest(request.id, { slug, organizationName: name.trim(), adminUsername: username.trim(), plan }),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ['access-requests'] });
      onDone(result);
    },
  });
  const problem = slugProblem(slug);

  return (
    <div className="approve-form">
      <div className="field-grid">
        <div className="field">
          <label htmlFor={`slug-${request.id}`}>Organization slug</label>
          <input id={`slug-${request.id}`} value={slug} onChange={(e) => setSlug(e.target.value.trim().toLowerCase())} aria-invalid={problem !== null} />
          {problem && <span className="hint">{problem}</span>}
        </div>
        <div className="field">
          <label htmlFor={`name-${request.id}`}>Organization name</label>
          <input id={`name-${request.id}`} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor={`admin-${request.id}`}>First admin username</label>
          <input id={`admin-${request.id}`} value={username} maxLength={50} onChange={(e) => setUsername(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor={`plan-${request.id}`}>Plan</label>
          <select id={`plan-${request.id}`} value={plan} onChange={(e) => setPlan(e.target.value as PlanCode)}>
            <option value="TRIAL">Trial (30-day pilot)</option>
            <option value="STARTER">Starter</option>
            <option value="PRO">Pro</option>
          </select>
        </div>
      </div>
      <div className="actions">
        <button onClick={() => approve.mutate()} disabled={approve.isPending || problem !== null || !username.trim()}>
          {approve.isPending ? 'Provisioning...' : 'Provision and invite'}
        </button>
      </div>
      <ErrorBanner error={approve.error} />
    </div>
  );
}

function RequestRow({ request }: { request: AccessRequestView }) {
  const queryClient = useQueryClient();
  const [approving, setApproving] = useState(false);
  const [confirmDecline, setConfirmDecline] = useState(false);
  const [approved, setApproved] = useState<ApprovedAccessRequest | null>(null);
  const decline = useMutation({
    mutationFn: () => declineAccessRequest(request.id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['access-requests'] }),
  });

  return (
    <div className="card request-card">
      <div className="card-head">
        <h3>{request.companyName}</h3>
        <span className={`badge ${BADGE[request.status]}`}>{request.status.toLowerCase()}</span>
      </div>
      <dl className="facts">
        <div><dt>Contact</dt><dd>{request.contactName}{request.jobTitle ? `, ${request.jobTitle}` : ''}</dd></div>
        <div><dt>Work email</dt><dd><a href={`mailto:${request.workEmail}`}>{request.workEmail}</a></dd></div>
        <div><dt>Received</dt><dd>{dateTime(request.createdAt)}</dd></div>
        {request.reviewedAt && <div><dt>Reviewed</dt><dd>{dateTime(request.reviewedAt)} by {request.reviewedBy}</dd></div>}
      </dl>
      {request.message && <p className="request-message">{request.message}</p>}

      {approved && (
        <div className="notice" role="status">
          <p>
            Organization <b>{approved.organization.slug}</b> created; <b>{approved.adminUsername}</b> is invited.{' '}
            {approved.emailed ? 'The activation link was emailed.' : 'Email is not configured: pass this link on yourself. It is shown only now.'}
          </p>
          {!approved.emailed && <pre className="code-block">{approved.activationLink}</pre>}
        </div>
      )}

      {request.status === 'PENDING' && !approved && (
        <>
          <div className="actions">
            <button onClick={() => setApproving((a) => !a)} aria-expanded={approving}>{approving ? 'Cancel' : 'Approve'}</button>
            {confirmDecline ? (
              <>
                <button className="danger-outline" onClick={() => decline.mutate()} disabled={decline.isPending}>Confirm decline</button>
                <button className="secondary" onClick={() => setConfirmDecline(false)}>Keep</button>
              </>
            ) : (
              <button className="secondary" onClick={() => setConfirmDecline(true)}>Decline</button>
            )}
          </div>
          {approving && <ApproveForm request={request} onDone={(r) => { setApproving(false); setApproved(r); }} />}
          <ErrorBanner error={decline.error} />
        </>
      )}
    </div>
  );
}

/** PLATFORM_ADMIN: requests from prospective customers, reviewed once each. Approving provisions the
 * organization on the chosen plan and invites its first admin, who sets a password and then enrols 2FA. */
export default function AccessRequestsPage() {
  const [status, setStatus] = useState<AccessRequestStatus | undefined>('PENDING');
  const requests = useQuery({ queryKey: ['access-requests', status ?? 'ALL'], queryFn: () => listAccessRequests(status) });
  const list = requests.data ?? [];

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Access requests</h2>
          <p className="page-subtitle">Organizations asking for a pilot. Approve once the security review and terms are agreed.</p>
        </div>
        <div className="segmented" role="group" aria-label="Show">
          {FILTERS.map((f) => (
            <button key={f.label} className={status === f.value ? 'on' : ''} aria-pressed={status === f.value} onClick={() => setStatus(f.value)}>
              {f.label}
            </button>
          ))}
        </div>
      </div>
      <ErrorBanner error={requests.error} />
      {requests.isLoading && <p className="page-subtitle">Loading...</p>}
      {!requests.isLoading && !requests.error && list.length === 0 && (
        <div className="card"><p className="page-subtitle">No {status ? status.toLowerCase() : ''} requests.</p></div>
      )}
      {list.map((r) => <RequestRow key={r.id} request={r} />)}
    </div>
  );
}
