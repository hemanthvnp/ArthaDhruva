"""Out-of-time validation and model card for the loss-given-default model (lgd_model.json).

The model is a Beta regression of realized LGD on five origination attributes, logit link, fitted by export_lgd_model.py on
the defaulted loans with a recorded loss (the exported artifact keeps the mean equation only). It had no card and no
out-of-time result. This script does not refit or rewrite the artifact: it reads the shipped lgd_model.json as the model of
record, refits the same specification on earlier vintages to judge it on later ones, and writes lgd_model_card.json, whose
checksum of lgd_model.json the service verifies at start-up (governance/ModelRegistry).

Out of time here means by origination vintage, the only time axis the dataset has (it records no liquidation date): fit on
vintages up to 2021, judge on 2022 to 2024. Run from backend/.
"""
from __future__ import annotations

import hashlib
import json
import math
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import polars as pl
import statsmodels.api as sm
from scipy.stats import spearmanr
from statsmodels.othermod.betareg import BetaModel

RESOURCES = Path(__file__).resolve().parent     # the retired artifact and card live beside this script
DATA = Path(__file__).resolve().parents[4] / "data" / "docs" / "lgd_ead_dataset.parquet"
NUMERIC_FEATURES = ["credit_score", "original_dti", "original_upb", "original_cltv", "original_interest_rate"]
SPLIT_YEAR = 2021          # fit on vintages up to this year, judge on later ones
EPS = 1e-4                 # the bound export_lgd_model.py puts on the response before the Beta likelihood


def sample() -> pl.DataFrame:
    """The same sample export_lgd_model.py fits on, with the origination year parsed from the loan sequence number."""
    df = pl.read_parquet(DATA)
    out = df.filter(pl.col("actual_loss_calculation").is_not_null() & (pl.col("zero_balance_removal_upb") > 0)).with_columns(
        lgd=(pl.col("actual_loss_calculation") / pl.col("zero_balance_removal_upb")).clip(0, 1.5),
        year=pl.col("loan_sequence_number").str.slice(1, 2).cast(pl.Int32) + 2000)
    return out.select(["loan_sequence_number", "year", "zero_balance_removal_upb", *NUMERIC_FEATURES, "lgd"]).drop_nulls()


def design(df: pl.DataFrame) -> tuple[np.ndarray, np.ndarray]:
    x = sm.add_constant(df.select(NUMERIC_FEATURES).to_pandas(), has_constant="add")
    y = df["lgd"].clip(EPS, 1 - EPS).to_numpy()
    return x, y


def shipped_prediction(df: pl.DataFrame) -> np.ndarray:
    """What the service computes: the logistic of the exported linear predictor, clipped to [0, 1]."""
    model = json.loads((RESOURCES / "lgd_model.json").read_text())
    z = model["const"] + sum(model["coefficients"][f] * df[f].to_numpy() for f in NUMERIC_FEATURES)
    return np.clip(1.0 / (1.0 + np.exp(-z)), 0.0, 1.0)


def report(actual: np.ndarray, predicted: np.ndarray, weight: np.ndarray, baseline: float) -> dict:
    order = np.argsort(predicted, kind="stable")
    groups = np.array_split(order, 5)
    return {
        "loans": int(len(actual)),
        "mean_actual": float(actual.mean()), "mean_predicted": float(predicted.mean()),
        "balance_weighted_actual": float(np.average(actual, weights=weight)),
        "balance_weighted_predicted": float(np.average(predicted, weights=weight)),
        "mae": float(np.abs(actual - predicted).mean()), "rmse": float(math.sqrt(((actual - predicted) ** 2).mean())),
        "benchmark_mae": float(np.abs(actual - baseline).mean()), "benchmark_rmse": float(math.sqrt(((actual - baseline) ** 2).mean())),
        "spearman": float(spearmanr(predicted, actual).statistic) if np.ptp(predicted) > 0 and np.ptp(actual) > 0 else None,
        "share_with_no_loss": float((actual <= 0).mean()),
        "by_predicted_quintile": [{"quintile": i + 1, "loans": int(len(g)), "predicted": float(predicted[g].mean()),
                                   "actual": float(actual[g].mean())} for i, g in enumerate(groups)],
    }


