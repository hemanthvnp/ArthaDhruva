"""Learns the segment graph and writes segment_graph.json and segment_graph_card.json
(openspec/changes/archive/2026-10-19-segment-graph-models, tasks 2.1-2.6, 3.5).

From the macro panel of build_macro_panel.py (state unemployment changes, monthly; house-price growth,
quarterly; each with the other states' mean removed), per source (segment_graph.py):
  moves with  graphical lasso, stability selection over half-size block subsamples
  leads       lasso on 6 monthly lags (unemployment) or 2 quarterly lags (house prices), the same selection
The sources are combined: a link kept in either is kept, its basis lists the sources that kept it, and its
weight, sign, confidence (and lag) come from the source with the higher confidence. Regions are Louvain
communities of the confident positive moves-with links, of at least 3 states; a region's stability is how
often its members land together across the subsamples.

Validation (experiments/segment_graph_validation.py) runs first, on a graph learned through 2016-12, and its
results go into the card. If the direction test does not support the leads links, they are left out of the
file and the card says why. If data/processed/segments/regional_factors.json exists (export_regional_factors.py,
group 3) and its regions match, regionCorrelation and regionalShare are added.

The graph file is reproducible byte for byte: its version names the data (the last day of the latest input
month), not the run; the card's trained_at names the run. The file is checked against
risk-engine/src/test/resources/segment/segment_graph.schema.json before anything is written.

Run from backend/:  python export_segment_graph.py   (about half an hour)
"""
from __future__ import annotations

import calendar
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import jsonschema
import numpy as np
import polars as pl

import segment_graph as sg
import segment_series as ss
from credit_common import sha256

sys.path.insert(0, str(Path(__file__).resolve().parent / "experiments"))
import segment_graph_validation as validation  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
MACRO = ROOT / "data" / "processed" / "segments" / "macro_panel.parquet"
RATIO = ROOT / "data" / "processed" / "segments" / "state_ratio.parquet"
FACTORS = ROOT / "data" / "processed" / "segments" / "regional_factors.json"
EARLY = ROOT / "data" / "processed" / "segments" / "validation_graph.json"
RESOURCES = Path(__file__).resolve().parent / "risk-engine" / "src" / "main" / "resources"
GRAPH = RESOURCES / "segment_graph.json"
CARD = RESOURCES / "segment_graph_card.json"
SCHEMA = Path(__file__).resolve().parent / "risk-engine" / "src" / "test" / "resources" / "segment" / "segment_graph.schema.json"

# Per source: lags in periods, block length in periods (12 months), months per period.
SOURCES = {
    "unemployment": {"max_lag": 6, "block": 12, "months": 1},
    "hpi": {"max_lag": 2, "block": 4, "months": 3},
}


def panel(source: str, end: int | None) -> tuple[list[str], list[int], np.ndarray]:
    """States, periods and the residual panel of one source, through `end` if given. The residuals are refitted
    on the rows kept, so a graph learned through a cut-off sees nothing after it."""
    m = pl.read_parquet(MACRO).filter(pl.col("source") == source)
    if end is not None:
        m = m.filter(pl.col("period") <= end)
    wide = m.pivot(index="period", on="state", values="change").sort("period")
    states = sorted(c for c in wide.columns if c != "period")
    x = wide.select(states).to_numpy()
    if np.isnan(x).any():
        raise ss.BuildError(f"{source}: missing periods in the macro panel")
    return states, wide["period"].to_list(), ss.residualise_loo(x)


