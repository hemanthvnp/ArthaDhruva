## ADDED Requirements

### Requirement: Links are learned, and carry a weight, a sign, a lag where directed, and a confidence
The system SHALL learn, from state macro data with the national movement removed, two kinds of link between states: "moves with", a direct same-period association after accounting for all other states, and "leads", where one state's past predicts another's future by a stated number of months. A link SHALL be kept only if it is selected in at least 80% of block-bootstrap resamples.

#### Scenario: Moves-with links are direct
- **WHEN** two states are associated only through a third state, as in a synthetic chain A, B, C
- **THEN** no moves-with link is recorded between A and C

#### Scenario: Leads links have a lag
- **WHEN** a leads link is recorded
- **THEN** it has a lag in months, a weight, a sign and a confidence equal to the share of resamples in which it was selected

#### Scenario: Weak links are dropped
- **WHEN** a link is selected in fewer than 80% of the resamples
- **THEN** it is not in the graph file

#### Scenario: Reproducible export
- **WHEN** the export is run twice on the same inputs
- **THEN** the two graph files are identical byte for byte

### Requirement: The graph is validated on later data against simple alternatives
The system SHALL test the graph learned on data through December 2016 against realised delinquency from January 2017, compared with a same-Census-division grouping, the existing 0.95 correlation graph and random graphs with the same degree sequence. The result SHALL be published in the model card whether or not the learned graph wins.

#### Scenario: Linked versus unlinked pairs
- **WHEN** validation runs
- **THEN** the card records the mean correlation of 3-month changes in the standardised delinquency ratio among linked pairs minus the same among all other pairs, with a one-sided p-value against degree-preserving random rewirings

#### Scenario: Geography does as well
- **WHEN** the learned graph does not exceed the same-division grouping at the 5% level
- **THEN** the card states that the graph adds no information beyond geography

#### Scenario: Direction is tested
- **WHEN** validation runs on the leads links
- **THEN** the card records the share of links whose forward cross-correlation exceeds the reverse, with a binomial p-value against 50%

#### Scenario: Direction is not supported
- **WHEN** the share of leads links with the expected direction is not above chance
- **THEN** the leads links are omitted from the graph file and the card states why

#### Scenario: Robustness runs
- **WHEN** results are reported
- **THEN** each headline number is given for all months and for the period excluding March 2020 to March 2021, and for the series with and without disaster-flagged delinquencies, and a conclusion that changes between them is labelled as not robust

### Requirement: Regions and bellwethers are read off the graph
The system SHALL group states into regions from the learned graph and SHALL count, for each state, how many states it leads and how many lead it.

#### Scenario: Regions
- **WHEN** regions are formed from the confident positive moves-with links
- **THEN** every region has at least 3 states, and the file records for each region how often its members were grouped together across the bootstrap resamples

#### Scenario: Bellwethers
- **WHEN** the leads links are known
- **THEN** each state record carries the number of states it leads and the number it follows

### Requirement: The artifact carries its own identity
The system SHALL write the graph as a versioned file with a model card that records the SHA-256 of the file.

#### Scenario: Card and file agree
- **WHEN** the export finishes
- **THEN** the card contains the version, training windows, algorithm and settings, validation results, limitations and the checksum of the graph file, and the graph file conforms to the schema in the design

#### Scenario: Limitations are stated
- **WHEN** the card is written
- **THEN** its limitations include that a lead is predictive precedence and not cause, that the delinquency data used for validation covers one benign period and one pandemic, and that state can stand in for protected characteristics so the graph is for portfolio-level analysis only
