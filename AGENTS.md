# Agent Instructions

> **Project rules live in [`project-context.md`](project-context.md).** Build and test entry points,
> package layout, the analyzer set, the hook policy, the non-root container rules, and the Boot 4.1 /
> Java 25 gotchas are recorded there and are binding. This file covers **coding style only**.
>
> The rules are deliberately in one place and not copied here: two files holding the same rules drift.

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

### SQL stays a literal at the call site

**An exception to the rule above, and it applies to SQL only.** A query is written inline, in the
`JdbcTemplate` call that runs it — not hoisted into a `private static final String`.

```java
// Good -- the statement and the call that runs it are one thing you read once.
return jdbcTemplate.query(
        "select base_fare, per_km_rate, per_minute_rate, surge_multiplier from fare_rules",
        AS_FARE_RULE);

// Bad -- now you read the method, jump to the constant, and come back.
return jdbcTemplate.query(SELECT_THE_PRICE_LIST, AS_FARE_RULE);
```

The reason is reading, not typing. A repository with one query per method accumulates one constant
per method, all declared together at the top and all named some variation of the method they belong
to — so every method becomes two hops instead of one, and the file's most important content sits
furthest from where it is used.

This covers **the query text**. It does not relax anything else: a numeric literal inside the query
still gets a named constant and a bind parameter (`where status = ?`, never the literal inlined into
the statement), and a `RowMapper` shared by more than one query stays a field.

### Member order

**Private methods go at the bottom of the file, below everything public.** A reader scanning a class
should meet its API first and its helpers only when they go looking for them.

- Order within a class: constants, fields, constructors — and a test class's lifecycle methods —
  then public or `@Test` methods, then private methods last.
- **Never between declarations.** A helper wedged between two constants, or between two `@ArchTest`
  rules, breaks up the run of declarations a reader was scanning. This is how it goes wrong in
  practice: a helper gets written directly under the field that first needed it.
- Java does not care about order, so nothing will fail. Deliberately not enforced by a rule.

### Comments

Comment the **why**, not the **what** — the code already says what it does.

- Write one only if a reader would otherwise be surprised, or would "fix" something that is correct on
  purpose. If deleting the comment costs nothing, delete it.
- Two lines is usually enough. If it needs a paragraph, the explanation belongs in
  `project-context.md` or the story file — leave a short pointer in the code instead.
- Plain English, short sentences. Do not restate architecture decisions that are written down
  elsewhere.
- **Do not state facts you have not checked** — library defaults, version behaviour, "X must be lower
  than Y". Nothing tests a comment, so a wrong one outlives wrong code. If a fact earns its place, say
  where you confirmed it.
- A comment naming a file, task, or setting has to move when that thing moves. A stale pointer is
  worse than no pointer.

```
Bad   # set the connection timeout to 2 seconds     <- repeats the line below it
Good  # Hikari defaults to 30s, too slow for a readiness probe.
```

### Service Naming

- Service names must express the **action** they perform, not the entity they touch.
- Good: `RequestDriver`, `AcceptRide`, `FindNearestDriver`, `UpdateLocation`, `ExpireStaleRequests`.
- Bad: `DriverService`, `RideService`, `MatchingService` (god-class smell, noun-only, no verb).
- Every service has a single public `execute(...)` method (or `calculateXxx(...)` / `findXxx(...)` for read-only services).

### Functional Tests Over Unit Tests

- Every feature is validated by `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` + real Postgres.
- No mocked repositories in feature tests. Mock only cross-service HTTP clients when the callee service is not running in the same JVM.

### No test-only seams

**A test never gets access it had to be given.** If a unit test can only reach the logic through a
package-private or `protected` member, the class is doing two things and the test is telling you so.
Split it; do not widen it.

The shape it always takes: a class fetches state — a repository, the `Clock`, an HTTP client — *and*
computes over it, so the computation cannot be exercised without the fetch.

```java
// Bad -- the seam exists for the test, and its own javadoc admits it.
@Service
public class CalculateFare {
    private final FareRuleRepository fareRules;

    public Money calculate(Coordinates pickup, Coordinates dropoff) {
        return priceOf(fareRules.priceList(), pickup.distanceTo(dropoff));
    }

    /** Package-private so a unit test can price a rule it chose. */
    static Money priceOf(FareRule rule, Distance distance) { ... }
}

// Good -- the computation takes its inputs, so the public API is what the test calls.
public final class CalculateFare {
    public static Money calculate(FareRule rule, Distance distance) { ... }
}
// the caller composes: CalculateFare.calculate(fareRules.priceList(), pickup.distanceTo(dropoff))
```

