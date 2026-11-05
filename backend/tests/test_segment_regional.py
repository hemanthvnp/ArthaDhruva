"""Regional loss factors (segment_regional.py): the estimator recovers what it is asked to measure."""
import numpy as np
import pytest

import segment_regional as sr

# Sizes like the real out-of-time panel: five regions, eleven quarters (2023-Q1 to 2025-Q3), and about 180
# sampled defaults per region and quarter (design.md, decision 6).
K, T = 5, 11
EXPOSURE = np.full((K, T), 150_000.0)
BASE = np.full((K, T), 0.0012)


def test_the_known_regional_share_is_inside_the_interval_in_90_percent_of_200_simulations():
    assert sr.coverage(EXPOSURE, BASE, rho_r=0.02, sims=200) >= 0.90


def test_unequal_regions_and_rates():
    exposure = np.outer([300_000.0, 150_000.0, 100_000.0, 80_000.0], np.ones(T))
    base = np.outer([0.0008, 0.0012, 0.0015, 0.0020], np.ones(T))
    assert sr.coverage(exposure, base, rho_r=0.03, sims=200, seed=11) >= 0.90


def test_no_regional_factor_gives_a_small_estimate():
    rng = np.random.default_rng(3)
    estimates = []
    for _ in range(50):
        events, expected = sr.simulate(EXPOSURE, BASE, 0.0, sr.RHO_NATIONAL, rng)
        estimates.append(sr.estimate(events, EXPOSURE, expected)["estimate"])
    assert np.median(estimates) < 0.005


def test_an_interval_including_zero_ships_zero():
    events = np.full((K, T), 180.0)
    out = sr.estimate(events, EXPOSURE, EXPOSURE * 0.0012)
    assert out["interval"][0] == 0.0
    assert out["default"] == 0.0


def test_share_from_variance_inverts_the_moment_relation():
    rho_n, rho_r = 0.15, 0.04
    v = rho_r / (1 - rho_n - rho_r)
    assert sr.share_from_variance(v, rho_n) == pytest.approx(rho_r)
    assert sr.share_from_variance(-0.01, rho_n) == 0.0


def test_region_correlation_is_a_valid_correlation_matrix():
    rng = np.random.default_rng(4)
    u = rng.normal(size=(K, T)) + 0.5 * rng.normal(size=T)
    c = sr.region_correlation(u)
    assert np.allclose(c, c.T)
    assert np.allclose(np.diag(c), 1.0)
    assert np.linalg.eigvalsh(c).min() >= -1e-12
    assert np.abs(c).max() <= 1.0 + 1e-12


def test_exact_moments_match_simulation():
    rng = np.random.default_rng(5)
    n, p = 120_000, 0.001
    draws = sr.probit_of_rate(rng.binomial(n, p, size=200_000).astype(float), float(n))
    mean, var = sr.probit_moments(np.array([[n * p]]), np.array([[float(n)]]))
    assert mean[0, 0] == pytest.approx(draws.mean(), abs=2e-4)
    assert var[0, 0] == pytest.approx(draws.var(), rel=0.02)
