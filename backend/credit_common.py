"""Shared definitions for the credit model training scripts (export_model.py, export_survival_model.py).

Keeping the feature list, category encoding and ONNX export in one place is what guarantees the PD
classifier and the survival model encode a loan identically, and that the Java side (which reads the
exported JSON) agrees with both.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
import polars as pl

DATA = Path("../data/processed")
SURVIVAL = DATA / "survival"
RESOURCES = Path("risk-engine/src/main/resources")

# Raw application fields, as the API receives them.
NUMERIC = ["credit_score", "original_dti", "original_upb", "original_cltv", "original_ltv",
           "original_interest_rate", "original_loan_term", "number_of_borrowers", "number_of_units", "mi_percent"]
CATEGORICAL = ["occupancy_status", "property_type", "loan_purpose", "channel",
               "first_time_homebuyer_flag", "property_state"]
ORIGINATION = NUMERIC + CATEGORICAL

# Model features. The note rate enters as its spread over the market 30-year rate at origination
# ("SATO"): a raw rate mixes the borrower's risk premium with the rate cycle, so a model trained on
# 2017-2020 loans reads every 2023 loan (7% because the market was 7%) as high risk. The spread keeps
# only the risk-based-pricing signal.
MODEL_NUMERIC = [("rate_spread" if f == "original_interest_rate" else f) for f in NUMERIC]
MODEL_ORIGINATION = MODEL_NUMERIC + CATEGORICAL

# Hard bounds: physically/contractually impossible values are rejected by the API. Soft bounds (the
# training distribution's 0.5th/99.5th percentiles) only produce an out-of-distribution warning.
HARD_BOUNDS = {
    "credit_score": (300, 850), "original_dti": (0.1, 100), "original_upb": (1_000, 5_000_000),
    "original_cltv": (1, 250), "original_ltv": (1, 250), "original_interest_rate": (0.1, 20),
    "original_loan_term": (60, 480), "number_of_borrowers": (1, 10), "number_of_units": (1, 4),
    "mi_percent": (0, 60),
}


# Every continuous model input is rounded (half-to-even) to a fixed grid before training AND serving.
# LightGBM compares inputs with float64 split thresholds; the exported ONNX graph stores them as
# float32. An unrounded computed value (e.g. mark-to-market LTV) can fall between t and float32(t) and
# take the other branch -- observed on 0.04% of loan-months before this. With inputs on a grid, every
# learned threshold sits halfway between two grid points, far from any float32 rounding boundary, so
# LightGBM, ONNX Runtime and the Java service all take the same path. Java applies the identical rule
# (Math.rint(x * 10^d) / 10^d) from the decimals exported with each model.
FEATURE_DECIMALS = {
    "credit_score": 0, "original_dti": 0, "original_upb": 0, "original_cltv": 0, "original_ltv": 0,
    "rate_spread": 3, "original_loan_term": 0, "number_of_borrowers": 0, "number_of_units": 0, "mi_percent": 0,
    "loan_age": 0, "regime_stressed": 0, "rate_incentive": 3, "unemployment": 2, "unemployment_change_12m": 2,
    "hpi_change_12m": 4, "mtm_ltv": 2,
}


def quantize(x: np.ndarray, features: list[str]) -> np.ndarray:
    out = x.astype(np.float64, copy=True)
    for j, f in enumerate(features):
        d = FEATURE_DECIMALS.get(f)
        if d is not None:
            scale = 10.0 ** d
            out[:, j] = np.rint(out[:, j] * scale) / scale
    return out.astype(np.float32)


def category_mappings() -> dict[str, dict[str, int]]:
    """string -> integer code per categorical feature, from the FULL loan-level data (not a sample), so a
    legitimate but rare value (e.g. a territory) is never treated as unseen."""
    lf = pl.scan_parquet(str(DATA / "loan_level/orig_year=*/orig_quarter=*/*.parquet"),
                         extra_columns="ignore", missing_columns="insert").select(CATEGORICAL)
    distinct = lf.select([pl.col(c).drop_nulls().unique().sort().implode() for c in CATEGORICAL]).collect()
    return {c: {v: i for i, v in enumerate(distinct[c][0].to_list())} for c in CATEGORICAL}


def encode(df: pl.DataFrame, mappings: dict[str, dict[str, int]]) -> pl.DataFrame:
    """Categoricals to float codes (-1 = unseen/missing, LightGBM's own convention); numerics to float32."""
    exprs = [pl.col(c).replace_strict(mappings[c], default=-1, return_dtype=pl.Float32).alias(c) for c in CATEGORICAL]
    exprs += [pl.col(c).cast(pl.Float32) for c in NUMERIC + ["rate_spread"] if c in df.columns]
    return df.with_columns(exprs)


def _macro() -> pl.DataFrame:
    return pl.read_parquet(SURVIVAL / "macro.parquet")


# The note rate is locked weeks before closing, so it must be compared with the market rate at the
# lock, not at origination. Two months minimizes the within-quarter dispersion of the spread (0.490 vs
# 0.525 at zero lag) and removes the artifact in fast-moving markets: at zero lag the 2022 vintage,
# locked while rates were climbing 3 points, shows a mean spread of -0.48, which a model reads as
# unusually safe borrowers; at two months it is +0.04, in line with every other vintage.
RATE_LOCK_LAG_MONTHS = 2


def with_spread(df: pl.DataFrame, orig_month: pl.Expr) -> pl.DataFrame:
    """Adds rate_spread = note rate minus the market 30y rate at the (approximate) rate-lock month."""
    rates = _macro().select(pl.col("month").alias("_om"), pl.col("rate").alias("_orig_rate"))
    return (df.with_columns(_om=(orig_month - RATE_LOCK_LAG_MONTHS).cast(pl.Int32))
            .join(rates, on="_om", how="left")
            .with_columns(rate_spread=(pl.col("original_interest_rate") - pl.col("_orig_rate")).cast(pl.Float32))
            .drop(["_om", "_orig_rate"]))


MACRO_FEATURES = ["unemployment", "unemployment_change_12m", "hpi_change_12m", "mtm_ltv"]


def amortization_factor(rate_pct: pl.Expr, term: pl.Expr, age: pl.Expr) -> pl.Expr:
    """Scheduled remaining balance as a fraction of the original balance after `age` payments on a
    level-payment fixed-rate loan: ((1+r)^n - (1+r)^a) / ((1+r)^n - 1), r = annual rate / 1200."""
    r = rate_pct / 1200.0
    a = pl.min_horizontal(pl.max_horizontal(age, pl.lit(0)), term)
    grow_n = (1.0 + r).pow(term)
    grow_a = (1.0 + r).pow(a)
    return pl.when(r > 0).then((grow_n - grow_a) / (grow_n - 1.0)).otherwise(1.0 - a / term)


def with_state_macro(df: pl.DataFrame, orig_month: pl.Expr, month_col: str = "month") -> pl.DataFrame:
    """Time-varying state macro drivers for each loan-month (raw property_state required, i.e. call
    before encode()): unemployment level and 12-month change, 12-month house price change, and the
    mark-to-market LTV -- scheduled balance over the property value re-marked by the state HPI:
        mtm_ltv = original_ltv * amortization_factor(age) * HPI(origination) / HPI(now)"""
    macro = pl.read_parquet(SURVIVAL / "macro_state.parquet").select(["state", "month", "hpi", "unemployment"])
    lag = macro.with_columns((pl.col("month") + 12).alias("month")).rename(
        {"hpi": "_hpi_lag12", "unemployment": "_ur_lag12"})
    at_orig = macro.select(["state", pl.col("month").alias("_om"), pl.col("hpi").alias("_hpi_orig")])
    out = (df.with_columns(_om=orig_month.cast(pl.Int32))
           .join(macro, left_on=["property_state", month_col], right_on=["state", "month"], how="left")
           .join(lag, left_on=["property_state", month_col], right_on=["state", "month"], how="left")
           .join(at_orig, left_on=["property_state", "_om"], right_on=["state", "_om"], how="left"))
    factor = amortization_factor(pl.col("original_interest_rate").cast(pl.Float64),
                                 pl.col("original_loan_term").cast(pl.Float64), pl.col("loan_age").cast(pl.Float64))
    return out.with_columns(
        unemployment=pl.col("unemployment").cast(pl.Float32),
        unemployment_change_12m=(pl.col("unemployment") - pl.col("_ur_lag12")).cast(pl.Float32),
        hpi_change_12m=(pl.col("hpi") / pl.col("_hpi_lag12") - 1.0).cast(pl.Float32),
        mtm_ltv=(pl.col("original_ltv") * factor * pl.col("_hpi_orig") / pl.col("hpi")).cast(pl.Float32),
    ).drop(["_om", "hpi", "_hpi_lag12", "_ur_lag12", "_hpi_orig"])


def with_macro(df: pl.DataFrame, month_col: str = "month") -> pl.DataFrame:
    """Adds regime (1 = stressed) and rate incentive (note rate minus the market 30y rate that month)."""
    return df.join(_macro(), left_on=month_col, right_on="month", how="left").with_columns(
        regime_stressed=pl.col("stressed").fill_null(0).cast(pl.Float32),
        rate_incentive=(pl.col("original_interest_rate") - pl.col("rate")).cast(pl.Float32),
    ).drop(["stressed", "rate"])


def loan_bucket(expr: pl.Expr, modulo: int = 10) -> pl.Expr:
    """Deterministic loan-level split bucket (same loan -> same bucket in every script)."""
    return expr.hash(seed=17) % modulo


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, payload) -> None:
    path.write_text(json.dumps(payload, indent=2, default=float) + "\n", encoding="utf-8")
    print(f"wrote {path}")


