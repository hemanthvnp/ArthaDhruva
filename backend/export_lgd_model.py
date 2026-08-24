"""Refit the same Beta regression LGD model as lgd_ead_expected_loss.ipynb and export what the
backend needs to predict LGD for a new loan: the mean-equation coefficients (logit link). The
precision/dispersion parameter BetaModel also fits is dropped -- irrelevant to a point prediction
of the mean, which is all a single expected-loss estimate needs.

EAD is deliberately NOT exported here -- unlike LGD, it isn't a fitted model in the notebook at
all. EAD is defined there as last_actual_upb, a value that only exists retroactively for loans
that have already defaulted and been liquidated; for a loan being scored at origination there is
no such value yet. The Java side uses original_upb as a stated simplification instead (see
ExpectedLossController)."""
import json
import polars as pl
import statsmodels.api as sm
from statsmodels.othermod.betareg import BetaModel

NUMERIC_FEATURES = ["credit_score", "original_dti", "original_upb", "original_cltv", "original_interest_rate"]

df = pl.read_parquet("../data/docs/lgd_ead_dataset.parquet")
has_loss = df.filter(pl.col("actual_loss_calculation").is_not_null())
lgd_df = has_loss.filter(pl.col("zero_balance_removal_upb") > 0).with_columns(
    lgd_raw=(pl.col("actual_loss_calculation") / pl.col("zero_balance_removal_upb"))
).with_columns(lgd=pl.col("lgd_raw").clip(0, 1.5))

model_df = lgd_df.select(NUMERIC_FEATURES + ["lgd"]).drop_nulls().to_pandas()
eps = 1e-4
model_df["lgd_bounded"] = model_df["lgd"].clip(eps, 1 - eps)

X = sm.add_constant(model_df[NUMERIC_FEATURES])
y = model_df["lgd_bounded"]
print(f"Beta regression sample: n={len(model_df):,}")

beta_fit = BetaModel(y, X).fit(disp=False)
print(beta_fit.summary())

export = {
    "feature_names": NUMERIC_FEATURES,
    "const": float(beta_fit.params["const"]),
    "coefficients": {name: float(beta_fit.params[name]) for name in NUMERIC_FEATURES},
}
with open("risk-engine/src/main/resources/lgd_model.json", "w") as f:
    json.dump(export, f, indent=2)
print("\nSaved risk-engine/src/main/resources/lgd_model.json")
