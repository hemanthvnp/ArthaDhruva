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
The survival model is also fitted on the 2006 to 2008 vintages (40,000 loans a quarter, 480,000 loans),
followed to 2016-12: the only housing downturn in the data. Their months end where the 2017 to 2026
sample's begin. The PD model, the regime series and the loan catalogue use 2017 to 2026 only.

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

Out of time (fitted on 2006-01 to 2022-12, tested on 2,429,834 loan-months from 2023-01):

| Metric | Model | Benchmark (hazard table by loan age) |
|---|---|---|
| Weighted log loss | 0.03598 | 0.04592 |
| Default AUC | 0.7943 | |
| Prepayment AUC | 0.6642 | |

The overlay test (scalar estimated on 2023-01 to 2024-06, judged on 2024-07 to 2025-08):

| Hazard | Scalar tried | Realized / predicted before | After | Carries forward |
|---|---|---|---|---|
| Default | x1.208 | 1.362 | 1.127 | Yes |
| Prepayment | x0.989 | 0.942 | 0.952 | Yes, narrowly |

Default drift persists. Prepayment drift follows the rate cycle and can reverse: in the previous export
(2017 to 2026 only) the same test moved later months further away (0.977 to 0.965) and no scalar was
applied; in this one it passes by one point, so treat the prepayment scalar as the weaker of the two. In
production (held-out loans, 2023-09 to 2025-08), realized over predicted default is 1.060 +/- 0.022 on
2,293 events, so the default hazard is scaled by 1.0605; prepayment is 0.981 +/- 0.007 on 19,251 events
and is scaled by 0.981. Both are more than two standard errors from 1.

Vintage backtest on held-out loans: the model's cumulative incidence, given the macro path that actually
happened, against the Aalen-Johansen estimate of what the loans did.

| Vintage | Loans | Months observed | Default, predicted / realized | Prepayment, predicted / realized |
|---|---|---|---|---|
| 2006 | 32,202 | 119 | 14.19% / 13.49% | 78.4% / 78.9% |
| 2007 | 32,149 | 107 | 15.75% / 15.61% | 76.2% / 75.5% |
| 2008 | 31,776 | 95 | 9.69% / 9.16% | 81.8% / 82.0% |
| 2017 | 32,394 | 98 | 6.24% / 5.55% | 71.5% / 71.2% |
| 2018 | 31,802 | 86 | 5.59% / 5.32% | 77.2% / 76.7% |
| 2019 | 31,779 | 74 | 5.72% / 5.50% | 68.9% / 68.1% |
| 2020 | 31,971 | 56 | 2.32% / 2.25% | 35.8% / 34.9% |
| 2021 | 32,016 | 44 | 1.81% / 1.94% | 15.5% / 15.5% |
| 2022 | 32,097 | 32 | 2.64% / 3.02% | 13.5% / 13.4% |
| 2023 | 32,091 | 20 | 1.54% / 1.65% | 14.6% / 14.0% |
| 2024 | 31,899 | 8 | 0.59% / 0.47% | 4.4% / 4.7% |

The 2006 to 2008 rows are loans held out of the fit, followed to 2016-12; the years they cover are years
the model trained on, so they show fit on unseen loans, not on an unseen crisis.

Month by month the model follows the June 2020 forbearance spike (180 bp realized, 193 bp predicted) and
the 2009 to 2010 peak (47 bp realized and predicted in 2010-01). By risk decile on held-out loans over
the latest 24 months, realized over predicted default is between 0.94 and 1.14 in seven deciles; the
lowest decile is over-predicted (0.82), the fourth and fifth are under-predicted (1.27 and 1.28), and the
riskiest 1% of loan-months is over-predicted (0.81).

### Why the 2006 to 2008 vintages are in the fit

Until 2026-10-13 the model was fitted on 2017 to 2026 only, whose one stress is COVID-19 forbearance: an
income shock with rising house prices. Three experiments under `backend/experiments/` decided the change;
every figure is on loans no model trained on.

