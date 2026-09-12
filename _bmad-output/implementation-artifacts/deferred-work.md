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

  **CLOSED by PUB-4-2 on 2026-09-12.** `rider-service` carries its own copies and every one was
  proven capable of failing there — violation planted, suite run red, reverted, suite green again.

  | Rule | In `rider-service` | Proven by planting |
  | --- | --- | --- |
  | `timeIsReadOnlyThroughTheClock` | yes, **stronger** — no `SystemClock` exemption | `Instant.now()` in `RequestQuote` |
  | `theLegacyDateApiIsNotUsedAtAll` | yes, verbatim | a `java.util.Date` returned from `Quote` |
  | `DatabaseNeverReadsTimeTest` | yes, **minus the migration scan** | `now()` inside a Java string |
  | `theRealClockIsOnlyEverInjected` | **no** — names `SystemClock`, absent here | — |
  | `modelDependsOnNothingFrameworkFlavoured` | yes, with PUB-4-1's contracts exclusion | a `GetQuoteResponse` in `Quote` |
  | `serviceDependsOnStrategyInterfacesOnly`, `nothingDependsOnController`, `noPackageIsNamedEntity` | yes | fixtures + a controller import in `RequestQuote` |
  | `floatingPointIsConfinedToDistance` → `noProductionTypeDeclaresFloatingPoint` | yes, **stronger** — no `Distance` exemption, and renamed for it in code review | a `double` field on `ErrorDetailsHandler` |
  | `bigDecimalIsNeverBuiltFromADouble` | yes, verbatim | `new BigDecimal(0.1)` in `RequestQuote` |
  | `TestNamingRulesTest` + `TestNamingRulesIntegrationTest` | yes, both | a camelCase `@Test` in each source set |
  | `sharedDependsOnNoFeaturePackage`, `featureDependenciesRunOneWay` | **no** — AD-9 scopes the feature split to `matching-service` | — |
  | `sharedDependsOnNothingElseInThisService` | **new, written here**, and added to `matching-service` too | `shared` naming `model` (rider) / `config` (matching) |
  | `onlyControllerDependsOnDto` | **new, written here**; `rider-service` only | a `dto` import in `RequestQuote` |

  **Two rules are absent on purpose and that is the residue of this item.** `theRealClockIsOnlyEverInjected`
  returns with the story that gives `rider-service` a clock, and `DatabaseNeverReadsTimeTest`'s migration
  scan with its first migration. Both are recorded in `project-context.md` → "A new service's rule copies
  are hand-made", which is what a **third** service reads — this file is an audit trail nothing consults.

  **What this item did not fix, and could not.** Nothing still reminds anyone to make the copies: the
  reminder is now a paragraph in `project-context.md`, which every BMad workflow loads, rather than a
  mechanism. A rule that a new service silently omits is still a rule nothing turns red about.

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

## Deferred from: PUB-4-2 implementation (2026-09-12)

- **Nothing proves `rider-service` and `matching-service` agree on *behaviour*** (the whole of
  `services/rider-service/src/integrationTest/`)

  `rider-service`'s suite stubs `matching-service` in-process against `contracts/proto`
  (`project-context.md` → "Own datastores are real. Another service is stubbed."). The two therefore
  cannot drift in **shape** — both compile against the same `.proto`, and the stub extends the
  generated `QuoteServiceImplBase`, so a proto change breaks both sides at compile time. They can
  drift in **behaviour**: nothing here shows `matching-service` returns what `rider-service` expects.

  That is deliberate and it is the named cost of the stub rule, not an oversight. **Routed to
  PUB-4-3 as `AI-4` in `sprint-status.yaml`** — one end-to-end quote through HAProxy →
  `rider-service` → `matching-service`, which costs almost nothing there because that slice brings
  the whole stack up for the gateway anyway. Do not close it a second time inside either service's
  own suite.

- **`rider-service` has no `Clock` and no migration, so two rules it will eventually need are absent**
  (`services/rider-service/src/test/java/com/puber/rider/rules/`)

  `theRealClockIsOnlyEverInjected` names `SystemClock` and
  `DatabaseNeverReadsTimeTest.no_migration_asks_the_database_for_the_time` asserts it scanned at least
  one `.sql`. Copying either into a service that has neither gives a rule that scans nothing — which
  passes forever and is indistinguishable from enforcement — or a red suite on correct code.

  **Routed to whichever story first gives `rider-service` a clock or a migration**, and recorded where
  that author will actually see it: `project-context.md` → "A new service's rule copies are hand-made,
  and two of them change". This file is the audit trail; that paragraph is the reminder.

