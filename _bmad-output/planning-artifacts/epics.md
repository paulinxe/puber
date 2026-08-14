---
stepsCompleted: ['step-01-validate-prerequisites', 'step-02-design-epics']
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-puber-2026-08-02/prd.md
  - _bmad-output/planning-artifacts/prds/prd-puber-2026-08-02/addendum.md
  - _bmad-output/planning-artifacts/architecture/architecture-puber-2026-08-03/ARCHITECTURE-SPINE.md
  - _bmad-output/specs/spec-puber/SPEC.md
  - _bmad-output/specs/spec-puber/state-machines.md
  - _bmad-output/specs/spec-puber/glossary.md
  - _bmad-output/specs/spec-puber/roadmap.md
---

# Puber - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for Puber, decomposing the requirements
from the PRD, Architecture spine, and the SPEC kernel into implementable stories.

**Authority chain.** `ARCHITECTURE-SPINE.md` governs every technical decision; the PRD governs product
shape; `SPEC.md` is the preservation-validated contract that distills both and carries the
`slice` / `property` / `enabler` classification that drives decomposition. `docs/puber.md` and
`docs/tickets/pb-*.md` are historical and explicitly non-authoritative — excluded from extraction.

**No UX design document exists.** Puber is a backend system; its only UI is FR-50's lightweight live
operational dashboard, whose shape is governed by AD-51 and AD-37 rather than by a UX spec. The
UX Design Requirements section below is therefore intentionally empty.

## Requirements Inventory

### Functional Requirements

**A. Rider Requests & Reads**

FR-1: Rider can request a fare quote for a pickup/dropoff pair, returning fare, distance and ETA, without creating a ride. Fare and distance always return; ETA is omitted (not an error) when no driver is available. The quote is indicative — the binding fare is re-locked at request time.
FR-2: Rider requests a ride with pickup/dropoff coordinates and a payment-method token; the fare is locked at request time and the ride identifier returns immediately while authorization proceeds in the background. No payment method is stored — the token travels with the request.
FR-3: A rider cannot hold two simultaneous active rides — a new request is rejected while one is in `REQUESTED`/`WAITING_MATCH`/`OFFERED`/`MATCHED`/`IN_PROGRESS`.
FR-4: Rider can look up their current active ride by rider identity alone, without the ride identifier; returns at most one ride, or nothing.
FR-5: Rider can read a single ride by its identifier, returning current state and details.
FR-6: While a ride is `MATCHED`, the rider can see the assigned driver's display identity, their current position, and an ETA to pickup that updates as the driver moves.
FR-7: Rider can see the payment outcome on a finished ride — final fare and whether it was captured, refunded, or left uncaptured (`CAPTURE_FAILED`) on a `COMPLETED` ride, or that authorization was declined on a `PAYMENT_FAILED` one. A capture still retrying is not yet an outcome.
FR-8: Rider can query ride history, most recent first, bounded by a result-size limit.
FR-51: A ride request is refused while the rider has money outstanding, in two cases beyond FR-3: (a) their most recent ride is terminal but its payment is still `INITIATED`/`AUTHORIZED`; (b) their most recent payment reached `CAPTURE_FAILED` less than 30 minutes ago. Both refusals and FR-3's are distinguishable by the caller and counted separately by reason. The window is self-clearing on wall clock from a recorded `capture_failed_at`.

**B. Matching, Fares & Ride State**

FR-9: No driver is dispatched until the fare is authorized. A ride persists as `REQUESTED` (meaning *awaiting authorization*, nothing else); the hold is placed asynchronously; the ride moves to `WAITING_MATCH` on success or terminal `PAYMENT_FAILED` on decline.
FR-10: System matches each ride to the nearest available driver within a 5km radius; unmatched rides retry continuously until matched or given up on. Matching reads only `WAITING_MATCH`.
FR-11: An offered driver has a bounded window to accept, during which the ride sits in `OFFERED`. On timeout or explicit decline the ride returns to `WAITING_MATCH` and is offered to the next-nearest driver. A driver who declined or timed out is never re-offered that ride.
FR-12: If no driver is found for a `WAITING_MATCH` ride within a bounded overall window, it becomes terminal `NO_DRIVER`, retrying stops, and the hold is released. The rider is never charged.
FR-13: If the driver holding a `MATCHED` ride goes silent past a staleness window before starting the trip, the ride returns to `WAITING_MATCH` (never to `REQUESTED`) and the silent driver is released.
FR-14: If the driver on an `IN_PROGRESS` ride goes silent past a staleness window, the system auto-completes the ride, captures the locked fare through the normal payment path, and releases the driver. The ride records `completed_by = SYSTEM` and the audit trail records `SYSTEM` as actor.
FR-15: Full ride state machine: `REQUESTED → WAITING_MATCH → OFFERED → MATCHED → IN_PROGRESS → COMPLETED`, plus terminals `CANCELLED`, `NO_DRIVER`, `PAYMENT_FAILED`. `REQUESTED` is entered exactly once and never returned to.
FR-16: Rider may cancel any time before the trip starts (`REQUESTED`, `WAITING_MATCH`, `OFFERED`, `MATCHED`); cancelling releases any assigned driver, withdraws any outstanding offer, and voids any hold — including one that lands *after* the cancellation. A mismatched rider identity is rejected without revealing whether the ride exists.
FR-17: Matching is race-safe under concurrency — no driver is ever double-booked; a rider cancelling as a driver accepts resolves to exactly one winner, the loser rejected.
FR-18: Fare is computed at request time from `(base + distance + time) × surge`.
FR-19: Surge is a multiplier in configurable fare rules. Static `1.00` through early phases; from the event-backbone phase onward recomputed periodically from the ratio of outstanding requests to available drivers, and exposed as an operational metric.

