## Context

`GraphLoader` MERGEs `segment_correlation_graph.json` (15 states, 41 `CORRELATES` edges) into Neo4j at start-up, and a failure there is logged, not fatal. `SegmentGraphService` serves the whole graph (`/v1/segments/graph`: state codes and edge pairs) and shortest-hop neighbour queries. `SegmentGraphController` caches both in Redis for 24 hours. The page draws the states on a circle.

`segment-graph-models` produced `segment_graph.json` (schema in `src/test/resources/segment/segment_graph.schema.json`): 51 states, 7 `movesWith` links, one region, no `leads`, no regional factors, and `segment_graph_card.json` with the artifact's checksum and the out-of-time validation. The design of that change named the serving side's job: a versioned Neo4j load, an API that returns weights, and the page. Leads and regional factors are absent from this release of the file, but the serving side must handle them being present in a later one.

## Goals / Non-Goals

**Goals:**
- Neo4j holds exactly the learned graph after start-up, whatever it held before.
- The artifact is held to its card like every other model, and appears in the inventory.
- The API and the page show what was validated and nothing more.

**Non-Goals:**
- Regional factors in the loss simulation (none ship; a later change if a graph with two or more regions does).
- Drawing leads links. The loader stores them if a future file has them; the page does not claim direction until a release ships them.
- A weighted distance between states. The partial correlations (0.05 to 0.14) would give every pair nearly the same distance; neighbours stay in hops.

## Decisions

**1. Replace the graph in one transaction, keyed by version.** A `(:SegmentGraph {version, sha256})` node records what is loaded. At start-up the loader reads it; if version and checksum match the file, it does nothing. Otherwise one write transaction detaches and deletes every `State` and the old marker, then creates the states, the `MOVES_WITH` (and, if present, `LEADS`) relationships and the new marker. *Why:* MERGE never deletes, so on an existing database the 41 old edges would stay. One transaction means a reader sees either the old graph or the new one, never half. Replicas starting together run the same transaction; Neo4j serialises the writes, and the second finds the marker or replaces with identical content. *Alternative considered:* MERGE plus deleting relationships without the current version property. That leaves states the new file drops and needs more statements for no gain at this size.

**2. Integrity in the registry, loading in the loader, one reader for the file.** A `SegmentGraphArtifact` component reads `segment_graph.json` and its card from the classpath once, and holds the parsed graph, the bytes' checksum and the card. `ModelRegistry` compares the checksum with the card's `artifact_sha256` and stops start-up on a mismatch, as for the PD and LSTM models, then adds an inventory entry (tier 2, validated, headline from the card). `GraphLoader` writes the same parsed graph to Neo4j; a Neo4j failure stays non-fatal (the existing fail-open behaviour). *Why:* a tampered file must stop the service, while an unavailable database must not take down scoring.

**3. The API serves the graph from Neo4j and the card from memory.** `/v1/segments/graph` returns `version`, `asOf`, `states` (code, name, region), `links` (a, b, weight, confidence, basis; each pair once, smaller code first), `regions` and `validation`: the headline the page quotes (linked-minus-unlinked statistic and p, learned-minus-division difference and p, whether each conclusion is robust, the test window, and why leads were left out). The validation comes from the card held by `SegmentGraphArtifact`, not from Neo4j. *Why:* the figures belong to the artifact, and there is nothing to traverse in them.

**4. Cache keys carry the graph version and checksum.** Today `segment-graph` and `segment-neighbors:<state>:<hops>` live 24 hours in Redis, so after a deploy every replica would keep serving the old graph's answers for up to a day. The new keys include the version and the first 12 characters of the file's checksum (`segment-graph:<version>:<sha>`, `segment-neighbors:<version>:<sha>:<state>:<hops>`): the version names the data, so a re-export from the same data with different code keeps it, while the checksum moves with any change. A new release never reads an old entry, and the old ones expire. The response shape changes too, so an old `segment-graph` entry would not deserialise into the new record anyway.

**5. Neighbours stay shortest paths in hops** over `MOVES_WITH`, with the same 1 to 5 bound. With seven links most states have no neighbour; the API returns an empty list and the page says so.

**6. The page uses a tile map.** 51 states on a circle are unreadable. A standard US tile grid (one square per state, 12 columns by 8 rows) keeps neighbours near each other, which matters because the learned links are mostly between neighbours. Links are lines between tile centres, thicker for larger weight; tiles in a region get its outline; a selected state and its neighbours take the existing neutral hop colours (hop distance is not risk, so not the risk spectrum). The validated figures sit beside the map in plain words. The subtitle says which states are linked and that pairs without a link are not shown to be unrelated.

**7. The batch refresh leaves the production path.** `batch_refresh_segment_correlation.py` writes the Pearson graph into a running Neo4j; it moves to `backend/experiments/` with a header saying it is superseded and would overwrite the learned graph. Refreshing the learned graph means rerunning `export_segment_graph.py` (about 30 minutes) and deploying, like every other model.

## Risks / Trade-offs

- **[Risk]** A replica restarts during a load. → One transaction: Neo4j rolls it back, the next start replaces again.
- **[Risk]** Old Redis entries. → Version in every key (decision 4).
- **[Risk]** The page looks empty with 7 links. → That is the validated result; the page says what the links mean and shows the validation, and the empty-neighbour message names it.
- **[Trade-off]** `edges` becomes `links` in `/v1/segments/graph`. The only client is the console, changed in the same commit.

## Migration Plan

Deploy as usual. On first start each replica finds no `SegmentGraph` marker (or the old graph), replaces it, and records the version. Rollback is the previous release: its loader MERGEs the old 41 edges next to the learned links, so a rollback must be followed by `MATCH (n) WHERE n:State OR n:SegmentGraph DETACH DELETE n` and a restart. That is written in DEPLOY.md's rollback notes.

## Open Questions

- None blocking. If a later export ships leads, a follow-up decides how the page shows direction.
