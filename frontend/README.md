# ArthaDhruva frontend

The analyst and administrator interface of ArthaDhruva: React 19, TypeScript, Vite, TanStack Query for
server state, Recharts for charts, React Router for navigation. It talks to the backend's `/v1` API
and holds no business logic of its own: every figure on screen is computed by the risk engine.

## Run it

Needs Node 22 and a running backend (see the [repository README](../README.md)).

```bash
npm install
npm run dev          # http://localhost:5173, against the API at http://localhost:8080
```

`VITE_API_BASE_URL` says where the API is. Copy `.env.example` to `.env.local` to point the dev
server somewhere else. A production build leaves it empty (`.env.production`), so the pages and the
API share one origin behind nginx:

```bash
npm run build     # type-checks, then writes dist/
```

## Checks

| Command | What it does |
|---|---|
| `npm run lint` | oxlint over the sources; it has to come back with no findings |
| `npm test` | Vitest unit tests: the session-refresh logic of the API client, and number and date formatting |
| `npm run build` | `tsc -b` type check, then the Vite build |
| `npm run check:bundle` | fails if the JavaScript needed for first paint exceeds 120 KB gzipped |

CI runs all four on every push.

## Layout

```
src/
  api/          client.ts: every backend call, the sliding session, error mapping; types.ts: API shapes
  auth/         the stored session, the React context around it, the useAuth hook
  components/   shared pieces: loan forms, risk badge and meter, chart theme, virtual list
  hooks/        usePortfolioRun (start a portfolio run and follow it), useDebounced
  pages/        one file per route; App.tsx lazy-loads each, so a page costs nothing until it is opened
  format.ts     money, percentages, basis points, dates: one fixed locale, so figures read the same everywhere
  index.css     the design tokens and every shared class
```

## Conventions

- **Server state lives in TanStack Query**, not in component state: pages read with `useQuery` and
  invalidate after a mutation. Component state is for what the user is typing or has selected.
- **One API client.** Pages never call `fetch`. `api/client.ts` attaches the token, exchanges an ageing
  one for a fresh one (once, however many requests notice at the same moment), and turns error
  responses into an `ApiError` with a message a person can read.
- **Component files export components only**; constants and helpers sit in plain modules next to them
  (`components/risk.ts`, `components/loanDefaults.ts`, `auth/session.ts`).
- **The look is ink on paper.** Colour is reserved for the green, amber, red risk spectrum, figures
  are set in the serif face, and pages are built from the classes in `index.css` (`card`, `kpi`,
  `list-row`, `badge`) rather than one-off styles.
- **Role checks here are a courtesy.** Routes are hidden by role to avoid dead ends; the backend is
  what enforces access.
