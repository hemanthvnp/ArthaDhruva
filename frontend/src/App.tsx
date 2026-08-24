import type { ReactElement } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import Layout from './Layout';
import LoginPage from './pages/LoginPage';
import ScorePage from './pages/ScorePage';
import ExpectedLossPage from './pages/ExpectedLossPage';
import RegimeForecastPage from './pages/RegimeForecastPage';
import CvarPage from './pages/CvarPage';
import TrajectoryPage from './pages/TrajectoryPage';
import SegmentGraphPage from './pages/SegmentGraphPage';
import AuditLogPage from './pages/AuditLogPage';

function ProtectedRoute({ children }: { children: ReactElement }) {
  const { auth } = useAuth();
  if (!auth) return <Navigate to="/login" replace />;
  return children;
}

function AppRoutes() {
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
        <Route index element={<Navigate to="/score" replace />} />
        <Route path="/score" element={<ScorePage />} />
        <Route path="/expected-loss" element={<ExpectedLossPage />} />
        <Route path="/regime-forecast" element={<RegimeForecastPage />} />
        <Route path="/cvar" element={<CvarPage />} />
        <Route path="/trajectory" element={<TrajectoryPage />} />
        <Route path="/segments" element={<SegmentGraphPage />} />
        <Route path="/admin/audit-log" element={<AuditLogPage />} />
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
