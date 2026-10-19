## 1. The artifact and its integrity

- [x] 1.1 `graph/SegmentGraphArtifact`: reads `segment_graph.json` and `segment_graph_card.json` from the classpath once; holds the parsed graph (records for states, links, regions, optional leads), the file's sha256 and the card. Unit test: parses the shipped file (51 states, 7 links, 1 region, no leads) and a file with leads.
- [x] 1.2 `ModelRegistry`: compare the checksum with the card's `artifact_sha256`, stop start-up on a mismatch naming both files; add the inventory entry (id `segment_graph`, tier 2, validated, headline from the card's validation). `ModelRegistryTest`: the entry is listed and verified; a mismatching checksum throws.

## 2. Loading into Neo4j

- [x] 2.1 `GraphLoader`: read the `(:SegmentGraph {version, sha256})` marker; if it matches the artifact, do nothing; otherwise, in one write transaction, detach-delete every `State` and the marker, create the states (code, name, region, covered), `MOVES_WITH` (and `LEADS`, if present) with their properties, and the marker. Neo4j failures stay logged and non-fatal.
- [x] 2.2 Integration test: seed Neo4j with the old 15-state `CORRELATES` graph, run the loader, assert 51 states, 7 `MOVES_WITH`, no `CORRELATES`; run it again and assert nothing was written (marker unchanged, same counts).

## 3. API

- [x] 3.1 `SegmentGraphService.graph()` returns the new record (version, asOf, states with name and region, links once with a < b, regions, validation headline from the artifact's card); `neighbors()` traverses `MOVES_WITH`.
- [x] 3.2 `SegmentGraphController`: cache keys carry the graph version and checksum prefix (`segment-graph:<version>:<sha>`, `segment-neighbors:<version>:<sha>:<state>:<hops>`).
- [x] 3.3 `ApiIntegrationTest`: the breadth-first check runs on `links`; 51 states; link fields in range; validation section present; a state with no link has no neighbours; `maxHops=9` is 400.

## 4. Console

- [x] 4.1 `api/types.ts` and `api/client.ts`: the new graph response.
- [x] 4.2 `SegmentGraphPage`: US tile map of all states, links weighted by strength, region outlines, neutral hop colours for a selected state's neighbours, the validation figures, and text without lead-lag claims; an empty-neighbour message that says the state has no validated link.
- [x] 4.3 Frontend lint, tests, build and bundle budget pass.

## 5. Retire the Pearson refresh and document

- [x] 5.1 Move `backend/batch_refresh_segment_correlation.py` to `backend/experiments/` with a header saying it is superseded and would overwrite the learned graph; check nothing on the production path references it.
- [x] 5.2 README feature table (a Segment graph row), `MODELS.md` inventory and "Segment graph" (now served), DEPLOY.md rollback note (clear `State` and `SegmentGraph` nodes after rolling back past this release).
- [x] 5.3 Full backend suite (with Docker) and `/verify-all`. Run individually (2026-10-19): backend 293/293 in 56 classes; frontend lint, 66 tests, build, bundle 92.7 KB of 120 KB; the page checked in a real browser on an isolated stack, no page or console errors.
