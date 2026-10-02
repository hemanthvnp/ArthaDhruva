# Models

What each model predicts, how it was validated, what it gets wrong, and how the engine turns hazards
into expected credit loss. Every figure below is read from the artifacts in
`backend/risk-engine/src/main/resources/` (`survival_model.json`, `pd_model_card.json`,
`survival_backtest.json`); the same figures are served at `GET /v1/models` and shown on the Model
Governance page.

## Inventory

| Model | Used for | Tier | Validated |
|---|---|---|---|
| Competing-risks survival (`survival_model.onnx`) | Monthly default and prepayment hazards behind PD term structures, lifetime ECL and stress scenarios | 1 | Yes |
| Origination PD (`model.onnx`) | Default within 24 months of origination; explanations and reason codes | 1 | Yes |
| Loss given default (`lgd_model.json`) | Fitted LGD, the floor of the collateral-based LGD | 1 | No |
| Macro regime (`hmm_regime.json`) | Calm / stressed regime and its transition matrix | 1 | No |
| Portfolio loss distribution | Value at risk, expected shortfall, tail contributions | 1 | No (analytical; see below) |
| Early warning, delinquency trajectory | Analyst decision support | 2 | No |

"Validated" means the model ships a card with out-of-time validation. The others are listed with their
checksum and a plain statement of what is missing, because an inventory that lists only the
well-documented models is not an inventory. At start-up the service hashes each artifact and refuses to
run one that does not match the checksum its card declares.

## Data

Freddie Mac Single-Family Loan-Level Dataset, fixed-rate loans. A stratified random sample of 40,000
loans per origination quarter, 2017 to 2026, each followed through its full monthly performance history.

- **Default**: the first month a loan is 90 or more days past due, an REO acquisition, or an adverse
  zero-balance termination (short sale, foreclosure, note sale).
- **Prepayment**: a clean zero-balance payoff.
- **Censored**: still active when the data ends, or removed for a non-credit reason.
- **Macro drivers** (FRED): state house price index (FHFA all-transactions, quarterly, interpolated
  log-linearly to months), state unemployment rate, and the Freddie Mac PMMS 30-year fixed rate.

Two data hygiene rules matter more than any hyperparameter:

1. **The last month of each data release is left out.** Panels of different vintages end at different
   releases, and zero-balance events are not reliably dated in a release's final month: the 2017 to 2019
   panels show no prepayments at all in 2026-03 and twice the usual number in 2025-09. Those two months
   are excluded from fitting and evaluation.
2. **Loans are split by a hash of the loan id**, not by row. Buckets 0 and 1 are never trained on (they
   are the backtest population), bucket 2 is for early stopping, the rest train. A loan's months never
   straddle the split.

No-event loan-months are subsampled at 10% with weight 10, the standard case-control correction for a
rare-event hazard model: estimates stay unbiased and the training set shrinks about eightfold.

## Competing-risks survival model

A discrete-time multinomial hazard: for each loan-month at risk the model gives the probability of
default, of prepayment and of continuing. Default and prepayment compete, because a loan that has
prepaid can no longer default; lifetime PD has to come from both hazards together.

LightGBM multiclass, 23 features: ten origination numerics (including the rate spread to the market
rate at lock, not the raw note rate, which moves with the rate cycle), six categoricals, and seven
time-varying drivers: loan age, macro regime, rate incentive (note rate minus market rate), state
unemployment and its 12-month change, the 12-month change in state house prices, and mark-to-market
LTV.

### Protocol

1. **Validation fit** on loan-months before 2023-01; out-of-time metrics on everything after.
2. **Does a drift overlay carry forward?** A hazard scalar estimated on the first 18 out-of-time months
   is applied to the months after them. A cause is eligible for an overlay only if that moved the later
   months closer to what happened.
3. **Production fit** on every month. The overlay is re-estimated on held-out loans over the latest 24
   months in which every vintage reports, and applied only where the cause is eligible and the ratio is
   more than two standard errors from 1.
4. **Backtests** on the held-out loans.

### Results

Out of time (fitted on 2017-01 to 2022-12, tested on 2,429,834 loan-months from 2023-01):

| Metric | Model | Benchmark (hazard table by loan age) |
|---|---|---|
| Weighted log loss | 0.03603 | 0.04671 |
| Default AUC | 0.7905 | |
| Prepayment AUC | 0.6618 | |