**C. Driver Session & Actions**

FR-20: Driver sets their own availability — online (`AVAILABLE`) or offline (`OFFLINE`). The system never puts a driver online. Going offline with an offer outstanding releases the offer and permits it; once `MATCHED`/`IN_PROGRESS` it is refused until they complete or are released.
FR-21: Driver reads their whole working state in one call — declared status, whether their heartbeat is currently being heard, any pending offer (with pickup, dropoff, fare, distance to pickup), and their active ride if any.
FR-22: Driver can accept the ride currently offered to them, and only that ride. Acting on any other ride, or with no live offer, is rejected.
FR-23: Driver can decline a pending offer, immediately releasing it to the next-nearest driver. Declining returns the driver to `AVAILABLE`.
FR-24: Driver explicitly starts the trip (`MATCHED → IN_PROGRESS`) and later completes it (`IN_PROGRESS → COMPLETED`). Both restricted to the driver's own assigned ride and valid only from the preceding state. Completion returns the driver to `AVAILABLE`.
FR-25: Driver can query their own completed ride history, most recent first, bounded by a result-size limit.

**D. Driver Location Tracking**

FR-26: Drivers report location via a heartbeat; the system tracks current position and availability status.
FR-27: Location updates persist to a durable history (position audit trail).
FR-28: Location reads are served from a fast path (sub-second) decoupled from durable slow-path persistence.
FR-29: Declared availability and observed reachability are separate facts. A driver is matchable only when declared `AVAILABLE` **and** their heartbeat is fresh. Loss of signal never writes to declared status; a returning driver becomes matchable on their next heartbeat.
FR-30: An **idle** driver unreachable far longer than any staleness window is set `OFFLINE`, recorded as a `SYSTEM` action, and must explicitly go online again. Never applies to a driver holding a ride.

**E. Event Backbone & Resilience**

FR-31: Domain events — ride, driver, payment, audit — flow through an event backbone (Kafka) rather than direct service-to-service calls. Commands and reads an actor waits on stay synchronous.
FR-32: Producers and consumers apply retry-with-jitter and circuit-breaking on failure.
FR-33: Multiple independent consumers can subscribe to the same event stream without coupling to each other.

**F. Payments**

FR-34: Payment follows a two-phase lifecycle against Stripe sandbox — **authorized** as a hold at ride request, **captured** at ride completion. Authorization is asynchronous; nothing is captured for a ride that delivered no trip.
FR-35: Payment state machine: `INITIATED → AUTHORIZED → CAPTURED → REFUNDED`, plus terminals `FAILED` (authorization declined), `CAPTURE_FAILED` (delivered trip whose hold the provider reports uncapturable), and `VOIDED` (hold released without capture).
FR-36: A ride ending without a trip — `CANCELLED` or `NO_DRIVER` — releases any hold and moves the payment to `VOIDED`, including when the authorization resolves *after* the ride is already terminal.
FR-37: Capture is pursued until it settles, not until a retry budget runs out. Failures retry with jittered backoff, the payment stays `AUTHORIZED` while retrying, and retrying survives process restarts. No retry cap. `CAPTURE_FAILED` is reached **only** when the provider reports the hold expired, was revoked, or was cancelled — provider unreachable is not that.
FR-38: Stripe webhooks are verified by signature and processed idempotently, deduped by provider event ID.
FR-39: Full refund flow end to end — refund issued against the provider, payment moved to `REFUNDED`, refund webhook processed idempotently, result reconciled. Triggered only by an internal operator-facing call. One payment per ride.
FR-40: A reconciliation task catches missed or failed webhook deliveries and flags any hold outstanding longer than a ride can plausibly live. Neither is corrected automatically.

**G. Audit & Analytics**

FR-41: Every domain state transition (ride, driver, payment) is recorded as an audit event tagged with the causing actor — including `SYSTEM` for offer expiry, `NO_DRIVER`, auto-completion, hold release, surge recomputation, and session expiry.
FR-42: Audit events are queryable by entity and by actor, retained under a partitioning + retention policy.
FR-43: Audit data is mirrored to a columnar store for analytical queries at scale.
FR-44: Aggregate analytics computed from data already collected — distance traveled per driver and ride density by area from the location-ping history; driver utilization (% time in each status) from the driver state-transition audit trail, not from pings.

**H. Real-Time & Deployment**

FR-45: Drivers receive a live push channel (WebSocket) rather than polling, carrying ride offers and the state changes a driver must react to — rider cancelled, offer withdrawn or expired, ride auto-completed.
FR-46: All services expose health and metrics; dashboards show live operational KPIs. Fixed definitions: **match latency** is request → driver accepting, reported alongside time-to-first-offer; **drivers online** counts only *matchable* drivers. Money gauges: **capture loss** (count and summed amount of `CAPTURE_FAILED`, zero in health) and **age of the oldest capture still retrying**. **Refused ride requests counted by reason**, never as one total. Also surfaced: ride throughput, current surge multiplier, payment success rate, audit ingest rate, rides awaiting authorization, undelivered-event backlog age.
FR-47: All services deploy to a local Kubernetes cluster.

**I. Identity & Simulation**

FR-48: No authentication or registration; identity is passed per-request (rider header-carried, driver fixture-seeded) and trusted as-is.
FR-49: A Simulator generates synthetic riders, drivers, and ride traffic at configurable, deterministic/seeded scale. In-process test fixture early, standalone containerized load generator later. Supplies payment-method tokens including deliberately-declining test tokens. Generates coordinates relative to the configured bounds.

**J. Live Operational Dashboard**

FR-50: A lightweight custom web UI shows live counts of system state — drivers by status, rides by status, active riders — pushed in real time via the same mechanism as FR-45. Distinct from the Grafana/Prometheus infrastructure dashboards of FR-46.

