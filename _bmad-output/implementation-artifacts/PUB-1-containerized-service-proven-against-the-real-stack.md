# Story 1.1: Containerized service, proven against the real stack

Ticket: PUB-1
Epic: 1 — Foundations & Fare Quote
Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an operator,
I want `matching-service` to build and run in Docker with health and metrics exposed, and its behaviour proven by tests running against the real stack,
so that I can start the system, confirm it is alive, and trust that every later correctness claim is measured against real datastore semantics rather than a substitute's.

## Scope orientation — read this first

**This is the first commit of a greenfield repository.** No `infra/`, no `Makefile`, no hooks, no
tests exist. The repository holds `AGENTS.md` and `_bmad-output/`. Everything below is created new —
with one exception:

**`services/matching-service/` is pre-seeded from Spring Initializr and already verified** — Gradle
`9.5.1`, Boot `4.1.0`, Java toolchain 25, package `com.puber.matching`, the six correct dependencies,
no root build. **Do not regenerate it**; see Dev Notes → "Seeded from Spring Initializr" for what was
checked and what still needs doing to it.

**The former `docs/` directory has been deleted** — it held a superseded planning attempt
(`puber.md`, `tickets/pb-*.md`) that the SPEC marks explicitly non-authoritative. It remains in git
history if ever needed. **Do not resurrect it, and do not treat anything recovered from it as
guidance.**

**This story delivers the substrate, not the domain.** `matching-service` starts, reports health and
metrics, owns a versioned schema with **no tables yet**, and is proven by tests that run against the
real Compose stack. No fare logic (Story 1.3), no `Clock` strategy (Story 1.2), no gRPC, no
`rider-service`, no gateway (Story 1.4).

**Three things are deliberately excluded — do not build them:**

| Excluded | Why | Where it lands |
| --- | --- | --- |
| Truncate-and-reseed test isolation (AD-56) | No owned tables and no seed data yet — it would assert over an empty set | Story 1.3 (`fare_rules`), extended in 2.1 |
| Any second service, gateway, or `.proto` | Nothing to talk to yet | Stories 1.4, 2.x |
| A CI pipeline | **Settled decision: there is no CI server.** The suite runs locally behind the git hooks this story installs, gated on the PR to `dev` | Nowhere — out of scope by decision |

**On the CI decision specifically:** the implementation-readiness review raised "no story stands CI up"
and it was resolved by decision, not by adding a story. The spine states it directly: *"no CI server:
the suite runs locally against the same Compose stack, behind git hooks, before a PR to `dev`. The
gate is local, so a green run is a fact about the developer's machine and nothing else asserts it."*
Do not add GitHub Actions, GitLab CI, or any pipeline file.

## Acceptance Criteria

**AC1 — Pinned Temurin base, no host JDK**
**Given** a machine with no JDK installed
**When** the service image is built
**Then** it builds from a pinned Temurin 25 base image
**And** no host JDK is required at any point in build or run (NFR-7)

**AC2 — Compose stack and private datastore**
**Given** the Compose stack in `infra/`
**When** it is brought up
**Then** `matching-service` and its own private Postgres 18.6 start
**And** the service reports healthy only once Postgres is reachable (AD-1)

**AC3 — Health and metrics surfaces**
**Given** a running service
**When** its health endpoint is requested
**Then** it returns UP
**And** its Prometheus endpoint exposes metrics in Prometheus text format (AD-54)

**AC4 — Health reports DOWN promptly when the datastore is unreachable**
**Given** a running service
**When** its Postgres is unreachable
**Then** health reports DOWN **within a bounded time short enough to serve a Kubernetes readiness probe**, rather than blocking on a default connection timeout
**And** this is proven by an integration test rather than by inspection
**And** the UP case needs no test of its own — every other integration test boots against a live Postgres and fails if health is not UP

**AC5 — Versioned schema, idempotent on restart**
**Given** a first start
**When** Flyway runs
**Then** the schema is versioned and migration state recorded
**And** a second start applies no migrations and does not fail

**AC6 — Independently buildable service, fixed package layout**
**Given** the service directory
**When** its structure is inspected
**Then** it carries its own Gradle wrapper (9.x) and build file with **no root build** (AD-52)
**And** packages are `controller` / `service` / `repository` / `model` / `strategy` / `config`
**And** the domain package is named `model`, never `entity` (AD-7)

**AC7 — Dependency direction is enforced by a test**
**Given** the layered structure
**When** a dependency-direction test runs
**Then** `model` imports nothing framework-flavoured
**And** `service` imports Strategy interfaces but no implementation
**And** nothing imports `controller` (AD-8)

**AC8 — Containerized test runner on the Compose network**
**Given** the Compose stack
**When** integration tests run
**Then** the test runner executes as a container joined to the Compose network
**And** it never requires a Docker socket of its own (AD-56)
**And** tests run sequentially against one shared database (AD-56)

**AC9 — Real Postgres only**
**Given** a test exercising persistence
**When** it runs
**Then** it uses the real Postgres instance
**And** no in-memory substitute, fake repository, or alternative SQL dialect is used anywhere (AD-10)

**AC10 — Makefile build target orchestrating per-service wrappers**
**Given** the repository root
**When** it is set up
**Then** a `Makefile` provides a build target that builds the project
**And** it **orchestrates each service's own Gradle wrapper** rather than becoming a root build, so every service stays independently buildable as if it lived in its own repository (AD-52)
**And** every target it runs executes in Docker, requiring no host JDK (NFR-7)

**AC11 — Build installs tracked git hooks**
**Given** the build target
**When** it runs
**Then** it installs a `pre-commit` and a `pre-push` git hook
**And** the hook sources are **tracked in the repository** rather than living only in `.git/hooks`, which is not version-controlled — so a fresh clone plus a build restores both gates

**AC12 — One hook of each kind, dispatching on touched services**
**Given** one repository holding all five services
**When** the hooks are installed
**Then** there is **one hook of each kind**, because a repository has a single hooks path
**And** per-service behaviour comes from the hook **dispatching on which services a change touches**, never from multiple hooks

**AC13 — pre-commit runs static analysis only**
**Given** a commit touching one service
**When** `pre-commit` runs
**Then** it runs that service's **static analysis and nothing else** — no tests, so the gate costs approximately nothing and is never worth bypassing
**And** a failure **blocks the commit**
**And** every test in the project runs at `pre-push` instead, where waiting is cheap and being wrong is expensive

**AC14 — pre-push runs the full suite**
**Given** a push
**When** `pre-push` runs
**Then** it runs the **full suite**, including the integration tests against the Compose stack
**And** a failure **blocks the push** — the boundary immediately before the PR to `dev`, which is where the gate actually has to hold

**AC15 — Hooks go through the containerized runner**
**Given** either hook
**When** it invokes tests
**Then** it runs them through the containerized test runner above
**And** it never assumes a JDK on the host (NFR-7, AD-56)

**AC16 — Static analysis fails the build**
**Given** the build
**When** it runs
**Then** static analysis runs as part of it
**And** a violation **fails the build** rather than producing a report nobody reads

**AC17 — Static analysis is containerized and per-service without a root build**
**Given** static analysis
**When** it is wired in
**Then** it runs inside the build container, requiring no host JDK and no IDE plugin (NFR-7)
**And** it is configured per service without introducing a root build, following AD-52's pattern of one versioned configuration source copied in at build time rather than a shared build plugin that couples the services

**AC18 — No container runs as root**
**Given** every container this project builds — the service image, the build container and the test runner
**When** any of them runs
**Then** none of them runs as `root`
**And** the service image declares its user **numerically** (`USER <uid>:<gid>`), because Kubernetes' `runAsNonRoot` verifies a numeric UID and cannot resolve a name (AD-49)
**And** containers that mount the repository run as the **host user's UID and GID, supplied by environment variables**, so nothing they write into the working tree is owned by `root` (NFR-7)
**And** this is proven by a check that no root-owned file exists after a full build and test run

> **Stock images are out of scope, deliberately.** AC18 binds containers **this project builds**.
> `matching-postgres` runs a stock image whose entrypoint already drops privileges itself; forcing a
> `user:` onto it can break first-start initialisation. Use a named volume rather than a bind mount
> and there is no host-ownership problem to solve. The rule is "our containers do not run as root",
> not "every container gets a `user:` line".

## Tasks / Subtasks

