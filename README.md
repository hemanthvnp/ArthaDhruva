# ArthaDhruva

A credit risk platform for mortgage lenders, built on the Freddie Mac Single-Family Loan-Level Dataset
(2017 to 2026 vintages, and 2006 to 2008 for the housing downturn). It answers three questions about a loan book: how likely each loan is to
default and why, what the book is expected to lose over a year and over its life (IFRS 9 and CECL), and
how much worse than expected a bad year can be.

## What it does

| | |
|---|---|
| **Default risk score** | Probability of default within 24 months of origination, a Shapley explanation that adds up exactly to the score, and adverse-action reason codes |
| **Lifetime risk** | One loan month by month to maturity: the probability it is still on the book, has prepaid or has defaulted, under a baseline, two stress scenarios and two rate shocks |
| **Portfolio risk** | Every loan projected and added up: the loss allowance, IFRS 9 staging, the response to stress, a ten-year run-off, and the allowance loan by loan |
| **Probability-weighted allowance** | One figure across the scenarios, each weighted by the probability the user gives it, for one loan or from the latest portfolio run without projecting again |
| **Region analysis** | Whether a loan's stressed figure is high under every variant of the stress, under none, or on the boundary where the answer turns on the details, for one loan or the whole book (rough-set three-way decision) |
| **Loss distribution** | Value at risk and expected shortfall of the portfolio, and the loans that drive the tail |
| **Model governance** | Every model with its checksum, validation, backtests and limitations, population drift against the training data, and today's macro conditions against the survival model's training window |
| **Workflow** | Cases with enforced transitions, a four-eyes rule and an immutable history; notes, documents, automation rules, notifications, webhooks |
| **Borrower portal** | A borrower's own loan as a status band and a sentence on how it moved (never the probability), the open review and who to contact, their documents, and a safety assistant that checks whether a message they received looks like a scam |
| **Platform** | Multi-tenant, with single sign-on, API keys, usage metering, an audit trail and rate limits |

How the models were built, validated and where they should not be trusted: [MODELS.md](MODELS.md).

## What is worth reading in the code

**The risk engines** (`backend/risk-engine/src/main/java/com/arthadhruva/riskengine/`)

- [`survival/TermStructureEngine`](backend/risk-engine/src/main/java/com/arthadhruva/riskengine/survival/TermStructureEngine.java):
  default and prepayment as competing risks whose hazards depend on a macro regime that follows a Markov
  chain. The expectation over all 2^T regime paths is computed exactly, by a forward recursion in O(T),
  rather than by simulation. Tested against brute-force enumeration and against an independent Python
  implementation of the whole projection.
- [`cvar/CvarEngine`](backend/risk-engine/src/main/java/com/arthadhruva/riskengine/cvar/CvarEngine.java):
  a one-factor Gaussian copula with importance sampling, confidence intervals from sectioning, and the
  tail attributed to loans by replaying scenarios from their seeds instead of storing them. Scenario
  `s` always draws from the same stream, so a result is identical on any number of threads and
  reproducible from the seed it reports. Tested against the exact distribution of a finite portfolio.
- [`survival/PortfolioRiskEngine`](backend/risk-engine/src/main/java/com/arthadhruva/riskengine/survival/PortfolioRiskEngine.java):
  thousands of loans per batched model call, merged in the order the work was cut so every figure is
  reproduced bit for bit; a sample with its standard error above 5,000 loans.
- [`roughset/DecisionService`](backend/risk-engine/src/main/java/com/arthadhruva/riskengine/roughset/DecisionService.java):
  a rough-set three-way decision laid over the term-structure engine without changing it. A loan's
  stressed figure is judged over variants of the stress (shock sizes and timing drawn from a
  counter-based generator, so the same request gives the same variants on any thread count) and the
  loan falls in the lower approximation (high under every variant), outside the upper approximation
  (high under none) or on the boundary, which is the set to send to an analyst. It is a robustness
  classification, not a probability, and the response says so. Held to an independent Python
  implementation of the generator and the rule by a golden test. Request it with an optional `decision`
  object on `POST /risk/term-structure` and `/risk/term-structure/compare`, or on `POST /risk/portfolio/runs`
  (`GET /risk/portfolio/loans?region=BOUNDARY` lists the boundary loans).