**Neither crisis teaches the other** (`cross_crisis.py`). The 2017 to 2026 model, scored on the 2006 to
2008 vintages, under-predicts default by 18% in 2008, 40% in 2009 and 69% in 2010 (12,259 events; AUC
falls from 0.80 to 0.65 as the crisis ages), and 56% of those loan-months have a house-price change below
anything it was fitted on. A model fitted only on the 2006 to 2008 vintages over-predicts 2017 to 2020 by
a factor of 2.5 and misses COVID-19 by half.

**The pooled fit is better where both can be measured** (`pooled_candidate.py`, `era_candidate.py`; the
shipped out-of-time protocol, nothing from 2023-01 onwards seen):

| | 2017 to 2026 only | Pooled (shipped) | Pooled + pre-2009 flag |
|---|---|---|---|
| Out-of-time log loss | 0.036028 | 0.035984 | 0.035989 |
| Default AUC / prepayment AUC | 0.7905 / 0.6618 | 0.7943 / 0.6642 | 0.7948 / 0.6625 |
| Realized / predicted default, out of time | 1.35 | 1.28 | 1.38 |
| Same, score < 680 and LTV >= 90 (812 events) | 1.11 | 1.06 | 1.11 |
| Same, where the model predicts 4% a month or more (held-out, before 2023) | 0.75 | 0.82 | 0.79 |
| Default hazard at a -20% house-price change / at 0% | 1.00 | 1.30 | 1.26 |
| Default hazard at mark-to-market LTV 130 / at 100 | 1.00 | 1.30 | 1.26 |
| Realized / predicted default, 2006 to 2008 loans, by year 2007 to 2013 | 1.36 to 2.57 | 0.89 to 1.05 | 0.89 to 1.01 |

The log-loss difference is small (-0.000044) and its loan-level bootstrap interval excludes zero
(-0.000052 to -0.000037 over 842,867 loans). The refit of the previous model reproduced its card's log
loss to every digit, so the comparison is like for like.

**It is not wrong about the crisis.** For the riskiest loans the model can be checked against (2007
vintage, score below 660, LTV 95 or more, in FL, CA, AZ or NV; 71 held-out loans) it predicts 26% default
within 12 months, 49% within 24 and 78% within 8 years; they realized 31%, 55% and 83%.

**It was exported, withdrawn the same day and reinstated**, and the reason is worth keeping. The first
export moved stressed figures a great deal (a new loan with score 640 and LTV 95 under SEVERELY_ADVERSE:
12-month PD 9% to 56%), which looked unbelievable, and two quick checks seemed to confirm a fault: the
model over-predicts its highest hazards, and recent first-year loans with score below 660 and LTV 95 or
more realize 0.71 of what it predicts. Neither held. The first check had not been run on the previous
model, which is worse there (0.75 against 0.82). The second rested on 20 events; on 812 out-of-time events
the pooled fit is the better calibrated. And loans of that kind did default at 31% to 38% a year in
2008 to 2009.

**A flag for loans originated before 2009 was tried and not used.** It does what it was meant to (the
same risky new loan's baseline 12-month PD is 10% with the flag, 17.5% without, 9% in the previous
model, hazards unscaled), but it improves nothing that can be measured out of time, and it would add an
input the Java engine has to build. No segment narrow enough to settle which baseline is right has
enough events.

What changed in the engine's outputs (the golden cases, previous model to this one):

| Loan, scenario | 12-month PD | Lifetime PD | Lifetime ECL |
|---|---|---|---|
| Prime, baseline | 0.18% to 0.31% | 2.25% to 2.72% | 38 to 51 |
| Prime, severely adverse | 1.60% to 1.08% | 6.09% to 7.25% | 561 to 997 |
| Seasoned, 45 days past due, adverse | 0.92% to 1.26% | 3.72% to 4.79% | 64 to 88 |
| Risky, baseline | 6.73% to 8.31% | 28.7% to 29.4% | 451 to 488 |
| Risky, severely adverse | 14.6% to 35.2% | 56.4% to 69.7% | 891 to 1,336 (stage 1 to 2) |
| High-LTV investor, severely adverse | 14.4% to 31.7% | 54.5% to 73.7% | 36,668 to 58,109 |

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

(The table was produced on the 2017 to 2026 sample alone and before the release-final months were
excluded, so its base row differs from the results above.)

### Known limitations

- Default is the first time a loan is 90 days past due. Most such loans later cure or are repaid from a
  sale, which is why loss given default is small, and why COVID-19 forbearance appears as a wave of
  defaults.
