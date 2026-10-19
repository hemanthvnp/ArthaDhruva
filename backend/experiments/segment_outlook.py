"""Regional outlook backtest (openspec/changes/segment-graph-models, decision 7, tasks 4.2-4.5).

The rule (design.md decision 7, confirmed unchanged before this ran except for the series window, which the
feasibility-gate amendment moved to 2019-01):
  target      per state, the mean log standardised delinquency ratio of the next three months minus that of the
              last three, at each origin t (information through month t)
  origins     monthly from 2021-01 to the last month with three months after it; expanding training window: the
              targets fully observed by t (t' + 3 <= t)
  baselines   no change (0); an autoregression on the state's own history (pooled OLS on its last 3-month change)
  candidates  ridge with state effects, and LightGBM, each without and with graph features; both carry the
              macro features (state unemployment and house-price changes, from the macro panel, one month old)
              and the national 90+ rate; no calendar-month features
  graph       features from the graph learned through 2016-12 at every origin: linked states' last 3-month change
              weighted by the partial correlation, the mean over the rest of the state's region, and leaders'
              changes weighted by their coefficients
  test        Diebold-Mariano on the per-origin cross-state mean squared error, HAC variance with lag 2, the
              Harvey-Leybourne-Newbold correction, one-sided at 5%; and the mean cross-state rank correlation
  rule        forecastable only if it beats both baselines; the graph is used only if the graph version beats the
              same model class without graph features at 5% and has the higher rank correlation; the best
              passing candidate (lowest MSE) ships; if none passes, no forecast ships
  intervals   split-conformal: at each origin, the 80% quantile of the absolute errors of earlier origins (at
              least 12); coverage reported for every model over the origins that have one
Everything is reported over all origins and without the origins whose features or target touch 2020-03 ..
2021-03 (forbearance); the decision uses all origins, and the card says whether it holds without forbearance.

Run from backend/:  python experiments/segment_outlook.py   (prints JSON; export_segment_forecast.py uses run())
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd
import polars as pl
from scipy import stats
from sklearn.linear_model import LinearRegression, Ridge

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import segment_series as ss  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SEGMENTS = ROOT / "data" / "processed" / "segments"
EARLY_GRAPH = SEGMENTS / "validation_graph.json"

HORIZON = 3
FIRST_ORIGIN = 2021 * 12
FORBEARANCE = (2020 * 12 + 2, 2021 * 12 + 2)
ALPHA = 0.05
DM_LAG = 2
RIDGE_ALPHA = 1.0
CONFORMAL_LEVEL = 0.80
CONFORMAL_MIN = 12
LGB_PARAMS = dict(objective="regression", n_estimators=200, learning_rate=0.05, num_leaves=15, min_child_samples=20,
                  subsample=0.8, subsample_freq=1, colsample_bytree=0.9, random_state=7, deterministic=True,
                  force_row_wise=True, verbose=-1)


# ---- data -----------------------------------------------------------------------------------------------

def series() -> pd.DataFrame:
    r = pl.read_parquet(SEGMENTS / "state_ratio.parquet").filter(pl.col("variant") == "primary").to_pandas()
    wide = r.pivot(index="month", columns="state", values="log_ratio").sort_index()
    wide = wide.reindex(range(ss.VALIDATION_START, int(wide.index.max()) + 1))[ss.STATES]
    return wide


def national_rate() -> pd.Series:
    c = pl.read_parquet(SEGMENTS / "state_month_cells.parquet").filter(pl.col("state").is_in(ss.STATES))
    n = c.group_by("month").agg(pl.col("n_delinquent").sum(), pl.col("n_active").sum()).sort("month").to_pandas()
    return np.log((n["n_delinquent"] + 0.5) / n["n_active"]).set_axis(n["month"])


def macro() -> dict[str, pd.DataFrame]:
    """Last 3-month unemployment change and last quarterly house-price growth per state and month, as known one
    month later (published with a lag)."""
    m = pl.read_parquet(SEGMENTS / "macro_panel.parquet").to_pandas()
    ur = m[m["source"] == "unemployment"].pivot(index="period", columns="state", values="change").sort_index()
    hpi = m[m["source"] == "hpi"].pivot(index="period", columns="state", values="change").sort_index()
    months = range(int(ur.index.min()), int(ur.index.max()) + 2)
    ur3 = ur.rolling(3).sum().reindex(months).shift(1)
    hpi_m = hpi.reindex(months).ffill().shift(3)  # a quarter's index is published about a quarter later
    return {"ur3": ur3[ss.STATES], "hpi_q": hpi_m[ss.STATES]}


def graph_weights(graph: dict) -> tuple[np.ndarray, np.ndarray, list[list[int]]]:
    pos = {s: i for i, s in enumerate(ss.STATES)}
    n = len(ss.STATES)
    moves = np.zeros((n, n))
    for m in graph.get("movesWith", []):
        moves[pos[m["a"]], pos[m["b"]]] = moves[pos[m["b"]], pos[m["a"]]] = m["weight"]
    leads = np.zeros((n, n))
    for link in graph.get("leads", []):
        leads[pos[link["from"]], pos[link["to"]]] = link["weight"]
    regions = [[pos[s] for s in r["states"]] for r in graph.get("regions", [])]
    return moves, leads, regions


# ---- features -------------------------------------------------------------------------------------------

def panel_rows(lr: pd.DataFrame, nat: pd.Series, mac: dict, graph: dict) -> pd.DataFrame:
    """One row per state and month t: features known at t, and the target (NaN where not observable)."""
    past3 = lr.rolling(3, min_periods=2).mean()
    nxt3 = lr[::-1].rolling(3, min_periods=2).mean()[::-1].shift(-1)
    target = nxt3 - past3
    change = past3 - past3.shift(3)
    moves, leads, regions = graph_weights(graph)
    ch = change.to_numpy()
    filled = np.nan_to_num(ch)
    present = (~np.isnan(ch)).astype(float)
    absw = np.abs(moves)
    linked = (filled @ moves) / np.where(present @ absw > 0, present @ absw, np.nan)
    lead_in = (filled @ leads) / np.where(present @ np.abs(leads) > 0, present @ np.abs(leads), np.nan)
    region_mean = np.full_like(ch, np.nan)
    for members in regions:
        sub_f, sub_p = filled[:, members], present[:, members]
        tot, cnt = sub_f.sum(axis=1, keepdims=True), sub_p.sum(axis=1, keepdims=True)
        others = (tot - sub_f) / np.where(cnt - sub_p > 0, cnt - sub_p, np.nan)
        region_mean[:, members] = others
    frames = []
    nat_now = nat.reindex(lr.index)
    for j, s in enumerate(ss.STATES):
        frames.append(pd.DataFrame({
            "month": lr.index, "state": s, "target": target[s].to_numpy(),
            "level": past3[s].to_numpy(), "change": change[s].to_numpy(),
            "national": nat_now.to_numpy(), "national_change": (nat_now - nat_now.shift(3)).to_numpy(),
            "ur3": mac["ur3"][s].reindex(lr.index).to_numpy(), "hpi_q": mac["hpi_q"][s].reindex(lr.index).to_numpy(),
            "g_linked": linked[:, j], "g_region": region_mean[:, j], "g_leaders": lead_in[:, j],
        }))
    out = pd.concat(frames, ignore_index=True)
    for g in ("g_linked", "g_region", "g_leaders"):
        out[g] = out[g].fillna(0.0)   # no link, no region or no leader: no graph information
    return out


BASE = ["level", "change", "national", "national_change", "ur3", "hpi_q"]
GRAPH = ["g_linked", "g_region", "g_leaders"]


def design(rows: pd.DataFrame, cols: list[str], state_effects: bool) -> np.ndarray:
    x = rows[cols].to_numpy(dtype=float)
    if state_effects:
        x = np.hstack([x, pd.get_dummies(pd.Categorical(rows["state"], categories=ss.STATES)).to_numpy(dtype=float)])
    return x


# ---- models ---------------------------------------------------------------------------------------------

def predict(name: str, train: pd.DataFrame, test: pd.DataFrame) -> np.ndarray:
    if name == "no change":
        return np.zeros(len(test))
    if name == "autoregression":
        return LinearRegression().fit(train[["change"]], train["target"]).predict(test[["change"]])
    use_graph = name.endswith("with graph")
    cols = BASE + (GRAPH if use_graph else [])
    if name.startswith("ridge"):
        xt, xs = design(train, cols, True), design(test, cols, True)
        mu, sd = xt[:, :len(cols)].mean(axis=0), xt[:, :len(cols)].std(axis=0) + 1e-12
        xt[:, :len(cols)], xs[:, :len(cols)] = (xt[:, :len(cols)] - mu) / sd, (xs[:, :len(cols)] - mu) / sd
        return Ridge(alpha=RIDGE_ALPHA).fit(xt, train["target"]).predict(xs)
    model = lgb.LGBMRegressor(**LGB_PARAMS).fit(design(train, cols, False), train["target"])
    return model.predict(design(test, cols, False))


MODELS = ["no change", "autoregression", "ridge", "ridge with graph", "lightgbm", "lightgbm with graph"]


def backtest(rows: pd.DataFrame) -> dict[str, pd.DataFrame]:
    """Per model, the predictions at every origin: state, origin, predicted, realised."""
    usable = rows.dropna(subset=["target", "level", "change", "national", "national_change", "ur3", "hpi_q"])
    last_origin = int(usable["month"].max())
    out = {m: [] for m in MODELS}
    for t in range(FIRST_ORIGIN, last_origin + 1):
        train = usable[usable["month"] + HORIZON <= t]
        test = usable[usable["month"] == t]
        if test.empty or len(train) < 100:
            continue
        for m in MODELS:
            out[m].append(pd.DataFrame({"state": test["state"].to_numpy(), "origin": t,
                                        "predicted": predict(m, train, test), "realised": test["target"].to_numpy()}))
    return {m: pd.concat(v, ignore_index=True) for m, v in out.items()}


# ---- evaluation -----------------------------------------------------------------------------------------

def touches_forbearance(origin: int) -> bool:
    lo, hi = FORBEARANCE
    return not (origin + HORIZON < lo or origin - 5 > hi)


def per_origin(pred: pd.DataFrame) -> pd.DataFrame:
    g = pred.assign(se=(pred["predicted"] - pred["realised"]) ** 2).groupby("origin")
    rank = g.apply(lambda d: stats.spearmanr(d["predicted"], d["realised"]).statistic
                   if d["predicted"].std() > 0 else 0.0, include_groups=False)
    return pd.DataFrame({"mse": g["se"].mean(), "rank_ic": rank})


def diebold_mariano(loss_base: np.ndarray, loss_cand: np.ndarray, h: int = HORIZON) -> float:
    """One-sided p that the candidate's loss is lower: DM with a HAC variance (autocovariances to DM_LAG, equal
    weights) and the Harvey-Leybourne-Newbold small-sample correction, against Student t with T - 1 df."""
    d = loss_base - loss_cand
    t = len(d)
    dc = d - d.mean()
    gamma = [dc @ dc / t] + [dc[k:] @ dc[:-k] / t for k in range(1, DM_LAG + 1)]
    var = (gamma[0] + 2 * sum(gamma[1:])) / t
    if var <= 0:
        return float("nan")
    dm = d.mean() / np.sqrt(var)
    hln = dm * np.sqrt((t + 1 - 2 * h + h * (h - 1) / t) / t)
    return float(1 - stats.t.cdf(hln, df=t - 1))


def conformal(pred: pd.DataFrame) -> tuple[pd.DataFrame, float | None]:
    """Adds lower/upper from earlier origins' absolute errors; returns the coverage where an interval exists."""
    pred = pred.sort_values(["origin", "state"]).copy()
    pred["lower"] = pred["upper"] = np.nan
    origins = sorted(pred["origin"].unique())
    for i, t in enumerate(origins):
        earlier = pred[pred["origin"].isin(origins[:max(0, i - HORIZON + 1)])]  # errors observed by origin t
        if earlier["origin"].nunique() < CONFORMAL_MIN:
            continue
        q = np.quantile(np.abs(earlier["predicted"] - earlier["realised"]), CONFORMAL_LEVEL)
        at = pred["origin"] == t
        pred.loc[at, "lower"], pred.loc[at, "upper"] = pred.loc[at, "predicted"] - q, pred.loc[at, "predicted"] + q
    has = pred["lower"].notna()
    cov = float(((pred.loc[has, "realised"] >= pred.loc[has, "lower"]) & (pred.loc[has, "realised"] <= pred.loc[has, "upper"])).mean()) if has.any() else None
    return pred, cov


