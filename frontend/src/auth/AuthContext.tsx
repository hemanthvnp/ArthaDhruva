import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { CHANGED, STORAGE_KEY, clearStoredAuth, getStoredAuth, storeAuth, type AuthState } from './session';
import { AuthContext } from './useAuth';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [auth, setAuth] = useState<AuthState | null>(getStoredAuth);

  // The API client refreshes the token on its own, and another tab may sign out: follow both.
  useEffect(() => {
    const sync = () => setAuth(getStoredAuth());
    const onStorage = (e: StorageEvent) => {
      if (e.key === STORAGE_KEY) sync();
    };
    window.addEventListener(CHANGED, sync);
    window.addEventListener('storage', onStorage);
    return () => {
      window.removeEventListener(CHANGED, sync);
      window.removeEventListener('storage', onStorage);
    };
  }, []);

  // Persistence itself is session.ts's job (storeAuth/clearStoredAuth) for every sign-in path, including
  // this one: the token is short-lived and rotated roughly every minute (AuthState.expiresAt/obtainedAt
  // and the API client's refresh logic), so localStorage's XSS exposure window is one rotation, not the
  // session lifetime. An httpOnly cookie would trade that for CSRF handling this API doesn't otherwise need.
  const login = (state: AuthState) => {
    storeAuth(state);
    setAuth(state);
  };

  const logout = () => {
    clearStoredAuth();
    setAuth(null);
  };

  const value = useMemo(() => ({ auth, login, logout }), [auth]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
