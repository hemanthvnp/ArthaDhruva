"""How a loan's history features are aged over a multi-state projection (numpy only; no model, no data).

The transition model is conditioned on three history features (spells of 30+ days past due that began in the last 12
months, months since the latest cure, the worst state in the last 12 months). They depend on the path, which would make
the exact recursion over delinquency states impossible, so a projection ages the history at the start by a fixed rule
instead of following it. The rule is defined once, here, in two forms that tests hold equal:

  history_at     one row, in plain Python: the reference golden_multistate.py and the Java engine are written from it
  aged_history   arrays: what export_delinquency_model.py evaluates its 12-month rates with

On any path with no new spell the history is exactly clean once 12 months have passed, because the features are defined
over a 12-month window, and the rule uses that. A spell that begins during the projection is represented only by the model
seeing, from a late state, at least one spell and a worst state at least the source (every training row from a late state
has them); a spell that ends during it is taken to have ended at the start, the most recent a cure can be.
"""
from __future__ import annotations

import numpy as np

NO_CURE = 13          # months-since-cure when there was none in the last 12 months
WINDOW = 12


def history_at(source: int, elapsed: int, episodes: int, since: int, worst: int) -> tuple[int, int, int]:
    """The (episodes, months since cure, worst state) a model row is evaluated with.

    source   the state the row is evaluated from (0 current, 1 30 days past due, 2 60 days)
    elapsed  months between the projection start and the month predicted (0 for the first projected month)
    episodes, since, worst   the loan's history at the start (since = NO_CURE when there was no cure in the window)

    It depends on the history and the month, never on the state the loan starts in: loans that differ only in their starting
    state are projected with the same transition matrices, which is what lets the monotone repair guarantee that a worse
    start never has a lower lifetime default. A loan that starts late already has what a late loan must carry (a spell, and a
    worst state at least today's), so nothing is added for it.

    From the current state: a cure the loan has already had ages (since + elapsed); a spell with no cure in the window is
    taken to end at the start (elapsed), the most recent a cure can be; once the cure is a window old the history is clean.
    From a late state: at least one spell and a worst state at least the source; earlier spells, and the worst state before
    them, roll out once the last cure before the projection is a window old.
    """
    if source == 0:
        age = min(since + elapsed, NO_CURE) if since < NO_CURE else (elapsed if episodes > 0 else NO_CURE)
        if age >= WINDOW:
            return 0, NO_CURE, 0
        return episodes, age, worst
    aged = min(since + elapsed, NO_CURE) if since < NO_CURE else NO_CURE
    rolled = aged >= WINDOW
    return max(0 if rolled else episodes, 1), NO_CURE if rolled else aged, max(0 if rolled else worst, source)


def aged_history(source, elapsed, episodes, since, worst):
    """history_at over arrays (broadcast together): the three features as integer arrays."""
    source, elapsed, episodes, since, worst = (np.asarray(a, dtype=np.int64) for a in (source, elapsed, episodes, since, worst))
    none = since >= NO_CURE
    # from the current state
    c_age = np.where(none, np.where(episodes > 0, elapsed, NO_CURE), np.minimum(since + elapsed, NO_CURE))
    c_clean = c_age >= WINDOW
    c_episodes = np.where(c_clean, 0, episodes)
    c_since = np.where(c_clean, NO_CURE, c_age)
    c_worst = np.where(c_clean, 0, worst)
    # from a delinquent state
    d_aged = np.where(none, NO_CURE, np.minimum(since + elapsed, NO_CURE))
    d_rolled = d_aged >= WINDOW
    d_episodes = np.maximum(np.where(d_rolled, 0, episodes), 1)
    d_since = np.where(d_rolled, NO_CURE, d_aged)
    d_worst = np.maximum(np.where(d_rolled, 0, worst), source)
    current = source == 0
    return np.where(current, c_episodes, d_episodes), np.where(current, c_since, d_since), np.where(current, c_worst, d_worst)


# Classes are (current, 30, 60, default, prepaid); ordered from best to worst they are prepaid, current, 30, 60, default.
_BEST_TO_WORST = [4, 0, 1, 2, 3]
_WORST_TO_INDEX = [1, 2, 3, 4, 0]


def monotone_repair(p):
    """Makes one month's transition probabilities stochastically ordered by source state.

    `p` has the three source states on its second-last axis and the five classes on its last (current, 30, 60, default,
    prepaid). With the classes ordered prepaid < current < 30 < 60 < default, a worse source state must have no smaller
    probability of landing at or above any given class than a better one has. Where the model's raw output breaks that
    (a loan 30 days late judged safer than a current one with the same recent history, from a sparse cell or from how
    "just cured" and "cured some months ago" are read), each upper-tail probability is replaced by its running maximum
    over source states in order. The result is a valid probability vector (non-negative, summing to 1), is unchanged
    where the raw output already respects the order, and is idempotent.

    A chain whose every step is stochastically ordered keeps the order of its starting states: started higher it ends
    stochastically higher, and default is the top state and absorbing, so the probability of having defaulted by any month
    does not fall as the starting state worsens. That holds on each regime path, so it holds for the expectation over
    regime paths the engine computes. It is the guarantee behind the monotonicity gate.
    """
    p = np.asarray(p, dtype=np.float64)
    q = p[..., _BEST_TO_WORST]
    tail = np.cumsum(q[..., ::-1], axis=-1)[..., ::-1]            # tail[..., k] = probability of class k or worse
    tail = np.maximum.accumulate(tail, axis=-2)                   # across source states 0, 1, 2
    q = np.concatenate([tail[..., :-1] - tail[..., 1:], tail[..., -1:]], axis=-1)
    return q[..., _WORST_TO_INDEX]
