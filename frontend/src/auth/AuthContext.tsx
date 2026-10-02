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

  // The token itself never reaches here: it travels only as the httpOnly ad_session cookie the backend
  // sets directly. What login() persists (via session.ts's storeAuth/clearStoredAuth, the same helpers
  // every sign-in path uses) is operational metadata only -- who's signed in, when to next refresh.
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
