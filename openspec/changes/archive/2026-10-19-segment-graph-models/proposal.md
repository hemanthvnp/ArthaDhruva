## Why

The Segment Graph says which US states' mortgage risk moves together, so that an analyst can ask where trouble in one market has tended to show up next. Nothing in it is learned, and on the current data it does not show what it claims to.

- **There is no model.** The graph is one Pearson correlation table over the monthly delinquency rates of 15 states, cut at r >= 0.95 (`backend/export_segment_correlation.py`). Nothing is fitted and nothing predicts. Unlike every other model in the service it has no validation, no model card and no checksum.
- **It mostly draws the national cycle.** One common factor explains 89.5% of the variance of the 15 series, and the median pairwise correlation is 0.93. Which pairs clear 0.95 is largely a matter of how closely each state follows that shared cycle.
- **Its edges are not stable.** The ranking of state pairs by correlation in the first half of the sample and in the second half agree at -0.23. At r >= 0.95 there are 56 edges in the first half and 67 in the second, and 32 are in both.
- **It throws away real structure.** After the national movement is removed, 37 of 105 pairs of monthly changes correlate significantly, where about 5 would by chance.
- **It covers a third of the country.** 15 of 51 places and 69 months. The 400-loan demo portfolio has loans in 47 states, and only 261 of them are in the 15.
- **It claims a direction it cannot show.** The page says where trouble "has tended to show up next". The correlation is same-month and has no direction.

This change replaces the correlation table with models that are learned from three decades of state macro data, validated on later delinquency data against simple alternatives, and published with their model cards whether or not they win.

## What Changes

- Build a standardised delinquency series for the 50 states and DC from the full loan-level panel (about 474 million loan-months), and a macro panel with the national movement removed.
- Learn the graph from state unemployment and house prices, 1995 to 2026. Two kinds of link: "moves with" (a direct same-period association after accounting for every other state) and "leads" (one state's past predicts another's future, with a lag in months). Each link carries a weight, a sign and a confidence from stability selection. Group states into regions and count how many states each one leads and follows.
- Validate on delinquency data the models did not see, against a same-Census-division grouping, the existing 0.95 correlation graph and random graphs with the same degree sequence. Record the result in the model card, including when the learned graph does not win.
- Estimate what a loss simulation needs to add a regional factor: the correlation between regions and the share of loan-level default risk a regional factor explains beyond what the survival model already expects, with an interval and a parameter-recovery test.
- Forecast each state's change in relative delinquency three months ahead under a decision rule written before the backtest is run. A forecast ships only if it beats declared baselines, the graph's contribution is measured by ablation, and a negative result is an accepted outcome that gets written down.
- Deliver versioned files with model cards and checksums: `segment_graph.json`, `segment_graph_card.json`, and `segment_forecast.json` if a forecast ships. Their shape is in `design.md`.
- **BREAKING**: none. New scripts and files. The existing correlation export and the graph file the application loads today are left as they are until the serving side adopts the new files.

## Capabilities

### New Capabilities
- `segment-risk-series`: the standardised delinquency series per state and the macro panel the models learn from, built reproducibly from the full panel.
- `segment-graph-learning`: the learned graph (links with weight, sign, direction, lag and confidence), its regions, and its out-of-time validation against baselines.
- `regional-loss-factors`: the regional correlation and regional share of default risk, estimated with intervals, for use by the portfolio loss simulation.
- `regional-outlook`: the three-month forecast of regional deterioration, shipped only if it passes the pre-registered rule.

### Modified Capabilities
- None. `openspec/specs/` is empty: no prior capability specs exist for this repository.

## Impact

- **Data**: two derived panels under `data/processed/segments/` (git-ignored like all of `data/`). One full pass over the monthly panel, run once and resumable.
- **Scripts**: `backend/build_state_panel.py`, `backend/build_macro_panel.py`, `backend/export_segment_graph.py`, `backend/export_segment_forecast.py` (only if a forecast ships), and experiments in `backend/experiments/`. Python tests under `backend/tests/` on synthetic data, run by a new CI job.
- **Resources**: `segment_graph.json`, `segment_graph_card.json` and optionally `segment_forecast.json` in `backend/risk-engine/src/main/resources/`. Tens of kilobytes each.
- **Docs**: a "Segment graph" section in `MODELS.md`, and the commands in the README's "Rebuilding the data and the models".
- **No Java or React change in this change.** The serving side (a versioned Neo4j load, an API that returns weights and lead-lag links, the Segment Graph page, regional factors in the portfolio loss simulation) is planned separately and reads the files described in `design.md`.
- **Out of scope**: serving, the page and the loss engine. An online model: production has no feed of new loan performance, so a model served online would see the same inputs for ever, and every other model in the service is also trained offline. Any use of the graph in an individual credit decision.
