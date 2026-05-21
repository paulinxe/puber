# Agent Instructions

## Coding Principles

### SOLID

All code in this project must follow **SOLID** principles. The following rules are non-negotiable:

1. **Single Responsibility Principle (SRP)** — Every class, method, and service must do **one thing only**.
   - No god-classes. If a service has more than one public responsibility, split it into separate services.
   - Example: `MatchingService` with 5 public methods was split into `FindNearestDriver`, `AssignDriver`, `AcceptRide`, `CompleteRide`, and `ReleaseDriver` — each with a single `execute(...)` method.

2. **Open/Closed Principle (OCP)** — Open for extension, closed for modification.
   - Prefer adding new classes/strategies over editing existing ones.
   - Example: new matching algorithms should be added as new implementations of an interface, not by adding `if` branches to `FindNearestDriver`.
   - **YAGNI:** Do not create interfaces for things that have only one implementation. Extract the interface when a second implementation (e.g., Redis, mock) is actually needed.

3. **Liskov Substitution Principle (LSP)** — Subtypes must be substitutable for their base types without altering correctness.
   - Example: any implementation of a `DriverOfferClient` interface (real HTTP, mock, test double) must satisfy the same contract.

4. **Interface Segregation Principle (ISP)** — No client should be forced to depend on methods it does not use.
   - Split broad interfaces into smaller, role-specific ones.
   - Example: `RideRepository` read-only queries can be separated from write operations if consumers only need one side.

5. **Dependency Inversion Principle (DIP)** — Depend on abstractions, not concretions.
   - Services should depend on interfaces or domain concepts, not on specific repository implementations or HTTP client libraries.
   - Example: `AssignDriver` depends on `DriverRepository` and `RideRepository` abstractions, not on `JdbcTemplate` directly.

### Immutable Domain Objects

- All domain classes (`Ride`, `Driver`, `Location`, `FareRule`) are **immutable** — `final` fields, no setters.
- State transitions return **new instances**: `driver.setBusy()`, `ride.assignDriver(...)`, `ride.markCompleted(...)`.
- Repositories receive the new instance and persist it via `save(...)`.

### No Magic Numbers

- Every numeric literal with business meaning must be a `private static final` constant with a descriptive name.
- Examples: `MAX_MATCHING_RADIUS_KM`, `AVERAGE_SPEED_KMH`, `SCHEDULER_INTERVAL_MS`, `OFFER_TIMEOUT_SECONDS`.

### Service Naming

- Service names must express the **action** they perform, not the entity they touch.
- Good: `RequestDriver`, `AcceptRide`, `FindNearestDriver`, `UpdateLocation`, `ExpireStaleRequests`.
- Bad: `DriverService`, `RideService`, `MatchingService` (god-class smell, noun-only, no verb).
- Every service has a single public `execute(...)` method (or `calculateXxx(...)` / `findXxx(...)` for read-only services).

### Functional Tests Over Unit Tests

- Every feature is validated by `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` + real Postgres.
- No mocked repositories in feature tests. Mock only cross-service HTTP clients when the callee service is not running in the same JVM.

---

*Created after Week 3 ticket definition. Updated whenever architectural constraints change.*
