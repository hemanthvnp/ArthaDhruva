import type { ReactElement } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import type { Role } from './api/types';
import Layout from './Layout';
import LoginPage from './pages/LoginPage';
import ScorePage from './pages/ScorePage';
import ExpectedLossPage from './pages/ExpectedLossPage';
import RegimeForecastPage from './pages/RegimeForecastPage';
import CvarPage from './pages/CvarPage';
import TrajectoryPage from './pages/TrajectoryPage';
import SegmentGraphPage from './pages/SegmentGraphPage';
import AuditLogPage from './pages/AuditLogPage';
import LoginAttemptsPage from './pages/LoginAttemptsPage';
import MyLoanPage from './pages/MyLoanPage';
import AdminCreateUserPage from './pages/AdminCreateUserPage';

function ProtectedRoute({ children }: { children: ReactElement }) {
  const { auth } = useAuth();
  if (!auth) return <Navigate to="/login" replace />;
  return children;
}

/** UX polish, not the real boundary -- the server enforces role restrictions regardless (a
 * CLIENT calling /score directly gets 403 either way). This just avoids showing a page whose
 * data fetch is guaranteed to fail. */
function RoleRoute({ allow, children }: { allow: Role[]; children: ReactElement }) {
  const { auth } = useAuth();
  if (auth && !allow.includes(auth.role)) {
    return <Navigate to={landingPathFor(auth.role)} replace />;
  }
  return children;
}

function landingPathFor(role: Role): string {
  return role === 'CLIENT' ? '/my-loan' : '/score';
}

function AppRoutes() {
  const { auth } = useAuth();

  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <ProtectedRoute>
            <Layout />
          </ProtectedRoute>
        }
      >
        <Route index element={<Navigate to={auth ? landingPathFor(auth.role) : '/login'} replace />} />
        <Route
          path="/score"
          element={
            <RoleRoute allow={['ANALYST', 'ADMIN']}>
              <ScorePage />
            </RoleRoute>
          }
        />
        <Route
          path="/expected-loss"
          element={
            <RoleRoute allow={['ANALYST', 'ADMIN']}>
              <ExpectedLossPage />
            </RoleRoute>
          }
        />
        <Route
          path="/regime-forecast"
          element={
            <RoleRoute allow={['ANALYST', 'ADMIN']}>
              <RegimeForecastPage />
            </RoleRoute>
          }
        />
        <Route
          path="/cvar"
          element={
            <RoleRoute allow={['ANALYST', 'ADMIN']}>
              <CvarPage />
            </RoleRoute>
          }
        />
        <Route
          path="/trajectory"
          element={
            <RoleRoute allow={['ANALYST', 'ADMIN']}>
              <TrajectoryPage />
            </RoleRoute>
          }
        />
        <Route
          path="/segments"
          element={
            <RoleRoute allow={['ANALYST', 'ADMIN']}>
              <SegmentGraphPage />
            </RoleRoute>
          }
        />
        <Route
          path="/admin/audit-log"
          element={
            <RoleRoute allow={['ADMIN']}>
              <AuditLogPage />
            </RoleRoute>
          }
        />
        <Route
          path="/admin/login-attempts"
          element={
            <RoleRoute allow={['ADMIN']}>
              <LoginAttemptsPage />
            </RoleRoute>
          }
        />
        <Route
          path="/admin/create-user"
          element={
            <RoleRoute allow={['ADMIN']}>
              <AdminCreateUserPage />
            </RoleRoute>
          }
        />
        <Route path="/my-loan" element={<MyLoanPage />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  );
}
