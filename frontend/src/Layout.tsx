import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import Icon from './components/Icon';
import NotificationBell from './components/NotificationBell';

type NavItem = { to: string; label: string; icon: string };
type NavGroup = { label: string; items: NavItem[] };

const ANALYST_GROUPS: NavGroup[] = [
  { label: 'Overview', items: [{ to: '/dashboard', label: 'Dashboard', icon: 'dashboard' }] },
  {
    label: 'Portfolio',
    items: [
      { to: '/loans', label: 'Loan Portfolio', icon: 'loans' },
      { to: '/cases', label: 'Cases', icon: 'cases' },
      { to: '/case-search', label: 'Case Search', icon: 'search' },
    ],
  },
  {
    label: 'Risk models',
    items: [
      { to: '/score', label: 'Default Risk Score', icon: 'score' },
      { to: '/expected-loss', label: 'Expected Loss', icon: 'loss' },
      { to: '/early-warning', label: 'Early Warning', icon: 'warning' },
      { to: '/trajectory', label: 'Trajectory', icon: 'trajectory' },
      { to: '/regime-forecast', label: 'Regime Forecast', icon: 'forecast' },
      { to: '/cvar', label: 'CVaR Simulation', icon: 'risk' },
      { to: '/segments', label: 'Segment Graph', icon: 'graph' },
    ],
  },
  {
    label: 'Intelligence',
    items: [
      { to: '/insights', label: 'ML Insights', icon: 'insights' },
      { to: '/assistant', label: 'AI Assistant', icon: 'assistant' },
    ],
  },
];

const ADMIN_GROUP: NavGroup = {
  label: 'Administration',
  items: [
    { to: '/admin/manage-users', label: 'Manage Users', icon: 'users' },
    { to: '/admin/create-user', label: 'Create User', icon: 'userplus' },
    { to: '/admin/automation-rules', label: 'Automation Rules', icon: 'rules' },
    { to: '/admin/integrations', label: 'Integrations & Plan', icon: 'plug' },
    { to: '/admin/audit-log', label: 'Audit Log', icon: 'audit' },
    { to: '/admin/login-attempts', label: 'Login Attempts', icon: 'lock' },
  ],
};

const CLIENT_GROUPS: NavGroup[] = [{ label: 'My account', items: [{ to: '/my-loan', label: 'My Loan', icon: 'home' }] }];

const ACCOUNT_LINKS = [
  { to: '/account/notifications', label: 'Notification preferences' },
  { to: '/account/password', label: 'Change password' },
  { to: '/account/2fa', label: 'Two-factor authentication' },
];

type Theme = 'system' | 'light' | 'dark';

function readTheme(): Theme {
  try {
    const t = localStorage.getItem('theme');
    return t === 'light' || t === 'dark' ? t : 'system';
  } catch {
    return 'system';
  }
}

function applyTheme(theme: Theme) {
  if (theme === 'system') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', theme);
  try {
    if (theme === 'system') localStorage.removeItem('theme');
    else localStorage.setItem('theme', theme);
  } catch {
    /* storage can be unavailable (private mode); the theme just won't persist */
  }
}

export default function Layout() {
  const { auth, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [navOpen, setNavOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [theme, setTheme] = useState<Theme>(readTheme);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => applyTheme(theme), [theme]);
  useEffect(() => {
    setNavOpen(false);
    setMenuOpen(false);
  }, [location.pathname]);
  useEffect(() => {
    if (!menuOpen) return;
    const close = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [menuOpen]);

  const doLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  const groups =
    auth?.role === 'CLIENT' ? CLIENT_GROUPS : auth?.role === 'ADMIN' ? [...ANALYST_GROUPS, ADMIN_GROUP] : ANALYST_GROUPS;
  const crumb = (() => {
    const path = location.pathname;
    let best: { group: string; item: NavItem } | null = null;
    for (const g of groups) {
      for (const item of g.items) {
        if ((path === item.to || path.startsWith(item.to + '/')) && (!best || item.to.length > best.item.to.length)) {
          best = { group: g.label, item };
        }
      }
    }
    if (best) {
      const extra = path.slice(best.item.to.length).split('/').filter(Boolean).map(decodeURIComponent);
      return [best.group, best.item.label, ...extra];
    }
    const acct = ACCOUNT_LINKS.find((a) => a.to === path);
    return acct ? ['Account', acct.label] : [];
  })();
  const isStaff = auth?.role === 'ANALYST' || auth?.role === 'ADMIN';
  // Cycle system -> light -> dark, showing the icon of the mode you'd switch to.
  const nextTheme: Theme = theme === 'system' ? 'light' : theme === 'light' ? 'dark' : 'system';

  return (
    <>
      {auth?.sandbox && (
        <div className="sandbox-banner" role="status">
          Sandbox organization &middot; demo data, not production
        </div>
      )}
      <div className={`app-shell${auth?.sandbox ? ' has-banner' : ''}`}>
        {navOpen && <div className="scrim" onClick={() => setNavOpen(false)} />}
        <aside className={`sidebar${navOpen ? ' open' : ''}`} aria-label="Primary">
          <Link to={auth?.role === 'CLIENT' ? '/my-loan' : '/dashboard'} className="brand">
            <span className="brand-mark">A</span>
            ArthaDhruva
          </Link>
          {groups.map((g) => (
            <div className="nav-group" key={g.label}>
              <div className="nav-label">{g.label}</div>
              {g.items.map((item) => (
                <NavLink key={item.to} to={item.to} className={({ isActive }) => (isActive ? 'active' : '')}>
                  <Icon name={item.icon} />
                  {item.label}
                </NavLink>
              ))}
            </div>
          ))}
        </aside>

        <div className="main-col">
          <header className="topbar">
            <button className="icon-btn menu-btn" aria-label="Open navigation" onClick={() => setNavOpen(true)}>
              <Icon name="menu" />
            </button>
            <nav className="crumbs" aria-label="Breadcrumb">
              {crumb.map((c, i) => (
                <span key={i} className={i === crumb.length - 1 ? 'crumb current' : 'crumb'}>{c}</span>
              ))}
            </nav>
            <span className="org-chip">
              {auth?.sandbox && <span className="badge badge-medium">Sandbox</span>}
            </span>
            <span className="spacer" />
            {isStaff && <NotificationBell />}
            <button
              className="icon-btn"
              onClick={() => setTheme(nextTheme)}
              aria-label={`Theme: ${theme}. Switch to ${nextTheme}`}
              title={`Theme: ${theme}`}
            >
              <Icon name={theme === 'dark' ? 'moon' : 'sun'} />
            </button>
            {auth && (
              <div style={{ position: 'relative' }} ref={menuRef}>
                <button className="user-btn" onClick={() => setMenuOpen((o) => !o)} aria-expanded={menuOpen} aria-haspopup="menu">
                  <span className="avatar">{auth.username.slice(0, 2)}</span>
                </button>
                {menuOpen && (
                  <div className="menu" role="menu">
                    <div className="menu-head">
                      <div className="name">{auth.username}</div>
                      <div className="role">{auth.role}</div>
                    </div>
                    <hr />
                    {ACCOUNT_LINKS.map((l) => (
                      <Link key={l.to} to={l.to} role="menuitem">
                        {l.label}
                      </Link>
                    ))}
                    <hr />
                    <button onClick={doLogout} role="menuitem">
                      Sign out
                    </button>
                  </div>
                )}
              </div>
            )}
          </header>
          <main className="content">
            <Outlet />
          </main>
        </div>
      </div>
    </>
  );
}
