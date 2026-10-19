## ADDED Requirements

### Requirement: Neo4j holds exactly the learned graph
The system SHALL load `segment_graph.json` into Neo4j at start-up and, when the graph stored there is a different version or checksum, SHALL replace it in a single transaction, so that afterwards Neo4j holds the file's states and links and nothing else. A failure to reach Neo4j SHALL NOT stop the application.

#### Scenario: Replacing the old correlation graph
- **WHEN** the application starts against a Neo4j holding the old 15-state graph with its `CORRELATES` edges
- **THEN** afterwards Neo4j holds the 51 states of the file, its `MOVES_WITH` links with their weights, and no `CORRELATES` edge

#### Scenario: The same version is already loaded
- **WHEN** the application starts and Neo4j already holds the file's version with the same checksum, and exactly its states and links
- **THEN** nothing is written

#### Scenario: Something was written after the load
- **WHEN** the marker matches but Neo4j holds a relationship the file does not have
- **THEN** the graph is replaced again

#### Scenario: Neo4j is unavailable
- **WHEN** Neo4j cannot be reached at start-up
- **THEN** the application starts, the failure is logged, and the segment endpoints fail per request until a later start succeeds

### Requirement: The graph is held to its card
The system SHALL verify `segment_graph.json` against the `artifact_sha256` in `segment_graph_card.json` at start-up and SHALL refuse to start on a mismatch. The model inventory SHALL list the graph as a validated tier-2 model, with its version, checksum and validation headline.

#### Scenario: A changed file
- **WHEN** the bytes of `segment_graph.json` do not match the checksum in its card
- **THEN** the application does not start, and the error names both files

#### Scenario: Inventory entry
- **WHEN** the model inventory is requested
- **THEN** it lists the segment graph as validated, tier 2, with its checksum marked verified

### Requirement: The graph API serves the learned graph and its validation
The system SHALL return, from `/v1/segments/graph`, the graph version and as-of month, every state with its name and region (null when it has none), every link once with the smaller state code first and its weight, confidence and basis, the regions, and the validation headline from the card. It SHALL NOT present any link as directional unless the file contains leads.

#### Scenario: Shape of the graph response
- **WHEN** an analyst requests the graph
- **THEN** the response has 51 states, each link has `a` before `b` in code order with a weight between -1 and 1 and a confidence between 0 and 1, and the validation section reports the out-of-time statistic, its p-value, the comparison with the Census-division grouping and whether each conclusion is robust

#### Scenario: No leads in the file
- **WHEN** the file has no `leads`
- **THEN** the response has no lead links and the validation section states why they were left out

### Requirement: Neighbour queries are shortest paths over the learned links
The system SHALL answer `/v1/segments/{state}/neighbors` with every state reachable within `maxHops` (1 to 5) links of the given state and its shortest distance in links, over the learned links only.

#### Scenario: Agreement with the graph served
- **WHEN** the neighbours of a linked state are requested for each hop limit from 1 to 5
- **THEN** they equal a breadth-first search over the links `/v1/segments/graph` returns

#### Scenario: A state with no link
- **WHEN** the neighbours of a state with no link are requested
- **THEN** the response is an empty list

#### Scenario: Hop limit out of range
- **WHEN** `maxHops` is outside 1 to 5
- **THEN** the response is 400

### Requirement: Cached answers belong to the graph that produced them
The system SHALL key every cached graph and neighbour response by the graph version and the file's checksum, so that no response computed from one graph is served for another.

#### Scenario: A new graph version is deployed
- **WHEN** a release with a new graph version starts while responses from the previous version are still cached
- **THEN** every graph and neighbour request is answered from the new graph

### Requirement: The page says only what was validated
The Segment Graph page SHALL draw all states served, the learned links weighted by strength and the regions, SHALL quote the validated figures, and SHALL NOT claim that trouble in one state tends to follow another unless lead links are served.

#### Scenario: The current release
- **WHEN** the page shows the graph without lead links
- **THEN** its text describes linked states as moving together, says pairs without a link are not shown to be unrelated, and shows the out-of-time validation figures

### Requirement: Nothing on the production path overwrites the learned graph
No script on the production path SHALL write a different segment graph into a running Neo4j.

#### Scenario: The Pearson refresh
- **WHEN** the deployed service and its scheduled jobs run
- **THEN** none of them writes `CORRELATES` edges or the Pearson graph, and the script that did lives under `backend/experiments/` marked as superseded
