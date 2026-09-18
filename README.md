# ArthaDhruva — Mortgage Credit Risk Intelligence Platform

Built on the Freddie Mac Single-Family Loan-Level Dataset. See `pipeline/` for the ETL, `notebooks/` for analysis and modeling.

## Setup (for a new collaborator)

The raw and processed data is **not in this repo** (raw ~9GB, processed ~53GB — see `.gitignore`). The pipeline is fully deterministic, so re-running it locally produces byte-identical output to what's already been analyzed. This is the intended way for a second person to get the same dataset without anyone transferring tens of gigabytes around.

### 1. Clone and install dependencies

```bash
git clone https://github.com/hemanthvnp/ArthaDhruva.git
cd ArthaDhruva
pip install -r requirements.txt
pip install -r requirements-notebooks.txt  # used by the notebooks and backend/ export scripts, not the core pipeline
```

Requires Python 3.10+ and roughly 16GB RAM (the pipeline is chunked specifically to stay within that; see the docstring in `pipeline/chunked.py`).

### 2. Get the raw data

Register for and download the Freddie Mac Single-Family Loan-Level Dataset yourself (each collaborator should obtain it under their own account per Freddie Mac's terms, rather than receiving a copy from a teammate).

Place the quarterly ZIPs at the repo root in this exact layout — the pipeline looks for them here (see `pipeline/config.py`):

```
ArthaDhruva/
  historical_data_2020/
    historical_data_2020Q1.zip
    historical_data_2020Q2.zip
    ...
  historical_data_2021/
    ...
  ... through historical_data_2025/ (2025 currently has Q1-Q3 only)
```

You don't need all 23 quarters to start — `extract.py` skips any quarter whose ZIP isn't present, so you can begin with a handful of quarters (e.g. just `2020Q1`-`2020Q4`) and add more later.

### 3. Run the pipeline

```bash
# Everything: extract -> readable -> clean -> preprocess, all quarters found
python -m pipeline.run

# Just a couple of quarters, useful for a fast first run
python -m pipeline.run --quarter 2020Q1 --quarter 2020Q2

# Re-run a single stage only (e.g. after a code change to preprocessing)
python -m pipeline.run --stage preprocess --force
```

Output lands in `data/readable/`, `data/clean/`, `data/processed/loan_level/`, `data/processed/monthly_panel/` — these are the paths the notebooks read from (`../data/processed/...`).

**Alternative: Docker.** `docker-compose.yml` already wires up the same volume layout:

```bash
docker compose run pipeline        # runs python -m pipeline.run
docker compose up jupyter          # Jupyter Lab on localhost:8888, same mounted data
```

### 4. Verify your local run matches

```bash
python -c "from pipeline import validate; validate.write_summary_readme()"
```

Writes `data/docs/final_summary.md` with row counts and default rate per quarter. For the full 23-quarter run, this should read **12,213,996 total loans, 474,387,684 monthly panel rows, ~1.99% overall default rate** — compare against that to confirm your local copy matches.

### 5. Notebooks

Everything in `notebooks/` is tracked in git and reads from `../data/...` relative paths, so once your local `data/` is populated, they run unchanged — no path edits needed.

**One thing to set up before both of you are committing notebook changes**: notebook output cells change on every re-run even when the code doesn't, which makes for noisy diffs and false merge conflicts. Install [`nbstripout`](https://github.com/kynan/nbstripout) once per machine so outputs are stripped automatically on commit:

```bash
pip install nbstripout
nbstripout --install
```

### 6. Backend (risk-engine)

`backend/` holds the deployable side: a Java/Spring Boot scoring service (`backend/risk-engine/`) plus the Python scripts that train the models and export the artifacts it serves (`export_model.py`, `export_hmm.py`, `reexport_onnx.py`).

The exported artifacts (`model.onnx`, `calibration.json`, `category_mappings.json`, `feature_order.json`, `hmm_regime.json`) are small and already committed under `backend/risk-engine/src/main/resources/`, so a teammate normally doesn't need to regenerate them — just start Postgres and run the service:

```bash
docker compose up -d postgres redis neo4j   # audit trail + score/forecast cache + segment-correlation graph
cd backend/risk-engine
./mvnw spring-boot:run                      # mvnw.cmd on Windows
```

Requires JDK 17 (the wrapper downloads Maven itself, no separate Maven install needed). The app connects to `jdbc:postgresql://localhost:5432/arthadhruva` and `redis://localhost:6379` by default (matching the Docker Compose services); override with the `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD` and `REDIS_HOST`/`REDIS_PORT` env vars if you're pointing it at different instances.

**Authentication.** Every endpoint except `/login` and `/actuator/health` requires a JWT (`Authorization: Bearer <token>`, obtained from `POST /login`). On first run against an empty database, a bootstrap `ADMIN` account is created automatically — **watch the startup logs** for a one-time banner with the generated username/password (set `ADMIN_USERNAME`/`ADMIN_PASSWORD` to skip the random generation, e.g. for CI). `JWT_SECRET` should be set explicitly for anything beyond local dev — if unset, a random signing key is generated per run, so restarting invalidates every outstanding token.

**Three roles**, all server-enforced (not just hidden in the UI):
- `ANALYST` / `ADMIN` — internal staff, full access to every scoring/analysis endpoint (`/score`, `/expected-loss`, `/regime-forecast`, `/cvar`, `/trajectory-score`, `/early-warning-score`, `/segments/**`).
- `ADMIN` additionally reaches everything under `/admin/**` — the audit log and user provisioning below.
- `CLIENT` — an external borrower. Can only reach `/my/loans` (their own linked loan(s)); every other scoring/analysis endpoint returns 403 for this role, even though the request would otherwise be well-formed.

There's still no self-registration (deliberate, for a financial platform) — every account, of any role, is created by an admin. How the password gets set differs by role, though: staff (`ADMIN`/`ANALYST`) get one set directly by the admin, matching normal enterprise onboarding —

```bash
curl -X POST localhost:8080/admin/users -H "Authorization: Bearer <admin token>" -H "Content-Type: application/json" \
  -d '{"username":"new.analyst","password":"...","role":"ANALYST"}'
```

— but a `CLIENT` account **cannot** take a password directly (the request is rejected if one is supplied): no real product has an admin choosing a customer's password and handing it over out-of-band. Instead:

```bash
curl -X POST localhost:8080/admin/users -H "Authorization: Bearer <admin token>" -H "Content-Type: application/json" \
  -d '{"username":"jane.borrower","role":"CLIENT","loanIds":["L-10293"]}'
```

creates the account in a pending, unusable state (a permanently-unguessable placeholder password, `activated: false`) and returns an `activationLink` (built from `FRONTEND_URL`, default `http://localhost:5173`) good for `ACCOUNT_ACTIVATION_EXPIRATION_HOURS` (default 72). There's no email-sending infrastructure in this app, so the admin shares that link manually — it's not a fully automated invite email, just a realistic step up from an admin-chosen password. The client visits it, sets their own password (`POST /activate`, body `{"activationToken", "password"}`, public/no auth required), and is logged in immediately in the same response. A login attempt against a still-pending account gets a distinct "Account not yet activated" message rather than a generic credential failure (and doesn't count toward lockout — it's a state check, not a guess).

None of this changes how a loan gets to a client or how it's analyzed — an admin still explicitly attaches `loanId`s (at creation, as above, or afterward via `POST /admin/users/{username}/loans`, body `{"loanId": "..."}`, e.g. for a refinance), and scoring is still just `ANALYST`/`ADMIN` running `/score` the same way regardless of who ends up able to view the result. The "Create User" page in the frontend covers both flows — it drops the password field entirely and shows the activation link when `CLIENT` is selected.

**Login hardening.** Two independent layers: a global rate limit on `/login` (`AUTH_MAX_FAILED_ATTEMPTS`-independent — a blunt volume cap, `resilience4j.ratelimiter.instances.login.*`, 20 req/s by default, rejected requests get a 429), and per-account lockout — `AUTH_MAX_FAILED_ATTEMPTS` (default 5) wrong passwords in a row locks the account for `AUTH_LOCKOUT_MINUTES` (default 15), enforced by Spring Security itself (the account is rejected as locked *before* the password is even checked, once triggered). Every login attempt, successful or not, is recorded credential-free (username + outcome, never the password) and viewable by an admin at `GET /admin/login-attempts` or the "Login Attempts" page in the frontend.

**Account self-service.** Passwords must be at least `AUTH_PASSWORD_MIN_LENGTH` characters (default 10) and contain a letter and a digit, enforced everywhere a password is set (user creation, self-service change, admin reset) via a shared `@StrongPassword` bean-validation constraint. Any logged-in user can change their own password at `POST /account/password` (body: `{"currentPassword": "...", "newPassword": "..."}`, requires the correct current password) or the "Change Password" page in the frontend. An admin can reset any user's password without knowing the old one at `POST /admin/users/{username}/reset-password` (body: `{"newPassword": "..."}`) — this also clears any lockout, since a reset is implicitly vouching the account is good again. An admin can also deactivate/reactivate an account at `POST /admin/users/{username}/deactivate` / `.../activate` (an admin can't deactivate their own account); a deactivated account gets a distinct "Account disabled" message at login. All three actions are on the "Manage Users" page in the frontend, which — since there's no user-listing endpoint yet — works by typing the exact username rather than picking from a list.

