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

**The test: does the type encode a convention?** Money (integer minor units in transit, `DECIMAL` at
rest) and Coordinates (`DECIMAL(10,8)`/`DECIMAL(11,8)`, WGS84, longitude first) are conventions.
Domain behaviour belongs to a feature. *"Two features happen to use it"* is not a reason.

`shared` has no `controller` and no `repository`. Do not duplicate its contents per feature either —
four private `Coordinate` types in one JVM is a convention nobody can enforce.

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
  service at build time. **Story 1.4 creates it**; do not create it speculatively.

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