- The only housing downturn in the data is the one the 2006 to 2008 vintages lived through (their months
  to 2016-12). The response to falling prices and to loans under water rests on that one downturn and on
  loans originated just before it, and has not been tested on a second. Every projection reports the
  months in which a driver leaves the training range (loan age 0 to 107 months, rate incentive -4.67 to
  +3.34 points, unemployment 2.3% to 15.7%, its 12-month change -8.1 to +11.8 points, house prices -20.2%
  to +27.4% over 12 months, mark-to-market LTV 13.1% to 121.6%).
- A 2006 loan and a 2024 loan with the same score, LTV and DTI were not the same risk: underwriting
  changed after 2008, and the macro drivers of 2006 look like today's, so the model cannot tell the two
  apart. Out of time it is not over-predicting recent risky loans (1.06 on 812 events), but for a single
  extreme new loan the baseline may lean on 2006 experience, and no segment that narrow has the events to
  check it.
- A scenario's forced stress months set the COVID-type regime, and a fall in house prices then acts on
  top of it. No training month contains both (the regime series starts in 2017, so 2008 to 2012 is not
  labelled stressed). For a new loan with score 640 and LTV 95 under SEVERELY_ADVERSE the price and
  unemployment path alone gives a 12-month PD of 44%; the forced regime takes it to 56%.
- About 45% of the 2006 to 2008 loan-months carry an origination channel code (third party, not
  specified) that the category encoding, built from 2017 to 2026, does not know; they are fitted as
  "missing".
- Loans are observed for at most about eleven years (the 2006 to 2008 vintages; the 2017 to 2026 sample
  for nine), and 99.5% of the training months lie within 107 months of origination; beyond that the
  dependence on loan age is held at its last fitted level.
- The model is about twice the size of the previous one (1,392 boosting rounds against 596, ONNX 34.8 MB
  against 16.4 MB) and a projection costs about twice as much; see PERFORMANCE.md.
- One stress episode (2020-06 to 2022-06) defines the stressed regime, and the regime chain is estimated
  from 99 months. An expected stress spell of 25 months and a long-run stressed share of 26% are
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

One behaviour of the rate scenarios is worth knowing before reading their results. A rate shock acts
mostly through prepayment. For a new prime loan, +200 bp cuts lifetime prepayment from 96% to 84%, so
more of the loan's life is exposed and lifetime PD rises from 2.7% to 3.8%; -200 bp prepays it almost at
once and lifetime PD falls to 0.5%. The 12-month default hazard moves little and not in one direction:
0.31% to 0.28% for that loan at +200 bp, but 0.33% to 0.40% for a seasoned loan at a 3% note rate, which
also rises (to 0.45%) at -200 bp. Under the previous model, fitted on 2017 to 2026 only, a +200 bp shock
lowered the default hazard (borrowers locked into cheap debt defaulted less); with the 2006 to 2008
vintages in the fit that no longer holds across loans. These are associations in the data, not causal
estimates of what a rate shock does.

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

## Probability-weighted figures

IFRS 9 asks for one allowance weighted over a range of outcomes; the scenarios above each give their own. A
caller may give each scenario of a comparison a probability (non-negative, summing to 1) and receive the
weighted figures beside the scenarios': `weights` on `POST /v1/risk/term-structure/compare` for one loan, and
`POST /v1/risk/portfolio/weighted` for the latest portfolio run, which is arithmetic over the stored results and
projects nothing again.

Every weighted figure is `sum_s w_s x figure_s`, each scenario's figure exactly as reported for it
(`survival.ScenarioWeights`). For the allowance this means each scenario stages the loan on its own path and its
allowance is then weighted; the response also gives the probability of each stage (the total weight of the
scenarios in which the loan is in that stage, or for a portfolio the weighted number of loans), so a reader can
see when the scenarios disagree. The alternative, staging once on a probability-weighted 12-month PD, needs the
staging rule outside the engine, cannot be done for a portfolio from stored results (only the baseline keeps
loan-level results), and makes the weighted figure a non-linear function of the scenarios; the response carries
the method label `SCENARIO_WEIGHTED` so another method could be added without ambiguity. Sums run in the order
the scenarios are compared, so the weights' order does not change the bits, and a weight of 1 returns that
scenario's figure exactly.