def main() -> None:
    df = sample()
    train, test = df.filter(pl.col("year") <= SPLIT_YEAR), df.filter(pl.col("year") > SPLIT_YEAR)
    print(f"{df.height:,} liquidations; fit on {train.height:,} (vintages {train['year'].min()} to {SPLIT_YEAR}), judge on {test.height:,} "
          f"(vintages {SPLIT_YEAR + 1} to {test['year'].max()})")
    x_train, y_train = design(train)
    fit = BetaModel(y_train, x_train).fit(disp=False)
    x_test, _ = design(test)
    predicted = np.clip(np.asarray(fit.predict(x_test)), 0.0, 1.0)
    actual, weight = test["lgd"].clip(0, 1).to_numpy(), test["zero_balance_removal_upb"].to_numpy()
    out_of_time = report(actual, predicted, weight, float(train["lgd"].clip(0, 1).mean()))
    shipped = report(df["lgd"].clip(0, 1).to_numpy(), shipped_prediction(df), df["zero_balance_removal_upb"].to_numpy(), float(df["lgd"].clip(0, 1).mean()))
    frame = df.with_columns(shipped=pl.Series(shipped_prediction(df)))
    by_year = {int(r["year"]): {"loans": int(r["n"]), "mean_actual": float(r["a"]), "mean_predicted_by_shipped_model": float(r["p"])}
               for r in frame.group_by("year").agg(n=pl.len(), a=pl.col("lgd").clip(0, 1).mean(), p=pl.col("shipped").mean()).sort("year").iter_rows(named=True)}
    print(json.dumps(out_of_time, indent=1))

    artifact = RESOURCES / "lgd_model.json"
    card = {
        "model": "lgd_beta_regression",
        "version": datetime.now(timezone.utc).strftime("%Y.%m.%d"),
        "validated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "artifact": "lgd_model.json",
        "artifact_sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
        "algorithm": "Beta regression, logit link; the mean equation is exported",
        "features_in_order": NUMERIC_FEATURES,
        "target": "realized loss over the balance removed at liquidation, clipped to [0, 1.5] (the Beta fit bounds it to [0.0001, 0.9999])",
        "population": "Freddie Mac defaults with a recorded loss and a positive removal balance, vintages 2020 to 2024",
        "training": {"loans": int(df.height), "vintages": [int(df["year"].min()), int(df["year"].max())], "by_vintage": by_year},
        "validation": {
            "protocol": f"refit the same specification on vintages up to {SPLIT_YEAR} and judge it on {SPLIT_YEAR + 1} to {int(test['year'].max())}; "
                        "out of time by origination vintage, the only time axis in the data (no liquidation date is recorded)",
            "out_of_time": out_of_time,
            "shipped_model_all_vintages": shipped,
            "benchmark": "the mean LGD of the fitting vintages, applied to every loan",
        },
        "use": "the fitted LGD is the floor of the engine's collateral-based LGD (survival.TermStructureEngine): "
               "LGD = min(1, max(fitted, collateral shortfall)), so it only matters where the shortfall is small",
        "limitations": [
            f"Only {df.height:,} liquidations from 2020 to 2024 vintages, and {shipped['share_with_no_loss']:.0%} of them lost nothing: the fitted LGD is near zero for almost every loan (mean {shipped['mean_actual']:.2%}). That is what defaults looked like while house prices rose, not a constant of nature.",
            "No housing downturn is in the data: the model says nothing about severity when prices fall, which is why the engine floors it with the collateral shortfall under the scenario's house-price path.",
            "The five features include the raw note rate, which moves with the rate cycle rather than with borrower risk; the survival model uses the spread to the market rate for this reason and this model does not.",
            "The realized loss is clipped to [0, 1.5] and the Beta likelihood needs it inside (0, 1); a loss above the balance is treated as a total loss.",
            "Exposure at default is the original balance, a stated simplification: the dataset has the balance at liquidation only for loans that already defaulted.",
        ],
    }
    path = RESOURCES / "lgd_model_card.json"
    path.write_text(json.dumps(card, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
