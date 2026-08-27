# Project Context: puber

Loaded automatically by every BMad workflow, so rules here reach every future `create-story`,
`dev-story` and `code-review` run. Rules left only in a story file are lost when the next story starts.

Not a summary of the architecture — `ARCHITECTURE-SPINE.md` governs technical decisions and `SPEC.md`
is the contract. Only what is non-obvious from reading the code belongs here.

**Read `AGENTS.md` before writing code.** Nothing loads it automatically. It holds the coding style:
SOLID, immutable domain objects, naming, no magic numbers, test naming, and how to write comments.

## YAGNI

Before adding a guard, a flag, or an abstraction: name the failure it prevents, then check that the
failure is real. If you cannot reproduce it, do not add it. **A rationale is not evidence** — three
guards written during PUB-1 (`ALLOW_ROOT`, `forbidSubstituteDatastores`, `.NOTPARALLEL`) each had a
convincing comment and each guarded nothing.

## No root build (AD-52)

Each service has its own Gradle wrapper and build file. No root `build.gradle`, `settings.gradle`,
`gradlew`, shared plugin, convention plugin or `buildSrc`. The `Makefile` invokes each wrapper; it
does not aggregate them.

## No host JDK (NFR-7)

Build, test, static analysis and hooks all run in containers. If a step works only because Java is
installed, it is wrong. Neither hook script may contain `java`, `javac`, `gradle` or a host `./gradlew`.

## Entry points

`make build` / `make run` / `make test`, and nothing else should be needed. `make test` is `test-unit`
+ `test-integration` and brings up its own datastores, so it works on a fresh clone. `make build` also
installs the git hooks.

## Before you call it done, run `make build` and `make test`

Both, in that order, and read the output.

**Not `make test-unit`.** It does not run Spotless, so a formatting violation that blocks
`make build` stays invisible — which is exactly how PUB-2's review left the build red while
reporting the suite green. `spotlessCheck` runs under `make build`, `make static-analysis` and
`pre-commit`; `make test-unit` runs neither it nor the integration suite.

This is the gate on a **status change** — anything moving to `review` or `done`, in the story file or
in `sprint-status.yaml`. A status is a claim that the work is finished, so it carries the same burden
as any other claim here (`CLAUDE.md` → "Prove it, don't reason about it"). Do not set it from a
partial run, and do not set it from a run you did before the last edit.

Which workflow enforces this, and when, is wired in `_bmad/custom/*.toml`. The rule lives here
because it binds whoever does the work, not only the workflows that happen to be in use.

Report what you saw, including a failure you did not cause. A known environmental red — PUB-1's
`HealthReportsDownPromptlyIntegrationTest` precondition is the current one — is named, not omitted
and not quietly treated as green.

## Package layout: feature outside, layer inside

AD-7 fixes the layers (`controller` / `service` / `repository` / `model` / `strategy` / `config`);
AD-9 splits `matching-service` by feature. They compose as feature first, layer inside:

```
com.puber.matching
  config/  shared/  fare/  ride/  dispatch/  quote/
```

- **Create layer packages only as they gain content.** Never scaffold empty ones to match a diagram.
  `config/` and `shared/` exist because Story 1.2's `Clock` gave them content; every other layer and
  feature package arrives with the story that first needs it.
- **The domain package is `model`, never `entity`** (AD-7): `entity` implies an ORM, and there is none.
- **Java packages are suffix-free** (AD-12): `com.puber.matching`. Directories and containers keep the
  `<role>-service` suffix.

## What belongs in `shared`

`shared` is the bottom of `shared ← fare ← ride ← dispatch ← quote`: everything may depend on it, it
may depend on no feature.

**The test: does the type encode a convention?** Money (integer minor units everywhere — `BIGINT` at
rest, `long` in Java, integer on the wire) and Coordinates (`DECIMAL(10,8)`/`DECIMAL(11,8)`, WGS84,
longitude first) are conventions. Domain behaviour belongs to a feature. *"Two features happen to use
it"* is not a reason.

