# Deferred Work

Items raised by a workflow, real but not actionable at the time they were found.

> **This file is an audit trail, not a queue.** Every BMad skill that mentions it only *appends*;
> `create-story`, `dev-story`, `sprint-planning`, `sprint-status` and `retrospective` never read it.
> An item recorded only here will not resurface on its own. To make one land, put it where something
> reads it: the epic file (loaded by `create-story`), `sprint-status.yaml` `action_items` (surfaced by
> `sprint-status`), or `project-context.md` (loaded by every workflow).

## Deferred from: code review of PUB-1-containerized-service-proven-against-the-real-stack (2026-08-18)

- **All six ArchUnit rules backing AC7 are currently vacuous, and the `model` rule is a blocklist rather than the stated constraint**
  (`services/matching-service/src/test/java/com/puber/matching/rules/ArchitectureRulesTest.java:34`)

  No `model`, `service`, `controller`, `strategy`, `entity` or `shared` package exists yet — `com.puber.matching`
  holds one class — so all five `noClasses()` rules carry `allowEmptyShould(true)` over an empty set and the
  layered rule carries `withOptionalLayers(true)`. None has ever evaluated a class. The story anticipates and
  endorses writing them early; `ArchUnitReadsJava25ClassFilesTest` proves ArchUnit parses Java 25 bytecode, but
  not that any rule can produce a violation.

  Revisit when the packages arrive (Stories 1.2 / 1.3):
  - Add fixture classes under `src/test/java/.../rules/fixtures` with an inverted assertion, so each rule is shown
    to be capable of failing.
  - `modelDependsOnNothingFrameworkFlavoured` enumerates nine banned packages rather than expressing "nothing
    framework-flavoured" as AC7 states — `org.slf4j`, `lombok`, `org.hibernate`, `com.google..` and any future
    framework pass it unchallenged.

## Deferred from: PUB-1 implementation (2026-08-19)

- **PostgreSQL JDBC `socketTimeout` is unset, so a partition mid-query hangs a request thread forever**
  (`services/matching-service/src/main/resources/application.properties`)

  PUB-1 capped Hikari's `connection-timeout` and `validation-timeout`, which bound *acquiring* a
  connection. The driver's `socketTimeout` defaults to `0` — no timeout — and `connectTimeout` to 10s.
  Nothing bounds a read on an established connection whose peer has gone away.

  **Routed to Epic 4, Story 4.4** — see the note in
  `planning-artifacts/epics/epic-4-event-backbone-resilience-operational-visibility-v0-1.md`, and
  `action_items: AI-1` in `sprint-status.yaml`. Recorded here only as history; those two are what will
  actually surface it.

- **Hikari's timeout values were chosen, not derived — confirm them against Epic 7's readiness probe**
  (`services/matching-service/src/main/resources/application.properties`, the `# TODO` beside
  `spring.datasource.hikari.connection-timeout`)

  `connection-timeout=2000` and `validation-timeout=1000` are reasoned bounds, not measured ones. They
  had to sit far below Hikari's 30s default, comfortably inside the AC4 test's 5s budget, and well above
  a healthy connect on the Compose network. 2000 satisfies all three with slack; it is a round number,
  not a calculated one.

  What would make them derived is the probe config itself, which does not exist until the manifests do:

  - **Kubernetes' default `timeoutSeconds` for a probe is 1.** A health endpoint that takes 2s to answer
    DOWN is therefore recorded as a probe *timeout* rather than a clean DOWN. The pod still ends up
    not-ready, so the outcome matches — but the probe never receives a real answer, and the event log
    says "timeout" instead of "the database is unreachable". If a real answer is wanted, either
    `connection-timeout` drops below the probe timeout or the probe's `timeoutSeconds` rises above it.
  - Hikari's floor is **250ms** (the setter throws below that), so there is room to go lower.
  - Whatever is chosen, `HealthReportsDownPromptlyIntegrationTest`'s `READINESS_PROBE_BUDGET` has to stay consistent
    with it, or the test stops asserting the bound that matters.

  Revisit in **Epic 7, Story 7.4 (PUB-57)** — the story that stands the stack up on a local cluster and
  therefore writes the first probe definitions. Not routed into the epic file or `action_items`: unlike
  the `socketTimeout` item above, nothing is unsafe today, and the `# TODO` sits directly beside the two
  values anyone editing them will read.

- **`/actuator/health/readiness` reports UP while Postgres is unreachable**
  (`services/matching-service/src/main/resources/application.properties`)

  `probes.enabled=true` creates the liveness and readiness endpoints, but the default readiness group
  holds only `readinessState` — not `db`. Measured with Postgres stopped: `/actuator/health` returned
  **503 DOWN**, `/actuator/health/readiness` returned **200 UP**. Since Spring's documentation points
  Kubernetes probes at the group paths, a readinessProbe would keep a database-less pod in service.

  **Routed to Epic 7, Story 7.4 (PUB-57)** — see the note in
  `planning-artifacts/epics/epic-7-real-time-live-dashboard-local-kubernetes-v1-0.md`, and
  `action_items: AI-2` in `sprint-status.yaml`. Nothing consumes the group paths today, so nothing is
  broken yet; the trap is that the manifests will follow the Spring docs.

- **Compose healthcheck timings are guesses, not measured**
  (`infra/docker-compose.yml`, the `# TODO(Epic 4)` beside `matching-postgres`' healthcheck)

  `matching-postgres` uses `interval: 2s / timeout: 3s / retries: 30 / start_period: 5s`, and
  `matching-service` uses `2s / 5s / 20 / 30s`. Both are conventional-looking numbers picked to be
  comfortably generous; nothing was timed to arrive at them.

  What they actually control:

  - `start_period` has to exceed a **first** start, which is the slow one: Postgres runs `initdb` and
    the service runs Flyway. Too short and a healthy container is reported unhealthy.
  - `retries × interval` is the total budget before the container is declared unhealthy and
    `depends_on: service_healthy` gives up, failing `make run` and `make test`.
  - `timeout` bounds one probe. For `matching-service` that probe opens a TCP connection and reads
    `/actuator/health`, which is bounded in turn by Hikari's `connection-timeout` — so this number and
    that one are related and were never chosen together.

  Revisit in **Epic 4**, alongside the retry, dead-letter and breaker work, which is where timing
  budgets get real attention and where controllable failure injection arrives to measure them with.

  Recorded here only; not routed to the epic file or `action_items`. Nothing is broken today — the
  numbers are generous rather than tight, and the `# TODO` sits beside them.
