"""Learning the segment graph (openspec/changes/archive/2026-10-19-segment-graph-models, group 2): pure functions on numpy arrays,
so backend/tests/ can check them on synthetic data with known structure.

Two kinds of link, from a panel X (rows = periods, columns = states, standardised residuals):
  moves with  sparse partial correlation: graphical lasso on the correlation matrix
  leads       for each target state, a lasso regression of its value on the lagged values of every state
              (itself included); a link i -> j exists when some lag of i has a non-zero coefficient for j
Stability selection decides which links are kept (Meinshausen and Buhlmann 2010; complementary pairs, Shah and
Samworth 2013): RESAMPLES half-size subsamples made of whole blocks of consecutive periods, drawn as complementary
pairs, each refitted with a fixed penalty. A link is kept when selected in at least STABILITY of them; its
confidence is that share, its weight the median of its non-zero estimates where selected.

The penalty is the smallest at which a subsample selects, on average, at most q links, with
q = sqrt(FALSE_LINKS * (2 * STABILITY - 1) * p) for p candidate links. That is what bounds the expected number of
false links at FALSE_LINKS. A penalty chosen by cross-validation is tuned for prediction and keeps too many: on a
synthetic chain A - B - C it kept the A - C link in 94% of resamples (design.md, amendment of 2026-10-19).
"""
from __future__ import annotations

import warnings
from dataclasses import dataclass

import networkx as nx
import numpy as np
from joblib import Parallel, delayed
from sklearn.covariance import graphical_lasso
from sklearn.exceptions import ConvergenceWarning
from sklearn.linear_model import Lasso

SEED = 20261019
RESAMPLES = 200          # 100 complementary pairs
STABILITY = 0.80
FALSE_LINKS = 1.0        # bound on the expected number of false links, per kind of link and source
PENALTY_SUBSAMPLES = 20  # subsamples used to set the penalty
JOBS = 8
WINSOR_SD = 4.0
MIN_REGION = 3
LOUVAIN_SEED = 7

NAMES = {
    "AK": "Alaska", "AL": "Alabama", "AR": "Arkansas", "AZ": "Arizona", "CA": "California", "CO": "Colorado",
    "CT": "Connecticut", "DC": "District of Columbia", "DE": "Delaware", "FL": "Florida", "GA": "Georgia",
    "HI": "Hawaii", "IA": "Iowa", "ID": "Idaho", "IL": "Illinois", "IN": "Indiana", "KS": "Kansas",
    "KY": "Kentucky", "LA": "Louisiana", "MA": "Massachusetts", "MD": "Maryland", "ME": "Maine", "MI": "Michigan",
    "MN": "Minnesota", "MO": "Missouri", "MS": "Mississippi", "MT": "Montana", "NC": "North Carolina",
    "ND": "North Dakota", "NE": "Nebraska", "NH": "New Hampshire", "NJ": "New Jersey", "NM": "New Mexico",
    "NV": "Nevada", "NY": "New York", "OH": "Ohio", "OK": "Oklahoma", "OR": "Oregon", "PA": "Pennsylvania",
    "RI": "Rhode Island", "SC": "South Carolina", "SD": "South Dakota", "TN": "Tennessee", "TX": "Texas",
    "UT": "Utah", "VA": "Virginia", "VT": "Vermont", "WA": "Washington", "WI": "Wisconsin", "WV": "West Virginia",
    "WY": "Wyoming",
}
# US Census Bureau divisions and their regions.
DIVISIONS = {
    "New England": ["CT", "MA", "ME", "NH", "RI", "VT"],
    "Middle Atlantic": ["NJ", "NY", "PA"],
    "East North Central": ["IL", "IN", "MI", "OH", "WI"],
    "West North Central": ["IA", "KS", "MN", "MO", "ND", "NE", "SD"],
    "South Atlantic": ["DC", "DE", "FL", "GA", "MD", "NC", "SC", "VA", "WV"],
    "East South Central": ["AL", "KY", "MS", "TN"],
    "West South Central": ["AR", "LA", "OK", "TX"],
    "Mountain": ["AZ", "CO", "ID", "MT", "NM", "NV", "UT", "WY"],
    "Pacific": ["AK", "CA", "HI", "OR", "WA"],
}
CENSUS_REGIONS = {
    "Northeast": ["New England", "Middle Atlantic"],
    "Midwest": ["East North Central", "West North Central"],
    "South": ["South Atlantic", "East South Central", "West South Central"],
    "West": ["Mountain", "Pacific"],
}
DIVISION_OF = {s: d for d, states in DIVISIONS.items() for s in states}


def standardise(x: np.ndarray) -> np.ndarray:
    return (x - x.mean(axis=0)) / x.std(axis=0, ddof=1)


# ---- subsamples and the penalty -------------------------------------------------------------------------