**`BigDecimal` is for arithmetic, never for storage or transport, and never built from a `double`.**
`new BigDecimal(0.1)` stores 0.1000000000000000055511151231257827; `BigDecimal.valueOf(0.1)` stores
0.1. Both compile and look identical in review, which is why the rule is mechanical and not a habit.
A calculation lifts `long` minor units into `BigDecimal`, rounds **once** at the end with `HALF_UP`,
and returns minor units. Integer-only arithmetic is not an option: distance and time are fractional,
so 120 minor-units/km x 5.327 km is 639.240. The discriminator is the type — `BIGINT` is money,
`DECIMAL` is a coefficient and never an amount.

`shared` has no `controller` and no `repository`. Do not duplicate its contents per feature either —
four private `Coordinate` types in one JVM is a convention nobody can enforce.

## Pricing a trip: the unit conversion (PUB-3)

**The architecture side of this now lives in `ARCHITECTURE-SPINE.md` → AD-62** (haversine, the earth
radius, the single-row `fare_rules` shape, the surge column) **and in the spine's Money convention**
(rounding once, `HALF_UP`, `setScale(0)`, `longValueExact()`, no `MathContext`). Promoted there on
2026-08-23 after PUB-3 landed, so do not restate any of it here — what follows is only the code shape
those rules imply, which is not visible from reading either.

**The rates are per kilometre and per minute; haversine returns metres.** A conversion is therefore
unavoidable, and a missed one is silent — metres into the per-km term is 1000x, the wrong time unit is
60x, and both still satisfy every acceptance criterion as written. Go to kilometres once, then to
minutes **from kilometres, never from metres**:

```java
Distance distance     = pickup.distanceTo(dropoff);          // metres, inside the type
BigDecimal kilometres = distance.inKilometres();
BigDecimal minutes    = AssumedSpeed.minutesToCover(distance);
```

`Distance` exists for exactly this reason. `inKilometres()` is the only `BigDecimal` it hands out, so
it is the only number a rate can multiply without a cast — `inMetres()` is private for that reason.
The record component `metres()` is still public, because a record cannot hide one; it returns a
`double`, so misusing it needs a visible conversion rather than being impossible. Do not add a
metres-returning accessor to anything else, and do not write a bare `/ 1000` or `* 2` at a call
site.

**The assumed speed lives in `fare/model/AssumedSpeed` because `fare` is its only caller today.**
AD-62 makes one speed serve both the trip duration and the driver-to-pickup ETA, so **Story 2.6
(PUB-10) must move that constant into `shared`, not copy it** — two speed constants in one JVM is the
same failure as four `Coordinate` types. Derive the 2 min/km from the speed; a hardcoded 2 lets the
speed change without anyone seeing what depended on it.

**Tuning `per_minute_rate` alone cannot make a price time-sensitive.** Because time is derived from
distance at a fixed speed, the two rate terms are proportional, so `fare_rules` has three degrees of
freedom and not four: `fare = (base + km x (per_km + 2 x per_minute)) x surge`. On the seeded values
the effective rate is exactly `120 + 2 x 25 = 170` minor units per kilometre. Both columns stay
because the contract specifies them and a real implementation needs them — in a real system the time
term prices congestion, and there is no traffic here to price. One consequence for tests: a case that
varies distance and time independently cannot be built from two coordinates, so assert on a
`FareRule` directly.

**"Never floating point" is enforced for declarations, and reviewed for the rest.** Know which half
you are relying on.

`floatingPointIsConfinedToDistance` fails the build on any `float`/`double`/`Float`/`Double` in a
production field, return type or parameter — including an array component and a generic argument, so
`double[]` and `List<Double>` are caught. `Distance` alone is exempt: it is the type that stores the
trigonometric result, and every arithmetic caller takes a `BigDecimal` back out of it.

