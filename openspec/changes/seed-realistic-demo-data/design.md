## Context

`risk-engine` has real ML models, real auth (JWT + mandatory 2FA for staff, invite-based activation for clients), and a real audit trail — but a brand-new Postgres instance is completely empty until someone manually creates accounts one at a time and types loan feature values into a form. There is also no "list users" endpoint (`ManageUsersPage` works by typing an exact username, per its own doc comment), so there's no natural way to browse what exists either. The goal here is a single script an operator runs once against a running instance that leaves it looking like a real, in-use enterprise system: real borrowers with real linked loans, real computed scores, and real activity in the audit/login logs.

The project already has a Python-script-drives-the-service pattern for exactly this kind of one-off/operational task — `backend/export_*.py` (train + export models) and `backend/batch_refresh_segment_correlation.py` (talks to a *running* Neo4j to refresh data in place). This change follows that same pattern, but the "running service" it drives is `risk-engine` itself, over HTTP.

## Goals / Non-Goals

**Goals:**
- Produce a realistic, browsable demo dataset (ANALYST + CLIENT accounts, real linked loans, real `loan_score` rows, real audit/login history) using nothing but the app's own public API.
- Use real loan feature values sampled from the already-processed dataset (`data/processed/loan_level`), not hand-typed or fabricated numbers — a mix of clearly-safe and clearly-risky loans so the demo has visible contrast.
- Leave every seeded account immediately usable: ANALYSTs past their mandatory 2FA bootstrap, CLIENTs past activation — no second manual step required before a demo.
- Be safe to re-run against an instance that's already partially seeded (skip what exists, don't duplicate or crash).
- Print a single end-of-run summary with every credential/secret/link needed to actually log in and drive the app.

**Non-Goals:**
- No direct database writes. Every row this script produces must be a side effect of a real API call, so the seeded data is provably consistent with what the app itself computes (and so this script breaks the moment a real validation rule would reject the data — a feature, not a bug).
- No changes to any controller, service, DB schema, or frontend.
- No seeding of `/expected-loss`, `/cvar`, `/trajectory-score`, `/early-warning-score` *results* as durable, browsable records — none of those endpoints persist their output anywhere (only `/score` writes to `loan_score`, only `/regime-forecast` is cached by `monthsAhead`), so there's nothing to seed for later browsing. The script still calls them a few times against real feature values purely so `model_invocation_events` shows realistic, varied activity — but that's a side effect of exercising the API, not a persistence target.
- No attempt to auto-provision the *initial* ADMIN account or drive its own 2FA bootstrap — that already exists (`AdminBootstrap`) and the operator is assumed to already have (or can trivially get) one working admin session before running this script.

## Decisions

**Drive the real HTTP API as an authenticated ADMIN, not direct SQL.** Alternative considered: a `CommandLineRunner` inside the Spring app gated by a profile/env flag, or a raw SQL seed script. Rejected both: a `CommandLineRunner` would need to duplicate password hashing, TOTP encryption, and JWT issuance logic that already exists correctly in the app; raw SQL would bypass every validation rule (password strength, role/CLIENT password prohibition, the `app_user_role_check` constraint) and produce data that's only realistic by accident. Going through the real API means the seeded data is exactly as valid as anything a real admin could produce, and it's free realism for the audit trail (every `*Controller` call except the four security ones is already captured by `AuditAspect`).

**The script needs one already-authenticated ADMIN bearer token as input (`ADMIN_TOKEN` env var), not credentials it logs in with itself.** Reimplementing the admin's own mandatory-2FA login (atomic username+password+code, or first-run `setupRequired` bootstrap) inside the seeding script would duplicate real auth-flow complexity for a one-time operator convenience. The operator already has to log in as admin through the frontend or a `curl` one-liner at some point; capturing that token into an env var is a two-second step and keeps this script's responsibility singular: provisioning demo data, not orchestrating auth.