def float32_exact_booster(booster):
    """Returns a copy of a LightGBM booster whose numerical split thresholds are floored to float32.

    LightGBM decides x <= t with t in float64; ONNX stores t as float32 using round-to-nearest, which
    can land ABOVE t (observed: t = 0.0589999985, float32(t) = 0.0590000004), flipping the branch for an
    input exactly at 0.059f. Replacing t with the largest float32 <= t is exact for float32 inputs --
    no float32 lies in (floor32(t), t] -- and the new threshold is itself a float32, so the ONNX export
    stores it without rounding. The model's decisions on float32 inputs are unchanged."""
    import lightgbm as lgb

    lines = booster.model_to_string().split("\n")
    thresholds_at = decisions = None
    patched = []
    for i, line in enumerate(lines):
        if line.startswith("Tree="):
            thresholds_at, decisions = None, None
        if line.startswith("decision_type="):
            decisions = [int(v) for v in line.split("=", 1)[1].split()]
        if line.startswith("threshold="):
            thresholds_at = len(patched)
        patched.append(line)
        if thresholds_at is not None and decisions is not None:
            values = patched[thresholds_at].split("=", 1)[1].split()
            out = []
            for v, d in zip(values, decisions):
                t = float(v)
                if d & 1 == 0 and np.isfinite(t):  # numerical split (bit 0 = categorical)
                    t32 = np.float32(t)
                    if float(t32) > t:
                        t32 = np.nextafter(t32, np.float32(-np.inf))
                    out.append(repr(float(t32)))
                else:
                    out.append(v)
            patched[thresholds_at] = "threshold=" + " ".join(out)
            thresholds_at, decisions = None, None
    # tree_sizes holds byte offsets of each tree; the edited thresholds change those lengths, and
    # without the line LightGBM parses the trees sequentially instead.
    patched = [line for line in patched if not line.startswith("tree_sizes=")]
    return lgb.Booster(model_str="\n".join(patched))


