"""Regional loss factors (openspec/changes/archive/2026-10-19-segment-graph-models, group 3, design decision 6): pure functions on
region x quarter arrays, so backend/tests/ can check the estimator recovers a known value.

The loss simulation's one-factor copula, extended by a regional factor:
    X_i = sqrt(rho_n) Z + sqrt(rho_r) R_region(i) + sqrt(1 - rho_n - rho_r) e_i
For region r and quarter t, d[r, t] is the probit of the realised default rate minus the probit of the rate the
survival model expected, out of time. The quarter's national deviation (the exposure-weighted mean of the
regional ones) is subtracted, which removes Z and anything else common to every region; what is left, net of binomial sampling noise, has variance
v = rho_r / (1 - rho_n - rho_r) to first order, so rho_r = v (1 - rho_n) / (1 + v).

The correlation between regions is a Ledoit-Wolf estimate, shrunk toward the identity, of the deviations after
the national one is removed. Removing the pooled national deviation biases those correlations slightly negative
(about -1 / (regions - 1) for equal regions); with three years of quarters the shrinkage dominates anyway.
"""
from __future__ import annotations

import numpy as np
from scipy.stats import binom, chi2, norm
from sklearn.covariance import LedoitWolf

RHO_NATIONAL = 0.15      # CvarRequest.DEFAULT_ASSET_CORRELATION
BOOT_DRAWS = 1000
BOOT_BLOCK = 2           # quarters
LEVEL = 0.95
SEED = 20261019


def probit_of_rate(events: np.ndarray, exposure: np.ndarray) -> np.ndarray:
    """Probit of the default rate; half an event where there are none, so an empty cell stays finite."""
    return norm.ppf(np.where(events > 0, events, 0.5) / exposure)