def learn(end: int | None = None) -> dict:
    """The graph learned through `end` (all data if None): movesWith, leads, regions and what the card needs."""
    per_source, moves, leads, periods = {}, {}, {}, {}
    states = None
    for source, cfg in SOURCES.items():
        st_codes, prd, x = panel(source, end)
        if states is not None and st_codes != states:
            raise ss.BuildError(f"{source}: a different set of states from the other source")
        states, periods[source] = st_codes, prd
        st = sg.stability(x, cfg["max_lag"], cfg["block"])
        per_source[source] = st
        n = len(states)
        for i in range(n):
            for j in range(n):
                if i < j and st.moves_freq[i, j] >= sg.STABILITY:
                    moves.setdefault((states[i], states[j]), []).append(
                        (float(st.moves_freq[i, j]), float(st.moves_weight[i, j]), source, None))
                if i != j and st.leads_freq[i, j] >= sg.STABILITY:
                    leads.setdefault((states[i], states[j]), []).append(
                        (float(st.leads_freq[i, j]), float(st.leads_weight[i, j]), source,
                         int(st.leads_lag[i, j]) * cfg["months"]))

    def best(entries):
        conf, weight, _, lag = max(entries, key=lambda e: (e[0], abs(e[1])))
        basis = [s for s in SOURCES if any(e[2] == s for e in entries)]
        return conf, weight, basis, lag

    moves_with = []
    for (a, b), entries in sorted(moves.items()):
        conf, w, basis, _ = best(entries)
        moves_with.append({"a": a, "b": b, "weight": round(w, 4), "sign": 1 if w > 0 else -1,
                           "confidence": round(conf, 3), "basis": basis})
    lead_links = []
    for (a, b), entries in sorted(leads.items()):
        conf, w, basis, lag = best(entries)
        lead_links.append({"from": a, "to": b, "lagMonths": lag, "weight": round(w, 4), "sign": 1 if w > 0 else -1,
                           "confidence": round(conf, 3), "basis": basis})

    positive = [(m["a"], m["b"], m["weight"]) for m in moves_with if m["weight"] > 0]
    comms = sg.communities(states, positive)
    samples = []
    for b in range(sg.RESAMPLES):
        links = {}
        for st in per_source.values():
            pc = st.moves_samples[b]
            for i, j in zip(*np.nonzero(np.triu(pc, k=1) > 0)):
                key = (states[i], states[j])
                links[key] = max(links.get(key, 0.0), float(pc[i, j]))
        samples.append([(a, c, w) for (a, c), w in links.items()])
    together = sg.co_assignment(states, samples)
    pos = {s: i for i, s in enumerate(states)}
    regions, taken = [], set()
    for k, members in enumerate(comms, start=1):
        ix = [pos[s] for s in members]
        pairs = together[np.ix_(ix, ix)][np.triu_indices(len(ix), k=1)]
        label = sg.region_label(members, taken, k)
        taken.add(label)
        regions.append({"id": f"R{k}", "label": label, "states": members, "stability": round(float(pairs.mean()), 3)})

    first = min(min(p) for p in periods.values())
    last = max(max(p) for p in periods.values())
    return {
        "states": states, "movesWith": moves_with, "leads": lead_links, "regions": regions,
        "learnedOn": {"from": ss.month_label(first), "to": ss.month_label(last), "sources": list(SOURCES)},
        "penalties": {s: {"graphical_lasso": round(st.glasso_penalty, 5), "lasso": round(st.lasso_penalty, 5),
                          "max_links_per_subsample_moves": round(st.q_moves, 2),
                          "max_links_per_subsample_leads": round(st.q_leads, 2)} for s, st in per_source.items()},
    }


