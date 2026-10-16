## 1. Script scaffolding and HTTP client

- [x] 1.1 Create `backend/seed_demo_data.py` with the same header/docstring style as `backend/batch_refresh_segment_correlation.py`, explaining what it does and that it's API-driven, not a direct DB seed.
- [x] 1.2 Read config from env vars: `RISK_ENGINE_URL` (default `http://localhost:8080`), `ADMIN_TOKEN` (required, fail fast with a clear message if missing).
- [x] 1.3 Implement a small `requests`-based client helper (base URL + bearer token injection, raise-with-body on non-2xx so failures are readable).
- [x] 1.4 Implement the RFC 6238 TOTP generator inline (stdlib `hmac`/`hashlib`/`struct`/`base64`/`time` only, no new dependency) — same algorithm already hand-validated against this app during manual testing.

## 2. Loan sampling from the processed dataset

- [x] 2.1 Load a candidate set from `data/processed/loan_level` via polars, selecting the columns `LoanFeatures` needs (credit_score, original_dti, original_upb, original_cltv, original_ltv, original_interest_rate, original_loan_term, number_of_borrowers, number_of_units, mi_percent, occupancy_status, property_type, loan_purpose, channel, first_time_homebuyer_flag, property_state) plus `loan_sequence_number`.
- [x] 2.2 Pick a fixed-size sample (default ~8 loans) that deliberately mixes: a few rows with high credit score + low LTV/DTI, a few with low credit score + high LTV/DTI, across at least 3 distinct `property_state` values.
- [x] 2.3 Handle the case where `data/processed/` isn't populated locally (this project's data is gitignored/regenerated per README) — fail with a clear message pointing at the pipeline setup instructions rather than a confusing stack trace. (Verified against the real local dataset during implementation: quarters processed under different states of the in-progress Freddie Mac schema migration have different column sets, which `pl.scan_parquet` rejects by default — fixed with `extra_columns="ignore"` since only `FEATURE_COLUMNS` is ever selected. Real run against local data: 8 loans sampled across 8 states, clean split between strong (credit 765-819) and weak (credit 629-650) profiles.)

## 3. ANALYST provisioning

- [x] 3.1 Create one ANALYST account (`analyst.demo`, fixed demo password) via `POST /admin/users`; treat a 409 response as "already exists, continue."
- [x] 3.2 Log in as that analyst (password-only); handle the `setupRequired` response by calling `/account/2fa/setup`, computing a TOTP code from the returned secret, and calling `/account/2fa/confirm` to complete enrollment and obtain a real session token.
- [x] 3.3 If login instead returns a normal session directly (account was already fully enrolled from a prior run), use that token as-is. (Refined during implementation: a 401 on password-only login for an already-2FA-enrolled account is indistinguishable from a bad password by design, so this case now triggers admin-driven `/reset-2fa` and fresh re-enrollment instead — see design.md's Idempotency decision and the script's own docstring.)
- [x] 3.4 Record the analyst's session token for use in section 5.

## 4. CLIENT provisioning

- [x] 4.1 For each sampled loan, derive a deterministic client username (e.g. `client.demo.<n>`) and create the CLIENT account via `POST /admin/users` with that loan's `loanId` attached; treat 409 as "already exists, continue" (and still attempt loan attachment via `POST /admin/users/{username}/loans` in case a prior partial run created the user without the loan).
- [x] 4.2 Extract the activation token from the returned `activationLink` and complete activation via `POST /activate` with a fixed demo password, unless the account already exists (already-activated accounts don't need this step — detect via the create call's 409 and skip).
- [x] 4.3 Record each client's username, loanId(s), and demo password for the final summary.

## 5. Score + activity generation

- [x] 5.1 As the seeded analyst, call `POST /score` with each sampled loan's features and `loanId` set, so `loan_score` and the Redis cache are populated with real calibrated output.
- [x] 5.2 Call `POST /expected-loss` and `POST /regime-forecast` a few times using real sampled feature values / a couple of `monthsAhead` values, purely to leave realistic variety in `model_invocation_events` (no persistence target of their own — see design.md Non-Goals).
- [x] 5.3 Perform one deliberate failed login attempt (wrong password) for one seeded account and one successful login for another, so `GET /admin/login-attempts` shows a realistic mix rather than all-success.

## 6. Idempotency and error handling

- [x] 6.1 Wrap each per-identity block (analyst setup, each client's creation/activation/scoring) in its own try/except so one failure doesn't abort the whole run; collect failures for the summary instead.
- [x] 6.2 Verify a full second run against an already-seeded instance completes cleanly with no duplicate-creation errors surfaced to the operator. **Verified**: ran twice against the same live instance. Second run reported `already existed: True` for the analyst and all 8 clients, re-enrolled the analyst's 2FA via the admin-driven reset path (as designed), attached each client's newly-sampled loan alongside their existing one (loanIds is an additive set, matching the real "refinance adds another loan" behavior already documented for `POST /admin/users/{username}/loans`), and printed "No issues encountered."

## 7. Summary output

- [x] 7.1 At the end of the run, print a clearly formatted block: analyst username/password/TOTP secret, each client's username/password/loanId(s), and a short list of anything that failed or was skipped with why.

## 8. Docs and verification

- [x] 8.1 Add a "Seeding demo data" subsection to README (after the existing Backend/Frontend sections) documenting prerequisites (a working ADMIN token, populated `data/processed/`), the exact command, and what it produces.
- [x] 8.2 Manually verify end-to-end against a fresh `docker compose up -d postgres redis neo4j` + freshly started `risk-engine`: run the script, then confirm in the frontend that `ManageUsersPage`, `MyLoanPage` (as a seeded client), and `AuditLogPage`/`LoginAttemptsPage` (as admin) all show the seeded content. **Verified in a real browser session**: `AuditLogPage` showed real `ScoreController.score` / `ExpectedLossController.expectedLoss` / `RegimeForecastController.forecast` entries from the seeding run; `LoginAttemptsPage` showed a realistic yes/no mix (including the deliberate failed+successful client pair and the analyst's expected 2FA-reset-triggering rejection); logging in as `client.demo.2` on `MyLoanPage` showed two real computed scores (0.165% and 1.834%) with real timestamps, one per seeded loan across the two runs.
- [x] 8.3 Re-run the script a second time against that same instance and confirm it reports the accounts as already seeded rather than erroring. **Verified** — see 6.2.

**Resolution of the ADMIN_TOKEN blocker**: the user explicitly authorized resetting the existing `admin` test account's password/2FA for this purpose (their own dev data). Used that to obtain a real ADMIN session and complete all three verification tasks above.

**Note on 6.2/8.2/8.3**: verifying these requires a real ADMIN bearer token. The only existing ADMIN account in this dev database already has a password and 2FA secret nobody currently running this session has access to, and Claude Code's own safety classifier correctly refused both routes to work around that (resetting that account's existing password/2FA, and inserting a new ADMIN row directly via SQL) as privilege-escalation-shaped actions. This needs a token supplied by whoever has legitimate access to an ADMIN account on this instance.