- [`score/ExplanationService`](backend/risk-engine/src/main/java/com/arthadhruva/riskengine/score/ExplanationService.java):
  Shapley values, exact for up to ten differing features and by antithetic sampling beyond, in one model
  call.

**The platform around them**

- **A long computation as a resource.** Starting a portfolio run answers `202` with a run to poll. The
  run is a database row, so any replica answers the poll, and the table is the queue: a waiting run is
  taken by whichever replica has a free worker (`FOR UPDATE SKIP LOCKED`), none waits in a JVM's memory.
  A worker heartbeats on a timer whether or not it is making progress, so a run waiting its turn on a
  busy compute pool is not mistaken for a dead one. A run whose worker did die is put back in the queue
  by the leading replica (a lease in Postgres) under a new attempt number, which also fences out a
  worker that was slow, not dead; the next attempt resumes from the chunks the last one finished and
  stores the same figures to the bit, and after three attempts the run is failed instead of hanging. A
  replica that is shut down hands its runs back at once. CI kills one of two replicas in the middle of
  a run and requires the other to finish it from the checkpoints
  ([`scripts/failover-test.sh`](scripts/failover-test.sh)). A run can be deferred until a replica is idle.
  A partial unique index allows one active run per organization.
  ([`PortfolioRiskService`](backend/risk-engine/src/main/java/com/arthadhruva/riskengine/survival/PortfolioRiskService.java))
- **Tenant isolation enforced by the database.** Postgres row-level security on every tenant table, and
  the application connects as a role that does not own them. The integration tests run as that role.
- **An audit trail the application cannot rewrite.** Every API call is recorded with who, what, the
  outcome and the model version; the application's role has no `UPDATE` or `DELETE` on it, and
  credentials are redacted before an entry is written.
- **A borrower sees a band, not a score.** The borrower API's response types have no field for the
  probability, the model version, an explanation or a case note (a unit test fails if one is added), and
  every borrower endpoint resolves the caller's own loans on the server, so a loan id sent by the client
  can only narrow that set. The borrower's safety assistant is a separate service from the analyst's,
  sharing nothing but the HTTP client, and its answers always come with the loan officer's contact.
  ([`borrower/BorrowerPortalService`](backend/risk-engine/src/main/java/com/arthadhruva/riskengine/borrower/BorrowerPortalService.java))
- **Rate limits that reflect cost.** Token buckets in Redis (one Lua script, atomic across replicas),
  one per organization and one per caller inside it; a simulation costs more tokens than a read.
- **Model integrity.** A model card that declares a checksum is binding: at start-up the artifact is
  hashed and a mismatch stops the service. Exports are reproducible bit for bit.
- **Sessions.** Fifteen-minute tokens refreshed while the user is active, an eight-hour cap, and a
  session version that revokes every outstanding token at once. The token itself lives only in an
  httpOnly, SameSite=Strict cookie -- never in a bearer header or anywhere JavaScript can read it. TOTP
  is mandatory for staff, with replay protection.
- **Outbound calls.** Webhooks through an outbox with leases and HMAC signatures, delivered on a few
  threads with one delivery in progress per subscription, so a slow endpoint does not hold up the
  others; every URL a tenant supplies is checked against private address ranges at connection time, not
  just at registration.
- **Operations that were rehearsed.** Prometheus alert rules with unit tests, including two that are
  about integrity rather than uptime: a decision served without its audit record, and replicas serving
  different binaries of one model. Nightly backups, and a restore that was run end to end onto an empty
  stack ([DEPLOY.md](DEPLOY.md)).
- **A demo that costs nothing while nobody is looking.** On Azure the VM deallocates itself after 30
  idle minutes and a serverless page starts it again on a click, about a minute later
  ([`deploy/azure/`](deploy/azure/)). Each side holds a managed identity that can do exactly one thing.

Measurements and the reasoning behind the runtime settings: [PERFORMANCE.md](PERFORMANCE.md).

## Run it

Requires Docker, JDK 17 or later and Node 22.

**The whole stack in containers**, served at `https://localhost` behind nginx with a self-signed
certificate:

