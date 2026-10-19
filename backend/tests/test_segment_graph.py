"""Learning the segment graph (segment_graph.py) on synthetic data with known structure."""
import numpy as np
import pytest

import segment_graph as sg


def chain(seed: int, n: int = 400) -> np.ndarray:
    """A -> B -> C, plus two independent states D and E."""
    rng = np.random.default_rng(seed)
    a = rng.normal(size=n)
    b = a + rng.normal(size=n)
    c = b + rng.normal(size=n)
    return np.column_stack([a, b, c, rng.normal(size=n), rng.normal(size=n)])


@pytest.mark.parametrize("seed", [0, 1, 2])
def test_moves_with_links_are_direct(seed):
    s = sg.stability(chain(seed), max_lag=1, block=12, resamples=60, jobs=1)
    keep = s.moves_freq >= sg.STABILITY
    assert keep[0, 1] and keep[1, 2]
    assert not keep[0, 2], f"A - C selected in {s.moves_freq[0, 2]:.0%} of subsamples"
    assert not keep[:, 3:].any() and not keep[3:, :].any()
    assert s.moves_weight[0, 1] > 0 and s.moves_weight[1, 2] > 0


def test_a_lagged_driver_becomes_a_leads_link_with_its_lag():
    rng = np.random.default_rng(5)
    n = 500
    a = rng.normal(size=n)
    b = np.r_[np.zeros(2), 0.8 * a[:-2]] + rng.normal(scale=0.6, size=n)  # B follows A by 2 periods
    x = np.column_stack([a, b, rng.normal(size=n), rng.normal(size=n)])
    s = sg.stability(x, max_lag=3, block=12, resamples=60, jobs=1)
    assert s.leads_freq[0, 1] >= sg.STABILITY
    assert s.leads_lag[0, 1] == 2
    assert s.leads_weight[0, 1] > 0
    assert s.leads_freq[1, 0] < sg.STABILITY
    assert (np.delete(s.leads_freq[2:], [], axis=0) < sg.STABILITY).all()


def test_two_runs_give_identical_results():
    x = chain(3)
    a = sg.stability(x, max_lag=1, block=12, resamples=40, jobs=1)
    b = sg.stability(x, max_lag=1, block=12, resamples=40, jobs=2)
    for field in ("moves_freq", "moves_weight", "leads_freq", "leads_lag", "leads_weight"):
        assert np.array_equal(getattr(a, field), getattr(b, field)), field
    assert a.glasso_penalty == b.glasso_penalty and a.lasso_penalty == b.lasso_penalty


def test_half_subsamples_are_complementary_pairs_of_whole_blocks():
    subs = sg.half_subsamples(50, block=12, resamples=10, seed=1)
    assert len(subs) == 10
    for first, second in zip(subs[::2], subs[1::2]):
        assert sorted(np.r_[first, second]) == list(range(50))
        assert not set(first) & set(second)
    blocks = [set(range(i, min(i + 12, 50))) for i in range(0, 50, 12)]
    for s in subs:
        assert all(b <= set(s) or not (b & set(s)) for b in blocks)
        assert list(s) == sorted(s)
    assert [list(s) for s in subs] == [list(s) for s in sg.half_subsamples(50, block=12, resamples=10, seed=1)]


def test_the_penalty_meets_the_false_link_bound():
    q = sg.max_selected(1275)
    assert q == pytest.approx(np.sqrt(0.6 * 1275))
    assert q ** 2 / ((2 * sg.STABILITY - 1) * 1275) == pytest.approx(sg.FALSE_LINKS)
    count = lambda alpha: 100 * (1 - alpha)  # links fall as the penalty rises
    alpha = sg.penalty_for(count, q=30, lo=0.01, hi=0.99)
    assert count(alpha) <= 30 and count(alpha * 0.98) > 30 - 1
    with pytest.raises(ValueError):
        sg.penalty_for(count, q=0.5, lo=0.01, hi=0.99)


def test_regions_have_at_least_three_states():
    links = [("A", "B", 0.5), ("B", "C", 0.4), ("A", "C", 0.3),       # a triangle
             ("D", "E", 0.6), ("E", "C", 0.1),                           # a pair hanging off it
             ("F", "G", 0.5),                                            # an isolated pair
             ("H", "I", -0.5)]                                           # negative links do not group
    comms = sg.communities(list("ABCDEFGHI"), links)
    assert comms == [["A", "B", "C", "D", "E"]]
    assert all(len(c) >= sg.MIN_REGION for c in comms)


def test_co_assignment_counts_shared_regions():
    states = list("ABCDEF")
    same = [("A", "B", 1.0), ("B", "C", 1.0), ("A", "C", 1.0), ("D", "E", 1.0), ("E", "F", 1.0), ("D", "F", 1.0)]
    split = [("A", "B", 1.0), ("B", "D", 1.0), ("A", "D", 1.0), ("C", "E", 1.0), ("E", "F", 1.0), ("C", "F", 1.0)]
    t = sg.co_assignment(states, [same, split])
    assert t[0, 1] == 1.0 and t[0, 2] == 0.5 and t[0, 4] == 0.0


def test_region_labels():
    assert sg.region_label(["CT", "MA", "RI"], set(), 1) == "New England"
    assert sg.region_label(["CT", "MA", "NY"], set(), 1) == "New England"
    assert sg.region_label(["CT", "NJ", "NY"], set(), 1) == "Middle Atlantic"
    assert sg.region_label(["CT", "MA", "RI"], {"New England"}, 2) == "Northeast"
    assert sg.region_label(["CA", "TX", "NY"], set(), 3) == "Region 3"
    assert set(sg.NAMES) == set(sg.DIVISION_OF) and len(sg.NAMES) == 51
