"""Feasibility prototype: can a loan's region be PROVEN over a whole stress band instead of sampled?

roughset.GranuleBuilder samples 32 variants of a scenario and calls a loan "certain high" when all of them are
at or above the threshold. A sample cannot show that no stress in the band flips the loan. The hazard model
is a tree ensemble, so over a box of inputs each tree's output is bounded by the leaves the box can reach.
This script

    1. bounds every month's default and prepayment hazard over a box of scenario parameters (the three shock
       factors in [1 - band, 1 + band], the two shock timings and the forced-stress length within +/- timing
       band), by interval evaluation of the exported ONNX trees;
    2. pushes the bounds through the regime recursion. Cumulative default rises with any month's default
       hazard and falls with any month's prepayment hazard, so (highest default, lowest prepayment) bounds it
       from above and the reverse from below;
    3. tightens the bounds by branch and bound: split the box that sets the lower or the upper bound;
    4. checks itself: a box of zero width must reproduce onnxruntime, and every sampled variant of the band
       must fall inside the proven bounds.

Lifetime and 12-month PD only (the loss rate adds a continuous LGD term). Prints one JSON report; writes nothing.
Run from backend/:  python experiments/certified_regions.py
"""
from __future__ import annotations

import heapq
import itertools
import json
import sys
import time
from pathlib import Path

import numpy as np
import onnx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import golden_rough_set as grs  # noqa: E402
import golden_term_structure as gts  # noqa: E402

BAND, TIMING, MEMBERS, SEED = 0.2, 3, 32, 11
SPLITS = 40          # branch-and-bound budget per case
CHUNK = 120          # rows per interval-evaluation batch (memory)
CASES = [("prime, adverse", gts.PRIME, gts.ADVERSE), ("prime, severely adverse", gts.PRIME, gts.SEVERELY_ADVERSE),
         ("risky as a new loan, severely adverse", dict(gts.RISKY, originationMonth=None), gts.SEVERELY_ADVERSE)]  # Loan assumes a new loan