- [ ] **Task 1 — Repository skeleton and versioned configuration sources** (AC: 6, 10, 17)
  - [ ] Create the fixed source tree: `infra/`, `deploy/`, `services/`, `static-analyzers/`, `.githooks/`
  - [ ] `deploy/` holds no manifests in this story. Create **`deploy/README.md`** — not a `.gitkeep` — carrying one line: *"Kubernetes manifests, reconciled from git by Argo CD (AD-49). Arrives in Epic 7 (Stories 7.4, 7.5); deliberately empty until then."* Git does not track empty directories, and a bare `.gitkeep` leaves the next person to guess why the folder exists
  - [ ] Do **not** create a root `build.gradle`, root `settings.gradle`, or root `gradlew` — their absence is asserted by AC6
  - [ ] Create `static-analyzers/` holding the single versioned static-analysis configuration source — a Gradle snippet carrying the Spotless configuration — copied into each service at build time (never a shared Gradle plugin, convention plugin, or `buildSrc`)
  - [ ] Add `.gitignore` entries for build output (`services/*/build/`, `services/*/.gradle/`) **and for the copied analyzer config** — the copy is a generated artifact and must never be committed (see Dev Notes → "The copy lands in build output")

- [ ] **Task 2 — Verify and reshape the seeded Gradle project** (AC: 1, 6, 10)
  - [ ] **`services/matching-service/` arrives pre-seeded from Spring Initializr** — wrapper, build file and application class already exist. **Do not regenerate it.** Run the verification checklist in Dev Notes → "Seeded from Spring Initializr" before writing any code
  - [ ] Confirm `gradle/wrapper/gradle-wrapper.properties` pins Gradle **9.x** (AD-52) — if the seed shipped an 8.x wrapper, upgrade it via the wrapper itself inside the build container
  - [ ] Confirm the Boot version is **4.1.x** and the Java toolchain is **25**; add the toolchain block if the seed omitted it
  - [ ] Confirm no JPA/Hibernate/Liquibase/Testcontainers/`spring-boot-docker-compose` dependency is present, and remove any that is (AD-10, AD-56, no-ORM convention)
  - [ ] Reshape the flat seeded package into `com.puber.matching` with `config/` and `shared/`; move the application class to `com.puber.matching` (see Dev Notes → "Package layout: how AD-7 and AD-9 compose")
  - [ ] **Create layer packages only where they have content.** Do not scaffold empty `controller`/`service`/`repository`/`model`/`strategy` directories under `shared` — it will never hold a controller or a repository
  - [ ] Delete the seed's placeholder context-loads test if it duplicates the real integration coverage, or keep it as the smoke test — do not leave both
  - [ ] Delete Initializr's generated `HELP.md` — it is boilerplate links, not project documentation

- [ ] **Task 3 — Dockerfile and Compose stack** (AC: 1, 2, 18)
  - [ ] Multi-stage `Dockerfile` in `services/matching-service/`: build stage and runtime stage both on a **pinned, digest-or-tag-pinned Temurin 25** image
  - [ ] **The runtime stage runs as a non-root user, declared numerically** (`USER 10001:10001`) so Kubernetes' `runAsNonRoot` can verify it in Epic 7 — see Dev Notes → "Containers never run as root"
  - [ ] `infra/docker-compose.yml` (Compose spec 3.9 / Compose v2): `matching-postgres` (Postgres 18.6, private to this service) and `matching-service`
  - [ ] Configure the service's datasource entirely from environment variables (Configuration convention — no secrets in source)
  - [ ] Wire `depends_on` with a Postgres healthcheck so start ordering is deterministic

- [ ] **Task 4 — Health and metrics from the first commit** (AC: 2, 3)
  - [ ] Add `spring-boot-starter-actuator` and `micrometer-registry-prometheus`
  - [ ] Expose `/actuator/health` and `/actuator/prometheus`; confirm the Prometheus endpoint returns `text/plain` in Prometheus exposition format
  - [ ] Ensure the datastore health contributor is active so health is UP only once Postgres is reachable (AD-1, AD-54)
  - [ ] Cap the connection/validation timeout so an unreachable datastore returns DOWN promptly instead of hanging the health probe

- [ ] **Task 5 — Flyway baseline** (AC: 5)
  - [ ] Add Flyway (**managed version from the Boot BOM — never pin it independently**)
  - [ ] Create `V1__baseline.sql` recording that `matching-service` owns no tables at this story (see Dev Notes → "What V1 contains")
  - [ ] **No dedicated Flyway test.** Fold two assertions into an integration test that already boots the context (the AC3 health test is the natural home): the schema-history table holds exactly one successful row, and no second version was applied. See Dev Notes → "Why AC5 gets no test of its own"

- [ ] **Task 6 — Running tests inside a container** (AC: 8, 9, 18)
  - [ ] **There is no separate test-runner image.** Tests run in **the build stage image, as a throwaway container** joined to the Compose network — see Dev Notes → "Running tests inside a container (AD-56)"
  - [ ] Declare it in `docker-compose.yml` **behind a Compose `profiles:` gate**, so `docker compose up` never starts it, and invoke it with `docker compose run --rm`. The declaration exists only to hold the network, mount, env and user settings in one place rather than repeating flags in every Makefile target
  - [ ] **No `/var/run/docker.sock` mount** — the container must never have a Docker socket of its own (AD-56)
  - [ ] Run it as `user: "${HOST_UID:-1000}:${HOST_GID:-1000}"` and set a writable `GRADLE_USER_HOME` inside the mounted workspace, so build output is host-owned rather than root-owned (AC18)
  - [ ] Point integration tests at the Compose service name (`matching-postgres`), never `localhost`
  - [ ] Force sequential execution: `maxParallelForks = 1`, JUnit parallel execution disabled
  - [ ] Forbid H2/HSQLDB/embedded-Postgres/Testcontainers dependencies anywhere in the dependency graph (AD-10, AD-56)

- [ ] **Task 7 — Dependency-direction test** (AC: 7)
  - [ ] Add `com.tngtech.archunit:archunit-junit6:1.5.0` (test scope) — **`junit6`, not `junit5`**, and **1.5.0 minimum**; see Dev Notes → "Analyzer selection" for why both matter
  - [ ] Write the AD-8 rules: `model` imports nothing framework-flavoured; `service` depends on Strategy interfaces, never implementations; nothing imports `controller`
  - [ ] Add the AD-7 rule: no package named `entity` anywhere
  - [ ] Add the AD-9 rule: **`shared` depends on no feature package** — it is the bottom of `shared ← fare ← ride ← dispatch ← quote`, and this rule is what stops it becoming a dumping ground
  - [ ] Smoke-check that ArchUnit reads Java 25 class files (major version 69) before building the full rule set on it

- [ ] **Task 8 — Health reports DOWN promptly** (AC: 4)
  - [ ] Cap `connectionTimeout` and the validation timeout in configuration so an unreachable datastore yields DOWN quickly — this matters in production, not only under test
  - [ ] Add an integration test: a context pointed at an **unreachable address** reports health DOWN **within a small, explicit time budget**. Roughly fifteen lines; no forwarder, no proxy, no extra Compose service
  - [ ] Disable actuator health caching for that test, or the assertion passes for the wrong reason
  - [ ] **Do not** build a TCP forwarder or add Toxiproxy — see Dev Notes → "AC4 — a bounded-time DOWN assertion" for what was rejected and why

- [ ] **Task 9 — Static analysis wired into the build** (AC: 16, 17)
  - [ ] **The analyzer set is decided: ArchUnit + Spotless, nothing else** (Dev Notes → "Analyzer selection"). Do not add Checkstyle, PMD, SpotBugs, Error Prone, or Sonar
  - [ ] Apply Spotless in `services/matching-service/build.gradle`, reading the config snippet copied from `static-analyzers/` at build time
  - [ ] Use **Spotless 8.10.0**, which selects a JVM-25-compatible google-java-format automatically. **Do not add `--add-exports` flags** — Spotless isolates the formatter and the changelog explicitly tells users to remove them
  - [ ] Bind them to the `check`/`build` lifecycle so a violation **fails the build**
  - [ ] Confirm they run inside the build container with no host JDK and no IDE plugin
  - [ ] Record the analyzer decision and its Java 25 verification in the Completion Notes (the epic explicitly defers this investigation to this story)

