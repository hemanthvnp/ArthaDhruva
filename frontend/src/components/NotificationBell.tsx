import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listNotifications, markNotificationRead, unreadNotificationCount } from '../api/client';
import type { NotificationView } from '../api/types';
import Icon from './Icon';

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
    <div style={{ position: 'relative' }}>
      <button className="icon-btn" onClick={toggleOpen} aria-label={`Notifications${count > 0 ? `, ${count} unread` : ''}`} aria-expanded={open}>
        <Icon name="bell" />
        {count > 0 && <span className="dot-badge">{count > 99 ? '99+' : count}</span>}
      </button>
      {open && (
        <div className="popover" role="dialog" aria-label="Notifications">
          <div className="popover-head">Notifications</div>
          {loading && <div className="popover-empty">Loading...</div>}
          {!loading && notifications.length === 0 && <div className="popover-empty">You're all caught up.</div>}
          {!loading &&
            notifications.map((n) => (
              <div key={n.id} className={`popover-item${n.read ? '' : ' unread'}`}>
                {n.link ? (
                  <Link to={n.link} onClick={() => handleClick(n)}>
                    {n.message}
                  </Link>
                ) : (
                  <span onClick={() => handleClick(n)} style={{ cursor: 'pointer' }}>
                    {n.message}
                  </span>
                )}
                <div className="when">{new Date(n.createdAt).toLocaleString()}</div>
              </div>
            ))}
        </div>
      )}
    </div>
  );
}
