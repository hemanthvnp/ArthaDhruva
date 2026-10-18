# Segment correlation distance

Converts the Pearson correlation matrix behind the segment graph into the correlation distance

    D_ij = sqrt(2 * (1 - r_ij))

and checks it against the graph the service loads,
`backend/risk-engine/src/main/resources/segment_correlation_graph.json` (written by
`backend/export_segment_correlation.py`). Nothing on the production path reads these files.

## Why the distance

Correlations are not a metric. For series standardised to mean 0 and variance 1, `D` is proportional to the
Euclidean distance between them, so it is symmetric, zero on the diagonal and obeys the triangle inequality.
Clustering or multidimensional scaling of the states should therefore run on `D`, not on `r`.

`D` falls as `r` rises, so the graph's edge rule `r >= 0.95` is the same rule as `D <= sqrt(0.1) ~ 0.316`. The
edge-set check is a check that the series still reproduces the shipped graph, not a new finding.

## Result (2020-01 to 2025-09, 15 states, 69 months)

| Check | Result |
|---|---|
| Symmetric, unit-correlation diagonal, zero-distance diagonal, non-negative | yes |
| Triangle inequality over all triples | holds |
| Off-diagonal `r` | 0.367 to 0.991 |
| Off-diagonal `D` | 0.130 to 1.125 |
| Pairs with `D <= 0.316` / edges in the shipped graph | 41 / 41, same pairs, same weights to 4 dp |
| Closest pair | MI-OH, r = 0.991, D = 0.130 |

## Running

Input: `data/docs/segment_monthly_series.parquet` (not in the repository; build it with
`backend/build_segment_series.py --out data/docs/segment_monthly_series.parquet`). Set `SEGMENT_SERIES` to read
a copy elsewhere. The notebook stops if the file is missing and asserts every check above.

```bash
python -m nbconvert --to notebook --execute --inplace backend/experiments/segment_correlation_distance/pearson_correlation_distance.ipynb
```

Needs pandas, numpy, matplotlib, seaborn, pyarrow and nbconvert (all in `requirements-notebooks.txt` or
`requirements.txt`).

## Outputs in `results/`

- `pearson_correlation_matrix.csv`, `distance_matrix.csv`: the 15 x 15 matrices.
- `pairwise_correlation_distance.csv`: the 105 state pairs, closest first, with both threshold flags.
- `validation_report.json`: the checks and the graph comparison.
- `pearson_correlation_heatmap.png`, `distance_matrix_heatmap.png`, `correlation_vs_distance.png`,
  `distance_distribution.png`, `threshold_equivalence.png`.
