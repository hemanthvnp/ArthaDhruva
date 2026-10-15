import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listNotifications, markNotificationRead } from '../api/client';
import type { NotificationView } from '../api/types';
import ErrorBanner from '../components/ErrorBanner';
import { dateTime } from '../format';

/** The signed-in user's own notifications, newest first. */
export default function NotificationsPage() {
  const queryClient = useQueryClient();
  const notifications = useQuery({ queryKey: ['notifications'], queryFn: () => listNotifications(50) });
  const markRead = useMutation({
    mutationFn: (n: NotificationView) => markNotificationRead(n.id),
    onSuccess: (_, n) =>
      queryClient.setQueryData<NotificationView[]>(['notifications'], (previous) =>
        (previous ?? []).map((x) => (x.id === n.id ? { ...x, read: true } : x))),
  });
  const list = notifications.data ?? [];

  return (
    <div>
      <h2>Notifications</h2>
      <p className="page-subtitle">Updates about your account, newest first.</p>
      <ErrorBanner error={notifications.error ?? markRead.error} />
      <div className="card">
        {notifications.isLoading && <p className="page-subtitle">Loading...</p>}
        {!notifications.isLoading && list.length === 0 && <p className="page-subtitle">You're all caught up.</p>}
        {list.map((n) => (
          <div className={`list-row${n.read ? '' : ' unread'}`} key={n.id}>
            <div className="grow">
              {n.link ? <Link to={n.link} onClick={() => !n.read && markRead.mutate(n)}>{n.message}</Link> : n.message}
              <div className="sub">{dateTime(n.createdAt)}</div>
            </div>
            {!n.read && (
              <button type="button" className="secondary small" onClick={() => markRead.mutate(n)}>
                Mark read
              </button>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
