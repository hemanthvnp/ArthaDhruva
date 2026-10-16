## Why

Right now the only way to see this app do anything is to manually type loan numbers into a form and click "Score" — there is no persistent portfolio, no real client accounts with real loans attached, and no history in the audit log, login-attempt log, or `loan_score` table. That's fine for exercising one endpoint at a time, but it means the app cannot be *demonstrated* the way a real enterprise risk platform would be: an admin browsing existing clients, a client logging in and seeing their own already-scored loan, an audit trail with real activity in it. For an SDE interview where this project is the centerpiece, "populated, real-looking system" matters as much as "the model is accurate."

## What Changes

- New seeding script (`backend/seed_demo_data.py`) that provisions a realistic demo dataset into a *running* `risk-engine` instance by driving its real HTTP API as an authenticated ADMIN — not by writing to Postgres directly. Every seeded record is produced by the same code path a real user would trigger.
- Selects a diverse, real sample of loans (mix of clean and distressed, multiple states/origination quarters) directly from the already-processed `data/processed/loan_level` / `data/processed/monthly_panel` datasets — no fabricated feature values.
- Creates one or more ANALYST accounts and completes their real mandatory-2FA bootstrap enrollment programmatically (computes a valid TOTP code from the secret the server returns), so they're immediately usable rather than stuck mid-setup.
- Creates one CLIENT account per selected borrower, attaches their real `loanId`(s) via the existing invite flow, and completes activation (sets a demo password) so every seeded client can log in immediately.
- As the seeded ANALYST, calls `/score` for every seeded loan (with `loanId`) so `loan_score` and the Redis cache hold genuine calibrated model output — this is what `GET /my/loans` and the cached-score lookup actually read from — plus a handful of realistic calls to the other scoring/analysis endpoints so the audit trail and login-attempt log reflect real day-in-the-life usage instead of empty tables.
- Safe to re-run: usernames/loans that already exist are skipped (via the API's own "already exists" responses) rather than duplicated or erroring out the whole run.
- Prints a final summary (usernames, demo passwords, TOTP secrets/activation links) so the operator has everything needed to log in and demo the app immediately after running it.

## Capabilities

### New Capabilities
- `demo-data-seeding`: provisioning realistic ADMIN/ANALYST/CLIENT users, real linked loans, and genuine computed model results into a running instance via its own public API, so the application can be demonstrated as a populated system rather than an empty calculator.

### Modified Capabilities
(none — no existing endpoint's request/response contract or behavior changes)

## Impact

- **Affected code**: one new script, `backend/seed_demo_data.py` (same category as the existing `backend/export_*.py` / `backend/batch_refresh_segment_correlation.py` utility scripts — not part of the deployed service, not touching `risk-engine`'s Java source).
- **Affected data**: adds rows to `app_user`, `user_loan_id`, `loan_score`, `model_invocation_events`, and `login_attempt` in whatever Postgres instance it's pointed at, plus Redis score cache entries — all through existing, already-audited endpoints.
- **Docs**: README gets a new "Seeding demo data" subsection describing how to run it and what it produces.
- **No changes** to any controller, service, DB schema, or frontend code — this is purely a client of the existing API surface.
