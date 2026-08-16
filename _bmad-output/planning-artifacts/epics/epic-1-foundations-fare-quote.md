## Epic 1: Foundations & Fare Quote

An operator can bring the whole stack up locally and see every service reporting health and metrics,
and a rider can price a pickup/dropoff pair through the gateway — proving the source tree, the build,
the schema pipeline, the test harness and the clock all work before any state machine is written.

### Story 1.1: Containerized service, proven against the real stack

As an operator,
I want `matching-service` to build and run in Docker with health and metrics exposed, and its behaviour proven by tests running against the real stack,
So that I can start the system, confirm it is alive, and trust that every later correctness claim is measured against real datastore semantics rather than a substitute's.

**Acceptance Criteria:**

**Given** a machine with no JDK installed
**When** the service image is built
**Then** it builds from a pinned Temurin 25 base image
**And** no host JDK is required at any point in build or run (NFR-7)

**Given** the Compose stack in `infra/`
**When** it is brought up
**Then** `matching-service` and its own private Postgres 18.6 start
**And** the service reports healthy only once Postgres is reachable (AD-1)

**Given** a running service
**When** its health endpoint is requested
**Then** it returns UP
**And** its Prometheus endpoint exposes metrics in Prometheus text format (AD-54)

**Given** a running service
**When** its Postgres is stopped and then restarted
**Then** health reports DOWN while the datastore is unreachable and UP once it returns
**And** this is proven by an integration test rather than by inspection

**Given** a first start
**When** Flyway runs
**Then** the schema is versioned and migration state recorded
**And** a second start applies no migrations and does not fail

**Given** the service directory
**When** its structure is inspected
**Then** it carries its own Gradle wrapper (9.x) and build file with **no root build** (AD-52)
**And** packages are `controller` / `service` / `repository` / `model` / `strategy` / `config`
**And** the domain package is named `model`, never `entity` (AD-7)

**Given** the layered structure
**When** a dependency-direction test runs
**Then** `model` imports nothing framework-flavoured
**And** `service` imports Strategy interfaces but no implementation
**And** nothing imports `controller` (AD-8)

**Given** the Compose stack
**When** integration tests run
**Then** the test runner executes as a container joined to the Compose network
**And** it never requires a Docker socket of its own (AD-56)
**And** tests run sequentially against one shared database (AD-56)

**Given** a test exercising persistence
**When** it runs
**Then** it uses the real Postgres instance
**And** no in-memory substitute, fake repository, or alternative SQL dialect is used anywhere (AD-10)

**Given** the repository root
**When** it is set up
**Then** a `Makefile` provides a build target that builds the project
**And** it **orchestrates each service's own Gradle wrapper** rather than becoming a root build, so
every service stays independently buildable as if it lived in its own repository (AD-52)
**And** every target it runs executes in Docker, requiring no host JDK (NFR-7)

**Given** the build target
**When** it runs
**Then** it installs a `pre-commit` and a `pre-push` git hook
**And** the hook sources are **tracked in the repository** rather than living only in `.git/hooks`,
which is not version-controlled — so a fresh clone plus a build restores both gates

**Given** one repository holding all five services
**When** the hooks are installed
**Then** there is **one hook of each kind**, because a repository has a single hooks path
**And** per-service behaviour comes from the hook **dispatching on which services a change touches**,
never from multiple hooks

**Given** a commit touching one service
**When** `pre-commit` runs
**Then** it runs that service's **unit tests only**, so the gate stays fast enough to be tolerated
**And** a failure **blocks the commit**

**Given** a commit touching the versioned contracts directory
**When** `pre-commit` runs
**Then** it runs **every** service's unit tests
**And** this is because those `.proto` and event-schema files are copied into all services at build
time, so a change there is a change to all of them (AD-52)

**Given** a push
**When** `pre-push` runs
**Then** it runs the **full suite**, including the integration tests against the Compose stack
**And** a failure **blocks the push** — the boundary immediately before the PR to `dev`, which is
where the gate actually has to hold

**Given** either hook
**When** it invokes tests
**Then** it runs them through the containerized test runner above
**And** it never assumes a JDK on the host (NFR-7, AD-56)

