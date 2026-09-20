import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import type { Role } from '../api/types';

export interface AuthState {
  token: string;
  username: string;
  role: Role;
  sandbox?: boolean;
}

interface AuthContextValue {
  auth: AuthState | null;
  login: (state: AuthState) => void;
  logout: () => void;
}

const STORAGE_KEY = 'arthadhruva-auth';

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

function loadStoredAuth(): AuthState | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as AuthState) : null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [auth, setAuth] = useState<AuthState | null>(loadStoredAuth);

  const login = (state: AuthState) => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    setAuth(state);
  };

  const logout = () => {
    localStorage.removeItem(STORAGE_KEY);
    setAuth(null);
  };

  const value = useMemo(() => ({ auth, login, logout }), [auth]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}

/** For the API client, which isn't a React component and can't use the hook. */
export function getStoredToken(): string | null {
  return loadStoredAuth()?.token ?? null;
}

export function clearStoredAuth(): void {
  localStorage.removeItem(STORAGE_KEY);
}

/** Single source of truth for "where does this role land after login" -- shared by LoginPage
 * (post-login redirect) and App's index-route redirect, so they can't drift out of sync again. */
export function landingPathFor(role: Role): string {
  return role === 'CLIENT' ? '/my-loan' : role === 'PLATFORM_ADMIN' ? '/account/password' : '/dashboard';
}
