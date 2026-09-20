## ADDED Requirements

### Requirement: Route-based frontend code-splitting
The frontend SHALL load each page's code on demand rather than bundling the entire application into a single JavaScript file.

#### Scenario: Initial load excludes unvisited pages
- **WHEN** a user loads the application and visits only the login and portfolio pages
- **THEN** the code for pages not yet visited (e.g., admin pages) is not downloaded until navigated to

### Requirement: Client-side data caching
The frontend SHALL cache API responses on the client and avoid redundant refetches for data that has not changed, while still reflecting updates after a mutation.

#### Scenario: Revisiting a page does not always refetch
- **WHEN** a user navigates away from and back to a page within a defined cache window
- **THEN** the previously fetched data is displayed immediately, with a background refresh rather than a blocking reload

### Requirement: Consistent pagination on list endpoints
Every API endpoint that returns a list of tenant-scoped records SHALL support pagination with an enforced maximum page size.

#### Scenario: Large result set is paginated
- **WHEN** a list endpoint's underlying result set exceeds the maximum page size
- **THEN** the response returns only up to the maximum page size and indicates more results are available

### Requirement: N+1 query prevention
Endpoints that return a list of entities with related data SHALL fetch that related data without issuing one additional query per returned entity.

#### Scenario: Fetching a list does not scale query count with row count
- **WHEN** a list endpoint returns N records that each reference related data
- **THEN** the total number of database queries issued does not grow linearly with N

### Requirement: CI-enforced performance gates
The CI pipeline SHALL fail the build if the frontend production bundle exceeds a defined size budget, or if a load test against key endpoints exceeds defined latency thresholds.

#### Scenario: Bundle size regression fails CI
- **WHEN** a change increases the production frontend bundle beyond its configured size budget
- **THEN** the CI pipeline fails with a message identifying the budget and the actual size