def evaluate(preds: dict[str, pd.DataFrame], exclude: bool) -> dict:
    po = {m: per_origin(p) for m, p in preds.items()}
    if exclude:
        po = {m: d[[not touches_forbearance(int(o)) for o in d.index]] for m, d in po.items()}
    res = {"origins": f"{ss.month_label(int(po['no change'].index.min()))} .. {ss.month_label(int(po['no change'].index.max()))}",
           "n_origins": len(po["no change"]), "models": {}}
    for m in MODELS:
        res["models"][m] = {"rmse": round(float(np.sqrt(po[m]["mse"].mean())), 5), "rankIC": round(float(po[m]["rank_ic"].mean()), 4)}
    for m in MODELS[2:]:
        r = res["models"][m]
        r["p_vs_no_change"] = round(diebold_mariano(po["no change"]["mse"].to_numpy(), po[m]["mse"].to_numpy()), 4)
        r["p_vs_autoregression"] = round(diebold_mariano(po["autoregression"]["mse"].to_numpy(), po[m]["mse"].to_numpy()), 4)
        r["forecastable"] = bool(r["p_vs_no_change"] < ALPHA and r["p_vs_autoregression"] < ALPHA)
    for cls in ("ridge", "lightgbm"):
        g, n = res["models"][f"{cls} with graph"], res["models"][cls]
        g["p_vs_without_graph"] = round(diebold_mariano(po[cls]["mse"].to_numpy(), po[f"{cls} with graph"]["mse"].to_numpy()), 4)
        g["graph_helps"] = bool(g["p_vs_without_graph"] < ALPHA and g["rankIC"] > n["rankIC"])
    return res


