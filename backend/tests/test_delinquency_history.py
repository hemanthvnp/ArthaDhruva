"""The rule that ages a loan's history over a multi-state projection (delinquency_history.py): worked cases, the property
that makes it sound (clean once the window has passed), and equality of its two forms, which the export (arrays) and the
reference and Java engine (one row) rely on."""
import itertools

import numpy as np

from delinquency_history import NO_CURE, WINDOW, aged_history, history_at


def test_the_two_forms_agree_everywhere():
    cases = list(itertools.product(range(3), range(0, 16), range(0, 4), range(0, NO_CURE + 1), range(3)))
    source, elapsed, episodes, since, worst = (np.array(column) for column in zip(*cases))
    e, s, w = aged_history(source, elapsed, episodes, since, worst)
    for i, case in enumerate(cases):
        assert (int(e[i]), int(s[i]), int(w[i])) == history_at(*case), case


def test_a_current_loan_with_a_recent_cure_ages_and_is_clean_after_the_window():
    h = (2, 3, 1)                                   # two spells, the last cured 3 months ago, worst 30 days
    assert history_at(0, 0, *h) == (2, 3, 1)
    assert history_at(0, 8, *h) == (2, 11, 1)
    assert history_at(0, 9, *h) == (0, NO_CURE, 0), "the cure is a window old: nothing left in the window"
    assert history_at(0, 300, *h) == (0, NO_CURE, 0)


def test_a_clean_loan_stays_clean_from_current_and_shows_one_fresh_spell_from_a_late_state():
    clean = (0, NO_CURE, 0)
    for elapsed in (0, 1, 40):
        assert history_at(0, elapsed, *clean) == (0, NO_CURE, 0)
        assert history_at(1, elapsed, *clean) == (1, NO_CURE, 1)      # a spell that has just begun
        assert history_at(2, elapsed, *clean) == (1, NO_CURE, 2)


def test_a_loan_that_starts_late_is_taken_to_have_cured_at_the_start_when_it_is_current_again():
    late = (1, NO_CURE, 1)                          # 30 days past due now, one spell, never cured in the window
    assert history_at(0, 0, *late) == (1, 0, 1)
    assert history_at(0, 5, *late) == (1, 5, 1)
    assert history_at(0, WINDOW, *late) == (0, NO_CURE, 0)
    # and from the late states themselves the current spell is in the history, with the worse of the source and the start
    assert history_at(1, 3, *late) == (1, NO_CURE, 1)
    assert history_at(2, 3, *late) == (1, NO_CURE, 2)


def test_a_delinquent_row_always_carries_its_own_spell():
    for episodes, since, worst in itertools.product(range(3), range(0, NO_CURE + 1), range(3)):
        for source, elapsed in itertools.product((1, 2), range(0, 20)):
            e, s, w = history_at(source, elapsed, episodes, since, worst)
            assert e >= 1 and w >= source and 0 <= s <= NO_CURE


def _random_months(n, seed):
    rng = np.random.default_rng(seed)
    p = rng.random((n, 3, 5)) ** 3 + 1e-4
    return p / p.sum(axis=-1, keepdims=True)


def test_the_repair_gives_valid_probabilities_with_ordered_tails():
    from delinquency_history import monotone_repair
    fixed = monotone_repair(_random_months(500, 1))
    assert (fixed >= -1e-15).all() and np.allclose(fixed.sum(axis=-1), 1.0, atol=1e-12)
    # classes are (current, 30, 60, default, prepaid); worst to best order is default, 60, 30, current, prepaid
    best_to_worst = fixed[..., [4, 0, 1, 2, 3]]
    tails = np.cumsum(best_to_worst[..., ::-1], axis=-1)[..., ::-1]
    assert (tails[:, 1:, 1:] >= tails[:, :-1, 1:] - 1e-12).all(), "a worse source state never has a smaller upper tail"


def test_the_repair_leaves_an_ordered_month_alone_and_is_idempotent():
    from delinquency_history import monotone_repair
    ordered = np.array([[[0.90, 0.05, 0.00, 0.01, 0.04], [0.60, 0.20, 0.10, 0.08, 0.02], [0.20, 0.20, 0.20, 0.38, 0.02]]])
    assert np.allclose(monotone_repair(ordered), ordered, atol=1e-15)
    once = monotone_repair(_random_months(200, 2))
    assert np.allclose(monotone_repair(once), once, atol=1e-12)


def test_with_the_repair_default_by_any_month_does_not_fall_as_the_starting_state_worsens():
    """The guarantee it exists for, checked on the recursion itself with random, disordered probabilities and a regime chain."""
    from delinquency_history import monotone_repair
    chain = np.array([[0.95, 0.05], [0.10, 0.90]])
    for seed in range(20):
        p = monotone_repair(_random_months(2 * 60, seed)).reshape(60, 2, 3, 5)     # month, regime, source, class
        by_start = []
        for start in range(3):
            alive = np.zeros((2, 3))
            alive[:, start] = [0.7, 0.3]
            default, total = 0.0, []
            for month in range(60):
                at = chain.T @ alive                                                 # regime of the month, by state
                default += sum(at[r, s] * p[month, r, s, 3] for r in range(2) for s in range(3))
                alive = np.array([[sum(at[r, s] * p[month, r, s, s2] for s in range(3)) for s2 in range(3)] for r in range(2)])
                total.append(default)
            by_start.append(total)
        by_start = np.array(by_start)
        assert (by_start[1] >= by_start[0] - 1e-12).all() and (by_start[2] >= by_start[1] - 1e-12).all(), seed
