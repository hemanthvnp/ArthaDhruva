"""Out-of-time validation of the segment graph (openspec/changes/segment-graph-models, decision 5, task 2.5).

The graph is learned on macro data through 2016-12 (export_segment_graph.learn with end=LEARN_END) and tested on
the standardised delinquency ratio from VALIDATION_START (2019-01, design.md amendment of 2026-10-19):
  statistic   mean correlation of 3-month changes in the log ratio among linked pairs, minus the same among all
              other pairs; correlations pairwise over the months both states have (at least MIN_PAIR_MONTHS)
  null        NULL_DRAWS degree-preserving rewirings of the graph (double edge swaps), one-sided
  baselines   same Census division; the existing r >= 0.95 graph (on its own 15 states, against the learned graph
              restricted to them); a graph learned the same way from the delinquency series itself (the months
              of 2019-01 .. 2021-12 every state has) and tested from 2022-01, if it can be estimated at all
  versus      learned minus baseline, one-sided p from a moving-block bootstrap of the test months
  direction   for each leads link A -> B at lag k months, whether corr(A at t, B at t + k) exceeds the reverse;
              binomial test of the share against 50%, one-sided
Every headline number is computed for all months and without 2020-03 .. 2021-03 (forbearance), and on the
primary series and the one without disaster-flagged delinquencies. A conclusion that changes between the four
runs is labelled not robust.

Run from backend/:  python experiments/segment_graph_validation.py   (learns the 2016 graph; prints JSON)
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import networkx as nx
import numpy as np
import pandas as pd
import polars as pl
from scipy import stats

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import segment_graph as sg  # noqa: E402
import segment_series as ss  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
RATIO = ROOT / "data" / "processed" / "segments" / "state_ratio.parquet"
OLD_GRAPH = ROOT / "backend" / "risk-engine" / "src" / "main" / "resources" / "segment_correlation_graph.json"

HORIZON = 3
MIN_PAIR_MONTHS = 24
NULL_DRAWS = 2000
BOOT_DRAWS = 2000
BOOT_BLOCK = 12
ALPHA = 0.05
FORBEARANCE = (2020 * 12 + 2, 2021 * 12 + 2)
DELINQUENCY_LEARN = (ss.VALIDATION_START, 2021 * 12 + 11)
DELINQUENCY_MIN_MONTHS = 18
MIN_COMPARE_LINKS = 3     # a comparison against a graph with fewer links than this is reported as not comparable
DELINQUENCY_TEST_START = 2022 * 12
SEED = 20261019


# ---- the test series ------------------------------------------------------------------------------------

def changes(variant: str, start: int, exclude_forbearance: bool) -> pd.DataFrame:
    """3-month changes in the log ratio (rows = months, columns = states), from `start`. Without forbearance, a
    change is dropped when any of the months it spans lies in the forbearance period."""
    r = pl.read_parquet(RATIO).filter(pl.col("variant") == variant).to_pandas()
    wide = r.pivot(index="month", columns="state", values="log_ratio").sort_index()
    wide = wide.reindex(range(wide.index.min(), wide.index.max() + 1))
    ch = (wide - wide.shift(HORIZON)).loc[start:]
    if exclude_forbearance:
        lo, hi = FORBEARANCE
        ch = ch[[not any(lo <= m - k <= hi for k in range(HORIZON + 1)) for m in ch.index]]
    return ch


def pairwise_corr(x: np.ndarray) -> np.ndarray:
    """Pearson correlations over the rows both columns have (NaN = missing), as pandas' DataFrame.corr with
    min_periods=MIN_PAIR_MONTHS, but in matrix form: the bootstrap calls it thousands of times."""
    m = (~np.isnan(x)).astype(float)
    x0 = np.nan_to_num(x)
    n = m.T @ m
    sx = x0.T @ m
    sxx = (x0 * x0).T @ m
    sxy = x0.T @ x0
    with np.errstate(invalid="ignore", divide="ignore"):
        cov = sxy - sx * sx.T / n
        r = cov / np.sqrt((sxx - sx * sx / n) * (sxx.T - sx.T * sx.T / n))
    r[n < MIN_PAIR_MONTHS] = np.nan
    return r


def pair_corr(ch: pd.DataFrame) -> pd.DataFrame:
    return pd.DataFrame(pairwise_corr(ch.to_numpy(dtype=float)), index=ch.columns, columns=ch.columns)


# ---- statistics -----------------------------------------------------------------------------------------

def edge_set(edges) -> set[tuple[str, str]]:
    return {tuple(sorted(e)) for e in edges}


def contrast(corr: pd.DataFrame, edges: set[tuple[str, str]], nodes: list[str]) -> float:
    """Mean correlation over linked pairs minus mean over the other pairs, among `nodes` with a correlation."""
    nodes = [n for n in nodes if n in corr.columns]
    c = corr.loc[nodes, nodes].to_numpy()
    pos = {s: i for i, s in enumerate(nodes)}
    linked = np.zeros(c.shape, dtype=bool)
    for a, b in edges:
        if a in pos and b in pos:
            linked[pos[a], pos[b]] = linked[pos[b], pos[a]] = True
    upper = np.triu(np.ones(c.shape, dtype=bool), k=1) & ~np.isnan(c)
    on, off = c[upper & linked], c[upper & ~linked]
    if not len(on) or not len(off):
        return float("nan")
    return float(on.mean() - off.mean())


def rewiring_p(corr: pd.DataFrame, edges: set[tuple[str, str]], nodes: list[str], seed: int) -> dict:
    observed = contrast(corr, edges, nodes)
    g = nx.Graph()
    g.add_nodes_from(nodes)
    g.add_edges_from(sorted(edges))  # a set's order varies between processes (string hashing); the swaps must not
    null = []
    rng = np.random.default_rng(seed)
    swaps = 10 * max(g.number_of_edges(), 1)
    for _ in range(NULL_DRAWS):
        h = g.copy()
        try:
            nx.double_edge_swap(h, nswap=swaps, max_tries=swaps * 100, seed=int(rng.integers(2**31)))
        except nx.NetworkXException:
            pass  # a graph too dense or too small to rewire further keeps the swaps it managed
        null.append(contrast(corr, edge_set(h.edges()), nodes))
    null = np.array(null)
    return {"statistic": round(observed, 4), "null_mean": round(float(np.nanmean(null)), 4),
            "p": round(float((1 + np.sum(null >= observed)) / (1 + len(null))), 4), "edges": len(edges)}


def versus(ch: pd.DataFrame, a: set, b: set, nodes: list[str], seed: int) -> dict:
    """Learned (a) minus baseline (b), with a one-sided moving-block bootstrap p over the test months."""
    diff = contrast(pair_corr(ch), a, nodes) - contrast(pair_corr(ch), b, nodes)
    small = min(len(a), len(b))
    if np.isnan(diff) or small < MIN_COMPARE_LINKS:
        return {"difference": None if np.isnan(diff) else round(diff, 4), "p": None,
                "note": f"not comparable: a graph has {small} link(s) among these states, fewer than {MIN_COMPARE_LINKS}"}
    rng = np.random.default_rng(seed)
    n = len(ch)
    boot = []
    for _ in range(BOOT_DRAWS):
        starts = rng.integers(0, n - BOOT_BLOCK + 1, size=-(-n // BOOT_BLOCK))
        rows = (starts[:, None] + np.arange(BOOT_BLOCK)).ravel()[:n]
        c = pair_corr(ch.iloc[rows].reset_index(drop=True))
        boot.append(contrast(c, a, nodes) - contrast(c, b, nodes))
    boot = np.array(boot)
    boot = boot[~np.isnan(boot)]
    return {"difference": round(diff, 4), "p": round(float(np.mean(boot <= 0)), 4) if len(boot) else None}


def direction(ch: pd.DataFrame, leads: list[dict]) -> dict:
    """Share of leads links whose forward lagged correlation exceeds the reverse, with a binomial p (one-sided)."""
    wins, tested = 0, 0
    for link in leads:
        a, b, k = link["from"], link["to"], link["lagMonths"]
        if a not in ch or b not in ch:
            continue
        fwd = ch[a].corr(ch[b].shift(-k), min_periods=MIN_PAIR_MONTHS)
        rev = ch[b].corr(ch[a].shift(-k), min_periods=MIN_PAIR_MONTHS)
        if np.isnan(fwd) or np.isnan(rev):
            continue
        tested += 1
        wins += fwd > rev
    p = float(stats.binomtest(wins, tested, 0.5, alternative="greater").pvalue) if tested else float("nan")
    return {"links_tested": tested, "forward_stronger": int(wins),
            "share": round(wins / tested, 4) if tested else None, "p": round(p, 4)}


# ---- baselines ------------------------------------------------------------------------------------------

def division_edges(states: list[str]) -> set[tuple[str, str]]:
    return {tuple(sorted((a, b))) for i, a in enumerate(states) for b in states[i + 1:]
            if sg.DIVISION_OF[a] == sg.DIVISION_OF[b]}


def old_graph() -> tuple[list[str], set[tuple[str, str]]]:
    g = json.loads(OLD_GRAPH.read_text(encoding="utf-8"))
    return sorted(g["nodes"]), edge_set((e["source"], e["target"]) for e in g["edges"])


def delinquency_graph(variant: str = "primary") -> tuple[set[tuple[str, str]] | None, str]:
    """Moves-with links learned the same way from monthly changes in the log ratio, DELINQUENCY_LEARN only.
    Returns (None, why) when the method cannot be applied to so short a window."""
    r = pl.read_parquet(RATIO).filter(pl.col("variant") == variant).to_pandas()
    wide = r.pivot(index="month", columns="state", values="log_ratio").sort_index()
    lo, hi = DELINQUENCY_LEARN
    x = wide.diff().loc[lo:hi][ss.STATES].dropna()  # the months every state has
    note = f"{len(x)} complete months for {len(ss.STATES)} states"
    if len(x) < DELINQUENCY_MIN_MONTHS:
        return None, f"not estimable: {note}, fewer than {DELINQUENCY_MIN_MONTHS}"
    try:
        st = sg.stability(x.to_numpy(), max_lag=1, block=6)
    except ValueError as e:
        return None, f"not estimable: {note}; half-size subsamples cannot meet the false-link bound ({e})"
    edges = {tuple(sorted((ss.STATES[i], ss.STATES[j]))) for i in range(len(ss.STATES))
             for j in range(i + 1, len(ss.STATES)) if st.moves_freq[i, j] >= sg.STABILITY}
    return edges, note


# ---- the protocol ---------------------------------------------------------------------------------------

def validate(moves: list[dict], leads: list[dict]) -> dict:
    """Runs decision 5 on a graph learned through 2016-12. `moves` and `leads` are in the graph-file shape."""
    learned = edge_set((m["a"], m["b"]) for m in moves)
    positive = edge_set((m["a"], m["b"]) for m in moves if m["sign"] > 0)
    states = ss.STATES
    division = division_edges(states)
    old_nodes, old_edges = old_graph()
    learned_old = {e for e in learned if e[0] in old_nodes and e[1] in old_nodes}
    delinquency, delinquency_note = delinquency_graph()

    runs = {}
    for variant in ("primary", "ex_disaster"):
        for exclude in (False, True):
            name = f"{variant}{'_ex_forbearance' if exclude else ''}"
            ch = changes(variant, ss.VALIDATION_START, exclude)
            corr = pair_corr(ch)
            seed = SEED + len(runs)
            late = changes(variant, DELINQUENCY_TEST_START, exclude)
            runs[name] = {
                "test_months": f"{ss.month_label(int(ch.index.min()))} .. {ss.month_label(int(ch.index.max()))}",
                "changes": len(ch),
                "learned": rewiring_p(corr, learned, states, seed),
                "learned_positive_links": rewiring_p(corr, positive, states, seed),
                "same_division": rewiring_p(corr, division, states, seed),
                "learned_vs_division": versus(ch, learned, division, states, seed),
                "correlation_095_graph_15_states": rewiring_p(corr, old_edges, old_nodes, seed),
                "learned_on_the_same_15_states": rewiring_p(corr, learned_old, old_nodes, seed),
                "learned_vs_correlation_095": versus(ch, learned_old, old_edges, old_nodes, seed),
                "from_2022": {
                    "test_months": f"{ss.month_label(int(late.index.min()))} .. {ss.month_label(int(late.index.max()))}",
                    "learned": rewiring_p(pair_corr(late), learned, states, seed),
                    "delinquency_graph": rewiring_p(pair_corr(late), delinquency, states, seed)
                    if delinquency is not None else None,
                    "learned_vs_delinquency_graph": versus(late, learned, delinquency, states, seed)
                    if delinquency is not None else None,
                    "delinquency_graph_note": delinquency_note,
                },
                "direction": direction(ch, leads),
            }

    def verdict(f):
        """True or False per run; None where the comparison could not be made."""
        return {name: f(run) for name, run in runs.items()}

    def below(p):
        return None if p is None or np.isnan(p) else bool(p < ALPHA)

    conclusions = {
        "linked_pairs_move_together_more_than_random": verdict(lambda r: below(r["learned"]["p"])),
        "adds_information_beyond_geography": verdict(lambda r: below(r["learned_vs_division"]["p"])),
        "beats_the_correlation_095_graph": verdict(lambda r: below(r["learned_vs_correlation_095"]["p"])),
        "beats_a_graph_learned_from_delinquency": verdict(
            lambda r: below(r["from_2022"]["learned_vs_delinquency_graph"]["p"])
            if r["from_2022"]["learned_vs_delinquency_graph"] else None),
        # the learned graph has 7 strong links, mostly between neighbours; the division grouping has 135 pairs
        # of every strength. The statistic is a mean over linked pairs, so a sparse graph of strong links is
        # favoured by construction: this answers "are these links real", not "is this a better partition".
        "leads_direction_supported": verdict(lambda r: below(r["direction"]["p"])),
    }
    summary = {k: {"primary": v["primary"], "robust": len(set(v.values())) == 1} for k, v in conclusions.items()}
    return {
        "protocol": {
            "learned_through": ss.month_label(ss.LEARN_END),
            "tested_from": ss.month_label(ss.VALIDATION_START),
            "statistic": f"mean correlation of {HORIZON}-month changes in the log standardised delinquency ratio, "
                         "linked pairs minus all other pairs",
            "null": f"{NULL_DRAWS} degree-preserving rewirings, one-sided",
            "versus": f"{BOOT_DRAWS} moving-block bootstrap resamples of the test months (blocks of {BOOT_BLOCK}), one-sided",
            "delinquency_graph": f"learned {ss.month_label(DELINQUENCY_LEARN[0])} .. {ss.month_label(DELINQUENCY_LEARN[1])}, "
                                 f"tested from {ss.month_label(DELINQUENCY_TEST_START)}",
            "forbearance_excluded": f"{ss.month_label(FORBEARANCE[0])} .. {ss.month_label(FORBEARANCE[1])}",
            "significance": ALPHA,
        },
        "summary": summary,
        "conclusions_by_run": conclusions,
        "runs": runs,
    }


def main() -> None:
    import export_segment_graph as eg
    graph = eg.learn(end=ss.LEARN_END)
    print(json.dumps(validate(graph["movesWith"], graph["leads"]), indent=2))


if __name__ == "__main__":
    main()