The overlay test (scalar estimated on 2023-01 to 2024-06, judged on 2024-07 to 2025-08):

| Hazard | Scalar tried | Realized / predicted before | After | Carries forward |
|---|---|---|---|---|
| Default | x1.252 | 1.467 | 1.172 | Yes |
| Prepayment | x1.013 | 0.977 | 0.965 | No, it moved further away |

Default drift persists; prepayment drift follows the rate cycle and reverses, so correcting it makes
later months worse. In production (held-out loans, 2023-09 to 2025-08), realized over predicted default
is 1.049 +/- 0.022 on 2,293 events, more than two standard errors from 1, so the default hazard is
scaled by 1.0495. Prepayment, at 0.986 +/- 0.007, is not eligible and is left alone.

Vintage backtest on held-out loans: the model's cumulative incidence, given the macro path that actually
happened, against the Aalen-Johansen estimate of what the loans did.

| Vintage | Loans | Months observed | Default, predicted / realized | Prepayment, predicted / realized |
|---|---|---|---|---|
| 2017 | 32,394 | 98 | 6.14% / 5.55% | 71.5% / 71.2% |
| 2018 | 31,802 | 86 | 5.58% / 5.32% | 76.9% / 76.7% |
| 2019 | 31,779 | 74 | 5.75% / 5.50% | 68.9% / 68.1% |
| 2020 | 31,971 | 56 | 2.32% / 2.25% | 35.6% / 34.9% |
| 2021 | 32,016 | 44 | 1.81% / 1.94% | 15.5% / 15.5% |
| 2022 | 32,097 | 32 | 2.63% / 3.02% | 13.4% / 13.4% |
| 2023 | 32,091 | 20 | 1.56% / 1.65% | 14.6% / 14.0% |
| 2024 | 31,899 | 8 | 0.57% / 0.47% | 4.3% / 4.7% |

Month by month the model follows the June 2020 forbearance spike (180 bp realized, 191 bp predicted).
By risk decile on held-out loans the default hazard is within about 15% of realized from the fourth
decile up; the lowest decile is over-predicted (0.56) and the riskiest 1% of loan-months is
over-predicted (0.82).

### What was tried and rejected

`backend/experiments/survival_path_features.py` keeps the evidence. Two textbook prepayment drivers were
tested with the same hyperparameters and left out, for reasons the headline metrics hide:

| Features | OOT log loss | Default AUC | Prepay AUC | Prepay predicted / actual |
|---|---|---|---|---|
| Base (23 features) | 0.036446 | 0.7918 | 0.6647 | 1.00 |
| + months in the money (burnout) | 0.036460 | 0.7916 | 0.6665 | 1.00 |
| + calendar month | 0.036404 | 0.7925 | 0.6692 | 1.13 |
| + both | 0.036397 | 0.7907 | 0.6711 | 1.09 |

- **Calendar month** absorbs the June to July 2020 forbearance spike as a permanent June effect: the
  fitted default hazard is 2.7 times higher in June than in May in every year, so a forward projection
  would print that spike every summer. It also over-predicts out-of-time prepayments by 13%.
- **Burnout** does not improve the out-of-time fit, and what it learns in sample is a ramp (the
  2020 to 2021 refinancing wave building up), not burnout. One refinancing wave cannot identify it.

An isotonic recalibration of the hazards was also implemented and removed: it distorted the response to
stress scenarios, and the refit on every month is already calibrated.

(The table was produced before the release-final months were excluded, so its base row differs slightly
from the results above.)

### Known limitations

- Default is the first time a loan is 90 days past due. Most such loans later cure or are repaid from a
  sale, which is why loss given default is small, and why COVID-19 forbearance appears as a wave of
  defaults.
- The data contains no housing downturn: over the training rows the 12-month change in state house
  prices was never below about -1%. The hazards' response to falling prices is held at the edge of that
  range; in a house-price scenario the extra loss comes from re-marking the collateral, not from the
  hazard model. Every projection reports the months in which a driver leaves the training range
  (loan age 0 to 96 months, rate incentive -4.75 to +2.53 points, unemployment 2.2% to 16.1%, its
  12-month change -9.1 to +12.0 points, house prices -1.1% to +28.1% over 12 months, mark-to-market LTV
  12.6% to 95.5%).
