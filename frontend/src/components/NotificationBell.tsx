import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listNotifications, markNotificationRead, unreadNotificationCount } from '../api/client';
import type { NotificationView } from '../api/types';
import Icon from './Icon';
import { dateTime } from '../format';

const POLL_MS = 30_000;
const MAX_POLL_MS = 5 * 60_000;

/** ANALYST/ADMIN only, same as the endpoints it calls. Polls the unread count rather than holding a
 * WebSocket open: 30 seconds of staleness is fine for "you were assigned a case".
 *
 * The polling adapts instead of ticking blindly: a hidden tab does not poll at all and catches up the
 * moment it is shown again; failures double the interval (up to five minutes) so a struggling server is
 * not hammered by every open tab; and each interval is jittered so tabs opened together drift apart. */
export default function NotificationBell() {
  const [count, setCount] = useState(0);
  const [open, setOpen] = useState(false);
  const [notifications, setNotifications] = useState<NotificationView[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    let delay = POLL_MS;
    let stopped = false;

    const poll = async () => {
      if (document.visibilityState === 'visible') {
        try {
          setCount(await unreadNotificationCount());
          delay = POLL_MS;
        } catch {
          delay = Math.min(MAX_POLL_MS, delay * 2);
        }
      }
      if (!stopped) timer = setTimeout(poll, delay * (0.85 + Math.random() * 0.3));
    };
    const onVisibility = () => {
      if (document.visibilityState !== 'visible') return;
      clearTimeout(timer);
      delay = POLL_MS;
      void poll();
    };

    void poll();
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      stopped = true;
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
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
                  <button type="button" className="plain-text" onClick={() => handleClick(n)}>
                    {n.message}
                  </button>
                )}
                <div className="when">{dateTime(n.createdAt)}</div>
              </div>
            ))}
        </div>
      )}
    </div>
  );
}