def probit_moments(expected: np.ndarray, exposure: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Exact mean and variance of probit_of_rate(X, N) for X ~ Binomial(N, expected / N), summed over the
    binomial distribution (to 12 standard deviations). The delta method understates both at a hundred or so
    defaults per cell, and the excess then reads as regional variation: on simulated portfolios with no
    regional factor the interval excluded zero in 97% of cases."""
    mean, var = np.empty(expected.shape), np.empty(expected.shape)
    for idx in np.ndindex(expected.shape):
        n, lam = int(round(exposure[idx])), float(expected[idx])
        p = lam / n
        sd = np.sqrt(lam * (1 - p))
        x = np.arange(max(0, int(lam - 12 * sd) - 1), int(lam + 12 * sd) + 2)
        pmf = binom.pmf(x, n, p)
        pmf /= pmf.sum()
        z = probit_of_rate(x.astype(float), float(n))
        mean[idx] = (pmf * z).sum()
        var[idx] = (pmf * (z - mean[idx]) ** 2).sum()
    return mean, var


def deviations(events: np.ndarray, exposure: np.ndarray, expected: np.ndarray):
    """u[r, t]: the regional deviation with the quarter's national deviation removed; its noise variance; and
    the regions' exposure shares w[r, t]."""
    # The noise depends on the level of the rate, and a national surprise moves every region's level (in the
    # simulation one standard deviation of Z roughly triples or thirds it); so the moments are taken at the
    # expected count scaled by the quarter's national actual / expected ratio.
    scaled = expected * (events.sum(axis=0) / expected.sum(axis=0))
    centre, noise = probit_moments(scaled, exposure)
    d = probit_of_rate(events, exposure) - centre
    w = exposure / exposure.sum(axis=0)
    # The exposure-weighted mean of the regional deviations, not the probit of the pooled rate: the probit is
    # not linear, and only the linear form makes regional_variance's expectation exact.
    return d - (w * d).sum(axis=0), noise, w


def regional_variance(u: np.ndarray, noise: np.ndarray, w: np.ndarray) -> float:
    """v from u = s (R - sum_k w_k R_k) + e - sum_k w_k e_k, with independent R of variance v = s^2 and noise e of
    variance noise: E[u^2] = v (1 - 2 w + sum w^2) + noise (1 - 2 w) + sum_k w_k^2 noise_k, per region and quarter."""
    sw2 = (w ** 2).sum(axis=0)
    wn = (w ** 2 * noise).sum(axis=0)
    signal = (u ** 2 - noise * (1 - 2 * w) - wn).sum()
    return float(signal / (1 - 2 * w + sw2).sum())


def share_from_variance(v: float, rho_n: float = RHO_NATIONAL) -> float:
    """rho_r = v (1 - rho_n) / (1 + v); a negative v (noise larger than the spread) gives 0."""
    return max(0.0, v * (1 - rho_n) / (1 + v))


def bootstrap_interval(u: np.ndarray, noise: np.ndarray, w: np.ndarray, rho_n: float, draws: int,
                       seed: int) -> list[float]:
    """Percentile interval from a moving-block bootstrap over quarters (blocks of BOOT_BLOCK). Kept for the
    comparison in the card: on simulated portfolios of the real size it covered the known value in only 82-88%
    of cases, short of the 90% the spec asks, so it is not the interval that is shipped."""
    t = u.shape[1]
    rng = np.random.default_rng(seed)
    boot = np.empty(draws)
    for b in range(draws):
        starts = rng.integers(0, t - BOOT_BLOCK + 1, size=-(-t // BOOT_BLOCK))
        cols = (starts[:, None] + np.arange(BOOT_BLOCK)).ravel()[:t]
        boot[b] = share_from_variance(regional_variance(u[:, cols], noise[:, cols], w[:, cols]), rho_n)
    lo, hi = np.quantile(boot, [(1 - LEVEL) / 2, 1 - (1 - LEVEL) / 2])
    return [float(lo), float(hi)]


def chi2_interval(u: np.ndarray, noise: np.ndarray, w: np.ndarray, rho_n: float) -> list[float]:
    """The sum of squared deviations is a scaled chi-square on (regions - 1) x quarters degrees of freedom (one
    per quarter goes to the national deviation): S / df estimates v a + n, with a and n the average signal and
    noise multipliers of regional_variance. The interval for that is S / chi2 quantiles; the noise is taken off
    and the result mapped to rho_r."""
    k, t = u.shape
    df = (k - 1) * t
    sw2 = (w ** 2).sum(axis=0)
    a = (1 - 2 * w + sw2).sum() / df
    n = (noise * (1 - 2 * w) + (w ** 2 * noise).sum(axis=0)).sum() / df
    s = float((u ** 2).sum())
    bounds = [s / chi2.ppf(1 - (1 - LEVEL) / 2, df), s / chi2.ppf((1 - LEVEL) / 2, df)]
    return [share_from_variance((m - n) / a, rho_n) for m in bounds]


def estimate(events: np.ndarray, exposure: np.ndarray, expected: np.ndarray, rho_n: float = RHO_NATIONAL,
             draws: int = BOOT_DRAWS, seed: int = SEED, bootstrap: bool = False) -> dict:
    """The regional share with its chi-square interval (and, if asked, the bootstrap one for comparison)."""
    u, noise, w = deviations(events, exposure, expected)
    v = regional_variance(u, noise, w)
    share = share_from_variance(v, rho_n)
    lo, hi = chi2_interval(u, noise, w, rho_n)
    out = {"estimate": share, "interval": [lo, hi], "default": share if lo > 0 else 0.0,
           "variance_v": v, "rho_national": rho_n}
    if bootstrap:
        out["bootstrap_interval"] = bootstrap_interval(u, noise, w, rho_n, draws, seed)
    return out


def region_correlation(u: np.ndarray) -> np.ndarray:
    """Ledoit-Wolf correlation of the regions' deviations (u is regions x quarters): unit diagonal, symmetric,
    positive semi-definite."""
    x = u.T
    x = (x - x.mean(axis=0)) / x.std(axis=0, ddof=1)
    cov = LedoitWolf(assume_centered=True).fit(x).covariance_
    d = np.sqrt(np.diag(cov))
    corr = cov / np.outer(d, d)
    corr = (corr + corr.T) / 2
    np.fill_diagonal(corr, 1.0)
    return corr


def simulate(exposure: np.ndarray, base_rate: np.ndarray, rho_r: float, rho_n: float, rng: np.random.Generator):
    """Default counts from the two-level factor model: one draw of Z per quarter and of R per region and quarter
    (independent across regions), binomial given the conditional default probability. Returns (events,
    expected) with expected = exposure x base_rate, the unconditional expectation."""
    k, t = exposure.shape
    z = rng.standard_normal(t)
    r = rng.standard_normal((k, t))
    threshold = norm.ppf(base_rate)
    p = norm.cdf((threshold - np.sqrt(rho_n) * z - np.sqrt(rho_r) * r) / np.sqrt(1 - rho_n - rho_r))
    events = rng.binomial(exposure.astype(np.int64), p).astype(float)
    return events, exposure * base_rate


def coverage(exposure: np.ndarray, base_rate: np.ndarray, rho_r: float, sims: int = 200, rho_n: float = RHO_NATIONAL,
             seed: int = SEED, draws: int = 300) -> float:
    """Share of `sims` simulated portfolios whose interval contains the known rho_r."""
    rng = np.random.default_rng(seed)
    hits = 0
    for i in range(sims):
        events, expected = simulate(exposure, base_rate, rho_r, rho_n, rng)
        lo, hi = estimate(events, exposure, expected, rho_n, draws=draws, seed=seed + i)["interval"]
        hits += lo <= rho_r <= hi
    return hits / sims