def finite(value):
    """NaN and infinities become null: json.dumps writes them as bare NaN, which is not JSON, and the Java side's
    parser rejects the whole file (a comparison that could not be made has a NaN statistic)."""
    if isinstance(value, dict):
        return {k: finite(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [finite(v) for v in value]
    if isinstance(value, (float, np.floating)):
        return float(value) if np.isfinite(value) else None
    return value


def write_lf(path: Path, payload) -> None:
    """JSON with LF line ends on every platform. The card's checksum is of these bytes, and git stores text with
    LF: written with Path.write_text on Windows (CRLF), the file checked out on Linux would not match its card."""
    text = json.dumps(finite(payload), indent=2, default=float, allow_nan=False)
    path.write_bytes((text + "\n").encode("utf-8"))
    print(f"wrote {path}")


def version_for(*months: str) -> str:
    y, m = map(int, max(months).split("-"))
    return f"{y:04d}.{m:02d}.{calendar.monthrange(y, m)[1]:02d}-0000"


def main() -> None:
    print("learning the validation graph (through 2016-12) ...", flush=True)
    early = learn(end=ss.LEARN_END)
    EARLY.write_text(json.dumps(early, indent=2) + "\n", encoding="utf-8")  # the outlook's graph features use it
    print(f"  {len(early['movesWith'])} moves-with, {len(early['leads'])} leads links; validating ...", flush=True)
    checks = validation.validate(early["movesWith"], early["leads"])
    leads_ok = checks["summary"]["leads_direction_supported"]["primary"]

    print("learning the graph on the full sample ...", flush=True)
    g = learn()
    as_of = ss.month_label(int(pl.read_parquet(RATIO)["month"].max()))
    leads = g["leads"] if leads_ok else []
    counts = {s: {"leads": sum(l["from"] == s for l in leads), "follows": sum(l["to"] == s for l in leads)}
              for s in g["states"]}
    region_of = {s: r["id"] for r in g["regions"] for s in r["states"]}
    graph = {
        "schemaVersion": 1,
        "version": version_for(as_of, g["learnedOn"]["to"]),
        "asOf": as_of,
        "method": "graphical lasso and sparse VAR (lasso on lags), stability selection over half-size block subsamples",
        "learnedOn": g["learnedOn"],
        "states": [{"code": s, "name": sg.NAMES[s], "region": region_of.get(s), "covered": s in g["states"],
                    **counts.get(s, {"leads": 0, "follows": 0})} for s in ss.STATES],
        "regions": g["regions"],
        "movesWith": g["movesWith"],
    }
    if leads:
        graph["leads"] = leads

    factors = json.loads(FACTORS.read_text(encoding="utf-8")) if FACTORS.exists() else None
    if factors is not None:
        if factors["regions"] != {r["id"]: r["states"] for r in g["regions"]}:
            raise ss.BuildError(f"{FACTORS.name} was estimated for different regions; rerun export_regional_factors.py")
        graph["regionCorrelation"] = factors["regionCorrelation"]
        graph["regionalShare"] = factors["regionalShare"]

    jsonschema.validate(graph, json.loads(SCHEMA.read_text(encoding="utf-8")))
    write_lf(GRAPH, graph)

    limitations = [
        "A lead is predictive precedence in the macro data (one state's past improves the prediction of another's "
        "future after every other state's past is accounted for), not cause.",
        "Learned from state unemployment and house prices, not from delinquency. The delinquency data used to "
        f"validate it covers {checks['protocol']['tested_from']} to {as_of}: one benign period and one pandemic "
        "with forbearance, and the panel holds only the 2017 and later vintages.",
        "State can stand in for protected characteristics. The graph is for portfolio-level analysis only and must "
        "not be used in an individual credit decision.",
        "Hurricanes and wildfires are regional shocks the macro data cannot see. Freddie Mac flagged COVID hardship "
        "as a disaster too, so the series without disaster-flagged delinquencies also leaves out most of 2020.",
        "The territories are not covered: too few loans and no state-level macro series.",
    ]
    if not leads_ok:
        limitations.append("Leads links are omitted: on the delinquency data the forward lagged correlation did not "
                           "exceed the reverse more often than chance (validation.summary.leads_direction_supported).")
    limitations.append(
        f"The graph is sparse: {len(graph['movesWith'])} moves-with links, kept at a bound of one expected false link "
        "per kind of link and source. Validation compares the mean correlation over linked pairs with the other "
        "pairs, which favours a few strong links; beating the Census-division grouping says these links are real, "
        "not that the graph partitions the country better. Pairs without a link are not shown to be unrelated.")
    if len(g["regions"]) < 2:
        limitations.append(
            f"{len(g['regions'])} region(s) of at least {sg.MIN_REGION} states: a regional loss factor needs two or "
            "more, so regionCorrelation and regionalShare are absent.")
    if not checks["summary"]["adds_information_beyond_geography"]["primary"]:
        limitations.append("The learned graph did not beat grouping states by Census division at the 5% level: it "
                           "adds no information beyond geography, and describes macro co-movement.")
    card = {
        "model": "segment_graph",
        "version": graph["version"],
        "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "algorithm": graph["method"],
        "hyperparameters": {
            "stability_threshold": sg.STABILITY, "subsamples": sg.RESAMPLES, "subsample": "half the blocks, complementary pairs",
            "block_months": 12, "expected_false_links_bound": sg.FALSE_LINKS, "seed": sg.SEED,
            "lags": {s: f"{c['max_lag']} x {c['months']} months" for s, c in SOURCES.items()},
            "penalties": g["penalties"], "min_region_states": sg.MIN_REGION, "louvain_seed": sg.LOUVAIN_SEED,
        },
        "training_window": {**g["learnedOn"], "validation_graph_learned_through": ss.month_label(ss.LEARN_END)},
        "links": {"movesWith": len(graph["movesWith"]), "leads_learned": len(g["leads"]), "leads_shipped": len(leads),
                  "regions": len(g["regions"]), "states_in_no_region": sorted(set(ss.STATES) - set(region_of))},
        "validation": checks,
        "regional_factors": factors["card"] if factors is not None else None,
        "limitations": limitations,
        "artifact_sha256": sha256(GRAPH),
    }
    write_lf(CARD, card)
    print(json.dumps({"links": card["links"], "summary": checks["summary"]}, indent=2))


if __name__ == "__main__":
    main()
