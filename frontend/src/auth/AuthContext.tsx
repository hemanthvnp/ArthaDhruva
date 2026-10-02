import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { CHANGED, STORAGE_KEY, getStoredAuth, type AuthState } from './session';
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

  const login = (state: AuthState) => {
    // A deliberate trade-off, not an oversight: the token is short-lived and rotated roughly every
    // minute (see AuthState.expiresAt/obtainedAt and the API client's refresh logic), so localStorage's
    // XSS exposure window is one rotation, not the session lifetime -- the same storage this app's own
    // session.ts uses for the regular sign-in path. An httpOnly cookie would trade that for CSRF handling
    // this API doesn't otherwise need.
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state)); // lgtm[js/clear-text-storage-of-sensitive-data]
    setAuth(state);
  };

  const logout = () => {
    localStorage.removeItem(STORAGE_KEY);
    setAuth(null);
  };

  const value = useMemo(() => ({ auth, login, logout }), [auth]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