- [ ] **Task 10 — Makefile** (AC: 10, 11, 18)
  - [ ] `make build`: for each directory under `services/`, **copy `static-analyzers/` into that service's build output first**, then run its own `./gradlew build` **inside a container**; never aggregate them into a root Gradle build
  - [ ] Do the copy in the Makefile, **not** as a Gradle `Copy` task — `apply from:` resolves at configuration time and would not see a file produced at execution time (Dev Notes → "The copy lands in build output")
  - [ ] Make a direct `./gradlew build` inside a service fail with a message naming `make build` when the copied config is absent
  - [ ] Export `HOST_UID`/`HOST_GID` from `id -u` / `id -g` so every container that mounts the repo runs as the host user — **not** the shell's `UID`/`GID`, which are unreliable to export
  - [ ] Provide this target set. **`build`, `run` and `test` are the three canonical entry points** — a newcomer should need no other command, and no documented preamble:

        | Target | Does |
        | --- | --- |
        | `build` | Builds the project — every service, each via its own wrapper (AC10) |
        | `run` | Runs the project — brings the Compose stack up |
        | `test` | Runs **all** tests — unit **and** integration |
        | `stop` | Brings the stack down |
        | `test-unit` | Unit tests only — what `pre-commit` invokes |
        | `test-integration` | Integration tests only |

  - [ ] **`test` must be self-sufficient**: it brings up whatever it needs (the datastores) rather than assuming `make run` was called first, so `make test` works on a fresh clone
  - [ ] `test` is exactly `test-unit` + `test-integration`; the split exists because the hooks need it (`pre-commit` = unit only, `pre-push` = everything) and must not drift from `test`
  - [ ] Every target executes in Docker, and none runs as root (NFR-7, AC18)
  - [ ] `make build` installs the git hooks as a side effect (AC11)
  - [ ] Write the loop so a new service directory is picked up with **no Makefile edit**

- [ ] **Task 11 — Git hooks** (AC: 11, 12, 13, 14, 15)
  - [ ] Author `.githooks/pre-commit` and `.githooks/pre-push` as tracked files
  - [ ] Install via `git config core.hooksPath .githooks` from the build target (tracked source, idempotent, nothing copied into `.git/hooks`)
  - [ ] `pre-commit`: derive touched services from `git diff --cached --name-only`; run **only those services' static analysis — no tests at all**; non-zero exit blocks the commit
  - [ ] **`pre-commit` must stay near-instant.** If it starts feeling slow, the fix is a faster check, never moving work off the hook — a gate people bypass protects nothing while reporting success
  - [ ] `pre-push`: run the **entire** suite — every service's unit **and** integration tests against the Compose stack, regardless of what changed; non-zero exit blocks the push
  - [ ] **No contracts special case is needed.** `pre-push` runs everything anyway, so a `contracts/` change is covered without a rule of its own (see Dev Notes → "Git hooks")
  - [ ] Both hooks invoke work **only** through containers — no `java`, no `javac`, no host `gradle` anywhere in either script
  - [ ] Verify both gates actually block: make a deliberately mis-formatted commit and a deliberately failing push

- [ ] **Task 12 — Record the conventions this story establishes** (no AC — house requirement)
  - [ ] Create **`project-context.md` at the repository root** (the `bmad-generate-project-context` skill produces it, or hand-write it). **This is not optional housekeeping:** every BMad workflow loads `{project-root}/**/project-context.md` as `persistent_facts` at activation, so rules recorded there reach every future `create-story`, `dev-story` and `code-review` run automatically. Rules left only in this story file are lost the moment the next story starts
  - [ ] Capture the conventions PUB-1 establishes that the four following services inherit:
    - Package layout — feature-first, layer-inside; **layer packages created only as they gain content**, never scaffolded empty
    - **What belongs in `shared`** — cross-cutting types encoding a *convention* (Money, Coordinates, `Clock`, the error vocabulary); never domain behaviour, and never "two features happen to use it". `shared` has no `controller` and no `repository`, and depends on no feature package
    - No root build; each service independently buildable via its own wrapper (AD-52)
    - No host JDK anywhere — build, test, static analysis and hooks all run in containers (NFR-7)
    - No container this project builds runs as root; numeric `USER`; host UID/GID for repo-mounting containers (AC18)
    - Analyzer set — ArchUnit + Spotless only, with the exclusions and their reasons
    - Real datastores only in tests; no in-memory substitute, fake repository, or self-starting containers (AD-10, AD-56)
    - `make build` / `run` / `test` as the canonical entry points
    - **`contracts/` is the versioned cross-service contract directory** (AD-52) — Story 1.4 creates the first `.proto` there
    - **Hook policy** — `pre-commit` runs static analysis only; **every test runs at `pre-push`**, which is the project's only gate since there is no CI server
  - [ ] Add a **pointer** from `AGENTS.md` to `project-context.md` — **do not copy the rules across.** Two files holding the same rules drift, which is the failure this whole story keeps designing against
  - [ ] Keep it to rules that are **not** derivable from the code, the spine, or the epics — `project-context.md` is for what a fresh agent would otherwise get wrong, not a summary of the architecture

- [ ] **Task 13 — Prove the whole thing end to end** (AC: all)
  - [ ] From a clean clone on a machine with no JDK: `make build` → stack up → health UP → metrics served → integration suite green
  - [ ] Assert **no root-owned files were produced**: `find . -user root -print -quit` outputs nothing after a full build and test run
  - [ ] Confirm the running service container is not root: `docker compose exec matching-service id -u` returns a non-zero UID
  - [ ] Verify the hooks actually fire and actually block by making a deliberately failing commit and a deliberately failing push

## Dev Notes

### Authority chain — resolve conflicts in this order

1. **`ARCHITECTURE-SPINE.md`** governs every technical decision.
2. **`SPEC.md`** + companions — the preservation-validated contract.
3. **PRD** — product shape.
4. **`AGENTS.md`** (repo root) — house coding style; binding where it does not contradict the spine.
5. ~~`docs/`~~ — **deleted from the repository.** It held a superseded planning attempt
   (`puber.md`, `tickets/pb-*.md`) that the SPEC marks explicitly non-authoritative: stale on the
   driver status enum, the payment flow, the ride state machine, and the database topology. It was
   removed precisely to stop it being mistaken for guidance. It survives in git history — **do not
   restore it, and do not treat anything recovered from it as authoritative.**

`AGENTS.md` is worth reading before writing code — it is the project's own style contract (SOLID,
immutable domain objects, verb-named single-`execute` services, no magic numbers, functional tests
over unit tests). Two notes on it: its examples predate the spine (it names HTTP clients where the
system now uses gRPC), and where its testing guidance and AD-56 differ, AD-56 wins. Nothing in this
story's scope conflicts with it.

### The three rules this story exists to make structural

Every later story inherits these. Getting them wrong here is expensive to unwind:

1. **No root build (AD-52).** Each service directory must build as if it lived in its own repository —
   its own wrapper, its own build file, no shared Gradle plugin, no `include` from a parent
   `settings.gradle`. The `Makefile` orchestrates by *invoking each wrapper*, which is not the same
   thing as aggregating them. A root `settings.gradle` that includes `services/*` **fails AC6.**
2. **No host JDK, ever (NFR-7).** Build, test, static analysis, hooks — all inside containers. If any
   step would work only because the developer happens to have Java installed, it is wrong.
3. **Real datastores only (AD-10, AD-56).** No H2, no embedded Postgres, no fake repository, no
   Testcontainers-style self-starting containers. Race-safety *is* Postgres's behaviour, and every
   later concurrency claim in this project is only worth what the datastore under the test was.

### Package layout: how AD-7 and AD-9 compose

AD-7 fixes the layers (`controller` / `service` / `repository` / `model` / `strategy` / `config`).
AD-9 additionally splits **`matching-service` alone** by feature: `shared`, `fare`, `ride`,
`dispatch`, `quote`, ordered one-way `shared ← fare ← ride ← dispatch ← quote`.

**Resolution: feature package first, layer package inside it.**

```
com.puber.matching
  config/                     application-wide wiring
  shared/                     cross-cutting types only — see below
  fare/        (Story 1.3)    each with the layer packages it actually needs
  ride/        (Epic 3)
  dispatch/    (Epic 3)
  quote/       (Story 1.4)
```

**Create layer packages only where they have content.** A feature gets `controller` / `service` /
`repository` / `model` / `strategy` **as it acquires each** — never five empty directories scaffolded
upfront. This story creates `config` and `shared`; the other four feature packages arrive with the
stories that first need them.

