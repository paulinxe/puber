# Deferred Work

Items raised by a workflow, real but not actionable at the time they were found.

> **This file is an audit trail, not a queue.** Every BMad skill that mentions it only *appends*;
> `create-story`, `dev-story`, `sprint-planning`, `sprint-status` and `retrospective` never read it.
> An item recorded only here will not resurface on its own. To make one land, put it where something
> reads it: the epic file (loaded by `create-story`), `sprint-status.yaml` `action_items` (surfaced by
> `sprint-status`), or `project-context.md` (loaded by every workflow).

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

## Deferred from: code review of PUB-2-time-is-injectable-and-never-read-directly (2026-08-21)

- **The rules that stop code reading the clock exist only in `matching-service`.** Four of them:
  `timeIsReadOnlyThroughTheClock`, `theRealClockIsOnlyEverInjected` and
  `theLegacyDateApiIsNotUsedAtAll` in `ArchitectureRulesTest`, plus `DatabaseNeverReadsTimeTest`.

  They are test code, so every new service needs its own copy. That is the intended design, and they
  were written to copy cleanly — no `matching`-specific names in any of them. The gap is that nothing
  reminds anyone to do it: forget the copy and the new service can read the clock however it likes,
  and nothing anywhere turns red.

  **Routed to PUB-4-2**, the slice that creates `rider-service` — the second service, and so the
  first one that has to copy them. Its story file already specifies the copy rule by rule
  (`implementation-artifacts/PUB-4-2-rider-service-delivers-the-quote.md`), which is what makes this
  landed rather than merely recorded.

  Two refinements that story makes and this note did not anticipate:
  `timeIsReadOnlyThroughTheClock` goes over **stronger** — without the `SystemClock` exemption,
  because `rider-service` has no clock at all, so nothing there may read time — and
  `theRealClockIsOnlyEverInjected` is deliberately **not** copied, since it names a class that does
  not exist in that service yet.

  Re-pointed 2026-08-26. It previously read "Revisit in Story 1.4", written before that story was
  split into PUB-4-1/2/3. PUB-4-1 added no second service, so the pointer was aimed at a slice that
  had already passed without touching it.

## Deferred from: PUB-4-1 implementation (2026-08-26)

- **Three of PUB-3's four deferred value-type guards are still unreachable, and were deliberately not
  added** (`services/matching-service/src/main/java/com/puber/matching/shared/model/Coordinates.java`,
  `.../Distance.java`, `.../fare/model/FareRule.java`)

  PUB-3's review deferred four weaknesses with the note *"Becomes real at PUB-4's HTTP edge."*
  **That note is one-third right.** Verified against the generated protobuf on 2026-08-26: proto3
  strings are never null and an absent message reads back as a default instance, so nothing null can
  arrive from the gRPC layer at all.

  | PUB-3's deferred weakness | Reachable from the quote endpoint? | Where it becomes real |
  | --- | --- | --- |
  | `Coordinates` latitude/longitude unparseable | **yes** — closed by PUB-4-1 | — |
  | `Coordinates` latitude/longitude out of range | **yes** — proven by PUB-4-1 | — |
  | `Coordinates(null, x)` | no — proto3 strings are never null | **Epic 2**, driver heartbeats |
  | `new Distance(-5000)`, `NaN`, infinite | no — built only by `Coordinates.distanceTo`, over range-checked points, and haversine is bounded to `[0, 20_015_087]` | **Story 4.7 (PUB-34)**, Redis `GEOSEARCH` output |
  | `new FareRule(…, null)` | no — read from `NOT NULL` columns | no story yet |

  Adding a guard whose failure cannot be reproduced is what `project-context.md` → YAGNI forbids, and
  is the mistake three PUB-1 guards already made. The two reachable rows are proven **through the
  gRPC endpoint** in `QuoteGrpcIntegrationTest`, not by a unit test on the type — AGENTS.md →
  *"Integration tests by default"*.

  **Routed to Epic 2 and to Story 4.7 (PUB-34)** — see the notes in
  `planning-artifacts/epics/epic-2-driver-presence-location-tracking.md` and
  `planning-artifacts/epics/epic-4-event-backbone-resilience-operational-visibility-v0-1.md`, and
  `action_items: AI-3` in `sprint-status.yaml`.

