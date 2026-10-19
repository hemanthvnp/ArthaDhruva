## Why

The service still loads and draws the old segment graph: one Pearson table over 15 states cut at r >= 0.95, which mostly draws the national cycle and has no card or checksum. The page also promises "where trouble in one market has tended to show up next", which nothing supports. `segment-graph-models` (#29) replaced it with a learned, out-of-time validated graph (`segment_graph.json` and its card), but nothing reads that file yet (#40).

## What Changes

- **Neo4j holds the learned graph.** At start-up `GraphLoader` reads `segment_graph.json` and replaces whatever segment graph Neo4j holds, in one transaction: states with name, region and coverage, `MOVES_WITH` links with weight, sign, confidence and basis, and the graph version. A restart against a Neo4j that already holds the same version does nothing. The old `CORRELATES` edges are removed: MERGE alone would leave them next to the new links.
- **Integrity and inventory.** `ModelRegistry` verifies `segment_graph.json` against the checksum in `segment_graph_card.json`, refuses to start on a mismatch, and lists the graph as validated, tier 2, with its validation headline.
- **API.** `/v1/segments/graph` returns the states (code, name, region), the links (pair, weight, confidence, basis), the regions, the version and the validation headline. **BREAKING** for clients of that endpoint: `edges` (pairs of codes) becomes `links` (objects). The only client is the console, which changes in the same commit. `/v1/segments` and `/v1/segments/{state}/neighbors` keep their shapes and traverse the learned links.
- **Page.** All 51 states on a tile map of the US, links drawn by weight, the region marked, and the validated figures quoted from the card. The lead-lag claim goes: no leads link passed validation, and the page says which states are linked, not which follow which.
- **Batch refresh retired.** `backend/batch_refresh_segment_correlation.py` recomputes the Pearson graph into a running Neo4j and would overwrite the learned graph. It moves to `backend/experiments/`, with a note saying why it is not on the production path.
- **Unchanged.** `segment_correlation_graph.json` stays in the resources: `experiments/segment_graph_validation.py` compares against it. Nothing loads it.

## Capabilities

### New Capabilities
- `segment-graph-serving`: loading the learned segment graph into Neo4j (versioned, replacing the old graph), verifying it against its card, and serving it, its neighbour queries and its validation figures to the console.

### Modified Capabilities
- None. `segment-graph-learning` describes the offline export, which does not change.

## Impact

- **Java**: `graph/GraphLoader`, `graph/SegmentGraphService`, `graph/SegmentGraphController` and new records for the graph response; `governance/ModelRegistry`. Tests: `ApiIntegrationTest` (segment test on the learned graph, version replacement), `ModelRegistryTest`, a unit test for parsing the graph file.
- **Frontend**: `api/types.ts`, `api/client.ts`, `pages/SegmentGraphPage.tsx`. The page is already lazy-loaded; the bundle budget is unaffected.
- **Python**: `batch_refresh_segment_correlation.py` moves to `backend/experiments/`.
- **Docs**: README feature table, `MODELS.md` inventory and "Segment graph" section.
- **Data**: none. Neo4j is rebuilt from the classpath file at start-up, as today.