Why feature-first: AD-9 describes the features as packages carrying a one-way *dependency order*
between them, and states that "the match transaction and the surge scheduler both live in `dispatch`"
— i.e. features hold code, layers organise it within. Layer-first would make AD-9's ordering
unexpressible. **Java package is `com.puber.matching` — suffix-free, no `-service` (AD-12).**

#### What belongs in `shared`, and what keeps it from becoming a dumping ground

`shared` sits at the **bottom** of AD-9's order (`shared ← fare ← ride ← dispatch ← quote`), so
everything may depend on it and it may depend on no feature.

It holds **cross-cutting types with no domain behaviour** — realistically `model` (value types) and
`strategy` (the `Clock` interface, CAP-40), plus the error vocabulary of AD-38 when it lands.
**It has no `controller` and no `repository`, and almost certainly never will.**

**The test for whether something belongs there:** does it encode a **convention**? Money (integer
minor units in transit, `DECIMAL` at rest, never floating point) and Coordinates
(`DECIMAL(10,8)`/`DECIMAL(11,8)`, WGS84, longitude before latitude) are conventions, so they live in
`shared`. Does it encode **domain behaviour**? Then it belongs to a feature. *"Two features happen to
use it today"* is **not** a reason to promote something into `shared`.

**Do not replicate `shared`'s contents into each feature instead.** The project's
*"duplicated domain code is accepted"* rule governs duplication **across services**, where a shared
library would couple five independently-built builds. That reasoning does not transfer **within** one
service: there is no coupling to avoid, and the cost is concrete. Every feature touches coordinates —
`fare` computes haversine distance from them, `quote` returns them, `ride` persists them, `dispatch`
searches by them. Four private `Coordinate` types in one JVM means conversion at every internal
boundary and a convention with four implementations, which is a convention no longer enforceable.
Longitude-before-latitude is exactly the rule that gets written correctly three times and wrongly the
fourth.

**Guard it with a rule rather than with discipline.** Add an ArchUnit rule alongside AC7's:
`shared` may depend on no feature package. That is what actually stops the dumping-ground failure,
and this story already stands up the machinery for it.

**The domain package is `model`, never `entity` (AD-7)** — `entity` implies JPA, and there is no ORM
anywhere in this system.

### What V1 contains

`matching-service` owns `rides`, dispatch `drivers`, `fare_rules` and the `payment_standing`
projection — **and creates none of them in this story.** `fare_rules` arrives in Story 1.3, `rides` in
Story 3.1. AC5 asks for a *versioned schema with migration state recorded*, not for tables.

Create `V1__baseline.sql` containing a SQL comment that records the ownership position and why the
migration is empty. Flyway accepts a comment-only migration, records it in the schema-history table,
and a second start then correctly applies zero. That makes AC5's second half — "does not fail" —
provable rather than vacuous.

**Migrations are expand-only from here (Schema evolution convention):** additive, nullable, no
backfill in the same migration. Never edit an applied migration file.

#### Why AC5 gets no test of its own

Note the asymmetry in the acceptance criteria: **AC4 explicitly demands** *"this is proven by an
integration test rather than by inspection"*, and **AC5 does not.** That contrast is deliberate — a
test is required exactly where the behaviour is otherwise hard to observe.

**A dedicated Flyway test would be testing Flyway, not this project.** That a schema-history table is
created, and that a second run applies zero migrations, is core third-party behaviour — asserting it
is close to asserting that `JdbcTemplate` executes SQL.

**Flyway is already self-verifying here.** Every integration test boots the Spring context against the
real Postgres, and booting the context runs migrations. A misconfigured Flyway or a malformed `V1`
fails the entire suite immediately and loudly, starting with the AC3 health test. The "second start"
case is exercised naturally too: AD-56 runs test classes sequentially against one shared,
already-migrated database, so every class after the first *is* a subsequent start.

**So: two assertions folded into an existing test, not a new one.** That costs less than a manual
database check — which nobody re-runs, and which no CI exists to re-run (by decision) — and it cannot
rot. Do not create a `FlywayMigrationIT`.

### AC4 — a bounded-time DOWN assertion, not a simulated outage

AC4 asks for health to report DOWN when the datastore is unreachable, **proven by a test**. AD-56
forbids the test runner from holding a Docker socket, so the test cannot stop the container.

**The defect worth catching is latency, not absence.** HikariCP's default `connectionTimeout` is
**30 seconds**. Left at the default the service still eventually reports DOWN — so it "works" — but
takes far too long to serve a Kubernetes readiness probe in Epic 7. Inspection cannot catch this: you
stop Postgres, curl health, wait, see DOWN, and conclude it is correct. What is wrong is *how long it
took*.

**So assert the bound, not the outage.** Boot a context pointed at an **unreachable address** and
assert health reports DOWN **within a small, explicit time budget**. That is roughly fifteen lines,
needs no extra infrastructure, and fails loudly if the timeouts are left at their defaults.

- Cap `connectionTimeout` and the validation timeout in configuration so DOWN is returned promptly —
  this matters in production, not only under test.
- Beware **health caching**: actuator may cache health for a TTL. Disable it for the test, or the
  assertion passes or fails for the wrong reason.
- **The UP case needs no test of its own.** Every other integration test boots against a live Postgres
  and fails if health is not UP, so it is continuously proven.

**Explicitly rejected, so they are not reintroduced:**

| Approach | Why not |
| --- | --- |
| Hand-written in-JVM TCP forwarder the test can close | Works, but it is bespoke plumbing — a `ServerSocket`, a thread per connection, and it must force-close established connections or Hikari's pool keeps working and health never drops. Disproportionate to one assertion |
| **Toxiproxy** sidecar in the Compose stack | Purpose-built and genuinely clean, but it introduces infrastructure for one test. Revisit **in Epic 4**, where proving retry, circuit breaking and dead-lettering (AD-34, AD-55, CAP-23) needs controllable failure anyway — that is when it earns its place |
| A delegating `DataSource` that throws on demand | Proves the health indicator reacts to a failing `DataSource`, not that datastore unreachability causes it. Too weak for this AC |
| A Makefile target that stops Postgres and curls health | Inspection, not a test. AC4 says *test* |

### Running tests inside a container (AD-56)

**There is no separate test-runner image, and nothing extra runs alongside the stack.** Tests execute
in **the Dockerfile's build stage image, as a throwaway container** joined to the Compose network.

#### Why the service container cannot run the tests

Worth stating, because it is the one place this stack differs from an interpreted-language setup where
a single image both serves the app and runs its tests. There, the interpreter *is* the toolchain, so
one image is obviously correct.

Java splits the two. The **build** needs a JDK, Gradle, the test sources and the test dependencies;
the **runtime** needs a JRE and one jar. That split is precisely what the multi-stage Dockerfile
expresses — the final stage throws the toolchain away. So the running `matching-service` container has
no `javac`, no Gradle, no test classes and no JUnit on its classpath. It *cannot* run tests, and that
is the intended outcome rather than a limitation to route around.

**Do not collapse the stages to make one image do both.** It is not forbidden by any AC, but Epic 7
deploys these images to Kubernetes — a single-stage image ships the full build toolchain and the test
sources into the cluster, and widens the runtime attack surface that AC18 exists to narrow.

#### How to express it

- **Declare it in `docker-compose.yml` behind a `profiles:` gate**, so `docker compose up` never
  starts it. Invoke with `docker compose run --rm`.
- The declaration exists **only to hold configuration in one place** — the network, the repository
  mount, the environment, and AC18's `HOST_UID`/`HOST_GID`. Without it, every Makefile target repeats
  the same eight `docker run` flags. It is not a service in the architectural sense and does not
  belong in any diagram of the system.
- **No Docker socket, ever** (AD-56). A library that starts its own containers would fight the
  no-host-JDK constraint rather than serve it.
- Integration tests address Postgres by its **Compose service name**, never `localhost` — the tests
  run in a peer container, not on the host.
- **Sequential is mandatory, not a default to leave alone.** Set `maxParallelForks = 1` and disable
  JUnit parallel execution explicitly. Tests share one database; AD-56 names flaky concurrency tests
  "the worst possible failure here" because a flake is indistinguishable from a real race — and the
  one suite whose job is to prove no driver is ever double-booked becomes the one people learn to
  re-run and ignore.
- **Unit vs integration must be separately runnable**, because the hooks depend on the split
  (`pre-commit` = unit only, `pre-push` = everything). Establish the separation now — a Gradle
  `integrationTest` source set or a JUnit tag — because Task 11 cannot be written without it.

### Containers never run as root

