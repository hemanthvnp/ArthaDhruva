# Score-change note: rate_spread rounding aligned with training

Status: implemented and verified locally. **Not committed, not deployed.** Deployment, and any production score change, needs separate review and explicit authorization.

## Change

`rate_spread` (note rate minus the PMMS 30-year rate at the rate-lock month) is now rounded the way the training pipeline rounds it:

1. subtract in float64,
2. cast the result to float32 (`credit_common.with_spread` stores the column as Float32),
3. round that float32 to the 3-decimal grid (`numpy.rint`, half to even, in float64) and cast back to float32.

Before, Java skipped step 2 and rounded the float64 value. The two sequences differ only on an exact tie on the grid, where the float32 representation error, not half-to-even, decides the direction. Example: 6.5 minus 6.9175 is -0.41750000000000043 in float64; rounding the double gives -0.418, while training's float32-first sequence gives -0.417 (the value the model was trained on).

Why it matters: the PMMS series has 4 decimals, so ties are common (about 14-16% of note-rate/month pairs in the sweeps below).

## Affected services

Both read the same feature through the same helper, `Quantizer.roundAfterFloat32` (new):

| Service | Where |
|---|---|
| Origination PD (`score.ModelService`) | `buildFeatureVector`; also reaches `ExplanationService` and drift scoring through the same vector |
| Regime-aware survival / term structure (`survival.SurvivalModel.template`) | the loan's static row, used by `TermStructureEngine` |

Not affected: the early-warning model (no spread feature), reported `rateSpread` values (unrounded), model artifacts, calibration, checksums.

## Expected scoring change

A loan whose spread lands on a tie gets the feature value one grid step (0.001) different from before; every other loan is bit-identical. Whether that moves the score depends on whether a tree split sits between the two grid points.

Investigation evidence (sweeps, **not** a universal bound on production impact):

- Scope: in a sweep of 317,223 (note rate, PMMS month) pairs, every pair whose feature value changed sat within 2e-12 of an exact tie. No non-tie input differed.
- PD, realistic population (PMMS months 2015+, note rates on the 1/8 grid from 3 to 9, five varied loans): 33,360 scorings; 4,740 (14.2%) had a different spread feature; **11 (0.033%) changed the calibrated PD**, by at most **0.053 percentage points** (4.4% relative), mean 0.018 pp among those changed.
- PD, tie-only sweep over a broader grid: 3,887 differing inputs, 7 changed the PD, maximum 0.039 pp.
- Survival: the impact on lifetime PD / ECL was **not measured**. None of the 11 golden survival cases lands on a tie.

## Verification (local)

- Java vs numpy: `Quantizer.roundAfterFloat32(x, 3)` matched `np.float32(np.rint(float(np.float32(x)) * 1000) / 1000)` bit for bit on 717,231 values (sweep over rate pairs, random doubles, random 4-decimal values and edge cases; 59,817 of them differ from the double route). Tie-breaking is Java's `Math.rint` on both sides: half to even, applied to the float32 value widened to double. The check used a temporary test, since removed.
- Unit and golden tests, run with `./mvnw -q test -Dtest=...`: `QuantizerTest` (6), `PdModelParityTest` (6), `EarlyWarningParityTest` (5), `FeatureContractTest` (4), `IsotonicCalibratorTest` (5), `FeatureVectorBuilderTest` (5), `TermStructureGoldenTest` (2), `TermStructureEngineTest` (13), `ExplanationServiceTest` (5): all pass.
- Fixtures: `golden_pd_model.py` regenerated `pd_model_golden.json` (the `spread-tie` case now expects -0.417; its probabilities are unchanged). `golden_term_structure.py` regenerated `term_structure_golden.json` and it is **byte-identical** to the committed file (`git status` shows it unmodified).
- Full backend suite: `./mvnw test` (Docker up), 155 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS.
- Model artifacts, checksums and the reproducible-export guarantees are untouched (no export was run).

## Regression coverage

- `PdModelParityTest.theRateSpreadIsNarrowedToFloat32BeforeItIsRounded` replaces the test that pinned the double-first behaviour.
- `QuantizerTest.roundAfterFloat32FollowsTheTrainingSequenceOnTies` covers five ties that resolve differently after the cast, with numpy-derived expectations; `roundAfterFloat32AgreesWithTheDoubleRouteAwayFromTies` shows the two routes agree away from ties.

## Known related items, out of scope here

- The same float32-then-round pattern exists in training for other computed survival features (`rate_incentive` in `with_macro`; unemployment, `hpi_change_12m` and `mtm_ltv` in `with_state_macro`), while the Java engine and `golden_term_structure.py` round those from the double (`Quantizer.value`). They were not part of this approval and were not measured; changing them would alter the survival golden, so they need their own review.
- The early-warning `calibratedProbabilities` batch path still throws `ClassCastException` (ZipMap output); it has no caller and stays documented until batch scoring is introduced.

## Files changed for this item

Code: `ml/Quantizer.java`, `score/ModelService.java`, `survival/SurvivalModel.java`. Docs: comments in `Quantizer`, `ModelService`, `credit_common.py` (comments and docstring only). Reference scripts: `golden_pd_model.py`, `golden_term_structure.py`. Fixture: `pd_model_golden.json`. Tests: `QuantizerTest`, `PdModelParityTest`.