def export_onnx(model, n_features: int, path: Path, sample: np.ndarray) -> "lightgbm.Booster":
    """LightGBM -> ONNX with a plain float probability tensor (zipmap off), then verifies ONNX Runtime
    reproduces the (float32-exact) booster before the artifact is trusted. Returns that booster, which
    is the model of record: callers compute every reported metric with it."""
    import onnxruntime as ort
    from onnxmltools.convert import convert_lightgbm
    from onnxmltools.convert.common.data_types import FloatTensorType

    booster = float32_exact_booster(model.booster_ if hasattr(model, "booster_") else model)
    onnx_model = convert_lightgbm(booster, initial_types=[("input", FloatTensorType([None, n_features]))],
                                  target_opset=15, zipmap=False)
    # The converter declares the label output as a single row, so ONNX Runtime logs a shape mismatch on
    # every batched call. Its first dimension is the batch, like the input's.
    for output in onnx_model.graph.output:
        dims = output.type.tensor_type.shape.dim
        if len(dims) and dims[0].dim_value == 1:
            dims[0].dim_param = "batch"
    # The converter names the graph with a fresh random id each time, which alone would give every export
    # a different checksum. With a fixed name (and deterministic training) the same data yields the same
    # bytes, so a checksum change always means the model changed.
    onnx_model.graph.name = path.stem
    path.write_bytes(onnx_model.SerializeToString())
    options = ort.SessionOptions()
    options.log_severity_level = 3
    sess = ort.InferenceSession(str(path), options)
    got = np.asarray(sess.run(None, {"input": sample.astype(np.float32)})[1])
    reference = booster.predict(sample.astype(np.float32))
    if reference.ndim == 1:
        reference = np.column_stack([1 - reference, reference])
    diff = np.abs(got - reference)
    # What remains is float32 accumulation of leaf values (ONNX) vs float64 (LightGBM): ~1e-6 relative.
    ok = bool(np.all(diff <= 1e-6 + 1e-4 * np.abs(reference)))
    print(f"wrote {path} ({path.stat().st_size / 1e6:.1f} MB); ONNX vs native max |diff| = {diff.max():.2e}, "
          f"max relative = {(diff / np.maximum(np.abs(reference), 1e-12)).max():.2e}")
    if not ok:
        raise SystemExit("ONNX output does not match the native model -- refusing to ship it")
    return booster