- Loans are observed for at most about nine years; beyond that the dependence on loan age is held at its
  last fitted level.
- One stress episode (2020-06 to 2022-06) defines the stressed regime, and the regime chain is estimated
  from 99 months. An expected stress spell of 37 months and a long-run stressed share of 35% are
  uncertain, and the baseline lifetime PD inherits that uncertainty.
- The model conditions on origination attributes and macro drivers, not on current delinquency status,
  so it understates the risk of a loan that is already past due.
- The property's state is a feature. Geography can stand in for protected characteristics; a lender
  using these figures in credit decisions must test for disparate impact on its own portfolio.
- Trained on agency-conforming fixed-rate mortgages; not validated for other products.

## Origination PD model

Probability of default within 24 months of origination. LightGBM with monotone constraints (risk can
only fall as credit score or the number of borrowers rises, and only rise with DTI, LTV, CLTV and the
rate spread), followed by a binned PAV calibration. The constraints make the model defensible to a
reviewer and keep reason codes coherent: a higher credit score can never count against a borrower.

A loan is included only if its 24-month outcome is known. A fixed horizon gives the probability an
unambiguous meaning; the earlier "ever defaulted, among loans observed for 24 months" label silently
dropped every loan that defaulted and was liquidated early, the early-payment defaults a scorecard most
needs to learn.

| | Loans | AUC | KS | Predicted | Realized |
|---|---|---|---|---|---|
| Out of time: fitted on 2017 to 2020, tested on 2021 to 2023 | 428,050 | 0.762 | 0.404 | 2.84% | 1.82% |
| In time: held out of the production fit and its calibration | 106,938 | 0.759 | 0.398 | 2.30% | 2.31% |

Discrimination is stable from one out-of-time vintage to the next (AUC 0.756, 0.752, 0.759). The level
is not: fitted on vintages that lived through 2020, the model over-predicts in a calm period. That is
the reason the survival model, which sees the macro path, carries the forward-looking figures, and this
model is used for ranking, explanation and reason codes.

**Explanations.** Baseline Shapley values against a reference loan (the training population's median
and most common values), computed exactly when at most ten features differ and otherwise from 64
antithetic orderings in one batched model call. Within every ordering the credits sum exactly to the
difference between the loan's PD and the reference loan's, so the attribution is always complete; the
orderings are seeded from the loan, so a loan always gets the same explanation.

**Reason codes.** The factors that raise the loan's risk the most, at most four. A factor is a reason
only if it adds at least 0.01 percentage points of PD and at least a twentieth of what the principal
reason adds.

**Drift.** `GET /v1/models/pd_24m/drift` compares the portfolio with the training population input by
input (population stability index). A sample of n loans drawn from exactly the training distribution
still shows about (bins - 1) / n of PSI, so each PSI is reported with that noise floor and judged after
subtracting it; otherwise every small portfolio would look like it was drifting.

## From hazards to expected credit loss

`survival.TermStructureEngine` projects one loan month by month under a scenario.

**Regimes, exactly.** The hazards depend on the macro regime, which follows a two-state Markov chain.
Averaging the hazards over regime probabilities would be wrong, because survival is a product along the
path. The engine carries `a_k(r)`, the joint probability of "still active at the end of month k and in
regime r":

```
b_k(r')   = sum_r a_{k-1}(r) P[r][r']        (all mass on the stressed regime while a scenario forces it)
default_k = sum_r b_k(r) h_d(k, r)           prepay_k = sum_r b_k(r) h_p(k, r)
a_k(r)    = b_k(r) (1 - h_d(k, r) - h_p(k, r))
```

That is the exact expectation over all 2^T regime paths in O(T), with no simulation noise.

**Loss.** Exposure is the scheduled balance at the start of each month. Loss given default is the fitted
LGD, floored each month by the shortfall a forced sale would leave:
`1 - (1 - haircut) x 100 / LTV - mortgage insurance`, with LTV re-marked along the scenario's house-price
path and a 15% liquidation haircut. A price shock therefore raises severity as well as frequency. Losses
are discounted at the note rate.

**Staging (IFRS 9).** Stage 3 at 90 or more days past due. Stage 2 at 30 or more days past due, or on a
significant increase in credit risk: the PD over the next 12 months is at least twice, and at least 0.5
percentage points above, the PD for the same 12 months as projected when the loan was originated.
Stage 1 carries 12-month ECL, stage 2 lifetime ECL, stage 3 the loss on the outstanding balance. CECL is
lifetime ECL from day one.