### NonFunctional Requirements

NFR-1 (Concurrency correctness): Matching must be race-safe under concurrent load — no double-booked drivers, no lost updates — proven via concurrent test scenarios, not code review.
NFR-2 (Scale ambition, phased): Functionally proven at fixture scale (~30 drivers) through the core phases; the Simulator must ramp to a stress scale of roughly 20k drivers and 200k riders as a late-phase milestone, surfacing real bottlenecks (connection pools, index gaps, Kafka partition throughput, cache hot-key contention). The operating area is a scenario parameter, not a constant. Payments is excluded from this stress test (see NFR-8).
NFR-3 (Resilience): Kafka producers/consumers and Stripe API calls apply retry-with-jitter and circuit-breaking; failures degrade gracefully via dead-letter queue rather than cascading.
NFR-4 (Idempotency & consistency): Delivery is at-least-once throughout, so duplicate processing is normal. Every event consumer and externally-triggered handler must be idempotent, deduplicating on a stable event identifier. Ride and payment state machines reject invalid transitions.
NFR-5 (Observability): Every service exposes health and metrics from day one; dashboards make match latency, throughput and error rates visible without log-diving. Money is watched separately from throughput. Refused ride requests are counted split by reason, never as one total.
NFR-6 (Data retention & queryability): Audit data in Postgres retained for a bounded window — 12-month revisable default, dropped by partition — with the same logical history preserved indefinitely in ClickHouse.
NFR-7 (Deployability, local only): Every service builds and runs in Docker with no host JDK dependency. The final deployment target is a **local** Kubernetes cluster; no cloud vendor at any point.
NFR-8 (Payments scale boundary): Payments correctness is proven at normal/small concurrent scale, not at NFR-2 volume. The exclusion is achieved by **swapping the payment provider, not by skipping the payment path** — the payment service and its state machine still run; only the outbound Stripe call is replaced. The same swap enables running before payments exist and CI without credentials.
NFR-9 (Determinism & time control): Simulator runs are reproducible — same seed, same sequence. Every bounded time window must be exercisable under a controlled clock rather than by waiting. Elapsed durations and deadlines use a **monotonic** clock; recorded facts use **wall clock**; a durable deadline crossing processes or services uses wall clock too.
NFR-10 (Payment data handling): Payment-method tokens are never logged, echoed in API responses, or persisted beyond what the provider integration requires; provider API keys live in environment configuration, never in source or fixtures.

### Additional Requirements

Technical requirements extracted from `ARCHITECTURE-SPINE.md` that materially shape epic and story
construction. Each is binding on the stories it touches.

**Starter template / greenfield bootstrap** — **No starter template is specified.** The architecture
mandates a hand-built structure instead: per-service Gradle wrappers (9.x), **no root build**, each
service directory independently buildable as if it lived in its own repo (AD-52, Source tree). This is
Epic 1 Story 1 work and cannot be satisfied by scaffolding a monorepo template.

