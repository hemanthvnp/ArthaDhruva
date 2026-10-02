import { createContext, useContext } from 'react';
import type { AuthState } from './session';

export interface AuthContextValue {
  auth: AuthState | null;
  login: (state: AuthState) => void;
  logout: () => void;
}

export const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
