import { lazy, Suspense, type ReactElement } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, landingPathFor, useAuth } from './auth/AuthContext';
import type { Role } from './api/types';
import Layout from './Layout';
import LoginPage from './pages/LoginPage';
const ScorePage = lazy(() => import('./pages/ScorePage'));
const LoanPortfolioPage = lazy(() => import('./pages/LoanPortfolioPage'));
const LoanDetailPage = lazy(() => import('./pages/LoanDetailPage'));
const CasesPage = lazy(() => import('./pages/CasesPage'));
const AssistantPage = lazy(() => import('./pages/AssistantPage'));
const ExpectedLossPage = lazy(() => import('./pages/ExpectedLossPage'));
const RegimeForecastPage = lazy(() => import('./pages/RegimeForecastPage'));
const CvarPage = lazy(() => import('./pages/CvarPage'));
const TrajectoryPage = lazy(() => import('./pages/TrajectoryPage'));
const EarlyWarningPage = lazy(() => import('./pages/EarlyWarningPage'));
const SegmentGraphPage = lazy(() => import('./pages/SegmentGraphPage'));
const AuditLogPage = lazy(() => import('./pages/AuditLogPage'));
const LoginAttemptsPage = lazy(() => import('./pages/LoginAttemptsPage'));
const MyLoanPage = lazy(() => import('./pages/MyLoanPage'));
const AdminCreateUserPage = lazy(() => import('./pages/AdminCreateUserPage'));
const ManageUsersPage = lazy(() => import('./pages/ManageUsersPage'));
const ChangePasswordPage = lazy(() => import('./pages/ChangePasswordPage'));
const TwoFactorPage = lazy(() => import('./pages/TwoFactorPage'));
const Setup2faPage = lazy(() => import('./pages/Setup2faPage'));
const ActivatePage = lazy(() => import('./pages/ActivatePage'));

const SsoCompletePage = lazy(() => import('./pages/SsoCompletePage'));
const SignupPage = lazy(() => import('./pages/SignupPage'));
const ForgotPasswordPage = lazy(() => import('./pages/ForgotPasswordPage'));
const ResetPasswordPage = lazy(() => import('./pages/ResetPasswordPage'));
const DashboardPage = lazy(() => import('./pages/DashboardPage'));
const CaseSearchPage = lazy(() => import('./pages/CaseSearchPage'));
const InsightsPage = lazy(() => import('./pages/InsightsPage'));
const NotificationPrefsPage = lazy(() => import('./pages/NotificationPrefsPage'));
const AutomationRulesPage = lazy(() => import('./pages/AutomationRulesPage'));
const IntegrationsPage = lazy(() => import('./pages/IntegrationsPage'));

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

function AppRoutes() {
  const { auth } = useAuth();

  return (
    <Suspense fallback={<p style={{ padding: '2rem' }}>Loading...</p>}>
      <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/setup-2fa" element={<Setup2faPage />} />
      <Route path="/activate" element={<ActivatePage />} />
      <Route path="/signup" element={<SignupPage />} />
      <Route path="/sso-complete" element={<SsoCompletePage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route
        element={
          <ProtectedRoute>
            <Layout />
          </ProtectedRoute>
        }
      >
        <Route index element={<Navigate to={auth ? landingPathFor(auth.role) : '/login'} replace />} />
        <Route
          path="/loans"
          element={
            <RoleRoute allow={['ANALYST', 'ADMIN']}>
              <LoanPortfolioPage />
            </RoleRoute>
          }
        />
        <Route
          path="/loans/:loanId"
          element={
            <RoleRoute allow={['ANALYST', 'ADMIN']}>
              <LoanDetailPage />
            </RoleRoute>
          }
        />
        <Route
          path="/cases"
          element={
            <RoleRoute allow={['ANALYST', 'ADMIN']}>
              <CasesPage />
            </RoleRoute>
          }
        />
        <Route
          path="/assistant"
          element={
            <RoleRoute allow={['ANALYST', 'ADMIN']}>
              <AssistantPage />
            </RoleRoute>
          }
        />
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
          path="/early-warning"
          element={
            <RoleRoute allow={['ANALYST', 'ADMIN']}>
              <EarlyWarningPage />
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
        <Route
          path="/admin/manage-users"
          element={
            <RoleRoute allow={['ADMIN']}>
              <ManageUsersPage />
            </RoleRoute>
          }
        />
        <Route
          path="/dashboard"
          element={
            <RoleRoute allow={['ANALYST', 'ADMIN']}>
              <DashboardPage />
            </RoleRoute>
          }
        />
        <Route
          path="/case-search"
          element={
            <RoleRoute allow={['ANALYST', 'ADMIN']}>
              <CaseSearchPage />
            </RoleRoute>
          }
        />
        <Route
          path="/insights"
          element={
            <RoleRoute allow={['ANALYST', 'ADMIN']}>
              <InsightsPage />
            </RoleRoute>
          }
        />
        <Route
          path="/account/notifications"
          element={
            <RoleRoute allow={['ANALYST', 'ADMIN']}>
              <NotificationPrefsPage />
            </RoleRoute>
          }
        />
        <Route
          path="/admin/automation-rules"
          element={
            <RoleRoute allow={['ADMIN']}>
              <AutomationRulesPage />
            </RoleRoute>
          }
        />
        <Route
          path="/admin/integrations"
          element={
            <RoleRoute allow={['ADMIN']}>
              <IntegrationsPage />
            </RoleRoute>
          }
        />
        <Route path="/account/password" element={<ChangePasswordPage />} />
        <Route path="/account/2fa" element={<TwoFactorPage />} />
        <Route path="/my-loan" element={<MyLoanPage />} />
      </Route>
      </Routes>
    </Suspense>
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