Deactivation and lockout take effect **immediately**, not just on the account's next login attempt: `JwtAuthenticationFilter` looks the user up on every authenticated request (not just at login) and only accepts the token if the account is still enabled and unlocked. Without this, a JWT issued before a deactivation would otherwise keep working for its full remaining lifetime (`JWT_EXPIRATION_HOURS`, default 8) purely because a signature check alone can't reflect account state that changed after the token was issued.

**Two-factor authentication (TOTP).** Mandatory for `ADMIN`/`ANALYST`, optional for `CLIENT`, verified differently for each: mandatory-role login is **atomic** — `POST /login` takes `{"username", "password", "totpCode"}` all at once, and a missing or wrong code produces the *exact same* generic "Invalid username or password" (and the same lockout increment) as a wrong password, so there's no way for an attacker to learn the password was correct without also having a valid code. Optional-role login is a **two-step handshake** — submit `{"username", "password"}` first; if that account has opted in, the response is `{"mfaRequired": true}` (no token) instead of a hard failure, and a follow-up call with `totpCode` completes it.

A brand-new `ADMIN`/`ANALYST` account has no secret yet, so it can't complete an atomic login on day one. Password-only login for such an account returns `{"setupRequired": true, "setupToken": "..."}` instead of a session — a short-lived (`TOTP_SETUP_EXPIRATION_MINUTES`, default 10), narrowly-scoped token good for *only* `POST /account/2fa/setup` and `POST /account/2fa/confirm`, rejected everywhere else even though it's a technically-valid, authenticated credential (enforced via a distinct `ROLE_TOTP_SETUP` authority in `SecurityConfig`, not just "don't hand it out carelessly"). `setup` generates and stores a pending secret, returning a QR code (as a data URI) plus the raw secret for manual entry; `confirm` (body `{"code"}`) activates it — and, if the caller got there via a setup token, also returns a real session in the same response, completing the bootstrap login. `GET /account/2fa/status` reports `{"enabled", "required"}`; `POST /account/2fa/disable` (body `{"code"}`, requires a valid current code) is available for optional-role accounts only — mandatory means self-service can't turn it off. An admin can force a re-enrollment (lost phone, offboarding) at `POST /admin/users/{username}/reset-2fa`, no code needed; the account's next login falls back into the same `setupRequired` flow as a brand-new account. The frontend's "Two-Factor Auth" page covers voluntary `CLIENT` opt-in/disable; the bootstrap flow has its own page (`/setup-2fa`), reached automatically from the login screen. TOTP secrets are encrypted at rest (`TOTP_ENCRYPTION_KEY`, AES-256-GCM) — never stored in plaintext, unlike a password they must stay recoverable to verify future codes. **If `TOTP_ENCRYPTION_KEY` isn't set, a random key is generated per run** (same pattern as `JWT_SECRET`) — every account's enrolled secret becomes permanently undecryptable the moment the process restarts, and every login for that account then fails with the same generic "Invalid username or password" a wrong code always produces, which looks exactly like a bug rather than an expected consequence of restarting. Recover via `POST /admin/users/{username}/reset-2fa` and re-enroll (the admin account itself needs another admin, or a direct one-time database fix, if it's the one locked out). Set a fixed `TOTP_ENCRYPTION_KEY` (`openssl rand -base64 32`) for any instance you intend to restart and still use.