def half_subsamples(n: int, block: int, resamples: int = RESAMPLES, seed: int = SEED) -> list[np.ndarray]:
    """Row indices of `resamples` half-size subsamples, as complementary pairs: the rows are cut into consecutive
    blocks of `block` periods (the last one shorter), each pair draws half the blocks at random and takes the
    other half as its complement. Rows stay in time order. Deterministic in `seed`."""
    blocks = [np.arange(i, min(i + block, n)) for i in range(0, n, block)]
    rng = np.random.default_rng(seed)
    out = []
    for _ in range(resamples // 2):
        perm = rng.permutation(len(blocks))
        half = len(blocks) // 2
        for chosen in (np.sort(perm[:half]), np.sort(perm[half:])):
            out.append(np.concatenate([blocks[i] for i in chosen]))
    return out


def max_selected(p: int, false_links: float | None = None, threshold: float = STABILITY) -> float:
    """The average number of links per subsample, q, at which the stability-selection bound
    E[false links] <= q^2 / ((2 * threshold - 1) * p) equals false_links (FALSE_LINKS if None)."""
    bound = FALSE_LINKS if false_links is None else false_links
    return float(np.sqrt(bound * (2 * threshold - 1) * p))


def winsorise(x: np.ndarray, limit: float = WINSOR_SD) -> np.ndarray:
    """Standardises, clips at +-limit standard deviations and standardises again. In the unemployment residuals
    April-May 2020 reach 16 standard deviations and Hurricane Katrina 14; unclipped, whether a subsample holds
    those few months decides its whole correlation matrix and no link is stable."""
    return standardise(np.clip(standardise(x), -limit, limit))


def penalty_for(count, q: float, lo: float, hi: float, steps: int = 14) -> float:
    """Smallest penalty in [lo, hi] (bisection on the log scale) at which count(penalty), the mean number of
    links selected over the penalty subsamples, is at most q. count must not increase with the penalty."""
    if count(hi) > q:
        raise ValueError(f"even penalty {hi} selects more than {q:.1f} links")
    if count(lo) <= q:
        return lo
    a, b = np.log(lo), np.log(hi)
    for _ in range(steps):
        mid = (a + b) / 2
        if count(np.exp(mid)) <= q:
            b = mid
        else:
            a = mid
    return float(np.exp(b))


# ---- moves with -----------------------------------------------------------------------------------------

def partial_correlations(x: np.ndarray, alpha: float) -> np.ndarray:
    """Partial correlations from the graphical-lasso precision of the correlation matrix; zero diagonal."""
    corr = np.corrcoef(x, rowvar=False)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", ConvergenceWarning)
        _, precision = graphical_lasso(corr, alpha=alpha, max_iter=500)
    d = np.sqrt(np.diag(precision))
    pc = -precision / np.outer(d, d)
    np.fill_diagonal(pc, 0.0)
    pc[np.abs(pc) < 1e-10] = 0.0
    return pc


def _partial_correlations_or_none(x: np.ndarray, alpha: float) -> np.ndarray | None:
    try:
        return partial_correlations(x, alpha)
    except FloatingPointError:
        return None


# ---- leads ----------------------------------------------------------------------------------------------

def lag_design(x: np.ndarray, max_lag: int) -> tuple[np.ndarray, np.ndarray]:
    """Targets y[t] = x[t] and features z[t] = (x[t-1], ..., x[t-max_lag]) for t >= max_lag. Feature column
    k * n_states + i is state i at lag k + 1."""
    n = len(x)
    return x[max_lag:], np.hstack([x[max_lag - k:n - k] for k in range(1, max_lag + 1)])


def lasso_coefficients(y: np.ndarray, z: np.ndarray, alpha: float) -> np.ndarray:
    """Coefficient matrix (target x feature), targets and features standardised first, so one penalty and the
    coefficients are comparable across targets."""
    zs, ys = standardise(z), standardise(y)
    gram = zs.T @ zs
    coef = np.empty((y.shape[1], z.shape[1]))
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", ConvergenceWarning)
        for j in range(y.shape[1]):
            coef[j] = Lasso(alpha=alpha, max_iter=5000, precompute=gram, fit_intercept=False).fit(zs, ys[:, j]).coef_
    return coef


def lead_links(coef: np.ndarray, n_states: int, max_lag: int) -> np.ndarray:
    """(from, to, lag) coefficients from a (to, lag * n + from) matrix."""
    return coef.reshape(n_states, max_lag, n_states).transpose(2, 0, 1)


# ---- stability selection --------------------------------------------------------------------------------

@dataclass
class Stability:
    moves_freq: np.ndarray      # (n, n) share of subsamples with a non-zero partial correlation
    moves_weight: np.ndarray    # (n, n) median partial correlation where selected
    leads_freq: np.ndarray      # (n, n) [from, to] share of subsamples with any lag of `from` selected for `to`
    leads_lag: np.ndarray       # (n, n) most often selected lag (1-based, in periods); ties go to the shorter
    leads_weight: np.ndarray    # (n, n) median coefficient at that lag where selected
    moves_samples: list[np.ndarray]  # per subsample, the partial-correlation matrix (for region stability)
    glasso_penalty: float
    lasso_penalty: float
    q_moves: float
    q_leads: float


def _median_where(values: np.ndarray, selected: np.ndarray) -> np.ndarray:
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        return np.nan_to_num(np.nanmedian(np.where(selected, values, np.nan), axis=0))


def stability(x: np.ndarray, max_lag: int, block: int, resamples: int = RESAMPLES, seed: int = SEED,
              jobs: int = JOBS) -> Stability:
    n_states = x.shape[1]
    x = winsorise(x)
    y, z = lag_design(x, max_lag)
    moves_rows = half_subsamples(len(x), block, resamples, seed)
    leads_rows = half_subsamples(len(y), block, resamples, seed + 1)
    upper = np.triu_indices(n_states, k=1)
    off = ~np.eye(n_states, dtype=bool)

    with Parallel(n_jobs=jobs) as par:
        def n_moves(alpha):
            pcs = par(delayed(_partial_correlations_or_none)(x[r], alpha) for r in moves_rows[:PENALTY_SUBSAMPLES])
            # A penalty too small for the solver (more states than periods in a subsample) is too small, full stop.
            return np.inf if any(pc is None for pc in pcs) else np.mean([(pc[upper] != 0).sum() for pc in pcs])

        def n_leads(alpha):
            cs = par(delayed(lasso_coefficients)(y[r], z[r], alpha) for r in leads_rows[:PENALTY_SUBSAMPLES])
            return np.mean([((lead_links(c, n_states, max_lag) != 0).any(axis=2) & off).sum() for c in cs])

        q_moves = max_selected(n_states * (n_states - 1) // 2)
        q_leads = max_selected(n_states * (n_states - 1))
        a_g = penalty_for(n_moves, q_moves, 1e-3, 0.999)
        a_l = penalty_for(n_leads, q_leads, 1e-3, 1.0)

        pcs = par(delayed(partial_correlations)(x[r], a_g) for r in moves_rows)
        coefs = par(delayed(lasso_coefficients)(y[r], z[r], a_l) for r in leads_rows)

    pcs_a = np.array(pcs)
    sel = pcs_a != 0
    moves_freq, moves_weight = sel.mean(axis=0), _median_where(pcs_a, sel)

    lc = np.array([lead_links(c, n_states, max_lag) for c in coefs])   # (b, from, to, lag)
    lsel = lc != 0
    leads_freq = lsel.any(axis=3).mean(axis=0)
    leads_lag = lsel.sum(axis=0).argmax(axis=2) + 1                     # argmax takes the first: the shorter lag
    idx = np.broadcast_to((leads_lag - 1)[None, :, :, None], lc.shape[:3] + (1,))
    at_lag = np.take_along_axis(lc, idx, axis=3)[..., 0]
    leads_weight = _median_where(at_lag, at_lag != 0)
    for m in (moves_freq, moves_weight, leads_freq, leads_weight):
        np.fill_diagonal(m, 0.0)  # a state's own past is a control, not a link
    return Stability(moves_freq, moves_weight, leads_freq, leads_lag, leads_weight, pcs, a_g, a_l, q_moves, q_leads)


# ---- regions --------------------------------------------------------------------------------------------

def communities(states: list[str], links: list[tuple[str, str, float]], seed: int = LOUVAIN_SEED,
                min_size: int = MIN_REGION) -> list[list[str]]:
    """Louvain communities of the positive links (weighted), then every community smaller than min_size is
    merged into the community it is most strongly linked to. A small community with no links to any other is
    dropped: its states belong to no region. Sorted by size, then by first member."""
    g = nx.Graph()
    g.add_nodes_from(sorted(states))
    g.add_weighted_edges_from((a, b, w) for a, b, w in links if w > 0)
    comms = [sorted(c) for c in nx.community.louvain_communities(g, weight="weight", seed=seed)]
    while True:
        comms.sort(key=lambda c: (len(c), c[0]))
        small = next((c for c in comms if len(c) < min_size), None)
        if small is None:
            break
        comms.remove(small)
        strength = [sum(g[u][v]["weight"] for u in small for v in c if g.has_edge(u, v)) for c in comms]
        if comms and max(strength) > 0:
            target = comms[int(np.argmax(strength))]
            target.extend(small)
            target.sort()
    comms.sort(key=lambda c: (-len(c), c[0]))
    return comms


def co_assignment(states: list[str], resample_links: list[list[tuple[str, str, float]]]) -> np.ndarray:
    """Share of resamples in which each pair of states lands in the same region."""
    pos = {s: i for i, s in enumerate(states)}
    together = np.zeros((len(states), len(states)))
    for links in resample_links:
        for c in communities(states, links):
            ix = [pos[s] for s in c]
            together[np.ix_(ix, ix)] += 1
    return together / max(len(resample_links), 1)


def region_label(members: list[str], taken: set[str], index: int) -> str:
    """The Census division that holds at least two thirds of the members, else such a Census region, else
    "Region N"; a name already used by a larger region is not reused."""
    by_region = {r: [s for d in ds for s in DIVISIONS[d]] for r, ds in CENSUS_REGIONS.items()}
    for groups in (DIVISIONS, by_region):
        for name, group in groups.items():
            if name not in taken and sum(s in group for s in members) >= 2 / 3 * len(members):
                return name
    return f"Region {index}"
