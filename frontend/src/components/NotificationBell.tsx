import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listNotifications, markNotificationRead, unreadNotificationCount } from '../api/client';
import type { NotificationView } from '../api/types';

const POLL_INTERVAL_MS = 30_000;

/** ANALYST/ADMIN only, same as the endpoints it calls -- see NotificationController's class doc
 * for why a CLIENT never has anything here yet. Polls the unread count rather than a WebSocket --
 * simple, and a 30s staleness window is fine for "you were assigned a case" style alerts. */
export default function NotificationBell() {
  const [count, setCount] = useState(0);
  const [open, setOpen] = useState(false);
  const [notifications, setNotifications] = useState<NotificationView[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const poll = () => {
      unreadNotificationCount().then(setCount).catch(() => {});
    };
    poll();
    const interval = setInterval(poll, POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, []);

  const toggleOpen = () => {
    const next = !open;
    setOpen(next);
    if (next) {
      setLoading(true);
      listNotifications(20)
        .then(setNotifications)
        .catch(() => {})
        .finally(() => setLoading(false));
    }
  };

  const handleClick = async (n: NotificationView) => {
    if (!n.read) {
      await markNotificationRead(n.id).catch(() => {});
      setNotifications((prev) => prev.map((x) => (x.id === n.id ? { ...x, read: true } : x)));
      setCount((c) => Math.max(0, c - 1));
    }
    setOpen(false);
  };

  return (
    <div style={{ position: 'relative', marginBottom: '0.75rem' }}>
      <button
        className="secondary"
        onClick={toggleOpen}
        style={{ width: '100%', textAlign: 'left', position: 'relative' }}
      >
        Notifications
        {count > 0 && (
          <span
            style={{
              marginLeft: '0.5rem',
              background: '#c0392b',
              color: '#fff',
              borderRadius: '999px',
              padding: '0.05rem 0.5rem',
              fontSize: '0.75rem',
            }}
          >
            {count}
          </span>
        )}
      </button>
      {open && (
        <div
          className="card"
          style={{
            position: 'absolute',
            top: '100%',
            left: 0,
            right: 0,
            zIndex: 10,
            maxHeight: 320,
            overflowY: 'auto',
            marginTop: '0.25rem',
          }}
        >
          {loading && <p className="page-subtitle">Loading...</p>}
          {!loading && notifications.length === 0 && <p className="page-subtitle">No notifications yet.</p>}
          {!loading &&
            notifications.map((n) => (
              <div
                key={n.id}
                style={{
                  padding: '0.4rem 0',
                  borderBottom: '1px solid var(--border, #2a3650)',
                  opacity: n.read ? 0.6 : 1,
                }}
              >
                {n.link ? (
                  <Link to={n.link} onClick={() => handleClick(n)} style={{ display: 'block' }}>
                    {n.message}
                  </Link>
                ) : (
                  <span onClick={() => handleClick(n)} style={{ cursor: 'pointer', display: 'block' }}>
                    {n.message}
                  </span>
                )}
                <span className="page-subtitle" style={{ fontSize: '0.75rem' }}>
                  {new Date(n.createdAt).toLocaleString()}
                </span>
              </div>
            ))}
        </div>
      )}
    </div>
  );
}