**If you have a database that predates a schema change**, `ddl-auto=update` (this project has no migration tool yet) only ever adds new tables/columns — it never retroactively widens an existing check constraint or adds a `NOT NULL` column to a table that already has rows, so upgrading an existing database (rather than starting from a fresh one) can fail on startup. Known cases so far, all one-time manual fixes:

- **Adding the `CLIENT` role** to an existing database — creating a `CLIENT` user fails with a Postgres `app_user_role_check` constraint violation, because Hibernate generated that check constraint from the `Role` enum's values back when the table was first created:
  ```sql
  ALTER TABLE app_user DROP CONSTRAINT app_user_role_check;
  ALTER TABLE app_user ADD CONSTRAINT app_user_role_check CHECK (role::text = ANY (ARRAY['ANALYST','ADMIN','CLIENT']::text[]));
  ```
- **Adding login lockout tracking** to an existing database — the backend fails to start with `column "failed_login_attempts" ... contains null values`, because Postgres won't add a `NOT NULL` column to a table with existing rows without a default:
  ```sql
  ALTER TABLE app_user ADD COLUMN IF NOT EXISTS failed_login_attempts INTEGER NOT NULL DEFAULT 0;
  ALTER TABLE app_user ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;
  ```
- **Adding account deactivation** to an existing database — same failure mode, this time on `enabled`:
  ```sql
  ALTER TABLE app_user ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT true;
  ```
