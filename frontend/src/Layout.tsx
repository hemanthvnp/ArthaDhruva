import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import NotificationBell from './components/NotificationBell';

const ANALYST_LINKS = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/loans', label: 'Loan Portfolio' },
  { to: '/cases', label: 'Cases' },
  { to: '/case-search', label: 'Case Search' },
  { to: '/insights', label: 'ML Insights' },
  { to: '/assistant', label: 'AI Assistant' },
  { to: '/score', label: 'Default Risk Score (manual)' },
  { to: '/expected-loss', label: 'Expected Loss' },
  { to: '/regime-forecast', label: 'Regime Forecast' },
  { to: '/cvar', label: 'CVaR Simulation' },
  { to: '/trajectory', label: 'Trajectory Score' },
  { to: '/early-warning', label: 'Early-Warning Delinquency' },
  { to: '/segments', label: 'Segment Graph' },
];

const ADMIN_LINKS = [
  { to: '/admin/audit-log', label: 'Audit Log' },
  { to: '/admin/login-attempts', label: 'Login Attempts' },
  { to: '/admin/create-user', label: 'Create User' },
  { to: '/admin/manage-users', label: 'Manage Users' },
  { to: '/admin/automation-rules', label: 'Automation Rules' },
  { to: '/admin/integrations', label: 'Integrations & Plan' },
];

const CLIENT_LINKS = [{ to: '/my-loan', label: 'My Loan' }];

const SELF_SERVICE_LINKS = [
  { to: '/account/password', label: 'Change Password' },
  { to: '/account/2fa', label: 'Two-Factor Auth' },
  { to: '/account/notifications', label: 'Notifications' },
];

export default function Layout() {
  const { auth, logout } = useAuth();
  const navigate = useNavigate();

  const doLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  const roleLinks =
    auth?.role === 'CLIENT'
      ? CLIENT_LINKS
      : auth?.role === 'ADMIN'
        ? [...ANALYST_LINKS, ...ADMIN_LINKS]
        : ANALYST_LINKS;
  const links = [...roleLinks, ...SELF_SERVICE_LINKS];

  return (
    <div className="app-shell">
      {auth?.sandbox && (
        <div role="status" style={{ position: 'fixed', top: 0, left: 0, right: 0, zIndex: 1000, background: '#b45309', color: '#fff', textAlign: 'center', padding: '0.3rem', fontWeight: 600 }}>
          SANDBOX ORGANIZATION - demo data, not production
        </div>
      )}
      <aside className="sidebar">
        <h1>ArthaDhruva Risk Console</h1>
        {(auth?.role === 'ANALYST' || auth?.role === 'ADMIN') && <NotificationBell />}
        <nav>
          {links.map((link) => (
            <NavLink key={link.to} to={link.to} className={({ isActive }) => (isActive ? 'active' : '')}>
              {link.label}
            </NavLink>
          ))}
        </nav>
        {auth && (
          <div style={{ marginTop: '1.5rem', paddingTop: '1rem', borderTop: '1px solid #2a3650' }}>
            <div style={{ fontSize: '0.8rem', color: '#b7c2d4' }}>
              {auth.username} <span style={{ opacity: 0.6 }}>({auth.role})</span>
            </div>
            <button
              onClick={doLogout}
              style={{ marginTop: '0.5rem', width: '100%', background: 'transparent', border: '1px solid #3a4a68', color: '#e7ecf3' }}
            >
              Log out
            </button>
          </div>
        )}
      </aside>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
