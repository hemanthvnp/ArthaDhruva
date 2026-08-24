"""Recompute the same segment-correlation graph as segment_correlation_graph.ipynb and export
what the backend needs for Neo4j loading: nodes (state codes) and edges (state pairs whose
monthly delinquency-rate series correlate at r >= 0.95, same threshold and method as the
notebook -- see its own honesty note on why 0.95 rather than a looser threshold: at r >= 0.5 the
graph came back nearly complete, since state-level delinquency is dominated by the shared
national macro cycle rather than distinct regional patterns, which defeats the point of
demonstrating multi-hop structure)."""
import json
import polars as pl

THRESHOLD = 0.95

segment_series = pl.read_parquet("../data/docs/segment_monthly_series.parquet")
wide = segment_series.pivot(
    index="monthly_reporting_period", on="property_state", values="delinquency_rate"
).sort("monthly_reporting_period")
wide_pdf = wide.to_pandas().set_index("monthly_reporting_period")

state_corr = wide_pdf.corr(method="pearson")
states = state_corr.columns.tolist()

edges = []
for i, s1 in enumerate(states):
    for s2 in states[i + 1:]:
        r = state_corr.loc[s1, s2]
        if r >= THRESHOLD:
            edges.append({"source": s1, "target": s2, "weight": round(float(r), 4)})

print(f"Threshold: r >= {THRESHOLD}")
print(f"Nodes: {len(states)}   Edges: {len(edges)}  (out of {len(states) * (len(states) - 1) // 2} possible pairs)")
for e in sorted(edges, key=lambda x: -x["weight"]):
    print(f"  {e['source']} -- {e['target']}: r={e['weight']}")

export = {"nodes": states, "edges": edges}
with open("risk-engine/src/main/resources/segment_correlation_graph.json", "w") as f:
    json.dump(export, f, indent=2)
print("\nSaved risk-engine/src/main/resources/segment_correlation_graph.json")