**This is AC18**, added to the epic via the sprint change proposal of 2026-08-17, and it is also a
**standing acceptance criterion** across the project (`epics/overview.md`) and a **Consistency
Convention** in the spine — so it binds the four services that follow without their stories restating
it. A reviewer can fail a story for shipping a root container.

There are **two distinct cases** and they are solved differently. Conflating them is the usual mistake.

#### Case 1 — build and test containers: match the host user

The build image — used both to build and to run tests — **mounts the repository and writes into it**
(`build/`, Gradle caches, the copied analyzer config). Running it as root leaves root-owned files on the host
that the developer cannot delete or edit without `sudo` — and on a project where every single build
runs in a container (NFR-7), that happens on the very first `make build`.

These containers must run as **the host user's UID/GID**, passed in as environment variables:

```makefile
# Makefile — compute and export; do not rely on the shell exporting them
export HOST_UID := $(shell id -u)
export HOST_GID := $(shell id -g)
```

```yaml
# infra/docker-compose.yml — the profile-gated test container
tests:
  profiles: ["tools"]          # never started by `docker compose up`
  user: "${HOST_UID:-1000}:${HOST_GID:-1000}"
```

**Three traps, all of which produce confusing failures:**

- **Do not name the variables `UID`/`GID`.** `UID` is read-only in bash and `GID` is often unset, so
  neither is reliably exported to Compose. Use distinct names — `HOST_UID`/`HOST_GID` above.
- **Set a writable `GRADLE_USER_HOME`.** Running as an arbitrary UID means `/root` and `/home/gradle`
  may be unwritable, and Gradle fails on startup. Point it at a path inside the mounted workspace
  (and gitignore it).
- **An arbitrary UID has no `/etc/passwd` entry**, so tools that look up the user name can complain.
  Setting `HOME` explicitly resolves the cases that matter here.

#### Case 2 — the runtime service image: a baked-in non-root user

`matching-service`'s own image mounts nothing from the host, so there is no ownership to match. It
simply must not run as root. Temurin base images do by default, so this is an explicit step:

```dockerfile
RUN groupadd --system --gid 10001 app \
 && useradd  --system --uid 10001 --gid app --no-create-home app
USER 10001:10001
```

**Use the numeric form in `USER`, not the name.** Kubernetes' `runAsNonRoot: true` verifies the image
does not run as root, and it can only do that when the UID is numeric — a named `USER app` fails that
check at admission. Epic 7 deploys to a local cluster, so writing it numerically now avoids a
rewrite there.

The service listens on 8080, well above 1024, so dropping root costs nothing at runtime.

#### Do not blanket-apply this to the datastore

**Leave `matching-postgres` alone.** The official Postgres image already starts as root and drops to
the `postgres` user itself, and forcing a `user:` on it can break `initdb` on first start. Use a
**named volume** rather than a bind mount for its data and there is no host-ownership problem to
solve. The rule is *"our containers do not run as root"*, not *"every container gets a `user:` line"*.

#### How to know it worked

After a clean `make build` and a test run, **no file anywhere in the working tree is owned by root**:

```sh
find . -user root -print -quit    # must output nothing
```

This is AC18's final clause and sits in Task 13's end-to-end check — it is the only assertion that
actually catches a regression here, and the failure is silent until someone tries to delete a build
directory.

### Git hooks — the details that are easy to get wrong

**Installation.** Use `git config core.hooksPath .githooks`. It satisfies AC11's "tracked in the
repository" directly rather than by copying files into the untracked `.git/hooks`, it is idempotent,
and a fresh clone plus `make build` restores both gates exactly as the AC requires. Ensure the hook
files are committed with the executable bit set.

**One hook of each kind (AC12).** A repository has a single hooks path — so there is exactly one
`pre-commit` and one `pre-push`, and per-service behaviour comes from *dispatch inside them*:

```
changed=$(git diff --cached --name-only)          # pre-commit
services=$(echo "$changed" | grep '^services/' | cut -d/ -f2 | sort -u)
```

**Write the dispatch generically over `services/*`.** Only `matching-service` exists today; four more
arrive across Epics 1–6. If the hook hardcodes service names, every future story pays a hook edit —
and the one that forgets ships an ungated service.

**The contracts directory is `contracts/`, at the repository root.** The name is fixed **here**,
because Story 1.4 must create the first `.proto` in the same place and later stories reason about that
path. Record it in `project-context.md` (Task 12) so it is inherited rather than reinvented. **Do not
create the directory speculatively** — it arrives with Story 1.4.

**No contracts special case in the hooks.** `.proto` and event-schema files are copied into every
service at build time (AD-52), so a change in `contracts/` is a change to all of them — but
`pre-push` runs **every** service's suite regardless of what changed, so that fact needs no rule of
its own. An earlier draft of this story carried one on `pre-commit`; it became vacuous when tests left
the commit gate. Do not reintroduce it.

**Containerized invocation (AC15).** Both hooks call the Makefile targets, which run in Docker.
Neither script may contain `java`, `javac`, `gradle`, or `./gradlew` executed on the host.

**Why no tests run on `pre-commit` — recorded so it is not "simplified" later.** By Epics 5–6 the
suite runs sequentially against Postgres, Kafka, Redis and ClickHouse, and a multi-minute gate on
every commit gets bypassed with `--no-verify` inside a week. A bypassed hook protects nothing while
reporting success, which is worse than no hook. **Even a single service's unit tests are too
expensive here**, because every test invocation runs in a throwaway container (Task 6) with no
surviving Gradle daemon — so each commit would pay container, JVM and Gradle startup. That is a
constraint of *this* build, not a judgement about the value of unit tests. Static analysis where
commits are frequent; the whole suite where it is cheap to wait and expensive to be wrong.

**`pre-push` is now the only gate, and that is deliberate.** With no CI server it is the last line
before the PR to `dev`. Two consequences worth holding: a broken commit can reach your local history
and only surface at push — accepted, since the push is what others see; and if `pre-push` ever becomes
intolerable, **the answer is faster tests, not moving the gate again.**

### Analyzer selection — the investigation this story owns

The epic explicitly defers this decision to story-detailing time, with one instruction:
*"Verify Java 25 and Spring Boot 4.1 support rather than assuming it."* Analyzer support for a new
JDK routinely lags by months, and an analyzer that cannot parse Java 25 is not a candidate however
good it is.

**The rules to enforce are already written across Stories 1.1 and 1.2** — this is a question of what
executes them, not what they should be:

| Rule already specified | Enforced by | Lands in |
| --- | --- | --- |
| AD-8 one-way dependency; AD-7 package structure; `model` never `entity` | **ArchUnit** | **This story** |
| AD-57 Liskov — no caller inspects a Strategy's concrete type | ArchUnit (`instanceof` against strategy impls) | Later, once strategies exist |
| NFR-9 / AD-58 — no `Instant.now()`, `System.currentTimeMillis()`, SQL `now()` outside the `Clock` | ArchUnit, or a compile-time checker | **Story 1.2** |
| NFR-10 / AD-42 — tokens never logged, provider keys never in source | Secret scanner + a no-log rule on the masked token type | Epic 5 |

#### Decision: ArchUnit + Spotless. Nothing else.

This is settled — implement it, do not re-open the survey.

**ArchUnit — adopt, at version `1.5.0`, artifact `archunit-junit6`.** The ecosystem standard for
asserting package dependency rules as ordinary tests, and the only candidate AC7 actually requires.
It reads compiled class files rather than hooking the compiler, so it carries none of a javac
plugin's JDK-internals coupling.

```groovy
testImplementation 'com.tngtech.archunit:archunit-junit6:1.5.0'
```

> **Version and artifact are both load-bearing — verified against upstream at story-detailing time:**
>
> - **`1.5.0` (tagged 2026-08-04) is the minimum, not merely the latest.** Spring Boot 4.1.0 manages
>   **JUnit Jupiter 6.0.3**, and ArchUnit's `archunit-junit6` module **first appears in `1.5.0`** —
>   `1.4.2` ships only `junit4` and `junit5`. Taking "the latest 1.4.x" leaves you without JUnit 6
>   support against a Boot version that ships JUnit 6.
> - **Use `archunit-junit6`, not `archunit-junit5`.** The `5` artifact is the wrong one for this stack.
> - **Java 25 class-file support (major version 69) is in.** TNG/ArchUnit PR #1440, *"Upgrade
>   dependencies: Support Java 25's class file major version 69"*, merged 2025-04-27 — well before
>   `1.5.0`. ArchUnit's own build matrix additionally lists `JavaVersion.VERSION_25` among the LTS
>   versions it targets.
> - **ASM is shaded and relocated** inside the ArchUnit jar, so there is no ASM version to manage and
>   no possibility of a conflict with anything else on the classpath.
> - **Ignore the `--add-exports` flags in ArchUnit's own `gradle.properties`.** Those are for building
>   *ArchUnit itself*, which compiles against javac internals for its own tests. **A consumer needs
>   none of them** — ArchUnit reads bytecode through its shaded ASM. If something appears to need
>   those flags, the diagnosis is wrong.
>
> Re-verify at implementation time rather than trusting this note.