What it is not: the weights are the caller's judgement, echoed in every response, not an estimate; the console
offers 50 / 30 / 20 over the three stress scenarios as an illustration only, and the stress scenarios are
illustrative, not a regulator's. The weighted figure inherits each scenario's limitations, including the stacking
of forced stress months on a fall in house prices described under the survival model.

## Region analysis: where the stressed answer is not decided

A stressed figure near a threshold flips with small changes in the stress. `roughset.DecisionService` asks, for
one loan or for every loan in a portfolio run, whether the figure (12-month PD, lifetime PD or the lifetime loss
rate) is at or above a caller's threshold under **every** variant of the stress, under **none**, or under **some**.
In rough-set terms the first is the lower approximation of "high", the second lies outside its upper
approximation, and the third is the boundary region: the loans to hand to an analyst. It is a layer over
`TermStructureEngine`; no model, card, checksum or golden output changes.

- **Variants.** Member 0 is the unperturbed scenario. The others scale the unemployment, house-price and rate
  shocks by a factor in 1 ± `shockBand` and move their timing by up to `timingBandMonths` (defaults ±20% and ±3
  months), clamped to the ranges a scenario allows. A parameter that is zero in the target stays zero, so a baseline
  has nothing to perturb. A `SCENARIO_SET` granule uses caller-listed scenarios instead ("high under every one of
  these"). Each variant is a pure function of the seed and its index (splitmix64), so the same request gives the
  same variants on any number of threads; the default seed is a hash of the request.
- **Rule.** With `k` of `n` variants high and a tolerance `beta` in [0, 0.5) (default 0): `k/n >= 1 - beta` is
  certain high, `k/n <= beta` is certain low, otherwise boundary. Tolerance 0 is the strict Pawlak approximation.
- **Portfolio.** A run that asks for regions judges every loan under one scenario (default the harshest requested),
  stores each loan's region beside its baseline result, and reports the split of loans and exposure, the boundary
  exposure by state, and a loss range: the lifetime expected loss of the certainly-high loans each at its lowest
  variant, up to that of the certainly-high and boundary loans each at its highest. The run costs `loans x
  variants` projections and is refused above `risk.portfolio.decision.max-projections` (2,000 by default) instead
  of being sampled.

The whole analysis, with charts, is in `notebooks/rough_set_boundary_regions.ipynb`: it runs the independent Python reference of the engine and of this layer (no service needed) on one loan, five loans, the 400-loan demo portfolio and a sweep of the neighbourhood bands.

**How wide the boundary is** depends on the neighbourhood chosen, not on a measured quantity. On the committed
engine, for a 30-year prime loan (score 760, LTV 75) under ADVERSE and 32 variants at the default bands, the
lifetime PD spreads about ±7% around its unperturbed value and the 12-month PD -14% to +13%; under
SEVERELY_ADVERSE -12% to +24% and -14% to +35% (seed 11; other seeds move an end by up to ten points). Under
the previous survival model the same loan gave ±7% and ±25%, then ±22% and ±43%: the 12-month figure is now
steadier and the severe lifetime figure is skewed upward. The table in
`openspec/changes/rough-set-boundary-regions/design.md` and the charts in the notebook were produced with the
previous model.

**What it does not do.**
- It is not a probability and carries no coverage guarantee: "boundary" means the answer depends on the details
  of the stress, not that the loan has some chance of being high. A conformal method would give coverage, but it
  needs a calibration set per segment that this data does not support at this granularity. The response, the API
  and the console all carry the method label.
- The boundary is only as wide as the neighbourhood: a wider band widens it. The bands are modelling choices,
  echoed in every response.
- Tree models hold their response at the edge of the training range, which can make variants look more alike
  than they are. The response flags (`extrapolated`) when a driver the scenario moves (unemployment, house prices,
  the rate incentive, or a mark-to-market LTV above its ceiling) leaves that range, and does not change the region
  because of it. Loan age beyond the 96-month training window and amortisation taking mark-to-market LTV below its
  floor occur for every long-dated loan in every scenario, the baseline included, so they do not set the flag.
- It adds nothing to the model's validation: it relies on the survival model and inherits its limitations.

## Time-series and sequence diagnostics

**Seasonality.** `backend/experiments/sarimax_seasonality.py` tests the portfolio 90+ delinquency series
(99 months, 54 to fit) for seasonality and stationarity. There is none to remove: STL seasonal strength is
0.0, month-of-year effects are not significant (F-test p = 0.50), and every seasonal variant forecasts the
out-of-time months worse than the base AR(1)+trend (MAE 0.00333; ratio-index deseasonalising 0.00378,
Fourier terms 0.00371, seasonal AR(12) 0.00462). The survival model's inputs are already seasonally
adjusted or year-on-year (state unemployment is the FRED seasonally adjusted series; house prices and
unemployment enter as 12-month changes), and calendar month was tested and rejected above.

**Regime chain.** `backend/experiments/hmm_stability.py` shows that the shipped chain was fitted on six
months after the panel's coverage cliff (2025-10 onward, where n_active collapses). Trimmed, the
stressed-to-stressed probability is 0.960 instead of 0.973, the expected stress spell 25 months instead of
37, and the stressed share 25% instead of 35%. `export_hmm.py` now trims the cliff and `hmm_regime.json`, `survival_model.json` and the golden fixtures
were regenerated from the trimmed chain (as of 2025-09). The survival ONNX is bit-identical to before (same
sha256), so only the regime chain in the card moved. Sampling error is larger than that correction: from 1 + 1 observed regime
switches, a 90% interval on the stress spell is 6 to 73 months.

**Trajectory LSTM.** Retrained with a vintage split (fit on 2020-2021 originations, judged on 2022-2023),
standardisation from real months only, and Platt calibration folded into the final layer (card:
`lstm_card.json`). Before this, the served probability averaged 0.54 against an actual default rate of
2.2% (25 times too high) because the loss is class-weighted. Out-of-time now: ROC-AUC 0.676, PR-AUC 0.192,
predicted / actual 0.97. The 0.713 AUC quoted by the notebook came from a random split. "Ever defaulted" is
right-censored, so later vintages look safer than they are; the registry entry stays unvalidated.

## How the numbers are checked

| Check | Where |
|---|---|
| The Java engine reproduces an independent Python implementation of the same projection to 1e-6 | `TermStructureGoldenTest`, `backend/golden_term_structure.py` |
| The regime recursion equals brute-force enumeration of all 2^13 regime paths to 1e-13 | `TermStructureEngineTest` |
| Probability is conserved every month: active + defaulted + prepaid = 1 | `TermStructureEngineTest` |
| The loss simulation matches the exact finite-portfolio distribution, and the closed-form limit for a granular portfolio | `CvarEngineTest` |
| Tail contributions sum to expected shortfall; importance sampling more than halves the interval | `CvarEngineTest` |
| The portfolio totals equal the sum of the loans, on any number of threads, to the bit | `PortfolioRiskEngineTest` |
| The ONNX graph reproduces the LightGBM booster (5.1e-7 absolute) | `export_survival_model.py` |
| The region layer's variant generator, seed hash and three-way rule reproduce an independent Python implementation (variants exactly, widths to 1e-9) | `RoughSetGoldenTest`, `backend/golden_rough_set.py` |
| Regions are monotone in the threshold, the lower approximation lies inside the upper, a wider tolerance never undoes a decision, and a single variant equals the engine's own result | `RegionClassifierTest`, `DecisionServiceTest` |

## Reproducing the artifacts

```bash
cd backend
python fetch_macro.py              # FRED series, cached locally
python build_survival_dataset.py   # the sample and its loan-month rows
python build_crisis_dataset.py     # the 2006-2008 vintages, read from their archives
python export_survival_model.py    # survival model, card, backtest, macro snapshot
python export_model.py             # PD model, calibration, card, drift reference
python golden_term_structure.py    # reference outputs for the Java golden test
python golden_rough_set.py         # reference for the region layer (needs no model artifact)
```

Exports are reproducible bit for bit: LightGBM runs with `deterministic=True, force_row_wise=True` on
sorted rows, split thresholds are rounded to values a 32-bit float holds exactly, and the ONNX graph
carries a fixed name (the exporter's default is random). The same data gives the same checksum.