**ANALYST 2FA bootstrap is completed programmatically, not left half-done.** The script calls `/account/2fa/setup` and computes a valid TOTP code from the returned secret itself (RFC 6238 over HMAC-SHA1, stdlib only — the same approach already validated by hand against this exact app during manual testing of the early-warning endpoint), then calls `/account/2fa/confirm`. This means the seeded ANALYST session is immediately usable for the rest of the script (scoring loans) *and* the printed secret can be added to a real authenticator app afterward if the operator wants to log in as that analyst themselves later.

**CLIENT activation is completed by the script, not left pending.** `POST /admin/users` for a CLIENT returns an `activationLink` carrying a token; the script extracts that token and calls `POST /activate` itself with a fixed demo password, rather than requiring the operator to click every link by hand. Every seeded CLIENT is immediately loggable-in.

**Loan sampling reads directly from `data/processed/loan_level` via polars**, picking a small, fixed-size, deliberately mixed sample: some loans with strong original features (high credit score, low LTV/DTI) and some with weak ones (low credit score, high LTV/DTI, higher original interest rate), across a few different `orig_year`/`property_state` values — so an admin/analyst browsing the seeded portfolio sees visible variation, not N nearly-identical loans. Loan feature values are read as-is from the processed parquet (already the exact columns `LoanFeatures` expects); no synthetic data generation.

**Idempotency is achieved by treating `POST /admin/users`'s existing 409 CONFLICT ("Username already exists") as "already seeded, move on"** rather than by any new list/lookup endpoint (none exists, and adding one is out of scope here). Usernames are deterministic (e.g. `analyst.demo`, `client.demo.<n>`) specifically so re-runs can detect prior seeding this way. If a username exists but wasn't fully set up in a prior partial run (e.g. process died mid-way), later steps for that identity (2FA confirm, activation, scoring) are attempted regardless and their own failures are logged and skipped rather than aborting the whole run — the script optimizes for "usable end state," not "byte-identical repeated runs."

## Risks / Trade-offs

- **[Risk]** Requiring a pre-obtained `ADMIN_TOKEN` is an extra manual step for the operator (log in once, copy the token) → **Mitigation**: this is a one-time, well-documented step (README subsection walks through the exact `curl`), and JWTs last `JWT_EXPIRATION_HOURS` (default 8h) — one login covers an entire seeding run comfortably.
- **[Risk]** TOTP codes are time-windowed (30s); if the script's clock skews from the server's, `/account/2fa/confirm` could fail → **Mitigation**: compute and submit the code immediately after receiving the secret (sub-second gap in practice); on failure, retry once with a freshly-computed code before giving up on that account.
- **[Risk]** Re-running against an instance where a prior run partially completed (e.g. user created but activation never finished) leaves an account in a pending state the script can't cleanly detect without a list endpoint → **Mitigation**: attempt every remaining step for a deterministic username regardless of whether creation itself returned 409 vs 201; log clearly per-account what succeeded/was skipped/failed in the final summary so the operator can see and manually fix any partial state.
- **[Risk]** Sampling real loans means seeded demo data could technically include a real (if anonymized-by-dataset-design) borrower's data pattern → **Mitigation**: this is the same public Freddie Mac Single-Family Loan-Level dataset already used throughout the rest of this project (already de-identified by Freddie Mac for public release); no new privacy exposure beyond what the project already carries.

## Migration Plan

Purely additive — no schema changes, no deploy step beyond "add this script to the repo." To use: bring up the stack, obtain one ADMIN token, run `python backend/seed_demo_data.py` with `ADMIN_TOKEN` (and `RISK_ENGINE_URL` if not localhost:8080) set. Re-running is safe (see idempotency decision above). No rollback needed beyond normal data cleanup (delete the seeded rows / recreate the Postgres volume) if a clean slate is ever wanted.

## Open Questions

- Exact count/mix of seeded clients (proposed default: 5-8 clients, 1-2 loans each, spanning at least 3 states and both directions of credit quality) — finalized during implementation, not a blocking decision.
- Whether to also seed one additional ADMIN account (beyond the bootstrap one) for completeness — leaning no, since `AdminBootstrap` already guarantees one exists and this script assumes it.
