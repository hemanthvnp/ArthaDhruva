## 1. Ground the design in the code

- [x] 1.1 Read `PortfolioRiskService`, `PortfolioRiskEngine`, the snapshot/run migrations under `src/main/resources/db`, and `AuditContext`/`@Metered` to settle open questions 2 and 4 in design.md; record the answers in design.md
- [x] 1.2 Write a Java measurement harness (a test class skipped unless a system property is set, using `SurvivalFixtures`) that reports boundary size per built-in scenario as a function of `shockBand` and `timingBandMonths`; record the figures and settle open question 1 (default bands) in design.md
- [x] 1.3 Update the `rough-set-decision-regions` spec with the frozen default bands and member caps

## 2. Core classifier and generator (Java)

- [x] 2.1 Create package `com.arthadhruva.riskengine.roughset` with `RoughSetMetric`, `Region`, `GranuleKind` and an immutable `DecisionOptions` record with bean validation (tolerance in [0, 0.5), member and band ranges)
- [x] 2.2 Implement the counter-based `splitmix64` member generator and `GranuleBuilder` (perturbation kind, clamping to `Scenario` ranges, member 0 = target, baseline handling)
- [x] 2.3 Implement the `scenario-set` granule from caller-listed built-in scenarios
- [x] 2.4 Implement `RegionClassifier`: k/n with variable-precision tolerance, min/median/max, width, region
- [x] 2.5 Implement `DecisionService` that builds the granule, calls `TermStructureEngine.projectAll` once per loan, extracts the chosen metric (`PD_12M`, `PD_LIFETIME`, `ECL_LIFETIME_RATE`), collects `extrapolations`, and returns the result record with the seed and method label
- [x] 2.6 Reject `regimeDraws` with a clear message while the posterior-draw capability is absent

## 3. Python reference and golden contract

- [x] 3.1 Write `backend/golden_rough_set.py`: independent splitmix64 generator, clamping rules and classifier, writing `src/test/resources/rough_set_golden.json` (seeds to member scenarios; metric vectors, thresholds and tolerances to regions)
- [x] 3.2 Run the new golden script (new file only; no model artifact is touched) and commit-ready fixture review
- [x] 3.3 Add `RoughSetGoldenTest` holding Java to the fixture (members exactly, derived widths to 1e-9)

## 4. Java tests for the capability

- [x] 4.1 `RegionClassifierTest`: certain high, certain low, boundary, variable-precision outlier case, tolerance validation
- [x] 4.2 Property tests: lower subset of upper, disjointness, monotone in threshold, larger tolerance never shrinks the decided set, `beta = 0` equals strict Pawlak
- [x] 4.3 `GranuleBuilderTest`: member 0 equals target, clamping, baseline behaviour, determinism for the same seed, order and thread independence
- [x] 4.4 `DecisionServiceTest` on `SurvivalFixtures`: `n = 1` equals the unperturbed engine result, extrapolation flag set under SEVERELY_ADVERSE with a stressed driver, region unchanged by the flag
- [x] 4.5 The survival card checksum and `TermStructureGoldenTest` expectations are untouched: no edit to the survival artifacts, card or golden file, and the existing `TermStructureGoldenTest` and `SurvivalModel` load (which refuses a checksum mismatch) pass in the full run

## 5. Single-loan API

- [x] 5.1 Add the optional `decision` and `seed` fields to `TermStructureRequest` and `CompareRequest` in `SurvivalController`, and the `decision` block to the responses; keep requests without `decision` byte-identical
- [x] 5.2 Map invalid options to 422 with the `error` shape via the existing exception handler
- [x] 5.3 Add a quantity overload to `UsageService.record` and record `DECISION_MEMBER` units equal to the granule size per decision call; record the model version in `AuditContext`
- [x] 5.4 Integration tests in `ApiIntegrationTest` style (no decision block unchanged, defaults echoed, 422 cases, label present, per-scenario regions on compare, metering scales with members)

## 6. Portfolio stage

- [x] 6.1 Add the optional `decision` block to the portfolio run request and a configured `loans x members` limit that refuses oversized runs before work starts
- [x] 6.2 Implement the regions stage in the run: per-loan classification with counter-based seeds, results merged in loan order, aggregates (region counts, exposure and share, segment boundary exposure, expected-loss range)
- [x] 6.3 Persist per-loan regions and aggregates in the snapshot; add a forward-only migration with nullable columns, RLS policies for any new tenant table, and no UPDATE/DELETE grant changes on audit
- [x] 6.4 Add the `region` filter to `/risk/portfolio/loans` and the region fields to the export
- [x] 6.5 Tests: regions partition the book, the unperturbed loss of the high loans lies inside the range, 1 thread equals many threads, oversized run refused, cross-tenant read returns nothing as the non-owner role, run without `decision` unchanged
- [x] 6.6 Record the cost of a portfolio run with regions in PERFORMANCE.md (measured on a development machine on one worker, 160 projections in 18.3 s = 114 ms each; the 1.5-CPU container was not run, and the note says so; the default limit was lowered from 4,000 to 2,000 projections because of it)

## 7. Frontend

- [x] 7.1 Add types and the API client calls for the `decision` block and the `region` filter
- [x] 7.2 Build the region badge, range-versus-threshold visual and method note for Lifetime Risk (risk-spectrum tokens, text and icon in addition to colour, no probability wording, extrapolation caution, loading and 422 states)
- [x] 7.3 Build the portfolio region view (split, expected-loss range, sorted boundary list with links and export) for Portfolio Risk, lazy-loaded if the bundle budget needs it
- [x] 7.4 Vitest tests for each region state, the extrapolation caution, 422 handling, and absence of confidence wording
- [x] 7.5 Run `npm run lint`, `npm test`, `npm run build` and `npm run check:bundle` in order and fix findings without raising the budget

## 8. Documentation

- [x] 8.1 Add a section to `MODELS.md` on the method, what it guarantees and does not (no coverage guarantee, dependence on the neighbourhood, the extrapolation caveat), the measured boundary sizes from task 1.2, and the contrast with conformal prediction
- [x] 8.2 Document the new request and response fields where the API is described in the README

- [x] 8.3 Add `notebooks/rough_set_boundary_regions.ipynb`: the single-loan, several-loan, portfolio, band-sensitivity and tolerance results with charts, run from the Python references (executed, with outputs saved)

## 9. Verify

- [x] 9.1 Run `./mvnw test` for the new and existing backend tests (integration tests need Docker) and report exactly which ran and their results
- [x] 9.2 Run the frontend checks from task 7.5 and report results
- [x] 9.3 `survival_model.json` and `term_structure_golden.json` were already modified at the start of the session and were not written by this change; no secrets or `historical_data_*` touched
