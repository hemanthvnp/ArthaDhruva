import type { LoginResponse, Role } from '../api/types';

/** The token itself lives only in the httpOnly ad_session cookie -- never in here, never readable by
 * this code. Everything below is operational metadata: who's signed in, and when this client should
 * next ask the server to refresh the session. */
export interface AuthState {
  username: string;
  role: Role;
  sandbox?: boolean;
  /** When the access token expires (ISO). While the user is active it is exchanged for a fresh one about once a minute. */
  expiresAt?: string;
  /** When the session reaches its absolute limit and a new sign-in is required (ISO). */
  sessionExpiresAt?: string;
  /** When this token was received, on this device's clock (ms): what "time for a fresh one" is measured from. */
  obtainedAt?: number;
}

export const STORAGE_KEY = 'arthadhruva-auth';
/** Fired on this tab whenever the stored session changes outside React (a token refresh in the API client). */
export const CHANGED = 'arthadhruva-auth-changed';

/** The stored session, for code that is not a React component and cannot use the hook. */
export function getStoredAuth(): AuthState | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as AuthState) : null;
  } catch {
    return null;
  }
}

/** Replaces the stored session from outside React and tells the provider. */
export function storeAuth(state: AuthState): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  window.dispatchEvent(new Event(CHANGED));
}

export function clearStoredAuth(): void {
  localStorage.removeItem(STORAGE_KEY);
  window.dispatchEvent(new Event(CHANGED));
}

/** The session a login-style response carries. */
export function sessionFrom(response: LoginResponse): AuthState {
  return {
    username: response.username,
    role: response.role,
    sandbox: response.sandbox,
    expiresAt: response.expiresAt,
    sessionExpiresAt: response.sessionExpiresAt,
    obtainedAt: Date.now(),
  };
}

/** Single source of truth for "where does this role land after login" -- shared by LoginPage
 * (post-login redirect) and App's index-route redirect, so they can't drift out of sync again. */
export function landingPathFor(role: Role): string {
  return role === 'CLIENT' ? '/my-loan' : role === 'PLATFORM_ADMIN' ? '/account/password' : '/dashboard';
}
