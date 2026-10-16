## Why

A stressed lifetime-risk result is only useful for a decision (flag, stage, review, reprice) when it falls clearly on one side of a threshold. Loans near the threshold flip between "risky" and "acceptable" with small changes in the stress shock, its timing, or the regime-chain estimate, yet the API returns one number and one label. The neighbouring `delinquency-aware-risk-and-uncertainty` change gives intervals on PD and expected loss, but an interval on a number does not say which *loans* are decided and which are not.

Rough-set theory gives a direct answer: a loan is in the **lower approximation** of "high risk" only if it is high risk under every admissible scenario and perturbation, in the **upper approximation** if it is high risk under at least one, and the gap between the two is the **boundary region** that should be deferred to an analyst instead of forced into a label. The engines are exact or seed-reproducible, so the three-way split can be reproducible too.

A further reason is specific to the model. The hazard model is LightGBM, and trees extrapolate flat outside the training range. Under severe stress the macro drivers can leave that range (`TermStructureEngine.extrapolations` already detects this), which makes a single stressed score look more stable than it is. Perturbing the shock parameters, not only the model, is how the boundary stays honest.

## What Changes

- Add a **three-way decision layer** over stressed risk outputs. For a loan and a decision threshold (on lifetime PD, 12-month PD, or lifetime expected loss rate), it classifies the loan as `CERTAIN_HIGH` (lower approximation), `CERTAIN_LOW` (outside the upper approximation) or `BOUNDARY`, and reports the boundary width (spread of the metric across the granule) and how many granule members crossed the threshold.
- Define the **granule** explicitly and deterministically: the built-in scenarios, a seeded jitter of the stress parameters (shock size, timing, recovery), and the regime-chain posterior draws from the uncertainty change when they have landed. The same request gives the same classification on any thread count.
- Support **variable-precision rough sets** (a configurable tolerance, default strict) so that one outlier member does not push an otherwise decided loan into the boundary, with the tolerance echoed in the response.
- Add **portfolio aggregates**: the share and exposure of the book in each region, the boundary exposure by segment, and the expected-loss range implied by the boundary loans (lower-approximation exposure versus upper-approximation exposure).
- Flag when the boundary is **understated by extrapolation**: if any granule member's macro path leaves the trained range, the response says so and the region carries a `extrapolated` indicator, because flat tree extrapolation narrows the apparent boundary.
- Extend the **API** (additive, backward compatible): term-structure compare, expected-loss and portfolio requests may ask for the decision layer by supplying a threshold; responses add the region, the boundary width and the method label. Requests without a threshold behave exactly as today.
- Show the three regions on the Lifetime Risk and Portfolio Risk pages, with the boundary loans listed for review and the method stated.
- Register the method in the **model inventory and card system** as a decision overlay on the existing models. It has no trained artifact of its own, so no new checksum, and it never alters the existing survival model, its card, its checksum or the golden test.
- Include a **benchmark against a conformal-style alternative** in the validation notes, so the card states what the rough-set split does and does not guarantee (no coverage guarantee).
- No **BREAKING** changes.

## Capabilities

### New Capabilities
- `rough-set-decision-regions`: the granule definition, lower/upper approximation and boundary semantics, variable-precision tolerance, determinism, extrapolation flag and invariants of the three-way classification for a single loan.
- `rough-set-portfolio-regions`: portfolio aggregation of regions (counts, exposure, segment breakdown, expected-loss range), parallel-run determinism and the run-resource behaviour.
- `rough-set-decision-api`: additive request and response fields, validation, defaults, fallback and method labelling on the term-structure and portfolio endpoints.
- `rough-set-region-display`: how the console presents regions and boundary loans without implying a probability or a coverage guarantee.

### Modified Capabilities
<!-- None. openspec/specs/ is empty; the related open change delinquency-aware-risk-and-uncertainty is referenced, not modified. -->

## Impact

- **Java:** a new package (for example `com.arthadhruva.riskengine.roughset`) holding the granule builder, the classifier and the portfolio aggregator; additive fields in `SurvivalController` and `PortfolioRiskController`; no edit to `TermStructureEngine`, `CvarEngine` or `PortfolioRiskEngine` internals, only calls through their public methods. The portfolio path must keep the ordering and merge determinism the repo requires.
- **Python:** an independent reference implementation of the classifier and a golden fixture (`backend/golden_rough_set.py`), plus an experiment under `backend/experiments/` that measures boundary size on the committed artifacts. No model is retrained and no model artifact changes.
- **Frontend:** Lifetime Risk and Portfolio Risk pages; a region badge and boundary list component; types and tests; the initial-bundle budget must still pass (lazy-load if needed).
- **Tests and CI:** a golden test against the Python reference to 1e-6, property tests (the lower approximation is a subset of the upper, monotone in the threshold, tolerance 0 equals strict rough sets), determinism across thread counts, and a regression test that the survival card checksum and golden output are unchanged. No existing test, budget or model-validation check is weakened.
- **Docs:** `MODELS.md` gains a section on the method and its limits.
- **Data:** none beyond the committed artifacts; no raw archives are needed.
- **Dependencies on other changes:** it works with the current engine alone. Regime-chain posterior draws and the delinquency-conditioned engine from `delinquency-aware-risk-and-uncertainty` enrich the granule when they land, and the design must degrade cleanly when they have not.
- **Out of scope:** attribute reduction and rule induction on loan features (classical rough-set data mining), a coverage-guaranteed method (conformal prediction), changing any threshold policy or staging rule, and retraining LightGBM.
