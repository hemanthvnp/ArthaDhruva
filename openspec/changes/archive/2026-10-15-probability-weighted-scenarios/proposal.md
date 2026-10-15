## Why

Every figure the platform reports is conditional on one scenario: a loan or a portfolio has a baseline allowance, an adverse one and a severely adverse one, side by side, and the baseline assumes flat unemployment, flat rates and +3% house prices for the life of the loan. IFRS 9 asks for a single allowance that is "an unbiased and probability-weighted amount … determined by evaluating a range of possible outcomes"; today a user has to do that weighting in a spreadsheet. Since the 2026-10-13 re-export the stressed scenarios respond to falling house prices, so a weighted figure now carries real information from them.

## What Changes

- A caller may give a weight to each scenario of a comparison (weights are non-negative and sum to 1). The response then carries one extra block: the probability-weighted 12-month PD, lifetime PD, 12-month ECL, lifetime ECL, IFRS 9 allowance and CECL allowance, the probability of each stage, and the weights used.
- Single loan: `POST /v1/risk/term-structure/compare` accepts an optional `weights` object.
- Portfolio: a new read-side endpoint, `POST /v1/risk/portfolio/weighted`, weights the scenarios of the latest portfolio run. No new run, no new projection, no schema change: it is arithmetic over stored per-scenario results.
- Console: a weights row on the Lifetime Risk comparison and on the Portfolio Risk page, with an illustrative 50 / 30 / 20 preset, and the weighted figures shown beside the scenarios.
- No weights, no change: a request without `weights` returns exactly what it returns today, and nothing in `TermStructureEngine`, the survival artifact, its card or its golden fixture is touched.
- `MODELS.md` states the method and what it is not (see the design: each scenario stages loans on its own path; the weights are the caller's judgement, not an estimate).

## Capabilities

### New Capabilities

- `scenario-weighted-ecl`: probability-weighted PD, ECL and stage probabilities across the scenarios of a single-loan comparison or of the latest portfolio run, with validation of the weights, the API surface and the console display.

### Modified Capabilities

<!-- none: openspec/specs/ holds no archived capability whose requirements change -->

## Impact

- Backend (`survival` package): one new pure class for validating weights and combining per-scenario results; `SurvivalController` (optional request field, optional response field); `PortfolioRiskController` (one new endpoint reading stored snapshots). No migration, no engine change, no model artifact change.
- Frontend: `api/types.ts`, `api/client.ts`, `LifetimeRiskPage.tsx`, `PortfolioRiskPage.tsx`, one small pure helper with a test. No new dependency; the bundle budget is unchanged.
- Docs: `MODELS.md` (method and limits), `README.md` (feature table).
- Metering and audit: the single-loan call is already metered and audited; the portfolio endpoint is a read and follows the other portfolio reads.