- **`javax.annotation:javax.annotation-api` was specified by the story and is not needed**
  (`services/rider-service/build.gradle`)

  Task 1.3 listed it `compileOnly`, on the reasoning that `protoc-gen-grpc-java` 1.80.0 emits
  `@javax.annotation.Generated` and Java 25 ships no `javax.annotation`. Checked against this
  service's own generated output on 2026-09-12 — `grep -r javax.annotation build/generated/sources/proto`
  returns nothing — and the build is green without the dependency. That matches what PUB-4-1 recorded
  in `project-context.md` → "The gRPC server" and contradicts the story's own Dev Notes, which had
  flagged the point as one to confirm rather than assume. **Nothing to route:** the dependency is
  simply absent.

## Deferred from: code review of PUB-4-2-rider-service-delivers-the-quote (2026-09-12)

- **No deadline on the gRPC call to `matching-service`**
  (`services/rider-service/src/main/java/com/puber/rider/config/GrpcClientConfiguration.java`,
  `src/main/resources/application.properties`, call site `.../service/RequestQuote.java:27`)

  Proven twice during review: `quotes.getCallOptions().getDeadline()` is `null`, and with the peer
  blocked for 9000 ms `rider-service` answered after **9213 ms** with a `200`. Tomcat's 200 request
  threads fill with waiters, `/actuator/health` then stops answering, and Compose — and Epic 7's
  Kubernetes probe, which is the same `exec` — kills a service whose only fault is a slow peer.

  **Routed to Epic 4**, by this story's own Scope boundaries: *"Rate limits, HAProxy queue bounds,
  AD-6's bound chain → Epic 4 — and AD-47 says those numbers are measured, not guessed."* A gRPC
  deadline is a bound in the request path and AD-6 requires every one of them to be explicit, so it
  belongs with the rest of the chain and with a measured number rather than a guessed one.

  **One thing for whoever sets it:** `DEADLINE_EXCEEDED` cannot occur today, and the moment it can it
  falls into `ErrorDetailsHandler`'s `default ->` arm and returns **500, not 504**. Add that row at
  the same time, or the first timeout reports itself as this service's fault.

- **A non-ASCII request id is silently mangled on the gRPC hop, and an oversized one is unbounded**
  (`services/rider-service/src/main/java/com/puber/rider/shared/RequestId.java`,
  `.../shared/RequestIdClientInterceptor.java`, `.../shared/RequestIdFilter.java`)

  `X-Request-Id: café-ééé` → `rider-service` echoes and logs `café-ééé` while the peer receives
  `caf?-??`, because the metadata key uses `Metadata.ASCII_STRING_MARSHALLER`. Nothing logs and
  nothing fails; the only symptom is two sets of logs that cannot be joined — which is the single
  thing AD-54 exists to prevent. Separately, a 7000-character request id crossed to the peer intact:
  Tomcat's 8 KB header cap is the only bound and it is not gRPC's, whose default
  `maxInboundMetadataSize` is 8192 for the whole metadata block.

  **Routed to PUB-4-3**, where the gateway becomes the minting point and is the natural place to
  decide whether a caller-supplied id is accepted, normalised, or replaced. Neither case is reachable
  today with a minted UUID.

  **Honest limit on the length case:** it was exercised over the in-process gRPC transport, which
  bypasses HTTP/2 header limits. It proves nothing in `rider-service` bounds the value; it does *not*
  prove the real Netty channel would accept it.

- **Compose makes `rider-service` unable to start when `matching-service` is down**
  (`infra/docker-compose.yml` — `depends_on: matching-service: condition: service_healthy`)

  `ErrorDetailsHandler` maps `UNAVAILABLE` → 503 precisely so the rider surface degrades rather than
  dies. In the stack as configured the container never comes up to serve that 503, so the degraded
  path cannot be demonstrated locally at all. The dependency is a startup-ordering convenience that
  quietly asserts an availability coupling AD-38 spent effort denying.

  **Routed to PUB-4-3**, which is where the gateway fronts `rider-service` and where showing the
  degraded path actually matters.