**It does not read method bodies.** `Coordinates.distanceTo` therefore computes the haversine in ten
`double` locals and passes — the trigonometry has to live somewhere, so that is intended, but it also
means a class doing its money arithmetic in locals would pass. Proven on 2026-08-23 by planting a
local-only method and watching the rule stay green. The exemption is on `Distance`; the haversine is
in `Coordinates`. Do not read the rule as "there is no floating point in this service".

`bigDecimalIsNeverBuiltFromADouble` fails on `new BigDecimal(double)` while leaving
`BigDecimal.valueOf(double)` — the correct call — untouched.

Both were proven by planting a violation in production code and watching them fire, and the array and
generic clauses were proven by re-planting after they were added.

## Reading time (AD-10, NFR-9)

Every read goes through the `Clock` strategy. `ArchitectureRulesTest.timeIsReadOnlyThroughTheClock`
fails the build on any other Java read and `DatabaseNeverReadsTimeTest` on any SQL one — `SystemClock`
excepted. `theRealClockIsOnlyEverInjected` closes the other half: only `config` may name
`SystemClock`, because a class that constructs its own reads a clock no test can advance.

- **`Clock.wallClockNow()` is wall clock, for recorded facts only.** A duration or a deadline never
  comes from arithmetic over two of its readings — wall clock moves when the host is corrected. Use
  `deadlineIn(...)`, or add a *monotonic* elapsed accessor when a caller first needs one, and do not
  give that accessor a `now()`-shaped name either; the verbosity is what puts the semantics at the
  call site. The one deadline that is wall clock: a durable one that outlives the process or crosses
  a service (AD-46's cooldown, AD-58's `next_attempt_at`).
- **`java.time.Clock` collides with ours, and an IDE auto-imports the JDK one first.** A production
  class that imports it by accident is caught only if it also calls a banned factory, so read the
  imports in any file touching both.
- **A deadline is read back through the clock, never the other way round** —
  `clock.hasReached(deadline)`, not `deadline.hasExpired(clock)`. A monotonic origin belongs to the
  clock that took it, so only that clock can judge its own deadline; and `Deadline` importing `Clock`
  put a cycle between `shared.model` and `shared.strategy`.

### Never let the database tell the time