- **A method that needs no instance state takes its inputs as parameters and is `public static`.** If
  that leaves the class with no fields, it is not a Spring bean — drop `@Service` and the constructor
  with it.
- **Choose the parameter type that keeps a test expectation derivable by hand.**
  `calculate(rule, Distance)` beats `calculate(rule, pickup, dropoff)`: `new Distance(1005)` prices at
  exactly 100.500, while the coordinates giving that distance are not a literal anyone can check.
- **The tell is a comment.** Production code that mentions a test, or explains why a member is
  package-private, is this smell with a note attached. Fix the design; do not keep the note.
- **Not a licence to make everything static.** A class that genuinely coordinates — several
  collaborators, an ordering, a transaction — stays a bean with injected dependencies and is proven by
  an integration test against the real stack. What is banned is *widening access for a test*, not
  dependency injection.
- **Nothing enforces this.** It was written after PUB-3 shipped exactly the bad shape above and the
  repo owner caught it in review. If it recurs, the fix is an ArchUnit rule: `ArchitectureRulesTest`
  imports production code only, so a package-private or `protected` method with zero callers in that
  import is by construction a member only tests use.

### Test Naming and Placement

- Unit tests: `*Test` in `src/test/java`. No database, no Spring context.
- Integration tests: `*IntegrationTest` in `src/integrationTest/java`. Real Postgres from the Compose
  stack.
- **The directory decides which suite a test belongs to, not the name.** `./gradlew test` runs one
  source set and `./gradlew integrationTest` the other, so a misnamed file still runs in the suite its
  folder puts it in. Name it correctly anyway — the name is what you read in a failure line.
- Not `*IT`. That suffix comes from Maven's Failsafe plugin, where it is the selector; here it selects
  nothing and only looks like it does.

**Test methods are `snake_case`. Everything else stays `camelCase`.**

```java
@Test
@DisplayName("AC2: a 5 km trip at the seeded rates prices at 1100 minor units")
void prices_a_five_kilometre_trip_from_the_seeded_rules() { ... }
```

- **`@Test` methods only.** Private helpers and lifecycle methods in a test class stay `camelCase`, so
  the casing itself tells you which methods are test cases when you scan the file.
- **`@ArchTest` `ArchRule` fields stay `camelCase`** — they are fields, not methods, and they are
  referenced by name from other test classes and from `project-context.md`.
- **Fixture classes stay `camelCase`** — `rules/fixtures/*`, `ControllableClock`,
  `NeighbourStrategyThatReadsTimeDirectly`. They exist to be scanned by ArchUnit *as if they were
  production code*; snake_case would make them unrepresentative of what they stand in for.
- **Keep `@DisplayName`.** The method name says what the test does; the display name carries the
  `AC<n>:` reference and the exact expectation, and can hold characters an identifier cannot (`€`,
  `×`, `5 km`). It is what lets a reviewer grep for whether a criterion is actually tested.
- **Verified 2026-08-23:** Spotless `googleJavaFormat().aosp()` accepts this — it is a formatter and
  never rewrites identifiers. Proven by planting a snake_case test, running
  `make static-analysis SERVICE=matching-service`, and watching it pass.
- **Enforced, not reviewed — but it takes two classes, one per source set.**
  `TestNamingRulesTest.testMethodsAreSnakeCase` covers `src/test/java`, and
  `TestNamingRulesIntegrationTest` covers `src/integrationTest/java`. It is a separate class from
  `ArchitectureRulesTest` because that one is `DoNotIncludeTests` — see `project-context.md` →
  Static analysis.

  **The second class is not redundant, and the rule body is deliberately duplicated.** The unit
  suite's classpath does not carry the integration source set, and ArchUnit's `OnlyIncludeTests`
  recognises test output by path and knows `build/classes/java/test`, not `.../integrationTest` — so
  the single class could not see those files by either route. Verified 2026-08-23: a camelCase
  `@Test` planted in `FareRulesIntegrationTest` gave `testMethodsAreSnakeCase PASSED` and
  `BUILD SUCCESSFUL` before the second class existed, and fails now. If you change one rule body,
  change both.

  Both are checked against vacuity: the integration one asserts it actually imported `@Test` methods
  before checking them, because a rule that scans nothing passes forever.
- **Warning for whoever adds Checkstyle** (the epics name it as the natural addition around Epic 3):
  its `MethodName` check rejects underscores by default. Relax it **for test sources only** rather
  than renaming these back.

---