- **Source tree is fixed** (Structural Seed): `infra/` (docker-compose), `deploy/` (K8s manifests), `docs/`, and `services/{rider,driver,matching,payment,audit}-service` plus `services/simulator` (plain Java, not Spring Boot).
- **Database per service** (AD-1) — private Postgres database each; no shared instance, no cross-database query, no foreign key spanning services.
- **Service ownership is fixed** (AD-3) — `rider-service` owns nothing (stateless façade); `driver-service` owns driver identity and location; `matching-service` owns `rides`, dispatch `drivers`, `fare_rules` and the `payment_standing` projection; `payment-service` owns `payments` and `webhook_events`; `audit-service` owns `audit_events` and the ClickHouse tables. **Redis is owned solely by `matching-service`.**
- **Internal package structure is fixed** (AD-7, AD-8, AD-9) — `controller`/`service`/`repository`/`model`/`strategy`/`config`, one-way dependencies, domain package named `model` never `entity`. `matching-service` alone splits by feature: `shared ← fare ← ride ← dispatch ← quote`.
- **Strategy interfaces exist only at the five roadmap swap seams** (AD-10) — `PaymentProvider`, `PaymentGateway`, `Clock`, `DriverLocationIndex`, `EventTransport`. **Never over Postgres** — repositories take no interface; tests run against real datastores.
- **REST at the edge, gRPC between services, WebSocket as a third push-only transport** (AD-37, AD-51). `matching-service` is never publicly routable (AD-5).
- **Cross-service contracts have one source** (AD-52) — `.proto` files and event schemas live in one versioned directory and are **copied into each service at build time**, never hand-edited per service. No shared library; duplicated domain code accepted.
- **Contracts evolve by addition only** (AD-33) — protobuf field numbers never reused; consumers must ignore unknown fields (note: a hand-built Jackson `ObjectMapper` fails on them by default).
- **State machines are explicit transition tables** (AD-11) — enum plus allowed-transitions map in `model`; illegal transitions raise a domain exception.
- **Every transition is a guarded conditional update with fixed lock ordering** (AD-15) — expected prior state *and* acting identity in the `WHERE`; zero rows affected means rejected, never retried as success. Transactions touching both tables take **`rides` first, then `drivers`**.
- **One-active-ride is a partial unique index** (AD-14), not a check-then-insert.
- **Domain events publish through a transactional outbox** (AD-28) — domain code never publishes directly. **Heartbeats never use the outbox.**
- **Outbox rows are claimed and deleted, never tracked by a cursor** (AD-29) — a high-water-mark relay would permanently skip late-committing rows.
- **Outbox is a bounded queue that sheds on backlog *age*, counting only claimable rows** (AD-35).
- **Kafka producers key by entity id; every consumer is idempotent on `event_id`** (AD-36). New consumer groups start at `earliest`.
- **Redis holds two structures with different populations** (AD-26) — a geo set of *matchable* drivers only, and a per-driver position key for *every* driver. Two pipelined writes per heartbeat, **zero Postgres writes**. **Absence is never evidence of staleness.**
- **Redis is a cache, configured as one** (AD-27) — one instance, no cluster, no persistence, `noeviction`.
- **Settlement is a durable claim-loop worker with a backoff *ceiling*, not a retry cap** (AD-58) — retry state is columns on the `payments` row; the same worker drives capture *and* void; every `now()` in a claim predicate is a bind parameter from the `Clock` strategy, never SQL `now()`.
- **Ride admission reads a local payment-settlement projection, never a synchronous call** (AD-59) — fails **open**, never closed; one row per `ride_id`; rebuildable by replaying from `earliest`. Reason tokens in gRPC error details are the same values the metric counts by label.
- **Time constants form one tuned set whose ordering is the invariant** (AD-46) — heartbeat 2s; driver poll 2s; offer timeout 10s; worker idle backoff 500ms; `NO_DRIVER` budget 60s (accumulated `WAITING_MATCH` time only); idle staleness 15s; `MATCHED` staleness 90s; `IN_PROGRESS` staleness 10min; `CAPTURE_FAILED` cooldown 30min; session expiry 1h.
- **One error vocabulary mapped at the façade** (AD-38) — RFC 9457 Problem Details with the gateway's correlation id. Identity mismatch is always **404, never 403**. `FAILED_PRECONDITION` and `ALREADY_EXISTS` both surface as 409.
- **Every service is observable the same way from its first commit** (AD-54) — not retrofitted. Metrics read where authoritative: durable state from the owning table, process-local state from the process. **Never persist a record solely to make it countable.**
- **Tests run against the real Compose stack, truncate-and-reseed per test class, sequential** (AD-56) — no Testcontainers-style self-starting containers (fights the no-host-JDK constraint), no in-memory substitutes.
- **Deployment is declarative and reconciled from git** (AD-49) — services generated from one template, not copied per service; provider secrets created out-of-band and excluded from reconciliation.
- **Tiering is a design constraint, not a description** (AD-48) — Tier 1 (gateway, three ride-path services + stores, Redis, Kafka) required; Tier 2 (`payment-service`) degrades to rides stalling before dispatch; Tier 3 (audit, ClickHouse, Prometheus, Grafana, dashboard) disableable with nothing breaking.
- **Conventions** — plural snake_case tables; `<entity>.<past-tense-action>` event names; UUID for domain entities and bigint identity only for internal ordering; `TIMESTAMPTZ` UTC; **integer minor units in transit, `DECIMAL` at rest, never floating point**; `DECIMAL(10,8)`/`DECIMAL(11,8)` WGS84 coordinates, longitude before latitude; expand-only migrations; explicit SQL via `JdbcTemplate`, no ORM; `READ COMMITTED`.
- **Pinned stack versions** — Java (Temurin) 25, Spring Boot 4.1.x, Spring gRPC 1.1.0, Gradle 9.x, PostgreSQL 18.6, Flyway 12.4.x (must track Boot's managed version), Kafka (KRaft) 4.3.1, Redis 8.x, ClickHouse 26.3 LTS, HAProxy 3.2.x LTS, Prometheus 3.x, Grafana 13.x, **Resilience4j 2.4.0 (hard constraint — only release publishing a `spring-boot4` module)**, Stripe Java SDK 33.3.x, Docker Compose v2, Kubernetes (kind) 1.35.x, Argo CD 3.5.x.

**Capability classification carried from `SPEC.md`** — this drives decomposition and is not optional:

- **`enabler` capabilities are built first, in week one, regardless of the phase their subject matter suggests**: CAP-36 (health and metrics per service), CAP-40 (the clock abstraction), CAP-39 (the Simulator, in-process fixture form).
- **`property` capabilities never become stories** — CAP-13 (race-safe concurrency), CAP-20 (declared status vs. observed reachability), CAP-23 (failures degrade instead of cascading), CAP-24 (duplicate delivery is safe), CAP-31 (every transition is audited with its actor). They attach as acceptance criteria to every story that could violate them, plus a suite that proves each. **A story named after a property has no definition of done.**
- **Deferred by design, not to be storied**: concrete capacity values (derived by measurement under the stress run), per-cell geo partitioning, eager staleness sweep, CDC for the outbox, schema registry, automated image promotion, encrypted secrets in git, rider push channel, per-cell surge, partial refunds, rider accounts, debtor standing, and recovering a `CAPTURE_FAILED` payment.

### UX Design Requirements

*Not applicable — no UX design document exists for this project.*

Puber is a backend system. Its only user interface is FR-50's lightweight live operational dashboard,
whose requirements are fully specified by FR-50 (live counts of drivers by status, rides by status,
active riders) and AD-51 (fan-out-and-filter WebSocket delivery, own consumer group per replica).
No design tokens, component library, accessibility contract, or responsive breakpoint set has been
authored, and none is required by the PRD. Should a UX pass be run later, its requirements would be
extracted here as `UX-DR*` items.

### FR Coverage Map

Every FR-1 through FR-51 is mapped. Where an FR is delivered across more than one epic, the epics
are listed with the portion each delivers — this is deliberate, not a gap: several FRs describe a
capability whose mechanism lands early and whose full behaviour lands with a later adapter swap.

| FR | Epic(s) | Coverage note |
| --- | --- | --- |
| FR-1 | 1 | Fare quote through the gateway; no ride created |
| FR-2 | 3 | Ride request with locked fare, identifier returned immediately |
| FR-3 | 3 | One-active-ride, enforced by partial unique index (AD-14) |
| FR-4 | 3 | Active-ride lookup by rider identity |
| FR-5 | 3 | Ride read by identifier |
| FR-6 | 3 | Driver identity, position and ETA while `MATCHED` |
| FR-7 | 5 | Rider-visible payment outcome |
| FR-8 | 3 | Rider ride history |
| FR-9 | **3** + **5** | E3: `REQUESTED` gate, state transitions, `PaymentGateway` seam with immediate-authorise impl (AD-43). E5: real asynchronous authorization |
| FR-10 | 3 | Nearest-driver matching within 5km, continuous retry |
| FR-11 | 3 | Bounded offer window, re-offer to next-nearest, `declined_by` exclusion |
| FR-12 | 3 | Terminal `NO_DRIVER` on exhausted seeking budget |
| FR-13 | 3 | Silent-driver salvage back to `WAITING_MATCH` |
| FR-14 | 3 | Auto-completion of an abandoned `IN_PROGRESS` trip |
| FR-15 | 3 | Full ride state machine as an explicit transition table (AD-11, AD-13) |
| FR-16 | **3** + **5** | E3: cancellation, driver release, offer withdrawal. E5: hold voided, including one landing after cancellation |
| FR-17 | 3 | Race-safe matching; CAP-13 property suite |
| FR-18 | 1 | Fare formula `(base + distance + time) × surge` |
| FR-19 | **1** + **4** | E1: static `1.00` in seeded `fare_rules`. E4: demand-derived recomputation + operational metric |
| FR-20 | 2 | Driver-controlled availability; go-offline guard reads the ride's state |
| FR-21 | **2** + **3** | E2: declared status + observed reachability. E3: pending offer and active ride added to the same response |
| FR-22 | 3 | Accept the offered ride, and only that ride |
| FR-23 | 3 | Decline releases to next-nearest immediately |
| FR-24 | 3 | Explicit start-trip and complete-trip |
| FR-25 | 3 | Driver ride history |
| FR-26 | 2 | Location heartbeat, produce-time stamped (AD-23) |
| FR-27 | **6** | Durable ping history lands with the columnar store — there is deliberately **no Postgres location history** (addendum, *Superseded Here* table) |
| FR-28 | **2** + **4** | E2: `DriverLocationIndex` Strategy seam with Postgres impl. E4: Redis fast path, genuinely sub-second and decoupled |
| FR-29 | 2 | Declared status vs. observed reachability; CAP-20 property |
| FR-30 | 2 | Session expiry on prolonged absence, idle drivers only |
| FR-31 | 4 | Domain events onto the backbone; commands and reads stay synchronous |
| FR-32 | 4 | Retry-with-jitter and circuit breaking on producers and consumers |
| FR-33 | 4 | Multiple independent consumers, no coupling |
| FR-34 | 5 | Two-phase authorize/capture lifecycle |
| FR-35 | 5 | Payment state machine with three terminal outcomes |
| FR-36 | 5 | Holds voided on `CANCELLED` / `NO_DRIVER`, including late-resolving authorizations |
| FR-37 | 5 | Capture pursued until it settles; `CAPTURE_FAILED` only on explicit provider verdict |
| FR-38 | 5 | Webhook signature verification and idempotent processing |
| FR-39 | 5 | Full refund flow, internally triggered |
| FR-40 | 5 | Reconciliation flags missed webhooks and implausibly long-lived holds |
| FR-41 | 6 | Every transition audited with its actor; CAP-31 property |
| FR-42 | 6 | Audit queryable by entity and actor, retained by partition drop |
| FR-43 | 6 | Columnar mirror fed as a parallel consumer |
| FR-44 | 6 | Distance, ride density, and driver utilization analytics |
| FR-45 | 7 | Live push channel to drivers |
| FR-46 | **1** + **4** + **5** + **6** | E1: health + metrics per service from the first commit (CAP-36 enabler). E4: dashboards and the fixed KPI definitions. E5: money gauges and refused-requests-by-reason. E6: audit ingest rate |
| FR-47 | 7 | Local Kubernetes deployment |
| FR-48 | 1 | Per-request identity, trusted as-is |
| FR-49 | **1** + **7** | E1: in-process test fixture (CAP-39 enabler). E7: standalone containerized generator, ramped to stress scale |
| FR-50 | 7 | Live operational dashboard |
| FR-51 | 5 | Money-outstanding admission guard, both arms |

**NFR coverage**

| NFR | Epic(s) | Coverage note |
| --- | --- | --- |
| NFR-1 | 3 | CAP-13 concurrency suite; ACs on every guarded transition story |
| NFR-2 | 7 | Stress ramp; produces the capacity numbers AD-47 leaves underived |
| NFR-3 | 4 | CAP-23 property — retry-with-jitter, circuit breaking, dead-letter |
| NFR-4 | 4 | CAP-24 property — idempotent consumers, transition tables reject replays |
| NFR-5 | **1** + **4** | E1: health and metrics baseline. E4: dashboards without log-diving |
| NFR-6 | 6 | Bounded Postgres retention, indefinite columnar history |
| NFR-7 | **1** + **7** | E1: Docker with no host JDK. E7: local Kubernetes as final target |
| NFR-8 | 5 | Provider swap, not path skip |
| NFR-9 | 1 | CAP-40 clock enabler; ACs on every story carrying a bounded window |
| NFR-10 | 5 | Token handling discipline, provider keys in environment config |

## Epic List

### Epic 1: Foundations & Fare Quote

An operator can bring the whole stack up locally and see every service reporting health and metrics,
and a rider can price a pickup/dropoff pair through the gateway — proving the source tree, the build,
the schema pipeline, the test harness, the clock and the Simulator all work before any state machine
is written.

**FRs covered:** FR-1, FR-18, FR-19 (static), FR-46 (baseline), FR-48, FR-49 (fixture form)

**Implementation notes:** No starter template — per-service Gradle wrappers, no root build (AD-52).
All three `enabler` capabilities land here: CAP-36 health/metrics, CAP-40 clock, CAP-39 Simulator.
AD-56 test harness against the real Compose stack. Gateway routes edge services only (AD-5). This
epic is what makes "instrumented from the first commit" true rather than aspirational.

### Epic 2: Driver Presence & Location Tracking

A driver can start and end a shift, be located, and see their own working state — and the system can
tell a driver who chose to stop from one who merely lost signal.

**FRs covered:** FR-20, FR-21 (status + reachability), FR-26, FR-28 (seam), FR-29, FR-30

**Implementation notes:** `driver-service` owns driver identity and location (AD-3). Introduces the
`DriverLocationIndex` Strategy with a **Postgres implementation** — the Redis swap is Epic 4, and is
one of the five roadmap adapter swaps the paradigm is built around. Heartbeats stamped at produce
time (AD-23). Session expiry (1h) must stay ordered above every staleness window (AD-46). FR-27's
durable history is deliberately not here.

### Epic 3: The Ride Loop

A rider can request a ride and watch a driver approach, accept, drive and complete it — and every way
that loop can go wrong (nobody available, driver goes silent, rider cancels, two actors racing)
resolves to exactly one correct outcome.

**FRs covered:** FR-2, FR-3, FR-4, FR-5, FR-6, FR-8, FR-9 (gate), FR-10, FR-11, FR-12, FR-13, FR-14,
FR-15, FR-16 (ride side), FR-17, FR-21 (offer + ride), FR-22, FR-23, FR-24, FR-25

**Implementation notes:** `matching-service` feature packages `shared ← fare ← ride ← dispatch ← quote`
(AD-9). Partial unique index for one-active-ride (AD-14). Guarded conditional updates with
`rides`-then-`drivers` lock ordering (AD-15). The `PaymentGateway` Strategy lands here with the
immediate-authorise implementation, explicitly confined to phases before `payment-service` exists
(AD-43). Claim-loop worker pool as the single offerer (AD-20). `declined_by` array excludes
re-offers (AD-17). The `NO_DRIVER` budget counts accumulated `WAITING_MATCH` time only (AD-46). The
CAP-13 race-safety property suite proves out here.

### Epic 4: Event Backbone, Resilience & Operational Visibility — `v0.1`

Services stop calling each other for anything nobody is waiting on, failures degrade instead of
cascading, and the system's behaviour is visible on a dashboard rather than in logs. Surge starts
moving with real demand.

**FRs covered:** FR-19 (demand-derived), FR-28 (Redis fast path), FR-31, FR-32, FR-33, FR-46
(dashboards + KPI definitions)

**Implementation notes:** Transactional outbox with a claim-and-delete relay (AD-28, AD-29, AD-34,
AD-35) — never a cursor. `EventTransport` Strategy. Kafka keyed by entity id with idempotent
consumers (AD-36). Dead-letter path bounded and visible (AD-55). Redis geo set and position keys
carry deliberately different populations (AD-26, AD-27). Properties CAP-23 and CAP-24 become
standing acceptance criteria from here onward.

### Epic 5: Payments — `v0.2`

A rider's fare is held when they request and taken when they arrive; a ride that delivers no trip
never costs them anything; and money that is genuinely lost is known rather than buried.

**FRs covered:** FR-7, FR-9 (async authorization), FR-16 (hold void), FR-34, FR-35, FR-36, FR-37,
FR-38, FR-39, FR-40, FR-46 (money gauges), FR-51

**Implementation notes:** Two Strategy layers at two levels (AD-43). Settlement driven by a durable
claim-loop worker with a **backoff ceiling, never a retry cap** (AD-58). AD-44's stamped void covers
both timings with one mechanism. AD-59's `payment_standing` projection is advisory and **fails
open**. Payment tokens are transit-only (AD-42, NFR-10). The roadmap warns this phase's capacity was
sized before the two-phase lifecycle and both failure paths landed — it grew from five capabilities
to seven, which is why rider accounts and debtor standing stay deferred.

### Epic 6: Audit & Analytics — `v0.3`

Every state transition the system ever made is queryable by entity and by actor, bounded in Postgres
and complete in a columnar store — and the location and audit history finally has a consumer.

**FRs covered:** FR-27, FR-41, FR-42, FR-43, FR-44, FR-46 (audit ingest rate)

**Implementation notes:** `audit-service` owns `audit_events` and the ClickHouse tables (AD-3).
Monthly partitions dropped, never row-level `DELETE` (AD-53). The columnar store is fed as a
**parallel consumer, never a Postgres-to-columnar copy job**, so either store can be rebuilt from the
log. The migration benchmark is a deliverable, not a side effect. Audit scope is state transitions
only, never location heartbeats. Property CAP-31 is proven here but attaches as ACs everywhere.

### Epic 7: Real-Time, Live Dashboard & Local Kubernetes — `v1.0`

Drivers stop polling for offers, an operator watches the system's domain state move in real time, and
the whole system runs as an orchestrated deployment under stress-scale load.

**FRs covered:** FR-45, FR-47, FR-49 (standalone + stress ramp), FR-50, NFR-2

**Implementation notes:** WebSocket as a third transport, admitted only for server-initiated push,
routed fan-out-and-filter with each replica in its own consumer group (AD-51) — push is never the
only delivery route for anything correctness-bearing. GitOps reconciliation from manifests in the
repository (AD-49). The stress run produces the concrete capacity values AD-47 deliberately leaves
underived.

## Epic 1: Foundations & Fare Quote

An operator can bring the whole stack up locally and see every service reporting health and metrics,
and a rider can price a pickup/dropoff pair through the gateway — proving the source tree, the build,
the schema pipeline, the test harness, the clock and the Simulator all work before any state machine
is written.

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

> **Note on ordering.** The truncate-and-reseed discipline of AD-56 is deliberately *not* here: with
> no owned tables and no seed data yet, it would assert over an empty set. It is introduced in Story
> 1.3 against `fare_rules` — the first seeded state — and extended in Story 2.1 to the fixture
> drivers AD-56 actually cites as the cross-test-interference risk.

### Story 1.2: Time is injectable and never read directly

As an engineer,
I want every read of the current time to go through a `Clock` strategy,
So that timing behaviour is testable in seconds rather than by waiting, from the first test that touches it.

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

**Given** the same seed and the same clock script
**When** a timing test is re-run
**Then** it produces the same result (NFR-9)

### Story 1.3: Fares are computed from configurable rules

As a rider,
I want the fare for a trip computed from a published formula over configurable rules,
So that the price I am quoted is explainable and consistent rather than arbitrary.

**Acceptance Criteria:**

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

> **Scope boundary:** with no drivers in the system yet, only FR-1's no-driver branch is reachable
> here, and it is fully delivered. The ETA-present branch lights up in Story 2.6 once driver
> positions exist. This story is complete and independently valuable without it.

### Story 1.5: Simulator drives reproducible quote traffic

As an engineer,
I want a seeded in-process Simulator generating riders and coordinate pairs,
So that load is reproducible from the very first phase and a failure can be re-run rather than merely observed once.

**Acceptance Criteria:**

**Given** a seed
**When** the Simulator runs twice
**Then** it produces an identical sequence of rider identities and coordinate pairs (NFR-9)

**Given** configured geographic bounds
**When** coordinates are generated
**Then** every coordinate falls within the configured bounds
**And** no coordinate literal is hard-coded (AD-25)

**Given** this phase
**When** the Simulator runs
**Then** it runs as an in-process test fixture rather than a standalone container (CAP-39)

**Given** a Simulator run
**When** it drives quote requests concurrently
**Then** every response is a valid quote
**And** the run's request count and outcomes are asserted rather than merely logged

**Given** the bounds configuration
**When** it is widened toward stress scale
**Then** generation adapts with no code change (AD-25, NFR-2)

## Epic 2: Driver Presence & Location Tracking

A driver can start and end a shift, be located, and see their own working state — and the system can
tell a driver who chose to stop from one who merely lost signal.

> **Seam decision carried by this epic.** The `DriverLocationIndex` Strategy (AD-10) lives in
> `matching-service`, which is the consumer of proximity search and the eventual owner of AD-26's
> Redis structures. `driver-service` owns driver identity and location (AD-3) and forwards each
> heartbeat over gRPC to the index. Epic 4 replaces the forward with a Kafka event and the Postgres
> columns with Redis — the seam does not move, only its implementation. This epic therefore creates
> `matching-service`'s dispatch `drivers` table carrying declared status, a position snapshot and
> `last_seen_at`; Epic 3 extends it with `current_ride_id` by expand-only migration.

### Story 2.1: Fixture drivers exist with identities and positions

As an engineer,
I want `driver-service` running with a seeded set of fixture drivers,
So that there are real drivers to place, track and later dispatch, without any registration flow.

**Acceptance Criteria:**

**Given** the Compose stack
**When** it is brought up
**Then** `driver-service` starts with its own private Postgres database (AD-1)
**And** it exposes health and Prometheus metrics exactly as the existing services do (AD-54)
**And** it carries its own Gradle wrapper and the standard package layout (AD-7, AD-52)

**Given** a first start
**When** Flyway runs
**Then** a `drivers` identity table is created holding id, display name and current position
**And** fixture drivers are seeded automatically

**Given** the configured geographic bounds and a seed
**When** fixture drivers are seeded
**Then** every driver's position falls within the configured bounds
**And** the same seed produces the same drivers and positions (AD-25, NFR-9)

**Given** a seeded driver
**When** their identity is read
**Then** the identifier is a UUID and is fixture-known rather than registered (FR-48, Identifiers convention)

**Given** two test classes that both drive the same fixture driver through a state change
**When** the suite runs
**Then** neither observes state left behind by the other
**And** the suite produces identical results regardless of class execution order (AD-56)

### Story 2.2: Driver goes online and offline

As a driver,
I want to declare when I am working and when I have stopped,
So that I receive offers only when I have chosen to be available.

**Acceptance Criteria:**

**Given** a seeded driver who is `OFFLINE`
**When** they go online
**Then** `driver-service` calls `matching-service` over gRPC
**And** the dispatch driver row is set `AVAILABLE`
**And** the driver's display identity travels on that call so no later fetch is needed (AD-24, AD-37)

**Given** an `AVAILABLE` driver
**When** they go offline
**Then** the dispatch driver row is set `OFFLINE`
**And** they receive no further offers

**Given** any status change
**When** it is written
**Then** it is a guarded conditional update carrying the expected prior state and the acting driver identity
**And** zero rows affected is a rejection, never a silent success (AD-15)

**Given** the system
**When** any code path attempts to set a driver online
**Then** only the driver's own action can do so — the system never puts a driver online (FR-20)

**Given** a status transition
**When** it is caused by the system rather than the driver
**Then** the acting actor is recorded as `SYSTEM` on the transition (FR-41 groundwork; the audit
event itself lands in Epic 6)

> **Scope boundary:** with no rides yet, going offline always succeeds. AD-16's guard — which reads
> the *ride's* state, refusing while `MATCHED`/`IN_PROGRESS` and releasing an outstanding `OFFERED`
> — is added in Story 3.11 when those states exist.

### Story 2.3: Driver reports position by heartbeat

As a driver,
I want my position reported continuously while I work,
So that the system knows where I am and can tell that I am still reachable.

**Acceptance Criteria:**

**Given** a working driver
**When** they send a location heartbeat
**Then** `driver-service` persists the current position
**And** forwards position and timestamp to `matching-service`'s `DriverLocationIndex` (AD-10)

**Given** a heartbeat
**When** it is produced
**Then** the timestamp is stamped by `driver-service` at produce time
**And** no consumer ever re-stamps it on receipt, so lag cannot mask staleness (AD-23)

**Given** a heartbeat
**When** it is handled
**Then** it does **not** write an `event_outbox` row — location pings are ephemeral telemetry, not
state transitions (AD-28)

**Given** the configured cadence
**When** a driver heartbeats normally
**Then** the interval is 2 seconds, and it is drawn from the shared time-constant set rather than a
local literal (AD-46)

**Given** a driver's position
**When** it is read back
**Then** it reflects the most recent heartbeat received

### Story 2.4: Matchability is derived from status and heartbeat freshness

As a rider,
I want only drivers who are both available and currently reachable to be considered for my ride,
So that my request is never held by a driver who has vanished and will never answer.

**Acceptance Criteria:**

**Given** a driver declared `AVAILABLE` whose last heartbeat is inside the staleness window
**When** matchable drivers are queried
**Then** that driver is returned (FR-29)

**Given** a driver declared `AVAILABLE` whose last heartbeat is older than the staleness window
**When** matchable drivers are queried
**Then** that driver is **not** returned
**And** their declared status remains `AVAILABLE`, unchanged (AD-21, CAP-20)

**Given** a driver who went silent and is later heard again
**When** their next heartbeat lands
**Then** they become matchable again immediately
**And** no status transition is written and no action is required of them (AD-21)

**Given** the system
**When** a driver's signal is lost
**Then** nothing writes to their declared status — status is written only by the driver's own action,
the ride lifecycle, or session expiry (AD-21)

**Given** a driver with **no** recorded position at all
**When** staleness is evaluated
**Then** absence is not treated as evidence of staleness
**And** the driver is simply not matchable, with no sweep acting on them (AD-26)

**Given** the staleness window
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by sleeping (NFR-9)

### Story 2.5: Driver reads their working state in one call

As a driver,
I want to see my whole working state in a single call,
So that when nothing is arriving I can tell whether it is because I am offline or because I am unreachable.

**Acceptance Criteria:**

**Given** a driver
**When** they read their session
**Then** one response carries their declared status and whether the system is currently hearing their
heartbeat (FR-21)

**Given** a driver declared `AVAILABLE` whose heartbeat has gone stale
**When** they read their session
**Then** the response shows `AVAILABLE` **and** not currently reachable
**And** the two facts are distinguishable rather than collapsed into one field (FR-21, AD-21)

**Given** a driver reading a session
**When** the identity does not match a known driver
**Then** the response is 404, never 403 (AD-38)

**Given** the response
**When** it is rendered
**Then** the poll cadence it is designed for is 2 seconds, drawn from the shared time-constant set (AD-46)

> **Scope boundary:** the pending-offer and active-ride fields of FR-21 are added in Story 3.12, when
> offers and rides exist. This story delivers the status and reachability halves in full.

### Story 2.6: Quote ETA reflects the nearest matchable driver

As a rider,
I want my quote to tell me how long a driver will take to reach me,
So that I know both the price and the wait before I commit.

**Acceptance Criteria:**

**Given** at least one matchable driver within the matching radius of the pickup point
**When** a quote is requested
**Then** the response carries fare, distance **and** ETA
**And** the ETA is haversine distance from the nearest matchable driver to pickup, divided by 8.33 m/s (FR-1)

**Given** no matchable driver within the radius
**When** a quote is requested
**Then** fare and distance are returned and ETA is omitted
**And** the response is a success, never an error (FR-1)

**Given** a driver who is `AVAILABLE` but unreachable
**When** a quote is requested near them
**Then** they are not considered for the ETA (FR-29)

**Given** a quote
**When** it is served
**Then** no ride is created and no driver is reserved (FR-1)

### Story 2.7: A shift ends after prolonged absence

As a driver,
I want a shift I plainly ended to not still be running days later,
So that I never start receiving offers on a day I did not choose to work.

**Acceptance Criteria:**

**Given** an idle driver declared `AVAILABLE` who has been unreachable for the session-expiry window
**When** the expiry sweep runs
**Then** the driver is set `OFFLINE`
**And** the transition records `SYSTEM` as the acting actor (AD-22, FR-30)

**Given** a driver expired this way
**When** they heartbeat again
**Then** they do **not** become matchable
**And** they must explicitly go online again to receive offers (FR-30)

**Given** an idle driver unreachable for less than the session-expiry window
**When** the sweep runs
**Then** their status is untouched (AD-21)

**Given** the time-constant set
**When** an ordering test runs
**Then** session expiry (1 h) is strictly greater than every staleness window
**And** the ordering `idle < MATCHED < IN_PROGRESS < capture-failed cooldown < session expiry` is
asserted by that test rather than left to review (AD-46)

**Given** the session-expiry window
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by sleeping (NFR-9)

> **Scope boundary:** with no rides yet, every driver is idle. AD-22's restriction to *idle* drivers —
> a driver holding a ride is resolved by Stories 3.8 and 3.9 first, never expired mid-ride — is
> asserted in Story 3.9 where `IN_PROGRESS` exists to test it against.