**Given** the build
**When** it runs
**Then** static analysis runs as part of it
**And** a violation **fails the build** rather than producing a report nobody reads

**Given** static analysis
**When** it is wired in
**Then** it runs inside the build container, requiring no host JDK and no IDE plugin (NFR-7)
**And** it is configured per service without introducing a root build, following AD-52's pattern of
one versioned configuration source copied in at build time rather than a shared build plugin that
couples the services

**Given** the `pre-commit` hook
**When** it runs
**Then** the fast static checks run alongside the unit tests, since they cost approximately nothing

> **Investigate when this story is detailed: which analyzers, and whether they run on Java 25.** The
> rules to enforce are **already written** across Stories 1.1 and 1.2 — this is a question of what
> executes them, not what they should be:
>
> | Rule already specified | What would enforce it |
> | --- | --- |
> | AD-8 one-way dependency: `model` imports nothing framework-flavoured, `service` imports Strategy interfaces but no implementation, nothing imports `controller` | **ArchUnit** is the Java ecosystem's standard for asserting package dependency rules as ordinary tests, and is the strongest candidate here |
> | AD-7 package structure, and `model` never named `entity` | ArchUnit |
> | AD-57 Liskov: no caller inspects a Strategy's concrete type | ArchUnit — assert no `instanceof` against strategy implementations |
> | NFR-9 / AD-58: no `Instant.now()`, `System.currentTimeMillis()`, or SQL `now()` outside the `Clock` | ArchUnit, or a compile-time checker such as Error Prone with a custom rule |
> | NFR-10 / AD-42: tokens never logged, provider keys never in source | A secret scanner over the repository, plus a rule that the masked token type is never passed to a logger |
>
> Broader candidates worth a look while there: **SpotBugs**, **Error Prone**, **PMD**, **Checkstyle**,
> **Spotless** (formatting), **NullAway**, and **OWASP Dependency-Check**.
>
> **Verify Java 25 and Spring Boot 4.1 support rather than assuming it.** The stack pins very recent
> versions, and analyzer support for a new JDK routinely lags by months — the same discipline the
> spine applied to its own Stack table, whose versions were *"verified against upstream release data
> at authoring, not asserted from memory."* An analyzer that cannot parse Java 25 is not a candidate,
> however good it is.

> **Why the split, recorded so it is not "simplified" later.** The gate exists because a suite that
> runs only when someone remembers it eventually stops being run — AD-56 names flaky concurrency
> tests *"the worst possible failure here"* precisely because people *"learn to re-run and ignore"*
> them. That same reasoning is why the **full** suite is not on `pre-commit`: by Epics 5–6 it runs
> sequentially against Postgres, Kafka, Redis and ClickHouse, and a multi-minute gate on every commit
> is one that gets bypassed with `--no-verify` inside a week. A bypassed hook protects nothing while
> reporting success, which is worse than no hook. Fast checks where commits are frequent, the full
> suite where it is cheap to wait and expensive to be wrong.

> **Note on ordering.** The truncate-and-reseed discipline of AD-56 is deliberately *not* here: with
> no owned tables and no seed data yet, it would assert over an empty set. It is introduced in Story
> 1.3 against `fare_rules` — the first seeded state — and extended in Story 2.1 to the fixture
> drivers AD-56 actually cites as the cross-test-interference risk.

### Story 1.2: Time is injectable and never read directly

As an engineer,
I want every read of the current time to go through a `Clock` strategy that is monotonic for durations and wall-clock for recorded facts,
So that no deadline in production can be broken by a clock correction, and timing behaviour is testable in seconds rather than by waiting.

**Acceptance Criteria:**

**Given** production code across every service
**When** a static-analysis test scans it
**Then** no call to `Instant.now()`, `System.currentTimeMillis()`, `LocalDateTime.now()` or SQL `now()`
appears outside the `Clock` implementation itself (NFR-9, AD-58)

**Given** a test
**When** it advances the clock by a chosen duration
**Then** code under test observes the new time immediately
**And** the test does not sleep (Testing convention)

