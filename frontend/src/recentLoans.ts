/** Per-browser "recently viewed loans" -- a per-viewer convenience, not shared state, so
 * localStorage is the right tool here (unlike case/note data, which is real shared state and
 * lives on the server). Wrapped in try/catch since storage access can throw or be unavailable
 * (private browsing, blocked site data). */
const STORAGE_KEY = 'arthadhruva.recentLoans';
const MAX_ENTRIES = 8;

export function recordLoanVisit(loanId: string): void {
  try {
    const current = getRecentLoans().filter((id) => id !== loanId);
    const updated = [loanId, ...current].slice(0, MAX_ENTRIES);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(updated));
  } catch {
    // best-effort only
  }
}

export function getRecentLoans(): string[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((x) => typeof x === 'string') : [];
  } catch {
    return [];
  }
}
