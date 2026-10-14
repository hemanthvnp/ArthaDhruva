## 1. Combiner

- [x] 1.1 Add `survival/ScenarioWeights`: `resolve` (trim, upper-case, unknown name, negative, non-finite, empty, sum within 1e-6 of 1; scenarios not named get 0) and `combine` (left-to-right sum in scenario order, zero-weight terms skipped)
- [x] 1.2 `ScenarioWeightsTest`: every rejection with its message; weight 1 returns the scenario's figure bit-for-bit; 0.5 / 0.5 is the mean; key order does not change the bits; a NaN figure under weight 0 does not leak

## 2. Single loan

- [ ] 2.1 `SurvivalController.CompareRequest`: optional `weights`; `CompareResponse`: optional `weighted` (`@JsonInclude(NON_NULL)`), record `Weighted` per design D6
- [ ] 2.2 Compute `weighted` from the curves already built; map a weights error to 422 the way the controller maps other input errors
- [ ] 2.3 Integration tests (`ApiIntegrationTest`): no weights gives no `weighted` field; weights 0.5 / 0.3 / 0.2 give the weighted sums of the three scenarios' own figures and stage probabilities summing to 1; a bad sum and an unknown name give 422

## 3. Portfolio

- [ ] 3.1 Read how `PortfolioRiskService` returns the latest snapshot per scenario and whether the detail holds `eclCecl`; record the answer in design.md's open question
- [ ] 3.2 `POST /v1/risk/portfolio/weighted` in `PortfolioRiskController`: latest snapshots, same date and model version or 409, combine per design D6; 404 with no run, 422 for a scenario the run did not cover
- [ ] 3.3 Integration tests: weighted allowance equals the weighted stored allowances; 404, 422; a second organisation cannot weight the first one's run

## 4. Console

- [ ] 4.1 `api/types.ts` and `api/client.ts`: the request field, the two response shapes, the new call
- [ ] 4.2 A pure helper (`weights.ts`): parse percentages, sum, validity message, the 50 / 30 / 20 preset restricted to the scenarios present; `weights.test.ts`
- [ ] 4.3 `LifetimeRiskPage`: weights row on the comparison, weighted row in the table, the method line
- [ ] 4.4 `PortfolioRiskPage`: weights row, weighted allowance card, the run date it was built from
- [ ] 4.5 `npm run lint`, `npm test`, `npm run build`, `npm run check:bundle`

## 5. Docs and verification

- [ ] 5.1 `MODELS.md`: a "Probability-weighted figures" section: the method, why each scenario is staged on its own path, what the weights are not, the cross-reference to the stacking limitation
- [ ] 5.2 `README.md`: feature table row
- [ ] 5.3 Run the backend unit suite and the integration tests (Docker); confirm a comparison without weights is byte-identical to before on a recorded request
- [ ] 5.4 `/verify-all`; record what ran, passed, failed or was skipped