- **Adding 2FA** to an existing database — same failure mode, this time on `totp_enabled` (`totp_secret` is nullable, so Hibernate adds it fine on its own):
  ```sql
  ALTER TABLE app_user ADD COLUMN IF NOT EXISTS totp_enabled BOOLEAN NOT NULL DEFAULT false;
  ```
- **Adding invite-based CLIENT activation** to an existing database — same failure mode, this time on `activated`. Also note the default here is `true`, not `false`: every *existing* row is a real, already-usable account (this only matters for newly-invited CLIENTs going forward), so backfilling it as `false` would incorrectly lock everyone out:
  ```sql
  ALTER TABLE app_user ADD COLUMN IF NOT EXISTS activated BOOLEAN NOT NULL DEFAULT true;
  ```

Expect the same class of issue for any future column added with a `NOT NULL` constraint or enum value added to a checked column, on a non-fresh database — apply the same pattern (`ALTER TABLE ... ADD COLUMN IF NOT EXISTS ... DEFAULT ...`, or drop/recreate the check constraint).

`GET /admin/audit-log?limit=50` (ADMIN only) exposes the `model_invocation_events` audit trail through the API instead of only via direct Postgres access.

`POST /score` accepts an optional `loanId` field; when present, the result is cached in Redis (fast repeat-reads via `GET /score/{loanId}`, analyst/admin only, 24h TTL) **and** upserted into a durable `loan_score` Postgres table — the record `GET /my/loans` (any authenticated role, but really for `CLIENT`) reads from, since a borrower might check their loan status long after Redis's cache window has expired. `GET /regime-forecast` is cached by `monthsAhead` for 1 hour.

**Scoring a real loan without typing feature values by hand.** `GET /loans` (analyst/admin) returns a browsable catalog of ~400 real loans sampled from the processed dataset (`backend/export_loan_catalog.py`, spread across the credit-score distribution) — each entry is already shaped exactly like a `/score` request body, `loanId` included, so the frontend's Loan Portfolio page can hand one straight to `POST /score` with no manual entry. `GET /loan-scores` (analyst/admin) lists every loan anyone has scored so far (from the same `loan_score` table `/my/loans` reads from), most recent first — the portfolio view. The original manual-entry form (`/score` in the frontend nav, labeled "(manual)") still exists for genuine what-if scoring of a hypothetical loan; the catalog is the added no-form path for scoring real ones.