def pav_calibration(raw: np.ndarray, y: np.ndarray, bins: int = 200) -> tuple[list[float], list[float]]:
    """Monotone calibration map with guaranteed support: raw scores are first grouped into equal-count
    bins, then pool-adjacent-violators runs over the bins (weighted by size). Plain isotonic regression on
    individual points lets a handful of extreme scores pin the top of the curve at 0 or 1; binning first
    means every breakpoint is backed by roughly len(raw)/bins observations."""
    order = np.argsort(raw)
    raw_s, y_s = raw[order], y[order]
    edges = np.linspace(0, len(raw_s), bins + 1).astype(int)
    xs, ys, ws = [], [], []
    for a, b in zip(edges[:-1], edges[1:]):
        if b > a:
            xs.append(float(raw_s[a:b].mean()))
            ys.append(float(y_s[a:b].mean()))
            ws.append(float(b - a))
    # pool adjacent violators
    blocks = [[x, yv, w, 1] for x, yv, w in zip(xs, ys, ws)]
    merged: list[list[float]] = []
    for blk in blocks:
        merged.append(blk)
        while len(merged) > 1 and merged[-2][1] > merged[-1][1]:
            b2, b1 = merged.pop(), merged.pop()
            w = b1[2] + b2[2]
            merged.append([(b1[0] * b1[2] + b2[0] * b2[2]) / w, (b1[1] * b1[2] + b2[1] * b2[2]) / w, w, b1[3] + b2[3]])
    floor = 1e-5
    return [m[0] for m in merged], [min(max(m[1], floor), 1 - floor) for m in merged]


def apply_calibration(raw: np.ndarray, xs: list[float], ys: list[float]) -> np.ndarray:
    return np.interp(raw, xs, ys)


def decile_table(predicted: np.ndarray, event: np.ndarray, weight: np.ndarray, groups: int = 10) -> list[dict]:
    """Predicted vs realized rate by exposure-weighted decile of the prediction."""
    order = np.argsort(predicted, kind="stable")
    share = np.cumsum(weight[order]) / weight.sum()
    group = np.minimum((share * groups).astype(np.int64), groups - 1)
    table = []
    for g in range(groups):
        idx = order[group == g]
        if len(idx):
            w = weight[idx]
            table.append({"decile": g + 1, "predicted": float(np.average(predicted[idx], weights=w)),
                          "actual": float(np.average(event[idx], weights=w))})
    return table


def ks_statistic(y: np.ndarray, p: np.ndarray) -> float:
    order = np.argsort(p)
    y_s = y[order]
    cum_bad = np.cumsum(y_s) / max(y_s.sum(), 1)
    cum_good = np.cumsum(1 - y_s) / max((1 - y_s).sum(), 1)
    return float(np.max(np.abs(cum_bad - cum_good)))


def psi_reference_numeric(values: np.ndarray, bins: int = 10) -> dict:
    v = values[~np.isnan(values)]
    edges = np.unique(np.quantile(v, np.linspace(0, 1, bins + 1)[1:-1]))
    counts = np.histogram(v, bins=np.concatenate(([-np.inf], edges, [np.inf])))[0]
    return {"edges": edges.tolist(), "proportions": (counts / counts.sum()).tolist()}