```bash
cd frontend && npm ci && npm run build && cd ..
docker compose --profile app --profile edge up -d --build postgres redis neo4j backend nginx
docker compose logs backend | grep -A3 "Bootstrap admin"    # the first admin's one-time password
```

Sign in to the organization `legacy` as `admin` with that password. Staff accounts must enrol a second
factor on first sign-in. The demo portfolio of 400 loans is there from the start; open Portfolio Risk
and run a projection, or load a system in use (an analyst, borrower accounts, scores, cases, a
projection) with `ADMIN_PASSWORD=<that password> python backend/seed_demo_data.py`.

Two things to know for anything longer than a first look:

- **Give the stack fixed keys.** Without `JWT_SECRET` and `TOTP_ENCRYPTION_KEY` a new key is generated
  at every start, and after a restart nobody who enrolled a second factor can sign in. Put both in a
  file (any `openssl rand -base64 32` value each) and pass it with `docker compose --env-file <file> ...`.
- **Ports.** nginx publishes 80 and 443; if they are taken, set `NGINX_HTTP_PORT` and
  `NGINX_HTTPS_PORT`, and `APP_PUBLIC_URL` and `FRONTEND_URL` to the address you will open
  (`https://localhost:8443`, say).

**For development**, the data stores in Docker and the two apps on the host:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres redis neo4j
cd backend/risk-engine && ./mvnw spring-boot:run        # http://localhost:8080, API under /v1
cd frontend && npm install && npm run dev               # http://localhost:5173
```

Other run modes (replicas, production secrets, observability) are in [DEPLOYMENT.md](DEPLOYMENT.md); a
step-by-step deployment to a free server is in [DEPLOY.md](DEPLOY.md).

## Tests

```bash
cd backend/risk-engine && ./mvnw test          # unit and integration; the integration tests need Docker
cd frontend && npm run lint && npm test && npm run build && npm run check:bundle
```

The integration tests start Postgres, Redis and Neo4j in containers and drive the API through the real
filter chain as the unprivileged database role: authentication, tenant isolation, the models, the
asynchronous portfolio run, workflow rules, idempotency, the exports and the audit trail.
`TermStructureGoldenTest` holds the Java engine to an independent Python implementation; rerun
`backend/golden_term_structure.py` after exporting a model.

CI ([.github/workflows/ci.yml](.github/workflows/ci.yml)) runs the backend tests; the frontend lint,
unit tests and build with its bundle budget; a check of the deployment files (every Compose layout
parses, the alert rules pass their unit tests, the scripts pass shellcheck); and a k6 load test against
the containerised stack that fails the build on a latency or error-rate regression. A second workflow
([security.yml](.github/workflows/security.yml)) scans every commit for secrets, refuses pull requests
that add a dependency with a known high-severity vulnerability, and reports CodeQL and container-scan
findings to the Security tab.

## Rebuilding the data and the models

The raw data is not in the repository (about 9 GB of quarterly archives, obtained under your own Freddie
Mac account). Place the archives as `historical_data_<year>/historical_data_<year>Q<n>.zip` at the
repository root; the quarters expected are listed in `pipeline/config.py`.

```bash
pip install -r requirements.txt -r requirements-notebooks.txt
python -m pipeline.run                 # extract, clean, preprocess: loan-level and monthly panel
cd backend
python fetch_macro.py                  # macro series from FRED, cached
python build_survival_dataset.py      # the sample and its loan-month rows
python export_survival_model.py       # survival model, model card, backtests
python export_model.py                # PD model, calibration, model card, drift reference
python golden_term_structure.py       # reference outputs for the Java golden test
```

The model artifacts the service needs are committed under
`backend/risk-engine/src/main/resources/`, so none of this is required to run the application.

## Layout

```
backend/risk-engine/   Spring Boot service: the engines, the API, migrations, tests
backend/*.py           training and export scripts; experiments/ keeps the rejected ones
frontend/              React console
pipeline/              data pipeline from the raw archives
MODELS.md, PERFORMANCE.md, DEPLOY.md, DEPLOYMENT.md   the models, the measurements, deployment
nginx/, postgres/, observability/, deploy/, scripts/   deployment
loadtest/              k6 script used by CI
```