**Spotless — adopt.** Formatting, auto-fixed, and the whole of what AC13 puts on `pre-commit`: it must
cost approximately nothing on every commit, and it does. Use a release that supports **Gradle 9**.

> **Java 25 compatibility — verified against upstream, not assumed.** Checked at story-detailing time
> against the Spotless Gradle changelog:
>
> - **Spotless handles JVM 25 explicitly.** Release `8.10.0` (2026-08-17): *"Default
>   `google-java-format` remains `1.28.0` on JVM 17; bumps to `1.30.0` on JVM 21+; require at least
>   `1.30.0` on JVM 25+ for `import module` support."* It detects the JVM and selects a compatible
>   formatter version itself — **nothing to configure.**
> - **The `--add-exports` problem is historical.** Spotless runs formatters in an isolated
>   classloader; the changelog instructs users to *remove* `--add-exports` from `gradle.properties`.
>   Do not add those flags — if something looks like it needs them, the diagnosis is wrong.
> - **Java 25 syntax is actively tracked.** `8.10.0` also fixed `removeUnusedImports` failing on Java
>   `import module` declarations, a Java 25 language feature.
> - **Gradle 9 is supported.** Minimum supported Gradle is `8.1`, with an explicit Gradle 9 fix in a
>   recent release.
>
> **Use Spotless `8.10.0`** and let it auto-select google-java-format. Note it was released the same
> day this story was written; if a same-day release is unwanted, `8.9.0` (2026-07-27) works with
> google-java-format **pinned explicitly to `1.30.0`**, which is what 8.10.0 would have chosen anyway.
>
> The **Eclipse formatter engine** (`eclipse()`) remains available as a fallback — it touches no
> compiler internals — but on this evidence it should not be needed. Re-verify these versions at
> implementation time rather than trusting this note; both projects move quickly.

**Deliberately excluded — do not add these without a decision:**

