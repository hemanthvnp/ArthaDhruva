import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';

const LINKS = [
  { to: '/score', label: 'Default Risk Score' },
  { to: '/expected-loss', label: 'Expected Loss' },
  { to: '/regime-forecast', label: 'Regime Forecast' },
  { to: '/cvar', label: 'CVaR Simulation' },
  { to: '/trajectory', label: 'Trajectory Score' },
  { to: '/segments', label: 'Segment Graph' },
];

export default function Layout() {
  const { auth, logout } = useAuth();
  const navigate = useNavigate();

  const doLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <h1>ArthaDhruva Risk Console</h1>
        <nav>
          {LINKS.map((link) => (
            <NavLink key={link.to} to={link.to} className={({ isActive }) => (isActive ? 'active' : '')}>
              {link.label}
            </NavLink>
          ))}
          {auth?.role === 'ADMIN' && (
            <NavLink to="/admin/audit-log" className={({ isActive }) => (isActive ? 'active' : '')}>
              Audit Log
            </NavLink>
          )}
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