def decide(res: dict) -> dict:
    passing = []
    for cls in ("ridge", "lightgbm"):
        g = res["models"][f"{cls} with graph"]
        name = f"{cls} with graph" if g["graph_helps"] else cls
        if res["models"][name]["forecastable"]:
            passing.append(name)
    if not passing:
        return {"ships": None, "reason": "no candidate beat both the no-change forecast and the autoregression at 5%"}
    best = min(passing, key=lambda m: res["models"][m]["rmse"])
    return {"ships": best, "reason": f"{best} beat no change and the autoregression at 5%"
            + ("; the graph version beat the same model without graph features" if best.endswith("graph") else
               "; graph features did not help, so the model without them ships")}


def run() -> dict:
    lr, nat, mac = series(), national_rate(), macro()
    graph = json.loads(EARLY_GRAPH.read_text(encoding="utf-8"))
    rows = panel_rows(lr, nat, mac, graph)
    preds = backtest(rows)
    all_months, ex = evaluate(preds, False), evaluate(preds, True)
    decision = decide(all_months)
    coverage = {m: round(conformal(preds[m])[1], 4) for m in MODELS}
    return {"rule": "design.md decision 7", "graph_learned_through": ss.month_label(ss.LEARN_END),
            "graph_links": {"movesWith": len(graph.get("movesWith", [])), "leads": len(graph.get("leads", [])),
                            "regions": len(graph.get("regions", []))},
            "all_origins": all_months, "excluding_forbearance": ex, "decision": decision,
            "holds_without_forbearance": decide(ex)["ships"] == decision["ships"],
            "interval_coverage_80": coverage, "_rows": rows, "_preds": preds}


def main() -> None:
    out = run()
    print(json.dumps({k: v for k, v in out.items() if not k.startswith("_")}, indent=2))


if __name__ == "__main__":
    main()