| Excluded | Why |
| --- | --- |
| **Checkstyle / PMD** | Overlaps Spotless on formatting. Its unique value is rule enforcement (magic numbers, naming — `AGENTS.md`'s house rules), but authoring a ruleset against a service with no business logic is guesswork, and it doubles the Java 25 surface to verify. **Natural to add around Epic 3**, when there is real logic to govern |
| **Error Prone** | A javac plugin coupled to compiler internals, needing `-XDcompilePolicy` and `--add-exports` flags that shift between JDK releases — the highest-risk item on a JDK this new for the least marginal gain. Its main draw is Story 1.2's `Instant.now()` ban, which ArchUnit expresses as a "no class calls this method" rule. Revisit only if ArchUnit proves insufficient there |
| **SpotBugs / NullAway / OWASP Dependency-Check** | Bug-finders need bugs; this service is a health endpoint and an empty migration. Reasonable later additions, none required by an AC here |
| **SonarQube / SonarLint** | Needs a server, and AC16 explicitly wants analysis that *"fails the build rather than producing a report nobody reads"* — which is what Sonar is unless a quality gate is wired. Also conflicts with the no-extra-infrastructure posture (no CI server, no cloud) |

**Version verification is part of the task, not a formality.** The spine's Stack table was
*"verified against upstream release data at authoring, not asserted from memory"* — hold the analyzer
versions to the same standard. Record what you land on and how you verified it in Completion Notes;
the epic asked for this investigation's answer, and this is where it gets written down.

#### Configuration without a root build (AC17)

Neither tool needs an XML ruleset, so `static-analyzers/` holds **a Gradle config snippet** — the Spotless
block and any future analyzer configuration — as **one versioned source, copied into each service at
build time** and applied by that service's own build file. Roughly 15 lines today.

Why not just inline it in `build.gradle`: it works fine for one service, but `rider-service` arrives
in **Story 1.4** and three more follow. Five inlined copies drift, and within a couple of epics the
services disagree about their own rules with no arbiter — precisely the failure AD-52 exists to
prevent. Extracting it now is a few lines; extracting it later costs the same plus reconciling
whatever has already diverged.

**Do not** solve this with a shared Gradle plugin, a convention plugin, or `buildSrc` — that is the
coupling AD-52 forbids, and each re-introduces the root build AC6 asserts is absent.

##### The copy lands in build output, never in a service's committed files

```text
static-analyzers/spotless.gradle          the single source — committed
        │
        │   copied by the Makefile, before each wrapper runs
        ▼
services/<svc>/<build-output-dir>/…       generated — gitignored
```

AD-52 is precise about which duplication is allowed: *"Duplication into build outputs is mechanical;
duplication of the **source** is forbidden."* So the copy is a build artifact — regenerated every
build, gitignored, never committed.

**That gitignore is what makes the rule self-enforcing.** A committed per-service copy would look
editable; someone relaxes one to get a commit through, and five services quietly disagree with no
arbiter. A regenerated copy is visibly pointless to edit, so there is only ever one file a human can
meaningfully change.

**The `Makefile` performs the copy**, immediately before invoking each service's wrapper — it already
loops over `services/*`, so this is one more step in that loop. Story 1.4 copies the `.proto` files
by the same mechanism, which is why AD-52 describes both the same way.

> **Do not implement this as a Gradle `Copy` task.** `apply from:` resolves at **configuration** time
> while a `Copy` task runs at **execution** time, so the file would not exist yet when Gradle needs
> it. Copying in the Makefile before `gradlew` starts avoids the ordering problem entirely.
>
> The consequence: running `./gradlew build` directly inside a service, bypassing the Makefile, finds
> no config. **Fail with a message naming `make build`** rather than letting Gradle emit a confusing
> missing-file error.

**ArchUnit rules do not live in `static-analyzers/`.** They are Java test classes compiled against each
service's own packages, so they belong in that service's `src/test/java`. There is no shared library
to hold them (AD-52 forbids one; *"duplicated domain code across services is accepted"*), so each
service carries its own copy. Only configuration is copied — never code.

### Stack — pinned versions, verified upstream

| Component | Version | Note |
| --- | --- | --- |
| Java (Temurin) | **25** | Base image pinned; no host JDK (NFR-7) |
| Spring Boot | **4.1.x** | Supports Java 25 and Gradle 9 |
| Gradle Wrapper | **9.x** | **Per service.** No root wrapper |
| PostgreSQL | **18.6** | Private to `matching-service` (AD-1) |
| Flyway | **12.4.x** | Boot 4.1.0's BOM manages **12.4.0** — confirmed. **Never pin it yourself**; overriding the BOM invites a resolution conflict for no gain |
| Docker Compose | **v2** (spec 3.9) | |
| Prometheus | 3.x | Scrape target only in this story; dashboards are Story 4.9 |
| JUnit Jupiter | **6.0.3** | Managed by Boot 4.1.0 — **JUnit 6, not 5.** Drives the `archunit-junit6` artifact choice |
| Micrometer | 1.17.0 | Managed by Boot 4.1.0; backs the Prometheus registry |
| ArchUnit | **1.5.0** | `archunit-junit6`. Not BOM-managed — pin it explicitly |
| Spotless | **8.10.0** | Gradle plugin. Selects a JVM-25-compatible formatter automatically |

Versions in the lower block were read from Spring Boot `v4.1.0`'s dependency BOM at story-detailing
time rather than recalled. Boot 4.1.0 is tagged GA, so the spine's `4.1.x` pin is real and available.

**Do not upgrade past these without a spine change.** The Stack table is cold-start seed; once code
exists, the code owns it.

Not yet in play (arriving with their epics): Spring gRPC 1.1.0 (Story 1.4), Kafka 4.3.1 and
Resilience4j 2.4.0 (Epic 4), Redis 8.x (Epic 4), Stripe SDK 33.3.x (Epic 5), ClickHouse 26.3 LTS
(Epic 6), HAProxy 3.2.x (Story 1.4), kind 1.35.x and Argo CD 3.5.x (Epic 7).

### Seeded from Spring Initializr

`services/matching-service/` is **pre-seeded from [start.spring.io](https://start.spring.io)** and
committed before implementation begins. This removes the one genuine chicken-and-egg problem in the
story — generating a Gradle wrapper normally needs Gradle, which needs a JDK, which NFR-7 forbids on
the host. Initializr produces the wrapper in a browser, with no JDK involved anywhere.

**The seed is a starting point for one service, not a scaffold for the repository.** It supplies the
wrapper, `settings.gradle`, `build.gradle`, an application class and an empty `application.properties`
— roughly the first 15% of Task 2 and nothing else in this story. Everything else is hand-built.

#### Generation settings used

| Field | Value | Why it matters |
| --- | --- | --- |
| Project | **Gradle - Groovy** or **Gradle - Kotlin** | Either is fine; use the same one for all five services |
| Language | Java | |
| Spring Boot | **4.1.x** (newest non-SNAPSHOT/non-M) | Stack table. If only 4.0.x is offered, stop and raise it — do not silently take 4.0 |
| Group | `com.puber` | |
| Artifact | `matching-service` | Directory and container naming (AD-12) |
| **Package name** | **`com.puber.matching`** — set explicitly | Initializr derives `com.puber.matchingservice` from group+artifact. AD-12 requires Java packages **suffix-free**. This field must be overridden by hand |
| Packaging | Jar | |
| Java | **25** | Stack table; Temurin 25 base image |

#### Dependencies selected — exact UI labels

| Group | Checkbox | Resolves to |
| --- | --- | --- |
| Web | **Spring Web** | `spring-boot-starter-webmvc` |
| Ops | **Spring Boot Actuator** | `spring-boot-starter-actuator` |
| Observability | **Prometheus** | `io.micrometer:micrometer-registry-prometheus` |
| SQL | **JDBC API** | `spring-boot-starter-jdbc` |
| SQL | **Flyway Migration** | `spring-boot-starter-flyway` |
| SQL | **PostgreSQL Driver** | `org.postgresql:postgresql` |

Two Boot 4.x renames worth knowing, since older references disagree: **Spring Web** now resolves to
`spring-boot-starter-webmvc` (not `spring-boot-starter-web`), and Flyway is now a first-class starter
(`spring-boot-starter-flyway`) rather than a bare `flyway-core` dependency. The starter is what makes
the spine's *"Flyway must track Boot's managed version"* automatic — **never pin a Flyway version
explicitly.**

#### Four things deliberately NOT selected

| Not selected | Why |
| --- | --- |
| **Spring Data JPA** | No ORM anywhere in this system — explicit SQL via `JdbcTemplate` (SQL convention, AD-10). The starter drags Hibernate in |
| **Spring Data JDBC** | Also not used; the repositories are hand-written `JdbcTemplate` |
| **Docker Compose Support** (`spring-boot-docker-compose`) | Makes the application manage its own Compose lifecycle, fighting `infra/` and AD-56's containerized runner |
| **Testcontainers** | AD-56 forbids self-starting containers — they need a Docker socket the runner must not have |
| **Liquibase Migration** | Flyway is the fixed choice (Stack table) |

#### Verification — already performed, 2026-08-17

The seed is present at `services/matching-service/` and **passed every check below**: Gradle
**9.5.1**, Spring Boot **4.1.0**, Java toolchain **25**, package **`com.puber.matching`** (suffix-free,
not `matchingservice`), `settings.gradle` inside the service with no root build files, all six
intended dependencies present, and no JPA / Liquibase / Testcontainers / `spring-boot-docker-compose`.
No Flyway version is pinned. **Do not regenerate it.**

> **Two things the seed revealed that contradict most existing Spring guidance — do not "fix" them:**
>
> - **Boot 4.x splits the test starters.** The seed carries `spring-boot-starter-actuator-test`,
>   `-flyway-test`, `-jdbc-test` and `-webmvc-test` rather than one `spring-boot-starter-test`. Almost
>   every tutorial and Stack Overflow answer says to add the monolithic starter; **do not add it.**
>   Add the matching `-test` starter when a new capability starter is added.
> - **`org.flywaydb:flyway-database-postgresql` is required alongside the Flyway starter.** Flyway 12
>   needs the per-database module declared explicitly. It is already there — leave it.

**Still to do to the seed** (part of Task 2): delete Initializr's generated `HELP.md`; reshape the
flat package per AD-7/AD-9; add ArchUnit; add the Dockerfile.

#### Verification checklist — the checks that were run

Re-run these if the seed is ever regenerated:

- [ ] `gradle/wrapper/gradle-wrapper.properties` pins **Gradle 9.x** — Initializr's wrapper version tracks the Boot version and is not guaranteed to be 9.x
- [ ] `build.gradle` declares **Boot 4.1.x** and a **Java 25** toolchain
- [ ] The package really is `com.puber.matching`, not `com.puber.matchingservice`
- [ ] `settings.gradle` sits **inside** `services/matching-service/` and there is **no** `settings.gradle`, `build.gradle`, or `gradlew` at the repository root (AC6)
- [ ] The dependency graph contains no JPA, Hibernate, Liquibase, Testcontainers, H2, or `spring-boot-docker-compose`
- [ ] No Flyway version is pinned independently of the Boot BOM
- [ ] `gradlew` is committed with its executable bit set

**A root `settings.gradle` is the one thing that fails an AC.** Initializr will not produce one *if
the archive is unpacked into `services/matching-service/`*; it fails only when the zip is unpacked at
the repository root. The `settings.gradle` inside the service directory is correct and required —
that is what makes the service independently buildable (AD-52).

The same seeding approach applies to the four services that follow, with the artifact, package and
dependency set changed to suit. `simulator` is plain Java, not Spring Boot, and is not seeded this way.

### Conventions binding on this story

- **Configuration** — environment variables; secrets never in source, fixtures, or manifests.
- **Logging** — structured. (Correlation-id propagation is Story 1.4's, once a gateway exists to mint one.)
- **Metrics** — in-process counters for events; durable state read from the owning table. **Never
  persist a record solely to make it countable.** AD-56's reset discipline does not reset in-process
  meters, so tests assert a counter as a **delta across the action under test**, never an absolute —
  and **no reset hook is ever added to a service** for a test's convenience.
- **Timestamps** — `TIMESTAMPTZ`, UTC.
- **SQL** — explicit SQL via `JdbcTemplate`. **No ORM, no JPA, no Hibernate** — not now, not later.
  Do not add the JPA starter "just for the datasource".
- **Transactions** — `READ COMMITTED`.
- **Service naming** — `<role>-service` for directories and containers; Java packages suffix-free.

### Source tree this story establishes

```text
puber/
  Makefile                    build / run / test; every target runs in Docker
  .githooks/
    pre-commit                unit tests for touched services + fast static checks
    pre-push                  full suite incl. Compose integration tests
  static-analyzers/           one versioned analyzer config, copied into each service at build time
  infra/
    docker-compose.yml        matching-service, matching-postgres, + a profile-gated
                              test container (never started by `up`)
  deploy/
    README.md                 manifests land here in Epic 7 (AD-49); deliberately empty until then
  services/
    matching-service/
      Dockerfile              multi-stage, pinned Temurin 25
      gradlew, gradle/        own wrapper, 9.x
      settings.gradle, build.gradle
      src/main/java/com/puber/matching/{config,shared}   layer packages added as they gain content
      src/main/resources/db/migration/V1__baseline.sql
      src/test/java/...       ArchUnit rules + integration tests
```

Later services (`rider-service`, `driver-service`, `payment-service`, `audit-service`, `simulator`)
land in their own epics under the same `services/` parent. `simulator` is plain Java, not Spring Boot.

**Why `infra/` and `deploy/` are separate**, since both describe "how the system runs" and merging
them looks tempting:

- **`deploy/` is read by a machine; `infra/` is invoked by a person.** AD-49 has a GitOps controller
  reconciling the cluster to whatever is in `deploy/` — the directory's contents *are* the desired
  cluster state, which is what makes "rollback is a revert" literally true. Unrelated files in that
  path are noise the controller has to be told to ignore.
- **They are different targets, not different environments.** Both run locally; there is no cloud at
  any point (NFR-7). `infra/` is the stack you develop and test against; `deploy/` is the orchestrated
  deployment the system is proven to run on.
- **`infra/` is part of the test harness and `deploy/` never is.** AD-56 binds the whole suite to the
  Compose stack, so `infra/` is a correctness dependency of every test from this story onward.
- **Opposite structure rules.** AD-49 requires K8s services be generated from one template,
  infrastructure declared individually, sync ordering enforced — and provider secrets deliberately
  *excluded* from reconciliation (NFR-10). That exclusion is a statement about a path inside the
  reconciled tree, and only stays expressible while that tree is its own directory.

### Project Structure Notes

- **Aligned:** the source tree above is the spine's Structural Seed verbatim. No deviation.
- **No starter template, by design.** The architecture specifies none and mandates a hand-built
  structure. This **cannot be satisfied by scaffolding a monorepo template** — a generated monorepo
  brings exactly the root build AD-52 forbids.
  **The Spring Initializr seed is not a monorepo template and does not conflict with this**: it
  generates a single self-contained service, which is precisely the per-service shape AD-52 requires.
  What remains hand-built is the repository *around* it — `infra/`, `static-analyzers/`, `.githooks/`, the
  `Makefile`, and the layered package structure inside the service.
- **Watch for:** the seed's flat package (one class at the package root) is not the AD-7/AD-9 layout.
  Reshaping it is Task 2, not a later tidy-up.

### Testing Requirements

- **The test framework is JUnit 6** (Jupiter 6.0.3, managed by Boot 4.1.0) — **not JUnit 5.** Most
  JUnit 5 guidance still applies, but verify rather than assume, and reach for JUnit 6 documentation
  when something does not behave as a JUnit 5 answer predicts. This is also why ArchUnit must be
  `archunit-junit6` at `1.5.0`.
- **Real Postgres from the Compose stack.** No in-memory substitute, no fake repository, no
  alternative SQL dialect (AD-10).
- **Runner is a container on the Compose network**, holding no Docker socket (AD-56).
- **Sequential**, against one shared database (AD-56).
- **Tests ship with the feature.** Project policy: *no story exists solely to add tests, and none may
  be added later.* Every AC above is proven inside this story — a feature is not done until it is
  proven. Do not defer the AC4 outage test or the AC7 dependency test to "a testing story"; there
  will not be one.
- **Never `sleep` in a test** to wait out a window (Testing convention). Nothing in this story has a
  bounded time window yet, but the habit starts here — Story 1.2 makes it structural.
- **What must be proven by a test of its own:** health UP (AC3); health DOWN-then-UP across a
  datastore outage (AC4); Prometheus endpoint returns Prometheus text format (AC3); the AD-8
  dependency directions (AC7).
- **What is asserted inside an existing test rather than a new one:** Flyway's migration state (AC5) —
  see Dev Notes → "Why AC5 gets no test of its own". Not every AC earns a dedicated test; AC4 is the
  only one whose wording demands one.
- **What is proven by structure rather than by a test:** the absence of a root build, the pinned base
  image, hook installation — verified by running `make build` from a clean clone on a JDK-free machine
  (Task 13).

### Definition of done beyond the ACs

A story implementation must leave the system working end to end, not merely satisfy its stated ACs. For
this story that means: **a fresh clone on a machine with no JDK, running `make build` and bringing the
stack up, yields a healthy service serving metrics, a green suite, and two installed hooks that
actually block on failure.** If any of that requires an undocumented manual step, the story is not done.

### Previous Story Intelligence

**None — this is the first story in the project.** No prior implementation, no prior review feedback,
no established code patterns. Every convention in this file comes from the planning artifacts rather
than from precedent, which is exactly why the guardrails above are stated at length: there is no
existing code to imitate, and everything established here is copied by four more services.

### Git Intelligence

`git log` contains **planning commits only** — PRD, architecture spine, SPEC, epics, sprint planning.
**No source file has ever been committed.** Outside `_bmad-output/` the working tree holds
`.gitignore`, `AGENTS.md`, and the unstaged Initializr seed at `services/matching-service/`.

**A deleted directory you may encounter in history:** `docs/` — `puber.md` and `tickets/pb-1.1.md` …
`pb-7.1.md`. A prior planning attempt, explicitly non-authoritative, stale on the driver status enum,
the payment flow, the ride state machine and the database topology. `pb-1.1.md` in particular *looks*
like it addresses this story and does not. It was deleted to remove exactly that trap. **If a search
surfaces it from history, ignore it.**

Current branch is `main`. Follow the project's branching rules for the working branch; the hooks
installed here gate the eventual PR to `dev`.

### References

- [Source: `_bmad-output/planning-artifacts/epics/epic-1-foundations-fare-quote.md#story-11-containerized-service-proven-against-the-real-stack`] — all 18 AC blocks, verbatim (as amended 2026-08-17 by two sprint change proposals)
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-puber-2026-08-03/ARCHITECTURE-SPINE.md#ad-1-database-per-service`] — private Postgres per service
- [Source: `…/ARCHITECTURE-SPINE.md#ad-7-layered-packaging-with-strategy-for-varying-behaviour`] — layer packages; `model` never `entity`
- [Source: `…/ARCHITECTURE-SPINE.md#ad-8-one-way-dependency-inside-a-service`] — dependency directions asserted by AC7
- [Source: `…/ARCHITECTURE-SPINE.md#ad-9-matching-service-alone-splits-by-feature`] — feature packages and their one-way order
- [Source: `…/ARCHITECTURE-SPINE.md#ad-10-strategy-interfaces-only-where-implementations-vary-never-over-postgres`] — real datastores, never a fake
- [Source: `…/ARCHITECTURE-SPINE.md#ad-12-all-services-carry-the-service-suffix`] — directory naming vs. Java package naming
- [Source: `…/ARCHITECTURE-SPINE.md#ad-52-cross-service-contracts-have-one-source-and-are-copied-mechanically`] — no root build; one config source copied at build time
- [Source: `…/ARCHITECTURE-SPINE.md#ad-54-every-service-is-observable-the-same-way-from-day-one`] — health and metrics from the first commit
- [Source: `…/ARCHITECTURE-SPINE.md#ad-56-tests-run-against-the-real-stack-reset-between-test-classes`] — containerized runner, no Docker socket, sequential
- [Source: `…/ARCHITECTURE-SPINE.md#consistency-conventions`] — SQL, transactions, timestamps, metrics, configuration, logging
- [Source: `…/ARCHITECTURE-SPINE.md#stack`] — pinned versions and the Flyway/Boot-BOM constraint
- [Source: `…/ARCHITECTURE-SPINE.md#source-tree`] — the fixed directory layout
- [Source: `_bmad-output/specs/spec-puber/SPEC.md#cap-36`] — health and metrics as an `enabler`
- [Source: `_bmad-output/specs/spec-puber/roadmap.md#week-one-before-any-phase`] — why CAP-36 lands before its phase
- [Source: `_bmad-output/planning-artifacts/prds/prd-puber-2026-08-02/prd.md#4-non-functional-requirements`] — NFR-5, NFR-7
- [Source: `_bmad-output/planning-artifacts/epics/overview.md#testing-policy-tests-ship-with-the-feature-that-needs-them`] — no test-only stories
- [Source: `_bmad-output/planning-artifacts/epics/requirements-inventory.md#additional-requirements`] — no starter template; fixed source tree; pinned versions
- [Source: `_bmad-output/planning-artifacts/implementation-readiness-report-2026-08-16.md`] — the CI decision (QUAL-8); Story 1.1 sizing reviewed and accepted at 19 ACs, since raised to 20 deliberately
- [Source: `_bmad-output/planning-artifacts/sprint-change-proposal-2026-08-17.md`] — AC18 (no container runs as root): rationale, impact analysis and approval
- [Source: `_bmad-output/planning-artifacts/sprint-change-proposal-2026-08-17-b.md`] — the hook rebalance (tests moved wholly to `pre-push`) and AC4's narrowing; why the old AC14 and AC19 were removed
- [Source: `AGENTS.md`] — SOLID rules, immutable domain objects, service naming, no magic numbers

## Dev Agent Record

### Agent Model Used

_To be filled by the dev agent._

### Debug Log References

### Completion Notes List

- The analyzer set is already decided (ArchUnit + Spotless). Record here the **versions** landed on,
  **how Java 25 compatibility was verified**, and which Spotless formatter engine was used. The epic
  deferred this investigation to this story and expects its answer written down.
- Record how AC4's outage test was implemented, since the approach is non-obvious and the next person
  to touch the health path will need it.

### File List
