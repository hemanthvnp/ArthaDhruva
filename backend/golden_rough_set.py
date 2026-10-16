"""Independent reference implementation of the rough-set decision layer, for a cross-language golden test.

roughset.GranuleBuilder / SplitMix64 / RegionClassifier (Java) turn a seed into scenario variants and a vector
of metrics into a three-way region. This script implements the same recurrences again in plain Python --
splitmix64, the clamped perturbation of a Scenario, the FNV-1a seed hash, and the variable-precision rough-set
rule -- and writes inputs and expected outputs to
risk-engine/src/test/resources/rough_set_golden.json. RoughSetGoldenTest asserts that Java reproduces them
exactly (members, regions, counts) and to 1e-9 (derived widths and medians), so a change on either side that
alters one draw or one rule fails the build.

The engine's own outputs are not part of this contract: TermStructureEngine is already held to its own
Python reference by golden_term_structure.py. No model artifact is read or written here.

Run from backend/:  python golden_rough_set.py
"""
from __future__ import annotations

import json
import math
import random
from pathlib import Path

OUT = Path("risk-engine/src/test/resources/rough_set_golden.json")

MASK = (1 << 64) - 1
GAMMA = 0x9E3779B97F4A7C15
EPSILON = 1e-12

BASELINE = dict(name="BASELINE", description="", stressedMonths=0, unemploymentShockPp=0.0, unemploymentShockMonths=12,
                unemploymentRecoveryMonths=0, hpiShockPct=0.0, hpiShockMonths=12, hpiAnnualGrowthPct=3.0, rateShockBp=0.0)
ADVERSE = dict(BASELINE, name="ADVERSE", stressedMonths=12, unemploymentShockPp=3.0, unemploymentRecoveryMonths=36,
               hpiShockPct=10.0, hpiAnnualGrowthPct=2.0)
SEVERELY_ADVERSE = dict(BASELINE, name="SEVERELY_ADVERSE", stressedMonths=24, unemploymentShockPp=5.0,
                        unemploymentRecoveryMonths=48, hpiShockPct=25.0, hpiShockMonths=24, hpiAnnualGrowthPct=2.0)
RATES_UP_200 = dict(BASELINE, name="RATES_UP_200", rateShockBp=200.0)
RATES_DOWN_200 = dict(BASELINE, name="RATES_DOWN_200", rateShockBp=-200.0)
EDGE_HIGH = dict(name="EDGE_HIGH", description="", stressedMonths=120, unemploymentShockPp=15.0, unemploymentShockMonths=1,
                 unemploymentRecoveryMonths=0, hpiShockPct=60.0, hpiShockMonths=60, hpiAnnualGrowthPct=2.0, rateShockBp=600.0)
EDGE_LOW = dict(name="EDGE_LOW", description="", stressedMonths=1, unemploymentShockPp=-5.0, unemploymentShockMonths=60,
                unemploymentRecoveryMonths=120, hpiShockPct=-30.0, hpiShockMonths=1, hpiAnnualGrowthPct=2.0, rateShockBp=-400.0)
TARGETS = [BASELINE, ADVERSE, SEVERELY_ADVERSE, RATES_UP_200, RATES_DOWN_200, EDGE_HIGH, EDGE_LOW]


def mix(x: int) -> int:
    z = x & MASK
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & MASK
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK
    return (z ^ (z >> 31)) & MASK


def signed(x: int) -> int:
    x &= MASK
    return x - (1 << 64) if x >= (1 << 63) else x


class Stream:
    """The stream of one granule member: its start is itself a mixed value, so streams do not overlap."""

    def __init__(self, seed: int, index: int):
        self.state = mix((seed & MASK) + (index + 1) * GAMMA)

    def next_long(self) -> int:
        self.state = (self.state + GAMMA) & MASK
        return mix(self.state)

    def next_unit(self) -> float:
        return (self.next_long() >> 11) * (2.0 ** -53)


def fnv1a(text: str) -> int:
    h = 0xCBF29CE484222325
    for b in text.encode("utf-8"):
        h ^= b
        h = (h * 0x100000001B3) & MASK
    return h


def clamp(value, low, high):
    return max(low, min(high, value))