- **CHECK constraints on `base_fare`, `per_km_rate` and `per_minute_rate` are still absent**
  (`services/matching-service/src/main/resources/db/migration/V2__create_fare_rules.sql`)

  Deferred by PUB-3's review as an architecture decision — AD-62 specifies exactly one CHECK, on the
  surge multiplier. PUB-4-1 did not reopen it. Unchanged from PUB-3's entry; recorded again only so
  a reader of this section does not think the quote endpoint made it urgent. It did not: a bad rate
  is a broken deployment, and `FareRuleRepository` already fails loudly on a missing row.

- **AC9 (contracts evolve by addition only) has no test and cannot have one**
  (`contracts/proto/puber/quote/v1/quote.proto`, `contracts/README.md`)

  It is a rule about a *future* edit, and there is no second version of the contract to compare
  against. What PUB-4-1 gives it is groundwork: explicit field numbers, `optional` presence for the
  ETA instead of a sentinel, and the rule written in the two places the next editor will read. It is
  enforced by review, not by the build.

  A real check becomes possible once a second version exists — a stored descriptor set plus
  `buf breaking` or equivalent. **Not routed anywhere**: nothing is broken today, and inventing the
  tooling before there is a contract change to test it against is speculative. Whoever makes the
  first breaking-shaped edit is the one who should reach for it.

## Deferred from: code review of PUB-4-1 (2026-08-26)

- **The request id is unset for every HTTP request the service serves**
  (`services/matching-service/src/main/resources/application.properties`,
  `.../config/RequestIdServerInterceptor.java`)

  `logging.pattern.level=%5p [%X{requestId:-}]` applies to every log line the service writes, but only
  `RequestIdServerInterceptor` ever populates the MDC key, and it is a gRPC `ServerInterceptor`. The
  service still serves HTTP — `spring-boot-starter-webmvc`, actuator on 8080, exercised by
  `HealthMetricsAndSchemaIntegrationTest` — and every one of those requests logs `[]`.

  AC4a is a gRPC criterion and this slice satisfies it, so this is not a criterion missed. It is a
  gap nothing recorded: PUB-4-3 stands up the gateway and owns the HTTP edge, and would have
  discovered the missing servlet filter rather than planned for it.

  **Routed to PUB-4-3.**

- **`Coordinates.of(null, …)` throws `NullPointerException`, not `IllegalArgumentException`**
  (`services/matching-service/src/main/java/com/puber/matching/shared/model/Coordinates.java`)

  `asDecimal` catches only `NumberFormatException`; `new BigDecimal((String) null)` throws NPE from
  `val.toCharArray()`. `NullPointerException` is not an `IllegalArgumentException` (checked against
  the JDK on 2026-08-26), so it escapes `QuoteGrpcService`'s single `catch (IllegalArgumentException)`
  around the two coordinate parses, is never mapped to `INVALID_ARGUMENT`, and surfaces to the caller
  as `INTERNAL` from `UnexpectedGrpcFailureStatusMapper`.

  Unreachable from the gRPC surface — proto3 strings are never null — so **leaving the guard out is
  correct** and is `project-context.md` → YAGNI applied as written. The gap is in the routing, not in
  the code: `AI-3` and the Story 2.3 note in
  `planning-artifacts/epics/epic-2-driver-presence-location-tracking.md` send the next author to this
  exact method for driver heartbeats, where null *can* arrive, without saying that the one input it
  was routed to handle is the one it does not currently handle.

  **Routed to Epic 2, Story 2.3** — one sentence to add to the existing note, not new code here.