**Scenarios.**

| Scenario | Regime | Unemployment | House prices | Rates |
|---|---|---|---|---|
| Baseline | Follows the fitted chain | Flat | +3% a year | Flat |
| Adverse | Stressed for 12 months | +3 points over 12 months, fading over 36 | -10% over 12 months, then +2% a year | Flat |
| Severely adverse | Stressed for 24 months | +5 points over 12 months, fading over 48 | -25% over 24 months, then +2% a year | Flat |
| Rates +200 bp / -200 bp | Follows the chain | Flat | +3% a year | Shifted |

The two stress scenarios are illustrative, in the style of supervisory scenarios, not a regulator's.

One behaviour of the rate scenarios is worth knowing before reading their results. In the data, loans
whose note rate is far below the market rate default less: their borrowers are locked into cheap debt.
The model has learned that, so a +200 bp shock lowers the default hazard as well as slowing prepayment,
and a loan's lifetime PD can fall even though it stays on the book longer. That is an association
observed over 2017 to 2026, not a causal estimate of what a rate shock does.

## Portfolio

`survival.PortfolioRiskEngine` projects every loan under each scenario and adds the results up: the
allowance, staging, a ten-year run-off and concentrations. Above 5,000 loans a simple random sample is
projected and scaled, and the result carries the standard error of the lifetime ECL. The baseline keeps
its loan-by-loan results, which are listed and exported, and which feed the loss simulation.

**Loss distribution.** A one-factor Gaussian copula: each loan defaults when a blend of one common
factor and its own shock falls below a threshold set by its PD. `cvar.CvarEngine` simulates it with:

- **importance sampling**: the common factor's mean is shifted toward bad years by
  `-inverse normal(confidence) x share of loss variance the factor explains`, so far more scenarios land
  in the tail, and each is reweighted by its likelihood ratio;
- **confidence intervals** from 20 independent sections of the simulation (valid for the weighted
  estimator, with no resampling);
- **Euler contributions**: each loan's share of expected shortfall, by replaying the tail scenarios
  from their seeds rather than storing every default; the shares add up to it exactly;
- the **closed-form VaR** of an infinitely fine-grained portfolio (Basel's formula) beside the simulated
  one, the gap being what concentration in individual loans adds.

On the portfolio, each loan defaults with its 12-month PD and then loses its 12-month ECL divided by
that probability. Taking the severity from the ECL carries the amortization, the discounting and the
month-by-month LGD into the simulation and makes the simulated mean exactly the 12-month ECL: expected
and unexpected loss are statements about the same loss. The asset correlation (0.15 by default, the
Basel value for residential mortgages) is an input, not an estimate from this data.

## How the numbers are checked

| Check | Where |
|---|---|
| The Java engine reproduces an independent Python implementation of the same projection to 1e-6 | `TermStructureGoldenTest`, `backend/golden_term_structure.py` |
| The regime recursion equals brute-force enumeration of all 2^13 regime paths to 1e-13 | `TermStructureEngineTest` |
| Probability is conserved every month: active + defaulted + prepaid = 1 | `TermStructureEngineTest` |
| The loss simulation matches the exact finite-portfolio distribution, and the closed-form limit for a granular portfolio | `CvarEngineTest` |
| Tail contributions sum to expected shortfall; importance sampling more than halves the interval | `CvarEngineTest` |
| The portfolio totals equal the sum of the loans, on any number of threads, to the bit | `PortfolioRiskEngineTest` |
| The ONNX graph reproduces the LightGBM booster (3.7e-7 absolute) | `export_survival_model.py` |

## Reproducing the artifacts

```bash
cd backend
python fetch_macro.py              # FRED series, cached locally
python build_survival_dataset.py   # the sample and its loan-month rows
python export_survival_model.py    # survival model, card, backtest, macro snapshot
python export_model.py             # PD model, calibration, card, drift reference
python golden_term_structure.py    # reference outputs for the Java golden test
```

Exports are reproducible bit for bit: LightGBM runs with `deterministic=True, force_row_wise=True` on
sorted rows, split thresholds are rounded to values a 32-bit float holds exactly, and the ONNX graph
carries a fixed name (the exporter's default is random). The same data gives the same checksum.
