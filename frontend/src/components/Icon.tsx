import type { ReactNode } from 'react';

/** Minimal stroke icon set (24x24 paths), inline so there is no icon-font or extra dependency. */
const PATHS: Record<string, ReactNode> = {
  dashboard: <><rect x="3" y="3" width="7" height="9" rx="1.5" /><rect x="14" y="3" width="7" height="5" rx="1.5" /><rect x="14" y="12" width="7" height="9" rx="1.5" /><rect x="3" y="16" width="7" height="5" rx="1.5" /></>,
  loans: <><path d="M3 7h18v12H3z" /><path d="M3 11h18" /><path d="M7 15h3" /></>,
  cases: <><path d="M4 6h16v13H4z" /><path d="M9 6V4h6v2" /></>,
  search: <><circle cx="11" cy="11" r="6.5" /><path d="m20 20-4-4" /></>,
  insights: <><path d="M4 19V9" /><path d="M10 19V5" /><path d="M16 19v-7" /><path d="M22 19H2" /></>,
  assistant: <><path d="M4 5h16v11H9l-5 4z" /></>,
  score: <><path d="M12 3v3" /><path d="M4.5 12a7.5 7.5 0 0 1 15 0" /><path d="m12 12 4-3" /><circle cx="12" cy="12" r="1" /></>,
  loss: <><path d="M3 17 9 11l4 4 8-9" /><path d="M15 6h6v6" /></>,
  forecast: <><path d="M3 12c3-6 6-6 9 0s6 6 9 0" /></>,
  risk: <><path d="M12 3 3 20h18z" /><path d="M12 10v4" /><path d="M12 17h.01" /></>,
  trajectory: <><path d="M3 20 9 9l4 5 8-10" /></>,
  warning: <><circle cx="12" cy="12" r="9" /><path d="M12 7v6" /><path d="M12 16.5h.01" /></>,
  graph: <><circle cx="6" cy="6" r="2.5" /><circle cx="18" cy="8" r="2.5" /><circle cx="9" cy="18" r="2.5" /><path d="m8 7.5 8 .5" /><path d="m7 8.5 1.5 7" /><path d="m17 10-6.5 7" /></>,
  audit: <><path d="M6 3h9l4 4v14H6z" /><path d="M9 12h7" /><path d="M9 16h7" /></>,
  lock: <><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></>,
  userplus: <><circle cx="10" cy="8" r="3.5" /><path d="M3 20c0-4 3-6 7-6s7 2 7 6" /><path d="M19 8v6" /><path d="M16 11h6" /></>,
  users: <><circle cx="9" cy="8" r="3.5" /><path d="M2.5 20c0-3.8 3-6 6.5-6s6.5 2.2 6.5 6" /><path d="M16 4.5a3.5 3.5 0 0 1 0 7" /><path d="M18 14c2.4.6 3.8 2.6 3.8 6" /></>,
  rules: <><path d="M4 6h10" /><path d="M4 12h16" /><path d="M4 18h7" /><circle cx="17" cy="6" r="2" /><circle cx="15" cy="18" r="2" /></>,
  plug: <><path d="M9 3v5" /><path d="M15 3v5" /><path d="M6 8h12v3a6 6 0 0 1-12 0z" /><path d="M12 17v4" /></>,
  bell: <><path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z" /><path d="M10 21h4" /></>,
  menu: <><path d="M4 7h16" /><path d="M4 12h16" /><path d="M4 17h16" /></>,
  sun: <><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5 19 19M5 19l1.5-1.5M17.5 6.5 19 5" /></>,
  moon: <><path d="M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5z" /></>,
  home: <><path d="m3 11 9-7 9 7" /><path d="M5 10v10h14V10" /></>,
};

export default function Icon({ name, size = 18 }: { name: string; size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {PATHS[name] ?? PATHS.dashboard}
    </svg>
  );
}
