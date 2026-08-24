"""Nightly batch refresh of the segment-correlation graph Neo4j serves -- the project's "batch
layer" (reusing the same bounded-chunk/Polars pattern used throughout this project rather than
introducing Apache Spark, which caused real memory problems elsewhere on this machine when
tried -- see pipeline/chunked.py's own docstring).

Unlike export_segment_correlation.py (a one-time export GraphLoader.java reads once at Java app
startup), this connects directly to a *running* Neo4j and replaces its graph in place -- no app
restart needed. Stale edges (a pair that qualified last run but no longer does) are deleted, not
just left in place, since a repeated refresh -- unlike GraphLoader's one-time load into an empty
database -- has to handle that case.

Run once (e.g. from cron / Windows Task Scheduler), or pass --loop for a demonstrable repeating
cadence without needing external scheduler setup.
"""
import argparse
import json
import os
import time

import polars as pl
from neo4j import GraphDatabase

DEFAULT_THRESHOLD = 0.95


def compute_graph(threshold: float):
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
            if r >= threshold:
                edges.append({"source": s1, "target": s2, "weight": round(float(r), 4)})
    return states, edges


def write_export_json(states, edges):
    path = "risk-engine/src/main/resources/segment_correlation_graph.json"
    with open(path, "w") as f:
        json.dump({"nodes": states, "edges": edges}, f, indent=2)
    print(f"Wrote {path}")


def refresh_neo4j(states, edges, uri, user, password):
    driver = GraphDatabase.driver(uri, auth=(user, password))
    try:
        def _refresh(tx):
            tx.run("MATCH ()-[r:CORRELATES]-() DELETE r")
            for state in states:
                tx.run("MERGE (:State {code: $code})", code=state)
            for edge in edges:
                tx.run(
                    "MATCH (a:State {code: $source}), (b:State {code: $target}) "
                    "MERGE (a)-[r:CORRELATES]-(b) SET r.weight = $weight",
                    source=edge["source"], target=edge["target"], weight=edge["weight"],
                )

        with driver.session() as session:
            session.execute_write(_refresh)
        print(f"Refreshed Neo4j at {uri}: {len(states)} nodes, {len(edges)} edges")
    finally:
        driver.close()


def run_once(threshold, uri, user, password):
    states, edges = compute_graph(threshold)
    print(f"Threshold: r >= {threshold}   Nodes: {len(states)}   Edges: {len(edges)}")
    write_export_json(states, edges)
    refresh_neo4j(states, edges, uri, user, password)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD,
                         help=f"minimum r to keep an edge (default: {DEFAULT_THRESHOLD})")
    parser.add_argument("--neo4j-uri", default=os.environ.get("NEO4J_URI", "bolt://localhost:7687"))
    parser.add_argument("--neo4j-user", default=os.environ.get("NEO4J_USER", "neo4j"))
    parser.add_argument("--neo4j-password", default=os.environ.get("NEO4J_PASSWORD", "arthadhruva"))
    parser.add_argument("--loop", action="store_true", help="keep running, refreshing on a repeating cadence")
    parser.add_argument("--interval-hours", type=float, default=24.0)
    args = parser.parse_args()

    if not args.loop:
        run_once(args.threshold, args.neo4j_uri, args.neo4j_user, args.neo4j_password)
        return

    while True:
        try:
            run_once(args.threshold, args.neo4j_uri, args.neo4j_user, args.neo4j_password)
        except Exception as e:
            print(f"Refresh failed, will retry next cycle: {e}")
        print(f"Sleeping {args.interval_hours}h until next refresh...")
        time.sleep(args.interval_hours * 3600)


if __name__ == "__main__":
    main()