`POST /cvar` runs a Monte Carlo bootstrap CVaR simulation: send `{"loans": [{"pd": 0.02, "lgd": 0.4, "ead": 250000}, ...], "confidenceLevel": 0.95, "numScenarios": 50000}` (`confidenceLevel`/`numScenarios` are optional) and get back `valueAtRisk`/`conditionalValueAtRisk` plus a bootstrap confidence interval on each — a genuine interval reflecting simulation uncertainty, not a single point estimate. This endpoint is stochastic by design (results vary slightly call to call) and isn't cached.

`GET /segments` lists the 15 states loaded into the segment-correlation graph, and `GET /segments/{state}/neighbors?maxHops=2` runs the actual multi-hop traversal query (which states' delinquency risk has historically moved together with this one, within N hops) — the real justification for using Neo4j here rather than a plain table, per `segment_correlation_graph.ipynb`. The graph is loaded from `segment_correlation_graph.json` (already committed, like the other model artifacts); regenerate it with `python backend/export_segment_correlation.py` if the underlying data changes.

**Batch layer.** `backend/batch_refresh_segment_correlation.py` is the project's batch/nightly-recompute layer (reusing the same bounded-chunk/Polars pattern as the rest of the pipeline, not Apache Spark — see the script's own docstring for why). Unlike `export_segment_correlation.py`, it connects directly to a *running* Neo4j and replaces the graph in place — no app restart needed:

```bash
python backend/batch_refresh_segment_correlation.py                    # run once
python backend/batch_refresh_segment_correlation.py --threshold 0.9    # override the correlation threshold
python backend/batch_refresh_segment_correlation.py --loop --interval-hours 24   # repeating cadence, no external scheduler needed
```

`POST /expected-loss` takes the same body as `/score` and returns `{pd, lgd, ead, expectedLoss}` — PD from the existing model, LGD from a Beta regression (`lgd_ead_expected_loss.ipynb`, exported via `python backend/export_lgd_model.py`), and EAD as `original_upb` (a stated simplification — EAD isn't a fitted model anywhere in the analysis; see the code comment on `ExpectedLossController` for why).

`POST /trajectory-score` runs the LSTM trajectory model (`lstm_trajectory_model.ipynb`, exported via `python backend/export_lstm_model.py`): send `{"originalUpb": 250000, "months": [{"currentLoanDelinquencyStatus": "0", "currentActualUpb": 248000, "modificationFlag": "N"}, ...]}` (1-12 months, ordered from origination) and get back `{"probability": ...}` — near-term default probability from the loan's *actual* trajectory so far, not a static origination-time snapshot. Unlike `/score`, this needs real performance history, not just origination features. **Uncalibrated** (see `TrajectoryScoreResponse`'s Javadoc): useful for ranking trajectories relative to each other, not as a dollar-valued probability — the source notebook never fits a calibration step for this model the way the PD model has one.

`POST /early-warning-score` runs the early-warning delinquency model (`notebooks/early_warning_delinquency.ipynb`, v3 feature set, exported via `python backend/export_early_warning_model.py`): for a currently-performing loan (0 DPD, never delinquent before), the probability it goes 30+ days late within the next 3 months — a first-time early-warning signal, distinct from `/trajectory-score`'s general near-term default probability and from `/score`'s lifetime-PD-at-origination. Send the loan's current snapshot — origination features plus `loanAge`, the equity/paydown/rate-lock-severity levels and their 3-/6-month trend changes (`eltv`, `upbPaydownRatio`, `rateLockSeverity`, `eltvChange3m`/`6m`, `upbPaydownChange3m`/`6m`, `rateLockSeverityChange3m`/`6m`), the HMM regime label as of that month (`hmmRegime`: `calm`/`stressed`/`unknown`), and prior-history flags (`priorAssistance`, `priorModification`, `priorDisaster`, `upbStalled`) — and get back `{"rawRisk", "calibratedRisk"}`. Like `/score`, the raw model output is trained on a 10:1 downsampled target rate and overpredicts real-world risk (~8.2x); `calibratedRisk` is isotonic-corrected against a natural-rate holdout built from four different macro quarters. The two are kept deliberately separate rather than only exposing one: `rawRisk` is full-resolution and what an analyst-facing ranked warning list should sort by, while `calibratedRisk` is what should actually be displayed as a probability — isotonic calibration is a monotonic step function that coarsens resolution at the extreme tail, so two loans can land on the same calibrated value while the model still ranks them differently underneath. This endpoint does not derive the trend/regime features itself from raw monthly history — that's the same kind of stated simplification as `/expected-loss`'s EAD, real feature engineering lives in the batch snapshot job (the training/calibration parquet-building step described in the notebook) rather than reimplemented a second time in the request handler.