**No SQL that asks the server what time it is — not in a migration, not in a query, not as a column
default.** Every timestamp is a **bind parameter from the `Clock`**. This is AD-58's rule for the
settlement worker (*"every `now()` in a claim or backoff predicate is a bind parameter from the AD-10
`Clock` strategy, never SQL `now()`"*) applied everywhere, and the reason is the same: a time the
database produced is a time no test can advance, so the window it guards can only be tested by
waiting — which the Testing convention forbids, which means the test that would catch a broken
predicate never gets written.

`DEFAULT now()` on a column is the most tempting form and the worst one: it reads the clock in the
one place nothing can reach.

Postgres has more ways to say this than anyone expects, and `DatabaseNeverReadsTimeTest` covers all of
them — but **read the list, because the scanner is a net and not a proof**:

- Functions: `now()`, `clock_timestamp()`, `transaction_timestamp()`, `statement_timestamp()`,
  `timeofday()`
- Bare keywords, no brackets: `current_timestamp`, `current_date`, `current_time`, `localtimestamp`,
  `localtime`
- **Special input literals**, which is the group everyone forgets: `'now'`, `'today'`, `'yesterday'`,
  `'tomorrow'`, `'allballs'` — as in `DEFAULT 'now'::timestamptz`, which reads the clock and contains
  no bracket to anchor a search on

The scanner is a **text** scan over `src/main/**/*.sql` and `src/main/**/*.java`. That is a real
limit: it reads one line at a time, so SQL split across lines or assembled by string concatenation
gets past it, and it only ever sees `matching-service`. **It is a backstop for the rule, not the
rule.** The rule is the paragraph above, and it is on whoever writes the SQL and whoever reviews it.

## Static analysis: ArchUnit + Spotless, nothing else

Settled by PUB-1. Do not add Checkstyle, PMD, SpotBugs, Error Prone, NullAway or Sonar without a
decision.

- `archunit-junit6:1.5.0` — `junit6`, not `junit5` (Boot 4.1 ships JUnit 6, and the module first
  appears in 1.5.0). Pinned explicitly, not BOM-managed.
- Spotless `8.10.0`. **Never add `--add-exports` flags** for either tool: both isolate their work, so
  if it looks necessary the diagnosis is wrong.
- `static-analyzers/` is the one config source; `make build` copies it into each service's build
  output. Never commit a per-service copy. ArchUnit *rules* are test code and are not shared.
- **There are two ArchUnit rule classes, and they cannot be merged.** `ArchitectureRulesTest` is
  declared `DoNotIncludeTests` and governs production code; `TestNamingRulesTest` is declared
  `OnlyIncludeTests` and governs test code. The import options are mutually exclusive, so a test-code
  rule placed in the production class scans nothing and passes forever. Add a third only if a third
  import option is genuinely needed.
- `./gradlew staticAnalysis` is the per-service entry point `pre-commit` invokes.

## Hooks — `pre-push` is the only gate

**There is no CI server. That is a decision, not an omission.**

- `pre-commit`: static analysis only, no tests. Every test run pays container, JVM and Gradle startup,
  and a slow commit gate gets bypassed with `--no-verify` within a week.
- `pre-push`: the entire suite, every service, regardless of what changed. No `contracts/` special
  case — running everything already covers it.
- If `pre-commit` feels slow, make the check faster. If `pre-push` does, make the tests faster. Never
  move the gate.
- One hook of each kind (a repository has one hooks path); per-service behaviour comes from dispatch
  inside it, written generically over `services/*`.
- Installed via `git config core.hooksPath .githooks`: tracked scripts, nothing copied into
  `.git/hooks`.
- `pre-commit` analyses the **index**, not the working tree. Fixing a file without staging it changes
  nothing.

## Real datastores only (AD-10, AD-56)

No H2, HSQLDB, embedded Postgres, fake repository, or self-starting container — anywhere. Race-safety
*is* Postgres's behaviour, so every concurrency claim is worth only what the datastore under the test
was. **Nothing enforces this mechanically; it is upheld here.** Reaching for Testcontainers is the
ecosystem's default answer to "these tests feel slow" — treat adding it as an architecture decision,
not a tooling choice.

- Tests run in the Dockerfile's **build stage image**, as a throwaway container on the Compose network.
  No separate runner image, and **no `/var/run/docker.sock` mount, ever**.
- Address datastores by **Compose service name**, never `localhost` — tests run in a peer container.
- **Sequential is mandatory**: `maxParallelForks = 1` and JUnit parallel execution off, enforced in
  `build.gradle` and nowhere else. A flaky concurrency test is indistinguishable from a real race.
- **Never `sleep`** to wait out a window. Wait on a condition or a healthcheck.
- **Tests ship with the feature.** No story exists solely to add tests, and none may be added later.

## Test data: migrations are production, fixtures are tests

Settled during PUB-3, before any table existed. Two tiers, and the boundary is not negotiable.

| Tier | For | Where |
| --- | --- | --- |
| **Inline SQL in the test** | Data *this test* needs — a specific row, a specific state | The test method, beside its assertion |
| **A `.sql` fixture** | The shared baseline every test assumes | `src/integrationTest/resources/fixtures/` |

**`db/migration/` is production.** Production schema and production data, nothing else. Test-only data
never goes there — AD-56's fixture drivers (Story 2.1) are the obvious case, and they must land in
`fixtures/`, not in a migration.

**A fixture does not read a migration file.** One exception, and it is narrow: a test whose *subject
is the production seed* may execute that migration's statement, because the thing under test is the
migration. PUB-3's AC3 test is the only instance so far.

**A fixture and a migration may hold different values, and that is not drift.** They answer different
questions — a fixture answers *"is the logic correct over known data"*, where any known data will do;
a migration's seed answers *"what does a first start produce"*. Believing the values must match is
what makes people couple the two and then freeze the fixture behind a migration checksum.

**Tests must own their own seeding.** A versioned migration runs once, so the first `TRUNCATE` deletes
a seeded row permanently — Flyway will not restore it. A repeatable `R__` does not rescue this either:
it re-runs only when its checksum changes, and a `TRUNCATE` does not change a checksum. Truncate and
reseed from the fixture, per test (`@BeforeEach`), opt in per class. **No base class** — the repo has
none deliberately, and `HealthReportsDownPromptlyIntegrationTest` boots against an unreachable
datasource with Flyway off, so a universal truncate breaks it for reasons unrelated to the test.

**"Fixture" means two different things in this repository. Do not mix them up.**

- `src/integrationTest/resources/fixtures/*.sql` — **test data**, the tier above.
- `src/test/java/.../rules/fixtures/*.java` — **not test data.** Deliberate rule-violators
  (`ReadsTimeDirectly`, `UsesTheLegacyDateApi`) that exist to be scanned by ArchUnit *as if they were
  production code*, proving a rule can actually fail. They are production-shaped: `camelCase` methods,
  no `@Test`, and they must stay that way or they stop representing the code they stand in for.

  **A violator whose rule names an absolute package cannot live there.** `sharedDependsOnNoFeaturePackage`
  and `featureDependenciesRunOneWay` are anchored on `com.puber.matching.shared..` and
  `com.puber.matching.fare..`, so a fixture under `rules.fixtures` matches neither and the rule would
  scan nothing and pass. Those fixtures sit in the production package they violate —
  `SharedTypeThatDependsOnAFeature` in `shared.model`, and `NeighbourStrategyThatReadsTimeDirectly`
  before it. Do not "tidy" them into `rules/fixtures/`; that silently disarms the rule.

## No container this project builds runs as root (AC18)

A reviewer can fail a story for shipping a root container. Two cases:

1. **Containers that mount the repository** (build, test) run as the host user's UID/GID, passed as
   `HOST_UID`/`HOST_GID` — not `UID`/`GID`, which bash will not reliably export. They also need an
   explicit `HOME` and a writable `GRADLE_USER_HOME` inside the workspace: an arbitrary UID has no
   `/etc/passwd` entry and cannot write `/root`.
2. **The runtime image** bakes a non-root user declared **numerically** (`USER 10001:10001`).
   Kubernetes' `runAsNonRoot` can only verify a numeric UID (AD-49).

**Not stock datastore images** — the official Postgres image drops privileges itself, and forcing a
`user:` onto it can break `initdb`. Use a named volume and there is no ownership problem to solve.

The check that catches regressions: `find . -user root -print -quit` outputs nothing after a full
build and test run.

## `infra/` vs `deploy/` vs `contracts/`

- **`infra/`** — the Compose stack you develop and test against; a correctness dependency of every test.
- **`deploy/`** — Kubernetes manifests, reconciled from git by Argo CD (AD-49). Its contents *are* the
  desired cluster state, so unrelated files there are noise. Empty until Epic 7.
- **`contracts/`** — versioned cross-service contracts at the repository root (AD-52), copied into each
  service at build time. Created by PUB-4-1; the copy mechanism has its own section below.

## `contracts/`: three files change together (PUB-4-1)

`contracts/proto/` is the single source (AD-52) and is **copied**, never referenced in place. The
copy happens in `make`'s `contract-config` target, written over `$(SERVICES)` so a new service needs
no edit there. Three files have to agree or the failure surfaces somewhere that looks unrelated:

| File | Line | Miss it and |
| --- | --- | --- |
| `Makefile` → `contract-config` | copies into `build/contracts/proto/` | codegen finds no `.proto` at all |
| `<svc>/.dockerignore` | `!build/contracts/` | the copy is silently excluded from the image context |
| `<svc>/Dockerfile` | `COPY build/contracts build/contracts` | `make build` works and the image build does not |

The `Dockerfile` line goes **below** `RUN ./gradlew dependencies` and **above** `COPY src src`, so a
`.proto` edit rebuilds codegen and the sources without re-resolving the runtime classpath. Above the
`RUN` — where PUB-4-1 first put it — every one-character contract edit busts that layer; corrected
2026-08-26 in code review.

`.githooks/pre-commit` treats `contracts/` as a shared build input, so editing the contract analyses
every service rather than none. `pre-push` needs no case — it already runs everything.

## The gRPC server (PUB-4-1)

- **The starter is Boot's:** `org.springframework.boot:spring-boot-starter-grpc-server`, with
  `-grpc-server-test` beside it. The coordinate every published example shows,
  `org.springframework.grpc:spring-grpc-spring-boot-starter`, is **not in Boot's BOM** and fails
  unversioned.
- **Three dependencies look redundant and are not.** The server starter carries `grpc-protobuf` and
  `protobuf-java` at *runtime* scope and no `grpc-stub` at all, while the generated `*Grpc.java`
  needs all three at compile time. Declare `io.grpc:grpc-protobuf`, `io.grpc:grpc-stub` and
  `com.google.protobuf:protobuf-java` explicitly.
- **`spring-boot-starter-grpc-server-test` drags in the monolithic `spring-boot-starter-test`.** That
  is Boot's choice inside its own capability starter, not ours. Do not add an `exclude` block — the
  split-starter rule is about what you *declare*.
- **Read both generator versions out of the BOM**, do not pin them: a generator that drifts from the
  managed runtime emits stubs against an API that is not on the classpath. Verified 2026-08-26 —
  Boot 4.1.0 resolves `protoc 4.34.2` and `protoc-gen-grpc-java 1.80.0`. Only the protobuf **Gradle
  plugin** is pinned (`0.9.6`), because Boot manages the Maven plugin and not it.
- **`protoc-gen-grpc-java` 1.80.0 does not emit `@javax.annotation.Generated`.** Checked in the
  generated output on 2026-08-26, so no `javax.annotation-api` dependency is needed. Published
  guidance saying otherwise predates this generator.
- **Netty wins, not the servlet path.** With `webmvc` on the classpath Boot could multiplex gRPC onto
  the HTTP port instead of binding its own. It does not: verified from the running service on
  2026-08-26 — `NettyGrpcServerFactory`, `listening on ...:9090`, Tomcat separately on 8080.
- **A fixed `spring.grpc.server.port` does not conflict across test contexts.** Four
  `@SpringBootTest` classes each auto-configuring a server on `9090` was expected to collide, and
  does not: Spring **stops** each context before loading the next, so only one server is ever bound.
  Verified 2026-08-26 by counting `gRPC Server started` against `Completed gRPC server shutdown` in
  a full integration run. **Do not add a dynamic-port pin to test classes to fix a conflict that
  does not exist.**
- **A gRPC test uses the in-process transport**, `@AutoConfigureTestGrpcTransport` plus the
  auto-configured `GrpcChannelFactory`. That is the honest subject — the service implementation, the
  interceptors and the status mapping — and asserts nothing about networking.
- **gRPC metadata keys must be lowercase.** `Metadata.Key.of` throws at construction otherwise.
- **A `ServerInterceptor` that sets the MDC must wrap the *listener*, not just `interceptCall`.**
  `startCall` only builds the listener; the service method runs later, from `onHalfClose`. Clearing
  the MDC in `interceptCall`'s `finally` leaves it unset exactly where the logging happens.
- **Every `GrpcExceptionHandler` bean is global, whatever package it sits in.**
  `GrpcServerAutoConfiguration.grpcGlobalExceptionHandlerInterceptor(List<GrpcExceptionHandler>)`
  collects them all into one `CompositeGrpcExceptionHandler`, first non-null wins. So a handler must
  match a **dedicated exception type**, never `IllegalArgumentException` — matching that reports any
  internal one as the caller's fault and echoes its message onto the wire, and
  `BigDecimal.valueOf(Double.NaN)` throws a subclass of it. Order the catch-all last with
  `@Order(Ordered.LOWEST_PRECEDENCE)`; Spring sorts an injected `List` by `@Order`.
- **Returning `null` from every handler does NOT give you `INTERNAL`.** spring-grpc's
  `FallbackHandler` calls `Status.fromThrowable`, which returns `UNKNOWN.withCause(t)` — and
  `withCause` is never serialized, so the caller gets `UNKNOWN` with a **null description**. That
  fallback's only log call is guarded by `isDebugEnabled()`, so at INFO nothing is logged either.
  Verified from the bytecode of `spring-grpc-core-1.1.0.jar` and `grpc-api` on 2026-08-26, after a
  comment claiming the opposite shipped in PUB-4-1. A last-resort handler that logs and returns
  `INTERNAL` is why `config/UnexpectedGrpcFailureStatusMapper` exists.

## Boot 4.1 / Java 25 — these contradict most existing guidance

Each looks like a mistake to anyone working from Boot 3 documentation. None is.

- **Boot 4 splits the test starters.** Use `spring-boot-starter-<capability>-test`, not the monolithic
  `spring-boot-starter-test`. Add the matching `-test` starter when a capability starter is added.
- **`spring-boot-starter-webmvc`**, not `-web`.
- **Flyway is a starter** (`spring-boot-starter-flyway`) and needs
  `org.flywaydb:flyway-database-postgresql` alongside it. **Never pin a Flyway version.**
- **`TestRestTemplate` is in `org.springframework.boot.resttestclient`** and is no longer registered by
  `RANDOM_PORT` alone: it needs `@AutoConfigureTestRestTemplate` plus both `spring-boot-resttestclient`
  and `spring-boot-restclient`.
- **Metrics exporters are off inside `@SpringBootTest`.** A test asserting on `/actuator/prometheus`
  needs `@AutoConfigureMetrics`, or the endpoint 404s under test while working in the running service.
- **Jackson 3, not 2.** `ObjectMapper` and `JsonNode` are `tools.jackson.databind.*`;
  `com.fasterxml.jackson.databind` is not on the classpath at all. Do not add Jackson 2 to "fix" the
  missing package.
- **Postgres 18+ mounts at `/var/lib/postgresql`**, not `.../data`, and refuses to start with the old
  path. Its healthcheck needs `pg_isready -h 127.0.0.1` — without `-h` it probes the Unix socket and
  reports healthy during the first-start `initdb`.

## Conventions easy to violate silently

- **Configuration** — environment variables. No secret in source, fixtures or manifests. Local
  credentials are generated by `make` into a gitignored `infra/.env`.
- **SQL** — explicit SQL via `JdbcTemplate`. No ORM, no JPA, no Hibernate, ever. Do not add the JPA
  starter "just for the datasource".
- **Timestamps** — `TIMESTAMPTZ`, UTC, at every boundary and not only at rest. No `LocalDateTime` or
  `LocalDate` in a signature, a column mapping or a DTO, and nothing reads the JVM or container
  default zone — `ZoneId.systemDefault()` and `TimeZone.getDefault()` fail the build alongside the
  time reads, and do not "fix" the underlying problem with `TZ=UTC`, since code that never asks the
  question cannot be given a wrong answer. Rendering an instant in a local zone is for the presentation edge, with the
  zone passed in explicitly. **Transactions** — `READ COMMITTED`.
- **Migrations are expand-only**: additive, nullable, no backfill in the same migration. Never edit an
  applied one.
- **Metrics** — in-process counters for events; durable state read from the owning table. Never persist
  a record just to make it countable. Tests assert a counter as a **delta**, never an absolute, and no
  service gets a reset hook for a test's convenience.

## A trap in this repository's history

`docs/` (`puber.md`, `tickets/pb-*.md`) was a superseded planning attempt, explicitly
non-authoritative and stale on the driver status enum, the payment flow, the ride state machine and
the database topology. `pb-1.1.md` looks like it addresses Story 1.1 and does not. If a search
surfaces it from git history, ignore it.