**Given** the `Clock` strategy
**When** production code measures an elapsed duration or an in-process deadline
**Then** it reads a **monotonic** source
**And** when it records a persisted fact, it reads **wall clock** (NFR-9, Timestamps convention)

**Given** an NTP correction or a daylight-saving shift landing mid-window
**When** an in-process deadline is evaluated
**Then** it neither fires early nor fails to fire, because the duration was measured monotonically
**And** this holds in production, not only under test (NFR-9)

**Given** the same seed and the same clock script
**When** a timing test is re-run
**Then** it produces the same result (NFR-9)

### Story 1.3: Fares are computed from configurable rules

As a rider,
I want the fare for a trip computed from a published formula over configurable rules,
So that the price I am quoted is explainable and consistent rather than arbitrary.

**Acceptance Criteria:**

**Given** a first start after this story
**When** Flyway migrations run
**Then** a `fare_rules` table is created holding base, per-km, per-minute and the surge multiplier
**And** it is owned by `matching-service` (AD-3)

**Given** a `fare_rules` row carrying base, per-km, per-minute and surge
**When** a fare is computed for a pickup/dropoff pair
**Then** it equals `(base + per_km × distance + per_minute × time) × surge` (FR-18)

**Given** a first start
**When** `fare_rules` is seeded
**Then** the surge multiplier is `1.00` (FR-19, early-phase value)

**Given** a pickup and dropoff coordinate
**When** distance and time are derived
**Then** distance is haversine
**And** time is `distance / 8.33 m/s`
**And** no maps or routing API is called anywhere

**Given** a monetary value
**When** it crosses a boundary or is persisted
**Then** it is integer minor units in transit and `DECIMAL` at rest
**And** no floating-point type is used (Money convention)

**Given** coordinates
**When** they are persisted or passed to a geo call
**Then** latitude is `DECIMAL(10,8)`, longitude is `DECIMAL(11,8)`, WGS84
**And** longitude precedes latitude in geo calls (Coordinates convention)

**Given** a test class
**When** it starts
**Then** `fare_rules` is truncated and reseeded before it runs
**And** running the full suite twice produces identical results (AD-56)

### Story 1.4: Rider gets a fare quote through the gateway

As a rider,
I want to price a pickup/dropoff pair before committing to anything,
So that I know the fare and distance before I request a ride.

**Acceptance Criteria:**

**Given** the gateway
**When** a rider requests a quote with pickup and dropoff coordinates
**Then** HAProxy routes it to `rider-service`
**And** `rider-service` obtains the quote from `matching-service` over gRPC
**And** no ride is created (FR-1, AD-37)

**Given** no driver is available
**When** a quote is requested
**Then** fare and distance are returned and ETA is omitted
**And** the response is a success, never an error (FR-1)

**Given** any client
**When** it attempts to reach `matching-service` through the gateway
**Then** `matching-service` is not routable (AD-5)

**Given** an inbound request
**When** it enters the gateway
**Then** a correlation id is minted, propagated over gRPC metadata, and included in every log line
and error response (AD-54)

**Given** a malformed quote request
**When** it is rejected
**Then** the response is RFC 9457 Problem Details carrying the correlation id
**And** the status is 400 (AD-38)

**Given** a rider identity header
**When** a request arrives
**Then** the identity is trusted as-is with no authentication or registration (FR-48)

**Given** `rider-service`
**When** it starts
**Then** it exposes health and Prometheus metrics exactly as `matching-service` does (AD-54)

**Given** the `.proto` defining this first internal hop
**When** it is stored
**Then** it lives in one versioned directory in the repository
**And** it is copied into each service at build time, never hand-edited per service (AD-52)
**And** the services still build independently, with no runtime or compile dependency between them

**Given** a contract change
**When** it is made
**Then** adding a field is safe, while removing, renaming, retyping or **changing what a field means**
is breaking and requires a new message alongside the old
**And** protobuf field numbers are never reused (AD-33)

> **Scope boundary:** with no drivers in the system yet, only FR-1's no-driver branch is reachable
> here, and it is fully delivered. The ETA-present branch lights up in Story 2.6 once driver
> positions exist. This story is complete and independently valuable without it.