Every call to `/score` and `/regime-forecast` is persisted immutably to `model_invocation_events` (request, response, latency, success/failure) via a Spring AOP aspect — inspect it directly:

```bash
docker compose exec postgres psql -U arthadhruva -d arthadhruva -c "select endpoint, success, latency_ms, occurred_at from model_invocation_events order by occurred_at desc limit 5;"
```

**Regenerating the artifacts** (only if you change the model/training code) requires `data/processed/...` to be populated first (step 3) and the notebook dependencies installed (step 1):

```bash
cd backend
python export_model.py   # retrains the LightGBM PD model, writes model.onnx + calibration.json + category_mappings.json + feature_order.json
python export_hmm.py     # refits the regime HMM, writes hmm_regime.json
```

Both are deterministic given the same `data/` contents. `trained_checkpoint.joblib` is a local cache of the trained model (regenerable, ~127MB) and is gitignored — don't commit it.

### 7. Frontend

`frontend/` is a React + Vite + TypeScript dashboard covering every `risk-engine` endpoint above (score, expected loss, regime forecast, CVaR simulation, trajectory score, early-warning delinquency, and an interactive segment-correlation graph view). It's a separate app talking to `risk-engine` over HTTP, not server-rendered. An ANALYST/ADMIN lands on **Loan Portfolio** first (`/loans`) — the no-form entry point described above — with the original manual-entry form still available as "Default Risk Score (manual)" for hypothetical what-if scoring.

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173
```

Expects `risk-engine` running on `http://localhost:8080`; override via `frontend/.env.local` (copy `.env.example`) if it's running elsewhere. `risk-engine`'s `CorsConfig` already allows the default Vite dev origin (`localhost:5173`) — if you change the frontend's port, update that too.

### 8. Seeding demo data

A fresh database has no accounts beyond the bootstrap admin and no loans at all — every demo otherwise means hand-typing loan feature values into a form. `backend/seed_demo_data.py` fixes that by provisioning a realistic dataset against a *running* instance entirely through its own public API (never direct SQL): one ANALYST account (with 2FA enrollment completed automatically), several CLIENT accounts each linked to a real loan sampled from `data/processed/loan_level` (a deliberate mix of strong and weak credit profiles across a few states), a real calibrated `/score` result for each of those loans, and a handful of realistic calls to the other endpoints so the audit log and login-attempt log aren't empty either.

Prerequisites: `risk-engine` running and reachable, `data/processed/loan_level` populated (step 3 above), and one already-authenticated ADMIN bearer token — log in as the bootstrap admin once (see the Authentication section above) and pass its token:

```bash
cd backend
ADMIN_TOKEN=eyJ... python seed_demo_data.py
# ADMIN_TOKEN=eyJ... RISK_ENGINE_URL=http://localhost:8090 python seed_demo_data.py   # non-default port
```

The script prints every credential it produces (analyst username/password/TOTP secret, each client's username/password/loanId) at the end — that output alone is enough to log in and demo the app immediately. It's safe to run more than once against the same instance: existing usernames are detected via the API's own "already exists" response and left alone rather than duplicated (an already-enrolled ANALYST account is re-enrolled via the same admin-driven 2FA-reset path a real lost-phone case would use, since the script never persists the TOTP secret it generated on a prior run).