def variant(t: dict, index: int, band: float, timing: int, seed: int) -> dict:
    rng = Stream(seed, index)
    unemployment_factor = 1.0 - band + 2.0 * band * rng.next_unit()
    hpi_factor = 1.0 - band + 2.0 * band * rng.next_unit()
    rate_factor = 1.0 - band + 2.0 * band * rng.next_unit()
    unemployment_shift = math.floor(rng.next_unit() * (2 * timing + 1)) - timing
    hpi_shift = math.floor(rng.next_unit() * (2 * timing + 1)) - timing
    stressed_shift = math.floor(rng.next_unit() * (2 * timing + 1)) - timing

    unemployment_pp = clamp(t["unemploymentShockPp"] * unemployment_factor, -5.0, 15.0)
    hpi_pct = clamp(t["hpiShockPct"] * hpi_factor, -30.0, 60.0)
    rate_bp = clamp(t["rateShockBp"] * rate_factor, -400.0, 600.0)
    unemployment_months = (t["unemploymentShockMonths"] if t["unemploymentShockPp"] == 0.0
                           else clamp(t["unemploymentShockMonths"] + unemployment_shift, 1, 60))
    hpi_months = (t["hpiShockMonths"] if t["hpiShockPct"] == 0.0 else clamp(t["hpiShockMonths"] + hpi_shift, 1, 60))
    stressed_months = 0 if t["stressedMonths"] == 0 else clamp(t["stressedMonths"] + stressed_shift, 0, 120)

    name = (t["name"][:24] if len(t["name"]) > 24 else t["name"]) + "~" + str(index)
    return dict(name=name, stressedMonths=stressed_months, unemploymentShockPp=unemployment_pp,
                unemploymentShockMonths=unemployment_months, unemploymentRecoveryMonths=t["unemploymentRecoveryMonths"],
                hpiShockPct=hpi_pct, hpiShockMonths=hpi_months, hpiAnnualGrowthPct=t["hpiAnnualGrowthPct"], rateShockBp=rate_bp)


def classify(metrics: list[float], threshold: float, tolerance: float) -> dict:
    n = len(metrics)
    high = sum(1 for m in metrics if m >= threshold)
    ordered = sorted(metrics)
    median = ordered[n // 2] if n % 2 == 1 else (ordered[n // 2 - 1] + ordered[n // 2]) / 2.0
    share = high / n
    if share >= 1.0 - tolerance - EPSILON:
        region = "CERTAIN_HIGH"
    elif share <= tolerance + EPSILON:
        region = "CERTAIN_LOW"
    else:
        region = "BOUNDARY"
    return dict(region=region, high=high, members=n, min=ordered[0], median=median, max=ordered[-1],
                width=ordered[-1] - ordered[0])


def generator_cases() -> list[dict]:
    seeds = [0, 1, 11, -5, 424242, 2 ** 63 - 1, -(2 ** 63), 0x1234567890ABCDEF]
    cases = []
    for target in TARGETS:
        for seed in seeds:
            for band, timing in ((0.2, 3), (0.5, 12), (0.0, 0), (0.1, 6)):
                cases.append(dict(target=target, seed=seed, shockBand=band, timingBandMonths=timing,
                                  members=[variant(target, i, band, timing, seed) for i in range(1, 9)]))
    return cases


def stream_cases() -> list[dict]:
    return [dict(seed=seed, index=index, firstLongs=[signed(l) for l in _take(Stream(seed, index), 3)])
            for seed in (0, 1, -1, 2 ** 63 - 1) for index in (1, 2, 7, 127)]


def _take(stream: Stream, n: int) -> list[int]:
    return [stream.next_long() for _ in range(n)]


def hash_cases() -> list[dict]:
    texts = ["", "a", "LoanFeatures[loanId=prime]|Scenario[name=ADVERSE]|null|null|null|null|PD_LIFETIME|0.05|0.0|PERTURBATION|32|0.2|3|[]",
             "naïve ✓"]
    return [dict(text=t, hash=signed(fnv1a(t))) for t in texts]


def classifier_cases() -> list[dict]:
    rng = random.Random(20260110)
    cases = []
    for _ in range(400):
        n = rng.randint(1, 40)
        metrics = [round(rng.random(), 6) for _ in range(n)]
        threshold = round(rng.random(), 6)
        tolerance = rng.choice([0.0, 0.0, 0.05, 0.1, 0.25, 0.49, round(rng.random() * 0.49, 6)])
        cases.append(dict(metrics=metrics, threshold=threshold, tolerance=tolerance,
                          expected=classify(metrics, threshold, tolerance)))
    # exact boundary cases: k/n equal to 1 - beta and to beta, ties with the threshold, a single member
    cases.append(dict(metrics=[0.3] * 9 + [0.1], threshold=0.2, tolerance=0.1, expected=classify([0.3] * 9 + [0.1], 0.2, 0.1)))
    cases.append(dict(metrics=[0.3] + [0.1] * 9, threshold=0.2, tolerance=0.1, expected=classify([0.3] + [0.1] * 9, 0.2, 0.1)))
    cases.append(dict(metrics=[0.2, 0.2], threshold=0.2, tolerance=0.0, expected=classify([0.2, 0.2], 0.2, 0.0)))
    cases.append(dict(metrics=[0.5], threshold=0.2, tolerance=0.0, expected=classify([0.5], 0.2, 0.0)))
    return cases


def main() -> None:
    payload = dict(generator=generator_cases(), streams=stream_cases(), hashes=hash_cases(), classifier=classifier_cases())
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, indent=1), encoding="utf-8")
    print(f"wrote {OUT} ({len(payload['generator'])} generator, {len(payload['streams'])} stream, "
          f"{len(payload['hashes'])} hash, {len(payload['classifier'])} classifier cases)")


if __name__ == "__main__":
    main()
