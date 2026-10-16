# Discovery (phase 1)

Evidence for tasks 1.1 to 1.5. Counts come from `backend/experiments/delinquency_state_counts.py`, run over the
whole monthly panel (2,264 part files, 16.8M loans, 2017 to 2026).

## 1.1 Where a delinquency field would enter

- Requests already carry `daysPastDue` (`SurvivalController` request records, validated 0 to 3650). Today it only
  drives IFRS 9 staging (`TermStructureEngine` around lines 394 and 513) and a warning that the model ignores it.
  The new optional fields extend that, so `daysPastDue` is the natural source of the starting state.
- Engine: `TermStructureEngine` stays untouched (D4); a new engine class carries `a_k(r, s)`.
- Portfolio: `PortfolioRiskEngine` / `PortfolioRiskService` route per loan (task 4.7); upload parsing needs the
  per-loan field (open question 5).
- Python: `build_survival_dataset.py` already reads the status column; the new dataset is a separate output.

## 1.2 Status coding

Raw values are strings. `0` and `00` both occur (later files zero-pad), `1` and `01`, and so on up to 90+;
`RA` (REO acquisition, 45,800 rows) and `XX` (unknown, 131,936 rows). Casting to an integer handles the
padding. Mapping used for the counts: `0` -> C, `1` -> D30, `2` -> D60, `3` or more or `RA` -> D90+ (the
existing default event). `XX` and any other non-numeric value are excluded from transitions, not guessed.

## 1.3 Loan-months and transitions (consecutive reported months of one loan)

| From | Loan-months | To C | To D30 | To D60 | To D90+ |
|---|---|---|---|---|---|
| C | 647,490,124 | 0.99526 | 0.00471 | 0.00003 | 0.00001 |
| D30 | 4,751,494 | 0.46219 | 0.34214 | 0.19431 | 0.00136 |
| D60 | 1,272,402 | 0.18054 | 0.10616 | 0.24496 | 0.46833 |
| D90+ | 3,882,191 | 0.11874 | 0.00967 | 0.01213 | 0.85947 |

Caveats: this is the full panel; the survival dataset samples 40,000 loans per origination quarter, so expect
about a twelfth of these counts. 2,183 of 16.8M loans (0.013%) have rows in more than one part file, so their
first transition in each file is missed; negligible. The small C -> D60 and C -> D90+ rates are reporting gaps
or status jumps, kept as observed.

**D60 pooling decision (open question 2): do not pool.** D60 has 1.27M loan-months in the full panel (about
100,000 in the sampled dataset) and a profile unlike D90+: 18% cure and 47% roll to 90+, against 12% and 86%.
Revisit only if the sampled out-of-time counts per vintage turn out too thin.

## 1.4 model-risk-governance

Largely landed (24 of 27 tasks ticked, `ModelRegistry`, `ModelGovernanceController` present). The new model
follows its card and registry conventions (task 9.3).

## 1.5 Raw archives

Present: `data/processed/monthly_panel`, `data/processed/loan_level`, `historical_data_*`. No pipeline step was
needed for this discovery.
