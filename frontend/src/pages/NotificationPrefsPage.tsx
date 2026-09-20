import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getNotificationPreferences, setNotificationPreference } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import type { DeliveryMode, NotificationType } from '../api/types';

const LABELS: Record<NotificationType, string> = {
  CASE_ASSIGNED: 'A case is assigned to me',
  NOTE_ADDED: 'Someone adds a note to my case',
  AUTOMATION: 'An automation rule notifies me',
};
const MODES: { value: DeliveryMode; label: string }[] = [
  { value: 'INSTANT', label: 'Instantly' },
  { value: 'DIGEST', label: 'In a daily digest' },
  { value: 'OFF', label: 'Never' },
];

export default function NotificationPrefsPage() {
  const queryClient = useQueryClient();
  const prefs = useQuery({ queryKey: ['notification-prefs'], queryFn: getNotificationPreferences });
  const update = useMutation({
    mutationFn: ({ type, mode }: { type: NotificationType; mode: DeliveryMode }) => setNotificationPreference(type, mode),
    onSuccess: (data) => queryClient.setQueryData(['notification-prefs'], data),
  });

  return (
    <div>
      <h2>Notification Preferences</h2>
      <p className="page-subtitle">Choose how you hear about each kind of event. Only your own settings change.</p>
      <ErrorBanner error={prefs.error ?? update.error} />
      <div className="card">
        {prefs.data && (Object.keys(LABELS) as NotificationType[]).map((type) => (
          <div className="field" key={type} style={{ marginBottom: '0.8rem' }}>
            <label htmlFor={type}>{LABELS[type]}</label>
            <select id={type} value={prefs.data[type]} onChange={(e) => update.mutate({ type, mode: e.target.value as DeliveryMode })}>
              {MODES.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
            </select>
          </div>
        ))}
      </div>
    </div>
  );
}