class Trees:
    """The ONNX TreeEnsembleClassifier as flat arrays, pruned to one loan's fixed features."""

    def __init__(self, path: Path, dynamic: list[int]) -> None:
        node = onnx.load(str(path)).graph.node[0]
        at = {a.name: a for a in node.attribute}
        tree = np.array(at["nodes_treeids"].ints)
        ids = np.array(at["nodes_nodeids"].ints)
        offset = np.concatenate([[0], np.cumsum(np.bincount(tree))])
        assert (offset[tree] + ids == np.arange(len(ids))).all(), "nodes are not stored tree by tree in id order"
        modes = np.array([s.decode() for s in at["nodes_modes"].strings])
        assert set(modes) <= {"LEAF", "BRANCH_LEQ", "BRANCH_EQ"}
        self.leaf, self.equal = modes == "LEAF", modes == "BRANCH_EQ"
        self.feature = np.array(at["nodes_featureids"].ints)
        self.threshold = np.array(at["nodes_values"].floats, dtype=np.float32)
        me = np.arange(len(ids))
        self.true = np.where(self.leaf, me, offset[tree] + np.array(at["nodes_truenodeids"].ints))
        self.false = np.where(self.leaf, me, offset[tree] + np.array(at["nodes_falsenodeids"].ints))
        self.roots = offset[:-1]
        self.value = np.zeros(len(ids))
        self.value[offset[np.array(at["class_treeids"].ints)] + np.array(at["class_nodeids"].ints)] = np.array(at["class_weights"].floats)
        tree_class = np.zeros(len(self.roots), dtype=int)
        tree_class[np.array(at["class_treeids"].ints)] = np.array(at["class_ids"].ints)
        self.tree_class = tree_class
        self.is_dynamic = np.isin(self.feature, dynamic) & ~self.leaf
        assert not (self.equal & self.is_dynamic).any(), "an equality split on a scenario-driven feature"

    def prune(self, row: np.ndarray) -> None:
        """Resolve every split on a feature that is fixed for the loan, keep what the roots can still reach."""
        x = row[self.feature]
        goes_true = np.where(self.equal, x == self.threshold, x <= self.threshold)
        me = np.arange(len(self.leaf))
        ptr = np.where(self.leaf | self.is_dynamic, me, np.where(goes_true, self.true, self.false))
        while True:
            nxt = ptr[ptr]
            if (nxt == ptr).all():
                break
            ptr = nxt
        t, f, roots = ptr[self.true], ptr[self.false], ptr[self.roots]
        seen = np.zeros(len(me), dtype=bool)
        frontier = np.unique(roots)
        while len(frontier):
            seen[frontier] = True
            inner = frontier[self.is_dynamic[frontier]]
            frontier = np.unique(np.concatenate([t[inner], f[inner]]))
            frontier = frontier[~seen[frontier]]
        keep = np.flatnonzero(seen)
        index = np.full(len(me), -1)
        index[keep] = np.arange(len(keep))
        self.k_inner = self.is_dynamic[keep]
        self.k_true, self.k_false = index[t[keep]], index[f[keep]]
        self.k_feature, self.k_threshold, self.k_value = self.feature[keep], self.threshold[keep], self.value[keep]
        self.k_roots = index[roots]
        height = np.zeros(len(keep), dtype=int)
        while True:
            new = np.where(self.k_inner, 1 + np.maximum(height[self.k_true], height[self.k_false]), 0)
            if (new == height).all():
                break
            height = new
        self.levels = [np.flatnonzero(height == h) for h in range(1, height.max() + 1)]

    def logit_bounds(self, lo: np.ndarray, hi: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """Per row and class, the lowest and highest raw score any input in [lo, hi] can produce."""
        out_lo, out_hi = np.zeros((len(lo), 3)), np.zeros((len(lo), 3))
        for start in range(0, len(lo), CHUNK):
            a, b = lo[start:start + CHUNK], hi[start:start + CHUNK]
            v_lo = np.tile(self.k_value, (len(a), 1))
            v_hi = v_lo.copy()
            for nodes in self.levels:
                feature, threshold, t, f = self.k_feature[nodes], self.k_threshold[nodes], self.k_true[nodes], self.k_false[nodes]
                only_true, only_false = b[:, feature] <= threshold, a[:, feature] > threshold
                v_lo[:, nodes] = np.where(only_true, v_lo[:, t], np.where(only_false, v_lo[:, f], np.minimum(v_lo[:, t], v_lo[:, f])))
                v_hi[:, nodes] = np.where(only_true, v_hi[:, t], np.where(only_false, v_hi[:, f], np.maximum(v_hi[:, t], v_hi[:, f])))
            for c in range(3):
                roots = self.k_roots[self.tree_class == c]
                out_lo[start:start + CHUNK, c] = v_lo[:, roots].sum(axis=1)
                out_hi[start:start + CHUNK, c] = v_hi[:, roots].sum(axis=1)
        return out_lo, out_hi


def probability_bounds(z_lo: np.ndarray, z_hi: np.ndarray, c: int) -> tuple[np.ndarray, np.ndarray]:
    others = [j for j in range(3) if j != c]
    low = 1.0 / (1.0 + sum(np.exp(z_hi[:, j] - z_lo[:, c]) for j in others))
    high = 1.0 / (1.0 + sum(np.exp(z_lo[:, j] - z_hi[:, c]) for j in others))
    return low, high


def scenario(target: dict, fu: float, fh: float, fr: float, su: int, sh: int) -> dict:
    """GranuleBuilder.variant with the draws given; forced stress is left out (the recursion handles it)."""
    return dict(target, stressedMonths=0,
                unemploymentShockPp=grs.clamp(target["unemploymentShockPp"] * fu, -5.0, 15.0),
                hpiShockPct=grs.clamp(target["hpiShockPct"] * fh, -30.0, 60.0),
                rateShockBp=grs.clamp(target["rateShockBp"] * fr, -400.0, 600.0),
                unemploymentShockMonths=target["unemploymentShockMonths"] if target["unemploymentShockPp"] == 0.0
                else grs.clamp(target["unemploymentShockMonths"] + su, 1, 60),
                hpiShockMonths=target["hpiShockMonths"] if target["hpiShockPct"] == 0.0 else grs.clamp(target["hpiShockMonths"] + sh, 1, 60))


class Loan:
    """One new loan under one target scenario: its rows for any scenario, and PD bounds over a box."""

    def __init__(self, a: gts.Artifacts, trees: Trees, loan: dict, target: dict) -> None:
        self.a, self.trees, self.loan, self.target = a, trees, loan, target
        self.months = min(loan["originalLoanTerm"], gts.MAX_MONTHS)
        self.initial = a.regime_distribution(a.origin, a.origin)
        trees.prune(self.rows(target)[0])
        self.evaluations = 0

    def rows(self, s: dict) -> np.ndarray:
        l, a = self.loan, self.a
        note = l["originalInterestRate"]
        market = a.rates[-1] + s["rateShockBp"] / 100.0
        p = gts.plan(a, l, dict(s, stressedMonths=0), a.origin, 1, self.months, 0, note - market,
                     a.series(l["propertyState"], "hpi", a.origin), 1.0, 0.0, note - a.rate(a.rate_as_of))
        return np.array(p["rows"], dtype=np.float32)  # two rows a month: calm, stressed

    def recurse(self, hd: np.ndarray, hp: np.ndarray, stressed_months: int) -> tuple[float, float]:
        t, alive, cum, pd12 = self.a.transition, list(self.initial), 0.0, 0.0
        for i in range(self.months):
            if i + 1 <= stressed_months:
                at = [0.0, alive[0] + alive[1]]
            else:
                at = [alive[0] * t[0][0] + alive[1] * t[1][0], alive[0] * t[0][1] + alive[1] * t[1][1]]
            d = [hd[2 * i], hd[2 * i + 1]]
            q = [hp[2 * i], hp[2 * i + 1]]
            cum += at[0] * d[0] + at[1] * d[1]
            alive = [at[0] * (1 - d[0] - q[0]), at[1] * (1 - d[1] - q[1])]
            if i == 11:
                pd12 = cum
        return pd12, cum

    def stressed_lengths(self, shifts) -> list[int]:
        base = self.target["stressedMonths"]
        return [0] if base == 0 else sorted({grs.clamp(base + s, 0, 120) for s in shifts})

    def bounds(self, box: dict) -> dict:
        """Proven [low, high] for pd12m and pdLifetime over every scenario in the box."""
        self.evaluations += 1
        corners_u = list(itertools.product(box["fu"], box["su"]))
        corners_h = list(itertools.product(box["fh"], box["sh"]))
        n = max(len(corners_u), len(corners_h), 2)
        # every scenario-driven feature depends on one (factor, timing) pair and is monotone in the factor, so the
        # column-wise hull over scenarios that between them visit each pair's corners is the exact feature range
        stack = np.stack([self.rows(scenario(self.target, *corners_u[i % len(corners_u)][:1], corners_h[i % len(corners_h)][0],
                                             box["fr"][i % 2], corners_u[i % len(corners_u)][1], corners_h[i % len(corners_h)][1]))
                          for i in range(n)])
        z_lo, z_hi = self.trees.logit_bounds(stack.min(axis=0), stack.max(axis=0))
        scalars = self.a.scalars
        d_lo, d_hi = (v * scalars["default"] for v in probability_bounds(z_lo, z_hi, 1))
        p_lo, p_hi = (v * scalars["prepay"] for v in probability_bounds(z_lo, z_hi, 2))
        assert (d_hi + p_hi <= gts.MAX_EXIT).all(), "the exit cap would bind; the bound below does not model it"
        low = [self.recurse(d_lo, np.minimum(p_hi, 1 - d_lo), m) for m in self.stressed_lengths(box["ss"])]
        high = [self.recurse(d_hi, p_lo, m) for m in self.stressed_lengths(box["ss"])]
        return {"pd12m": (min(v[0] for v in low), max(v[0] for v in high)),
                "pdLifetime": (min(v[1] for v in low), max(v[1] for v in high))}

    def exact(self, s: dict) -> tuple[float, float]:
        rows = self.rows(s)
        probs = self.a.session.run(None, {"input": rows})[1].astype(np.float64)
        return self.recurse(probs[:, 1] * self.a.scalars["default"], probs[:, 2] * self.a.scalars["prepay"], s["stressedMonths"])


def split(box: dict) -> list[dict]:
    """Halve the widest dimension: a factor range, or a set of timings."""
    widths = {k: (box[k][1] - box[k][0]) / (2 * BAND) for k in ("fu", "fh", "fr")}
    widths.update({k: (len(box[k]) - 1) / (2 * TIMING) for k in ("su", "sh", "ss")})
    key = max(widths, key=widths.get)
    if widths[key] <= 1e-9:
        return []
    if key.startswith("f"):
        mid = (box[key][0] + box[key][1]) / 2
        return [dict(box, **{key: (box[key][0], mid)}), dict(box, **{key: (mid, box[key][1])})]
    half = len(box[key]) // 2
    return [dict(box, **{key: box[key][:half]}), dict(box, **{key: box[key][half:]})]


def root_box(target: dict) -> dict:
    """The band, with dimensions the target does not use collapsed (GranuleBuilder keeps a zero at zero)."""
    shifts = tuple(range(-TIMING, TIMING + 1))
    factor = (1 - BAND, 1 + BAND)
    return {"fu": factor if target["unemploymentShockPp"] else (1.0, 1.0), "fh": factor if target["hpiShockPct"] else (1.0, 1.0),
            "fr": factor if target["rateShockBp"] else (1.0, 1.0), "su": shifts if target["unemploymentShockPp"] else (0,),
            "sh": shifts if target["hpiShockPct"] else (0,), "ss": shifts if target["stressedMonths"] else (0,)}


def branch_and_bound(loan: Loan, metric: str, root: dict, first: dict) -> list[list[float]]:
    """Global proven bounds after 0, 1, 2, ... splits. The global bound is the hull of the leaves' bounds."""
    leaves = [(root, first)]
    trail = []
    for step in range(SPLITS + 1):
        low, high = min(b[metric][0] for _, b in leaves), max(b[metric][1] for _, b in leaves)
        trail.append([low, high])
        if step == SPLITS:
            break
        # split the leaf that sets the looser side of the hull
        pick = min(range(len(leaves)), key=lambda i: leaves[i][1][metric][0]) if step % 2 == 0 else \
            max(range(len(leaves)), key=lambda i: leaves[i][1][metric][1])
        parts = split(leaves[pick][0])
        if not parts:
            break
        leaves.pop(pick)
        leaves.extend((part, loan.bounds(part)) for part in parts)
    return trail


def main() -> None:
    a = gts.Artifacts()
    dynamic = [a.order.index(f) for f in ("loan_age", "regime_stressed", "rate_incentive", "unemployment",
                                          "unemployment_change_12m", "hpi_change_12m", "mtm_ltv")]
    trees = Trees(gts.RESOURCES / "survival_model.onnx", dynamic)
    report: dict = {"band": BAND, "timing_band_months": TIMING, "sampled_variants": MEMBERS, "splits": SPLITS, "cases": {}}
    for name, loan_features, target in CASES:
        started = time.time()
        loan = Loan(a, trees, loan_features, target)
        point = dict(root_box(target), fu=(1.0, 1.0), fh=(1.0, 1.0), fr=(1.0, 1.0), su=(0,), sh=(0,), ss=(0,))
        engine = gts.project(a, dict(loan=loan_features, scenario=target))
        zero_width = loan.bounds(point)
        sampled = [loan.exact(target)] + [loan.exact(grs.variant(target, i, BAND, TIMING, SEED)) for i in range(1, MEMBERS)]
        root = root_box(target)
        first = loan.bounds(root)
        case: dict = {"pruned_nodes": int(len(trees.k_inner)), "of_nodes": int(len(trees.leaf)), "tree_levels": len(trees.levels)}
        for j, metric in enumerate(("pd12m", "pdLifetime")):
            trail = branch_and_bound(loan, metric, root, first)
            s_min, s_max = min(v[j] for v in sampled), max(v[j] for v in sampled)
            assert abs(zero_width[metric][0] - engine[metric]) < 1e-6 and abs(zero_width[metric][1] - engine[metric]) < 1e-6, \
                "a zero-width box does not reproduce the engine"
            assert trail[-1][0] <= s_min + 1e-12 and s_max <= trail[-1][1] + 1e-12, "a sampled variant is outside the proven bounds"
            case[metric] = {"engine": round(engine[metric], 6), "zero_width_box": [round(v, 6) for v in zero_width[metric]],
                            "sampled_min_max": [round(s_min, 6), round(s_max, 6)],
                            "proven_no_split": [round(v, 6) for v in trail[0]],
                            f"proven_after_{len(trail) - 1}_splits": [round(v, 6) for v in trail[-1]],
                            "proven_width_over_sampled_width": round((trail[-1][1] - trail[-1][0]) / (s_max - s_min), 2)}
        case["box_evaluations"] = loan.evaluations
        case["seconds"] = round(time.time() - started, 1)
        report["cases"][name] = case
        print(json.dumps({name: case}, indent=2), flush=True)


if __name__ == "__main__":
    main()
