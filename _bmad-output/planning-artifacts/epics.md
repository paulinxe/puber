---
stepsCompleted: ['step-01-validate-prerequisites', 'step-02-design-epics', 'step-03-create-stories', 'step-04-final-validation']
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

### Testing policy — tests ship with the feature that needs them

**No story in this breakdown exists solely to add tests, and none may be added later.** Every story
carries the tests that prove its own acceptance criteria; a feature is not done until it is proven.
A separate "write the tests for X" story is forbidden, because it lets X be marked done while
unproven and turns its proof into work that can be deprioritised independently of the feature it
belongs to.

**The diagnostic:** a story must *deliver* a requirement, not merely *prove* one. A story that
delivers an FR or a CAP is legitimate even when tests are its only consumer for a phase — the
Simulator (FR-49) is the standing example. A story that delivers no requirement and exists only to
prove requirements delivered elsewhere is the forbidden shape, and its criteria belong on the stories
that deliver them.

This has a specific consequence for the `property` capabilities of `SPEC.md` — CAP-13 (race-safe
concurrency), CAP-20 (declared status vs. observed reachability), CAP-23 (failures degrade),
CAP-24 (duplicate delivery is safe) and CAP-31 (every transition is audited). SPEC describes each as
*"acceptance criteria on every story that could break it, plus a suite that proves it."* Both halves
still hold — but **the suite is built inside the stories whose behaviour it proves, never as a story
of its own.** A property therefore surfaces only as acceptance criteria, distributed across every
story that could violate it.

**Standing acceptance criteria.** The following apply to every story performing the action described,
whether or not the story restates them; they are written out on individual stories only where the
detail is easy to get wrong:

- Any guarded conditional update affecting **zero rows is a rejection**, never a silent success, and
  never a retry treated as success (AD-15).
- Any bounded time window is exercised by **advancing the `Clock`**, never by sleeping (NFR-9).
- Any metric assertion reads a **delta across the action under test**, never an absolute, and no
  reset hook is added to a service (Metrics convention, AD-56).
- Any lost race maps to `ABORTED`, is retried internally, and is **never surfaced to the caller** (AD-38).
- Any consumer or externally-triggered handler is **idempotent on a stable event identifier** (NFR-4,
  AD-36) — binding from Epic 4 onward, where such handlers first exist.

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

- **`enabler` capabilities are built first, regardless of the phase their subject matter suggests**: CAP-36 (health and metrics per service) and CAP-40 (the clock abstraction) land in Epic 1. **CAP-39 (the Simulator) is the exception** — `roadmap.md` places it in week one because *"Phase 1's matching correctness is proven against it"*, but that correctness lands in Epic 3, and the deterministic scenarios that prove it are ordinary test fixtures rather than synthetic traffic. The Simulator is therefore delivered once, as a component, in Epic 7.
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
| FR-2 | **3** + **5** | E3: ride request with locked fare, identifier returned immediately. E5: the payment-method token joins the request contract, alongside the service that consumes it |
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
| FR-33 | **4** + **6** | E4: consumer groups, independent offsets, new groups start at `earliest` — proven with the location consumer. E6: `audit-service` is the second independent consumer on the same streams, which is what proves decoupling rather than describing it |
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
| FR-49 | **7** | The Simulator is a containerized load generator delivered once, in Epic 7. Its "in-process test fixture" form is not built as a component: the deterministic scenarios earlier epics need are ordinary test fixtures shipping inside the stories they prove (see *Testing policy*) |
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
the schema pipeline, the test harness and the clock all work before any state machine is written.

**FRs covered:** FR-1, FR-18, FR-19 (static), FR-46 (baseline), FR-48

**Implementation notes:** No starter template — per-service Gradle wrappers, no root build (AD-52).
Two of the three `enabler` capabilities land here: CAP-36 health/metrics and CAP-40 clock. AD-56 test
harness against the real Compose stack. Gateway routes edge services only (AD-5). This epic is what
makes "instrumented from the first commit" true rather than aspirational.

**On CAP-39 (Simulator), the third enabler:** it is *not* built here. `roadmap.md` hoists it to week
one on the grounds that *"Phase 1's matching correctness is proven against it"* — but matching lands
in Epic 3, so that justification never applies to Epic 1, and quotes are read-only with no
concurrency to exercise. The deterministic scenarios Epic 3 needs are test fixtures shipping inside
the stories they prove; the Simulator as a component is delivered once, in Epic 7, for observing
emergent behaviour under load.

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

**FRs covered:** FR-2 (request), FR-3, FR-4, FR-5, FR-6, FR-8, FR-9 (gate), FR-10, FR-11, FR-12,
FR-13, FR-14, FR-15, FR-16 (ride side), FR-17, FR-21 (offer + ride), FR-22, FR-23, FR-24, FR-25

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

**FRs covered:** FR-45, FR-47, FR-49, FR-50, NFR-2

**Implementation notes:** WebSocket as a third transport, admitted only for server-initiated push,
routed fan-out-and-filter with each replica in its own consumer group (AD-51) — push is never the
only delivery route for anything correctness-bearing. GitOps reconciliation from manifests in the
repository (AD-49). The stress run produces the concrete capacity values AD-47 deliberately leaves
underived.

### Epic independence and file overlap

**Each epic delivers complete functionality for its domain and needs no later epic to function.**
Epic 1 stands alone — a rider can price a trip through the gateway. Epic 2 stands alone — a driver
can work a shift and be located. Epic 3 builds on both and runs on the immediate-authorise gateway
with no Kafka and no payments. Epic 4 changes how the system communicates; the system worked before
it. Epic 5 is Tier 2 — without it rides stall before dispatch, and ride requests still succeed.
Epic 6 is Tier 3 — disabling it breaks nothing and loses no data. Epic 7 builds on all of them.

**File overlap was assessed and consolidation deliberately rejected.** Epics 2–5 all modify
`matching-service`: Epic 2 adds the dispatch `drivers` table, Epic 3 the `ride` / `dispatch` /
`quote` packages, Epic 4 the outbox and the Redis `DriverLocationIndex` implementation, Epic 5 the
`payment_standing` projection. That overlap is real rather than incidental, and it was kept for
three reasons. The **phase sequence and its milestone tags are binding** (`roadmap.md`), so merging
across them is not an available move. Each boundary is a **genuine feedback point** where what is
measured changes what follows — `v0.1`'s dashboards inform `v0.2`'s money gauges, and the stress run
sets capacity values for everything already built. And a consolidated matching epic would exceed 40
stories, far past what a single dev agent's context can hold. Within Epic 3, by contrast, the
stories *were* consolidated for exactly this reason rather than split by rider-side and driver-side.

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

**Given** a first start after this story
**When** `matching-service`'s Flyway migrations run
**Then** its dispatch `drivers` table is created, holding driver id, declared status
(`OFFLINE | AVAILABLE | BUSY`), the driver's display identity, a position snapshot and `last_seen_at`
**And** it is a separate table in a separate database from `driver-service`'s identity table, with no
foreign key between them (AD-1, AD-3, AD-16)

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

**Given** this first driver-facing gRPC contract
**When** it is defined
**Then** it is segregated by *consumer*, not by owner — no single service definition carries both
rider-facing and driver-facing methods
**And** a driver-side change therefore cannot force a rider-side rebuild (AD-57)

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
**And** forwards position and timestamp to `matching-service`'s `DriverLocationIndex` (FR-26, AD-10)

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
> a driver holding a ride is resolved by Stories 3.8 and 3.10 first, never expired mid-ride — is
> asserted in Story 3.10 where `IN_PROGRESS` exists to test it against.

## Epic 3: The Ride Loop

A rider can request a ride and watch a driver approach, accept, drive and complete it — and every way
that loop can go wrong (nobody available, driver goes silent, rider cancels, two actors racing)
resolves to exactly one correct outcome.

### Story 3.1: The ride state machine is an explicit transition table

As a rider,
I want my ride to only ever enter a state that legitimately follows the last one,
So that no out-of-order or replayed event can advance it twice or move it somewhere impossible.

**Acceptance Criteria:**

**Given** the ride lifecycle
**When** it is declared
**Then** it is an enum plus an allowed-transitions map in `model`
**And** validity is never scattered across `if` checks at call sites (AD-11)

**Given** the declared machine
**When** its legal transitions are inspected
**Then** they are exactly: `REQUESTED →` {`WAITING_MATCH`, `PAYMENT_FAILED`, `CANCELLED`};
`WAITING_MATCH →` {`OFFERED`, `NO_DRIVER`, `CANCELLED`}; `OFFERED →` {`MATCHED`, `WAITING_MATCH`,
`CANCELLED`}; `MATCHED →` {`IN_PROGRESS`, `WAITING_MATCH`, `CANCELLED`}; `IN_PROGRESS →`
{`COMPLETED`}
**And** `COMPLETED`, `CANCELLED`, `NO_DRIVER` and `PAYMENT_FAILED` have no outgoing transitions (AD-13)

**Given** any transition not in that table
**When** it is attempted
**Then** a domain exception is raised
**And** it is never a silent no-op (AD-11)

**Given** the machine
**When** `REQUESTED` is examined
**Then** no state transitions back into it, so a ride holding funds can never fall back into
re-authorising them (AD-13, FR-15)

**Given** a first migration
**When** the `rides` table is created
**Then** it holds a UUID id, a plain `rider_id` UUID with no foreign key, pickup and dropoff
coordinates, fare, status, and the timestamps marking each transition
**And** a monotonic bigint identity exists for internal ordering and is never exposed (Identifiers convention)

### Story 3.2: Rider requests a ride and the fare is locked

As a rider,
I want to request a ride and receive its identifier immediately,
So that I am not left waiting on a payment provider before I even know my ride exists.

**Acceptance Criteria:**

**Given** a rider with pickup and dropoff coordinates
**When** they request a ride
**Then** the ride is persisted `REQUESTED` and its identifier is returned immediately
**And** the response does not wait on authorization (FR-2, AD-41)

**Given** the request
**When** the fare is computed
**Then** it is locked at request time from the current fare rules
**And** it is never recomputed at trip end (FR-2, FR-18)

**Given** the `REQUESTED` state
**When** its meaning is asserted
**Then** it means *awaiting authorization* and nothing else (AD-13)

**Given** the `PaymentGateway` Strategy
**When** no `payment-service` exists in this phase
**Then** the immediate-authorise implementation signals authorised
**And** the ride moves to `WAITING_MATCH` (AD-43)

**Given** that implementation
**When** it is wired
**Then** it is confined to phases before `payment-service` exists, and to test runs that exercise the
ride path without payments
**And** it is never selectable as a runtime degradation for a payment outage (AD-43)

> **Scope boundary — the payment-method token is deliberately not here.** FR-2 defines it as part of
> the ride-request contract, but nothing in this phase consumes it: the immediate-authorise gateway
> calls no provider, and AD-42's rule that the token "passes through to `payment-service`" has no
> `payment-service` to pass through to. It joins the request in Epic 5 alongside the component that
> reads it, together with AD-42's masked value type and the leak test that keeps NFR-10 honest.
> Adding it then is additive and safe (AD-33); it is never persisted (AD-42), so no migration is
> deferred with it. Deliberately-declining tokens arrive in that same story as test fixtures, since
> there is nothing to decline before then; the Simulator exercises them under load in Epic 7.

### Story 3.3: A rider may hold only one ride at a time

As a rider,
I want a second request refused while I already have a ride in flight,
So that I cannot accidentally hold two rides — and later two holds — at once.

**Acceptance Criteria:**

**Given** a rider holding a ride in `REQUESTED`, `WAITING_MATCH`, `OFFERED`, `MATCHED` or `IN_PROGRESS`
**When** they request another ride
**Then** it is refused as `ALREADY_EXISTS` → 409 (FR-3, AD-38)

**Given** the rule
**When** it is enforced
**Then** it is a partial unique index on `rides (rider_id)` filtered to those five states
**And** it is never a check-then-insert (AD-14)

**Given** two concurrent requests from the same rider
**When** both are submitted
**Then** exactly one succeeds
**And** the other fails on the constraint and becomes a 409 (AD-14, NFR-1)

**Given** a rider whose rides are all terminal
**When** they request a ride
**Then** it is admitted (FR-3)

**Given** the index
**When** the table grows
**Then** it holds only active rides and stays small regardless of total ride volume (AD-14)

**Given** a refusal
**When** it is counted
**Then** it increments a refused-ride-request counter in `matching-service` carrying the `active_ride`
reason label
**And** the label is a closed enum with a single registration point, never free text (AD-54)

**Given** that counter
**When** its storage is inspected
**Then** it is an ordinary in-process monotonic counter exposed on the service's Prometheus endpoint
**And** no table row and no outbox row is written to make a refusal countable, because a refusal
persisted nothing to derive from and this is a Tier-1 request path (Metrics convention)

**Given** a request rejected for any other reason
**When** the counter is examined
**Then** only AD-38's 409 admission refusals have incremented it
**And** a malformed 400 and a shed 503 have not, each keeping its own signal (AD-54, AD-35)

**Given** a client re-attempting a refused request on the 2 s poll cadence
**When** each call reaches the admission check
**Then** the counter increments once per call rather than once per affected rider (AD-54)

**Given** an integration test asserting the counter
**When** it runs after other test classes against the shared stack
**Then** it asserts the **delta across the action under test**, never an absolute value
**And** no metrics-reset hook exists on the service, since truncate-and-reseed does not reset
in-process meters and a reset would break the monotonicity `rate()` depends on (Metrics convention, AD-56)

> **Scope boundary:** AD-54's other two reason labels — unsettled payment and capture-failed cooldown
> — are added in Epic 5 with the projection that can see them. The closed enum and its single
> registration point are established here so they cannot be bolted on as free strings later.

### Story 3.4: Rider reads their rides

As a rider,
I want to find my in-flight ride, read any ride by identifier, and page my history,
So that I always know where my ride is and what happened on the ones before it.

**Acceptance Criteria:**

**Given** a rider with a ride in flight
**When** they look up their active ride by identity alone
**Then** at most one ride is returned (FR-4, FR-3)

**Given** a rider with no ride in flight
**When** they look up their active ride
**Then** nothing is returned
**And** it is not an error (FR-4)

**Given** a ride identifier
**When** the owning rider reads it
**Then** current state and details are returned (FR-5)

**Given** a `COMPLETED` ride
**When** the rider reads it
**Then** the response says whether the driver completed it or the system did
**And** an auto-completed trip is therefore distinguishable **when serving a read**, not only in the
audit trail after the fact (FR-14, AD-18)

**Given** ride detail
**When** it is requested repeatedly
**Then** it is served from an endpoint separate from live driver position
**And** it carries an `ETag` and answers 304 to a matching `If-None-Match` (AD-40)

**Given** a rider identity that does not own the ride
**When** they read it
**Then** the response is 404, never 403
**And** it does not reveal whether the ride exists (AD-38, AD-39)

**Given** ride history
**When** it is requested
**Then** results are most-recent-first and bounded by a result-size limit (FR-8)

**Given** any rider-facing response
**When** it is rendered
**Then** it carries the driver's display name and never a driver identifier (AD-39)

### Story 3.5: Matching offers the ride to the nearest matchable driver

As a rider,
I want the system to find me the nearest driver who is genuinely able to take my ride,
So that I am matched quickly and not held by someone who will never answer.

**Acceptance Criteria:**

**Given** rides awaiting a driver
**When** the matching worker claims work
**Then** it selects `status = 'WAITING_MATCH'` alone (AD-19, FR-10)

**Given** a ride whose funds are not yet held
**When** matching runs
**Then** the ride is *invisible* to dispatch rather than rejected by a guard (AD-19, FR-9)

**Given** matchable drivers
**When** a ride is matched
**Then** the nearest driver within a 5 km radius is chosen
**And** matchability requires `AVAILABLE` **and** a fresh heartbeat (FR-10, FR-29)

**Given** the matching worker
**When** it runs
**Then** it is a small fixed pool claiming batches with `FOR UPDATE SKIP LOCKED`, processing and
re-claiming, waiting only on an empty claim (AD-20)
**And** pool size derives from arrival rate × match time, never from backlog depth (AD-20, AD-47)

**Given** an offer
**When** it is made
**Then** the ride moves `WAITING_MATCH → OFFERED`
**And** the driver's dispatch status is set `BUSY`
**And** the driver's display identity is snapshotted onto the ride (AD-16, AD-24)

**Given** the whole system
**When** offers are made
**Then** they are made in exactly one place — this worker (AD-20)

**Given** a transaction touching both `rides` and `drivers`
**When** it runs
**Then** it takes `rides` first, then `drivers` (AD-15)

**Given** many concurrent match attempts against the same driver
**When** they run
**Then** exactly one succeeds and the rest are rejected
**And** no driver is ever double-booked (FR-17, NFR-1)

**Given** a match attempt that loses its race
**When** it is handled
**Then** it maps to `ABORTED`, is retried internally against a re-search
**And** it is never surfaced to the caller (AD-38, AD-4)

### Story 3.6: Driver accepts the offer

As a driver,
I want to accept the ride currently offered to me,
So that I commit to a passenger and begin the trip.

**Acceptance Criteria:**

**Given** a driver holding a live offer
**When** they accept
**Then** the ride moves `OFFERED → MATCHED` (FR-22, AD-13)

**Given** the `MATCHED` state
**When** it is asserted
**Then** it means the driver has *accepted* and is en route
**And** it is distinct from `OFFERED`, which means a driver is still deciding (AD-13)

**Given** a driver acting on a ride not offered to them
**When** they accept
**Then** it is rejected (FR-22)

**Given** a driver with no live offer at all
**When** they accept anything
**Then** it is rejected (FR-22)

**Given** the accept
**When** it is written
**Then** it is a guarded conditional update carrying expected prior state `OFFERED` and the acting
driver identity
**And** zero rows affected is a rejection, never a silent success (AD-15)

**Given** a rejected accept
**When** it surfaces
**Then** wrong state maps to `FAILED_PRECONDITION` → 409
**And** identity mismatch or absent maps to `NOT_FOUND` → 404 (AD-38)

**Given** two drivers accepting the same offered ride concurrently
**When** both commit
**Then** exactly one succeeds and the other is rejected
**And** the ride carries exactly one assigned driver (FR-17, NFR-1)

### Story 3.7: Offer is declined or expires

As a rider,
I want a driver who declines or ignores my ride to release it immediately to the next-nearest driver,
So that I am not left waiting on someone who was never going to come.

**Acceptance Criteria:**

**Given** a driver with a pending offer
**When** they decline
**Then** the ride returns to `WAITING_MATCH` immediately
**And** the driver returns to `AVAILABLE` (FR-23)

**Given** an offer unanswered for the offer timeout of 10 s
**When** the timeout is reached
**Then** the ride returns to `WAITING_MATCH`
**And** the driver is released (FR-11, AD-46)

**Given** a decline or a timeout
**When** it occurs
**Then** the driver's id is appended to the ride's `declined_by` array (AD-17)

**Given** a ride carrying `declined_by`
**When** matching next runs
**Then** those drivers are filtered out and never offered that ride again (AD-17, FR-11)

**Given** the decline and timeout paths
**When** they run
**Then** they **release only** — neither makes an offer, because offers are made in exactly one
place (AD-20)

**Given** the design
**When** offer history is considered
**Then** no separate offers table is created — the durable record is the audit trail (AD-17)

**Given** the offer timeout
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by sleeping (NFR-9)

### Story 3.8: Driver goes silent before pickup

As a rider,
I want a ride whose driver vanished before reaching me to be handed to another driver,
So that my ride is salvaged rather than killed by someone else's lost signal.

**Acceptance Criteria:**

**Given** a `MATCHED` ride whose driver's last heartbeat is older than the `MATCHED` staleness window of 90 s
**When** the recovery sweep runs
**Then** the ride returns to `WAITING_MATCH`
**And** the silent driver is released to `AVAILABLE` (FR-13, AD-46)

**Given** that recovery
**When** the ride transitions
**Then** it returns to `WAITING_MATCH` and never to `REQUESTED`, because its hold is already in place
and must not be authorised a second time (FR-13, AD-13)

**Given** a driver with no recorded position at all
**When** the sweep runs
**Then** absence is not treated as evidence of staleness
**And** the sweep does not act on them (AD-26)

**Given** the recovered ride
**When** it re-enters matching
**Then** it is offered to the next-nearest matchable driver (FR-13)

**Given** the staleness window
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by sleeping (NFR-9)

### Story 3.9: Driver runs the trip

As a driver,
I want to explicitly start the trip once my passenger is aboard and complete it at the end,
So that the ride reflects what is actually happening on the road.

**Acceptance Criteria:**

**Given** a `MATCHED` ride
**When** its own assigned driver starts the trip
**Then** it moves `MATCHED → IN_PROGRESS` (FR-24)

**Given** an `IN_PROGRESS` ride
**When** its own assigned driver completes it
**Then** it moves `IN_PROGRESS → COMPLETED`
**And** the driver returns to `AVAILABLE` (FR-24)

**Given** a driver acting on a ride that is not their own
**When** they start or complete it
**Then** it is rejected as 404 (FR-24, AD-38)

**Given** a ride not in the immediately preceding state
**When** start or complete is attempted
**Then** it is rejected as 409 (FR-24, AD-38)

**Given** a driver-completed ride
**When** completion is recorded
**Then** `completed_by = DRIVER` is written as a column
**And** it is never expressed as a distinct ride state (AD-18)

### Story 3.10: Abandoned trip is auto-completed

As a rider,
I want a trip whose driver went silent mid-route to reach a definite end,
So that my ride does not hang unresolved forever.

**Acceptance Criteria:**

**Given** an `IN_PROGRESS` ride whose driver's last heartbeat is older than the `IN_PROGRESS`
staleness window of 10 minutes
**When** the sweep runs
**Then** the ride is completed
**And** the driver is released (FR-14, AD-46)

**Given** that completion
**When** it is recorded
**Then** the ride ends in `COMPLETED` with `completed_by = SYSTEM`
**And** it is not a separate state (AD-18)

**Given** the transition
**When** its actor is recorded
**Then** it is `SYSTEM` (FR-14, FR-41 groundwork)

**Given** a "completed rides" query
**When** it runs
**Then** it returns both driver-completed and system-completed rides by matching one status value (AD-18)

**Given** a driver holding a ride who has gone unreachable
**When** the session-expiry sweep runs
**Then** it does not expire them — the ride is resolved by this story or Story 3.8 first
**And** the session-expiry window is strictly longer than both staleness windows, so no driver is
ever expired mid-ride (AD-22, AD-46, FR-30)

**Given** the staleness window
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by sleeping (NFR-9)

> **Scope boundary:** FR-14 also requires the locked fare to be captured through the normal payment
> path. With no `payment-service` yet, this story delivers the ride-side completion and the `SYSTEM`
> provenance; the capture it triggers arrives in Epic 5, which routes system-completed rides through
> exactly the same path as driver-completed ones.

### Story 3.11: Driver goes offline mid-engagement

As a driver,
I want going offline to be refused once I have accepted a ride but permitted while I am only deciding,
So that I can stop working without stranding a passenger who is already expecting me.

**Acceptance Criteria:**

**Given** a driver with an outstanding `OFFERED` ride
**When** they go offline
**Then** the offer is released back to `WAITING_MATCH`
**And** the driver goes `OFFLINE` (FR-20, AD-16)

**Given** a driver on a `MATCHED` or `IN_PROGRESS` ride
**When** they go offline
**Then** it is refused until they complete the ride or are released (FR-20, AD-16)

**Given** the guard
**When** it is evaluated
**Then** it reads the **ride's** state, not the dispatch status
**And** this is because `BUSY` spans the whole engagement from offer through completion, making
status alone unable to distinguish the two cases (AD-16)

**Given** a refusal
**When** it surfaces
**Then** wrong state maps to `FAILED_PRECONDITION` → 409 (AD-38)

### Story 3.12: Driver session shows pending offer and active ride

As a driver,
I want my session read to show the offer awaiting my answer and the ride I am on,
So that one call tells me everything I need to act on.

**Acceptance Criteria:**

**Given** a driver with a pending offer
**When** they read their session
**Then** the response carries that offer with pickup, dropoff, fare and distance to pickup (FR-21)

**Given** a driver on an active ride
**When** they read their session
**Then** the response carries that ride (FR-21)

**Given** a driver with neither
**When** they read their session
**Then** declared status and reachability are returned with no offer and no ride (FR-21)

**Given** the response
**When** it is assembled
**Then** it is delivered by one call, not several (FR-21)

**Given** the 2 s driver poll cadence
**When** it is compared with the 10 s offer timeout
**Then** poll is comfortably shorter, so an offer is never missed between polls (AD-46)

### Story 3.13: Ride gives up as NO_DRIVER

As a rider,
I want a definitive answer when no driver can be found,
So that I am not left waiting indefinitely on a ride that is never coming.

**Acceptance Criteria:**

**Given** a `WAITING_MATCH` ride whose accumulated seeking budget of 60 s is exhausted
**When** the sweep runs
**Then** the ride moves to terminal `NO_DRIVER`
**And** retrying stops (FR-12, AD-46)

**Given** the seeking budget
**When** it is accumulated
**Then** it counts time in `WAITING_MATCH` only
**And** never time spent `OFFERED` or `MATCHED` (AD-46)

**Given** a ride salvaged from a silent driver by Story 3.8
**When** its budget is evaluated
**Then** the time it spent `MATCHED` did not consume the budget
**And** the salvage path is therefore reachable rather than killed on the next sweep (AD-46)

**Given** the bounded window
**When** its justification is examined
**Then** it bounds an outcome the system can determine — "we looked and found nobody"
**And** it is never applied to a wait on external truth the system will eventually receive (AD-45)

**Given** the seeking budget
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by sleeping (NFR-9)

> **Scope boundary:** FR-12 also requires the authorization hold to be released on `NO_DRIVER`. The
> ride-side terminal state lands here; the void it stamps arrives in Epic 5 (FR-36, AD-44).

### Story 3.14: Rider cancels before the trip starts

As a rider,
I want to cancel any ride that has not yet started moving,
So that I have a way out right up until the moment the trip begins.

**Acceptance Criteria:**

**Given** a ride in `REQUESTED`, `WAITING_MATCH`, `OFFERED` or `MATCHED`
**When** the owning rider cancels
**Then** it moves to terminal `CANCELLED` (FR-16, AD-13)

**Given** a ride with an assigned driver
**When** it is cancelled
**Then** the driver is released back to `AVAILABLE` (FR-16)

**Given** a ride with an offer still outstanding
**When** it is cancelled
**Then** the offer is withdrawn (FR-16)

**Given** a ride already `IN_PROGRESS` or terminal
**When** cancel is attempted
**Then** it is refused as 409 (FR-16, AD-38)

**Given** a mismatched rider identity
**When** cancel is attempted
**Then** it is rejected as 404
**And** the response does not reveal whether the ride exists (FR-16, AD-38, AD-39)

**Given** the cancellation
**When** it is written
**Then** it is a guarded conditional update carrying the expected prior state and the acting rider
identity (AD-15)

**Given** a rider cancelling at the same instant the assigned driver accepts
**When** both commit
**Then** exactly one wins — whichever commits first
**And** the loser is rejected rather than both applying, because both are guarded transitions on one
ride (FR-17, AD-17)

> **Scope boundary:** FR-16 also requires any hold to be voided, including one that lands *after* the
> cancellation. That race is Epic 5's, resolved by AD-44's stamped `void_requested_at` rather than by
> anything this story can do without a payment to void.

### Story 3.15: Driver reads their ride history

As a driver,
I want to review the rides I have completed,
So that I can see the work I have done.

**Acceptance Criteria:**

**Given** a driver
**When** they query their history
**Then** their completed rides are returned most-recent-first
**And** the result is bounded by a result-size limit (FR-25)

**Given** rides completed by the system rather than by the driver
**When** history is read
**Then** they appear alongside driver-completed ones, matched by one status value (AD-18)

**Given** a driver identity
**When** history is requested
**Then** only that driver's own rides are returned (FR-25)

### Story 3.16: Rider watches the driver approach

As a rider,
I want to see who is coming, where they are, and when they will arrive,
So that waiting for my ride is something I can watch rather than guess at.

**Acceptance Criteria:**

**Given** a `MATCHED` ride
**When** the rider reads the live position
**Then** they receive the assigned driver's current position and an ETA to pickup (FR-6)

**Given** repeated reads
**When** the driver's heartbeats land
**Then** the position and the ETA both update (FR-6)

**Given** the live position endpoint
**When** it is served
**Then** it is separate from ride detail
**And** it returns coordinates **and** ETA from a single read of the location index (AD-40)

**Given** the response
**When** it is rendered
**Then** it carries the driver's display name and never their identifier (AD-39)

**Given** a ride not in `MATCHED`
**When** live position is requested
**Then** no position is returned
**And** it is not an error (FR-6)

> **CAP-13 (race-safe concurrency) has no story of its own, by policy.** Its proof is distributed
> across the stories whose behaviour it constrains: concurrent match attempts and the lost-race path
> in Story 3.5, concurrent accepts in Story 3.6, concurrent requests from one rider in Story 3.3, and
> the cancel-versus-accept race in Story 3.14. See *Testing policy* in the Overview.
>
> **Those races are proven by deterministic test scenarios, not by synthetic traffic.** A hand-written
> scenario — two threads, one ride, one latch, the `Clock` advanced deliberately — is *more*
> deterministic than a seeded generator, because it contains no randomness at all to reproduce. The
> Simulator arrives in Epic 7 for the opposite job: surfacing emergent behaviour under mixed load
> that nobody wrote a scenario for. The fixtures each of these stories needs ship inside that story,
> as every other test does.

## Epic 4: Event Backbone, Resilience & Operational Visibility — `v0.1`

Services stop calling each other for anything nobody is waiting on, failures degrade instead of
cascading, and the system's behaviour is visible on a dashboard rather than in logs. Surge starts
moving with real demand.

> **Properties CAP-23 and CAP-24 become standing acceptance criteria from this epic onward.** Neither
> is a story: retry-with-jitter, circuit breaking and dead-lettering attach to every producer and
> consumer story, and idempotency-on-`event_id` attaches to every consumer and externally-triggered
> handler for the rest of the project. See *Testing policy* in the Overview.

### Story 4.1: Domain events are recorded in a transactional outbox

As an operator,
I want every domain event written in the same transaction as the state change it describes,
So that the system can never commit a state change whose event was lost, nor emit an event for a change that never committed.

**Acceptance Criteria:**

**Given** a domain state change
**When** it commits
**Then** an `event_outbox` row is written in the same local transaction (AD-28, FR-31)

**Given** that transaction
**When** it rolls back
**Then** neither the state change nor the outbox row persists (AD-28)

**Given** domain code
**When** it is inspected
**Then** it never publishes to a transport directly — it only writes the outbox row (AD-28)

**Given** a location heartbeat
**When** it is handled
**Then** **no** outbox row is written, because pings are ephemeral telemetry rather than state
transitions and would put the hot-row write pattern back into Postgres (AD-28)

**Given** an outbox row
**When** its envelope is inspected
**Then** it carries `event_id`, `event_type`, `entity_type`, `entity_id`, `actor_type`, `actor_id`,
`schema_version`, `occurred_at` and `correlation_id` as columns
**And** `entity_type`/`entity_id` use the same vocabulary that `audit_events` will use, so one
vocabulary spans both tables (AD-31)

**Given** the payload
**When** it is written
**Then** it is the fully-formed event serialised as JSON at transaction time
**And** it is never the entity stored to be rendered later, since the relay runs seconds afterwards
by which point the ride may have moved on
**And** it never uses native Java serialisation, since consumers are independently built (AD-30)

**Given** the payload contents
**When** they are reviewed
**Then** the event carries what its consumers need inline and nothing more, because every extra field
becomes one that can never be removed (AD-32)

**Given** an event type
**When** it is declared
**Then** it is named `<entity>.<past-tense-action>` in lowercase
**And** its prefix matches `entity_type` (Event naming convention)

**Given** the `event_outbox` primary key
**When** it is declared
**Then** it is a `GENERATED ALWAYS AS IDENTITY` bigint, assigned from one authority rather than from
a replica's clock (AD-29)

### Story 4.2: Each service declares the topics it produces and consumes

As an operator,
I want every service to declare its topics and subscriptions in its own configuration, and the broker to be provisioned from those declarations,
So that adding a consumer is a configuration change in one service rather than a coordinated edit across several.

**Acceptance Criteria:**

**Given** the Compose stack
**When** this story lands
**Then** Kafka 4.3.1 in **KRaft mode** joins it, with no ZooKeeper
**And** it is Tier 1 infrastructure — required, never optional (AD-48, Stack)

**Given** the broker
**When** it is configured
**Then** `auto.create.topics.enable` is **false**
**And** a reference to an undeclared topic therefore fails loudly rather than silently creating a
phantom topic with default partitions

**Given** a service
**When** its configuration is inspected
**Then** it declares the topics it **produces** — each with partition count and replication factor —
and the topics it **subscribes** to, each with its consumer group

**Given** a topic
**When** its shape is declared
**Then** only the **producing** service declares partition count and replication factor
**And** a consuming service declares subscription alone, so two services can never disagree about a
topic's shape

**Given** a service starting
**When** a topic it owns is absent
**Then** it is created from the declaration
**And** starting a second time creates nothing and fails nothing

**Given** a new consumer of an existing stream
**When** it is added
**Then** the change is a subscription declaration in that service alone
**And** no producer and no other consumer is touched (FR-33, AD-36)

**Given** a topic that already exists
**When** a partition-count change is proposed
**Then** it is treated as breaking and requires a new topic rather than an edit
**And** this is because AD-36 keys by entity id for ordering, so re-mapping the hash would land one
entity's future events on a different partition from its past ones and break ordering silently (AD-33, AD-36)

**Given** a new consumer group
**When** it first starts
**Then** it starts at `earliest` (AD-36)

**Given** declared partition counts
**When** they are chosen
**Then** the reasoning is recorded alongside them
**And** the concrete values are confirmed by measurement under the stress run rather than guessed
now (AD-47)

**Given** the declarations
**When** the system later deploys to Kubernetes
**Then** they remain the single source for topic shape
**And** topic configuration is not reinvented as a second, divergent description in the manifests (AD-49)

**Given** integration tests exercising the event path
**When** they run
**Then** they run against the real Kafka in the Compose stack
**And** no embedded broker, in-memory substitute or mocked producer is used anywhere (AD-56, AD-10)

**Given** a test class
**When** it starts
**Then** it begins from a known broker state, so events published by an earlier class cannot be
consumed by a later one (AD-56)

> **Note on the division with AD-52.** The versioned contracts directory owns the event *schema* —
> what a `ride.matched` payload contains — and is copied into each service at build time. This
> per-service configuration owns the *wiring*: which topics this service produces to and subscribes
> to. One is the contract, the other is the deployment fact; keeping them apart is what lets a
> service change its subscriptions without touching a shared contract.

### Story 4.3: The relay ships outbox rows to Kafka

As an operator,
I want a relay that claims outbox rows and publishes them,
So that events reach consumers without the domain ever knowing a transport exists.

**Acceptance Criteria:**

**Given** claimable outbox rows
**When** the relay runs
**Then** it selects by claimability, locks with `FOR UPDATE SKIP LOCKED`, publishes, and **deletes**
each row on success (AD-29)
**And** how many rows are claimed per pass is left open — see the note below

**Given** the relay
**When** its progress tracking is inspected
**Then** it never advances a high-water-mark cursor such as `WHERE id > lastSeen` (AD-29)

**Given** a transaction holding a lower identity that commits *after* one holding a higher identity
**When** the relay next runs
**Then** the late-committing row is picked up
**And** it is never skipped permanently — which is exactly the dual-write loss the outbox exists to
prevent (AD-29)

**Given** `occurred_at`
**When** ordering is considered
**Then** it records when the event happened
**And** it is never used for ordering (AD-29)

**Given** a published event
**When** it reaches Kafka
**Then** the partition key is the entity id, so one entity's events never process out of order across
partitions (AD-36)

**Given** the transport
**When** it is wired
**Then** it sits behind the `EventTransport` Strategy (AD-10)

**Given** commands and reads that an actor waits on
**When** this epic lands
**Then** they remain synchronous gRPC calls and are never moved to the backbone, because the edge
services own no data and must reach `matching-service` for an id, a rejection or a result (FR-31, AD-37)

> **Open decision — claim size: batched or single-row.** Deliberately unsettled, to be taken when
> this story is detailed. AD-34 describes a batch that "commits once so successes persist regardless
> of neighbours"; the clause after *so* is the invariant, and the batch is one way of reaching it.
> Single-row processing reaches it structurally instead, at the cost of one transaction per event.
>
> Three inputs for that conversation. **Volume is small**: outbox traffic is state transitions only —
> roughly 10k/day at simulator load against ~1.3M/day of heartbeats, which deliberately bypass the
> outbox (AD-28) — so the throughput case for batching argues about a stream two orders of magnitude
> smaller than the one already excluded. **Batch-of-one is the degenerate case**: if claim size is a
> configuration value rather than a structural choice, single-row needs no separate code path and the
> decision becomes a tuning value settled by the stress run, like every other capacity number
> (AD-47). **The choice should be consistent across all three claim-loop workers** — matching
> (AD-20), this relay (AD-29/AD-34), and settlement (AD-58) share one shape, and three different
> answers would be three things to reason about instead of one.
>
> If a fixed single-row design is chosen rather than a configured claim size, AD-34's wording is
> the authority and should be amended, not silently diverged from.

### Story 4.4: Outbox rows retry, dead-letter, and respect the breaker

As an operator,
I want one poison event to be unable to stall the stream and a brief outage to be unable to mass-dead-letter the backlog,
So that a single bad message never becomes a total outage.

**Acceptance Criteria:**

**Given** a row that publishes successfully
**When** the batch commits
**Then** that row is deleted (AD-34)

**Given** a row that fails to publish
**When** the attempt completes
**Then** `tries` increments and `next_attempt_at` is pushed out by exponential backoff **with jitter**
(AD-34, FR-32, NFR-3)

**Given** a pass in which some rows publish and others fail
**When** their outcomes are applied
**Then** each row's outcome persists independently of its neighbours'
**And** one failing row never rolls back, discards or skips another row's success — however many rows
a pass claims (AD-34)

**Given** a row reaching the retry cap
**When** it is evaluated
**Then** `dead_at` is stamped
**And** the row stops being claimed (AD-34)

**Given** the circuit breaker
**When** it is evaluated
**Then** it is checked **before touching the database**, so rows that were never fetched cannot have
their `tries` incremented by an outage (AD-34)

**Given** a half-open breaker
**When** the relay probes
**Then** it probes with a deliberately limited volume rather than a full pass, so a still-broken
dependency costs a handful of attempts rather than a backlog's worth (AD-34)

> **Open decision.** This story's retry bookkeeping is per row either way, but whether a pass claims
> one row or many is settled together with Story 4.3 — see the open-decision note there.

**Given** dead rows
**When** they are observed
**Then** they are tracked by their own gauge which alerts, rather than being deleted (AD-34, AD-35)

### Story 4.5: The outbox sheds when its backlog ages

As an operator,
I want new ride requests refused once the outbox backlog has stopped moving,
So that the system never silently accumulates unpublished truth while appearing healthy.

**Acceptance Criteria:**

**Given** a backlog past its bound
**When** a new ride request arrives
**Then** it is shed with `UNAVAILABLE` → 503 rather than accepting work the system cannot record
(AD-35, AD-38)

**Given** the shed trigger
**When** it is evaluated
**Then** it is backlog **age**, not depth — a large backlog draining healthily is fine, while a small
one that has not moved is broken (AD-35)

**Given** the age metric
**When** it is computed
**Then** it counts only claimable rows (`dead_at IS NULL`) (AD-35)

**Given** a single dead-lettered poison event
**When** the age metric is read
**Then** it does not age the backlog
**And** one bad message therefore cannot shed every ride request permanently (AD-35)

**Given** a shed request
**When** metrics are examined
**Then** it is **not** counted as an admission refusal — a 503 keeps its own signal, separate from
the 409 refusal counter (AD-54)

**Given** the bound
**When** it is derived
**Then** it is arrival rate × the outage duration to ride out
**And** drain rate exceeds arrival rate with headroom (AD-35, AD-47)

**Given** every queue in the request path
**When** it is observed
**Then** it exposes depth and age as gauges, and the tightest bound sits at HAProxy where there is
visibility, so overflow sheds rather than piling up inside the JVM (AD-6, AD-54)

### Story 4.6: Driver location events reach matching-service over the backbone

As a driver,
I want my position to reach the matching system over the event backbone,
So that the two services stop calling each other for something nobody is waiting on.

**Acceptance Criteria:**

**Given** a heartbeat
**When** `driver-service` handles it
**Then** it publishes a location event **directly** to the backbone, not through the outbox (AD-28)

**Given** that event
**When** it is produced
**Then** the timestamp is stamped at produce time
**And** no consumer re-stamps it on receipt, so consumer lag cannot make a vanished driver look
freshly heard (AD-23)

**Given** the event
**When** it is published
**Then** the partition key is the driver id (AD-36)

**Given** `matching-service` consuming location events
**When** an event arrives
**Then** it is deduplicated on `event_id` (AD-36, NFR-4)

**Given** a redelivered event
**When** it is processed again
**Then** nothing changes, because at-least-once delivery makes duplicates normal rather than
exceptional (NFR-4, CAP-24)

**Given** a consumer that cannot process a message
**When** it retries
**Then** backoff is **jittered**, never synchronised — or every replica retries in lockstep and
hammers the dependency in waves (AD-55)

**Given** retries exhausted
**When** the message is finally handled
**Then** it is routed to a dead-letter topic
**And** it neither blocks its partition nor is silently discarded (AD-55)

**Given** dead-lettered volume
**When** it is observed
**Then** it is a metric that alerts and is zero in health (AD-55)

**Given** a new consumer group
**When** it starts
**Then** it starts at `earliest` (AD-36)

**Given** the gRPC location forward introduced in Story 2.3
**When** this story lands
**Then** it is removed
**And** the `DriverLocationIndex` seam is unchanged — only the transport behind it (AD-10)

### Story 4.7: Redis serves the matchable geo index and every driver's position

As a rider,
I want driver positions served from a store built for proximity search,
So that matching stays fast and my live driver position stays sub-second as the system grows.

**Acceptance Criteria:**

**Given** the `DriverLocationIndex` Strategy
**When** its implementation is swapped to Redis
**Then** no caller changes
**And** no caller branches on which implementation is active or special-cases its behaviour (AD-10, AD-57)

**Given** Redis
**When** its structures are inspected
**Then** there are two with deliberately different populations: a geo set holding **matchable drivers
only**, searched with `GEOSEARCH`; and a per-driver position key covering **every** driver, TTL'd
longer than the longest staleness window (AD-26)

**Given** a heartbeat
**When** it is applied
**Then** it performs two pipelined Redis writes
**And** **zero** Postgres writes (AD-26)

**Given** a stale member encountered during a geo search
**When** it is found
**Then** it is removed lazily on encounter rather than by an eager sweep (AD-26)

**Given** a driver whose position key is absent
**When** staleness is evaluated
**Then** absence is **never** evidence of staleness and no sweep acts on a missing key
**And** this is because Redis holds no persistence, so after a restart every key is absent while the
drivers behind them are perfectly healthy — treating that as staleness would auto-complete and
capture every in-flight ride at once (AD-26, AD-27)

**Given** Redis deployment
**When** it is configured
**Then** it is one instance with no cluster, no persistence, and `noeviction` with generous headroom
**And** it is never a source of truth (AD-27)

**Given** a candidate selected from the geo set
**When** a state change is decided
**Then** the decision is made by a conditional `UPDATE` against the owning row
**And** the loser re-searches, because a cache may only select candidates (AD-4)

**Given** position reads
**When** they are served
**Then** they are sub-second and decoupled from durable persistence (FR-28)
**And** the rider's live position endpoint returns coordinates **and** ETA from a single Redis read (AD-40)

### Story 4.8: Surge is recomputed from live demand

As a rider,
I want the fare multiplier to reflect how busy the system actually is,
So that the price I am quoted moves with real conditions rather than sitting at a constant.

**Acceptance Criteria:**

**Given** the surge multiplier
**When** the system runs from this phase onward
**Then** it is recomputed periodically from the ratio of outstanding ride requests to available
drivers (FR-19)

**Given** the surge scheduler
**When** it is placed
**Then** it lives in `matching-service`'s `dispatch` package, which sits above both `ride` and `fare`
(AD-9)

**Given** a recomputation
**When** it happens
**Then** the acting actor is recorded as `SYSTEM` (FR-41 groundwork)

**Given** the current multiplier
**When** it is observed
**Then** it is exposed as an operational metric (FR-19, FR-46)

**Given** a fare
**When** it is computed at request time
**Then** it uses the multiplier current at that moment
**And** it is never recomputed later, so a surge change between quote and request moves the price
while a change after request does not (FR-18, FR-1)

**Given** the scope of surge
**When** it is examined
**Then** it is one ratio-derived multiplier and nothing more — no demand forecasting, no machine
learning, no per-rider or time-of-day pricing, and no geographic granularity (Non-goals, Deferred)

**Given** the recomputation interval
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by sleeping (NFR-9)

### Story 4.9: Operational dashboards show the fixed KPIs

As an operator,
I want the system's behaviour visible on a dashboard with its key definitions fixed,
So that I can see what is happening without log-diving, and two people reading the same panel mean the same thing.

**Acceptance Criteria:**

**Given** the stack
**When** it runs
**Then** Prometheus scrapes every service and Grafana renders dashboards
**And** match latency, throughput and error rates are visible without log-diving (FR-46, NFR-5)

**Given** **match latency**
**When** it is defined
**Then** it is the time from request to a driver **accepting** — what the rider actually waits
**And** it is reported alongside time-to-**first-offer**, because the gap between them separates a
matching problem from drivers ignoring offers (FR-46, AD-54)

**Given** **drivers online**
**When** it is defined
**Then** it counts only *matchable* drivers — declared available **and** currently reachable
**And** never everyone whose last declared status was available (FR-46, AD-54)

**Given** the dashboards
**When** they are assembled
**Then** they also show ride throughput, the current surge multiplier, rides awaiting authorization,
undelivered-event backlog age, and error rates (FR-46)

**Given** every queue, pool and backlog named in AD-6, AD-34 and AD-35
**When** it is observed
**Then** each exposes depth and age as gauges, because an unmeasured bound cannot be tuned (AD-54)

**Given** a request crossing services
**When** it is traced
**Then** the correlation id minted at the gateway is propagated over gRPC metadata, carried in the
event envelope, and present in every log line and error response — so a trace does not end where the
request does (AD-54, AD-31)

**Given** each metric
**When** its source is chosen
**Then** durable state is read from the owning table, process-local state from the process, and
events as in-process monotonic counters
**And** no record is ever persisted solely to make it countable (Metrics convention)

> **Scope boundary:** three panels named by FR-46 cannot be populated yet and land with the data that
> feeds them. **Payment success rate and the two money gauges** — capture loss, and the age of the
> oldest capture still retrying — arrive in Epic 5, as do the second and third refusal reasons.
> **Audit ingest rate** arrives in Epic 6. *Rides awaiting authorization* is built here and reads a
> constant zero until Epic 5, since the immediate-authorise gateway never leaves a ride waiting —
> which is itself the correct reading for this phase.

## Epic 5: Payments — `v0.2`

A rider's fare is held when they request and taken when they arrive; a ride that delivers no trip
never costs them anything; and money that is genuinely lost is known rather than buried.

> **Capacity warning carried from `roadmap.md`.** This phase was sized before the two-phase lifecycle
> and both failure paths landed — it grew from five capabilities to seven. That is why rider
> accounts, debtor standing, partial refunds and rider-initiated refund requests stay deferred rather
> than being folded in. Resist adding to it.

### Story 5.1: payment-service and the payment state machine

As an operator,
I want `payment-service` running with its lifecycle declared as an explicit transition table,
So that four services never read payment outcomes with four different vocabularies.

**Acceptance Criteria:**

**Given** the Compose stack
**When** it is brought up
**Then** `payment-service` starts with its own private Postgres database (AD-1, AD-3)
**And** it exposes health and Prometheus metrics like every other service (AD-54)
**And** it carries its own Gradle wrapper and the standard package layout (AD-7, AD-52)

**Given** the `payments` table
**When** it is created
**Then** it holds exactly one payment per ride, enforced by a unique `ride_id`
**And** the provider's intent identifier is a **column**, named for the domain concept rather than
the provider, because the provider is swappable (AD-3, AD-50)

**Given** the payment lifecycle
**When** it is declared
**Then** it is an enum plus an allowed-transitions map: `INITIATED → AUTHORIZED → CAPTURED →
REFUNDED`, plus terminals `VOIDED`, `FAILED` and `CAPTURE_FAILED`
**And** illegal transitions raise rather than no-op, which is what makes a replayed provider webhook
safe (FR-35, AD-11, AD-50)

**Given** `CAPTURED`
**When** its meaning is asserted
**Then** it means the money moved
**And** the payment stays `AUTHORIZED` for the whole capture pursuit including every retry, so there
is no `CAPTURING` state to invent
**And** retry bookkeeping is columns on the row, never a state (AD-50)

**Given** `FAILED` and `CAPTURE_FAILED`
**When** their separation is questioned
**Then** they remain distinct states — a deliberate divergence from AD-18's fold-provenance-into-a-column
rule, because they differ in **kind**: `FAILED` is a ride that never happened and cost nothing, while
`CAPTURE_FAILED` is a delivered trip whose money is gone
**And** only the second is revenue loss, so collapsing them would hide a loss inside a non-loss (AD-50)

**Given** `CAPTURE_FAILED`
**When** its finality is asserted
**Then** it is terminal for this build
**And** leaving it later would be a state-machine change — a newly declared transition or a second
payment for the ride — never a background job added quietly (AD-50)

**Given** any payment state entry, `INITIATED` included
**When** it commits
**Then** an event is written to `payment-service`'s own transactional outbox in the same transaction
**And** no payment state exists that a downstream projection could never hear about (AD-28, AD-59)

**Given** monetary values
**When** they are handled
**Then** they are integer minor units in transit and `DECIMAL` at rest, never floating point (Money convention)

### Story 5.2: Provider strategy and a stub that can produce every outcome

As an engineer,
I want the provider behind a strategy whose stub can produce every settlement outcome on demand,
So that the stress run and credential-free test runs exercise the real payment path rather than code that exists only in tests.

**Acceptance Criteria:**

**Given** the two strategy layers
**When** they are placed
**Then** `matching-service` holds the outbound `PaymentGateway` — call `payment-service`, or signal
authorised immediately
**And** `payment-service` separately holds the `PaymentProvider` — real provider, or stub (AD-43)

**Given** the provider strategy
**When** its outcome set is defined
**Then** it carries the **three-way capture answer** — settled, uncapturable, or neither —
**and the two-way void answer** — released, or the provider reporting the hold already gone (AD-43, AD-58)

**Given** the stub
**When** it is exercised
**Then** it can produce every one of those outcomes on demand
**And** without that, the retry-until-settled path, `CAPTURE_FAILED` and the durable void are never
exercised outside production (AD-43)

**Given** the stub and the real provider
**When** either is active
**Then** no caller inspects the concrete type or branches on which is in use
**And** the stub delivers its outcome through the **same channel** as the real one, merely faster — a
stub resolving synchronously where the provider resolves asynchronously would mean the stress run
never exercises the asynchronous path (AD-57)

**Given** the NFR-2 stress run
**When** payments are excluded from it
**Then** the exclusion is achieved by swapping the **provider**, keeping the whole payment state
machine in the flow
**And** never by skipping the payment path, which would stress-test code that does not exist in
production (NFR-8, AD-43)

**Given** no provider credentials configured in the environment
**When** the full suite runs
**Then** it runs against the stub and passes
**And** a fresh clone is therefore testable without obtaining sandbox credentials first (AD-43, NFR-10)

**Given** provider API keys
**When** they are configured
**Then** they live in environment configuration
**And** never in source, fixtures, or manifests (NFR-10, AD-49)

**Given** the real provider
**When** it is wired
**Then** it is the Stripe Java SDK 33.3.x against the sandbox, and no real card data exists anywhere (Stack, NFR-10)

### Story 5.3: Authorization gates dispatch, asynchronously

As a rider,
I want my fare held before any driver is dispatched, and my ride identifier back immediately anyway,
So that no driver drives toward me for a ride whose funds nobody holds, and I never wait on a payment provider to learn my ride exists.

**Acceptance Criteria:**

**Given** the ride request
**When** the payment-method token joins its contract
**Then** the token is **required**, and is a dedicated value type whose `toString()` is masked
**And** it is never persisted, never echoed in a response, and never written to a log
**And** it passes through to `payment-service` as the only component that talks to the provider (FR-2, AD-42, NFR-10)

**Given** the token
**When** a leak test runs
**Then** it appears in no log line, no response body, and no stack trace (NFR-10)

**Given** deliberately-declining test tokens
**When** they are used
**Then** the authorization-failure path is exercised routinely rather than theoretically
**And** they are supplied as test fixtures here; the Simulator exercises the same path under load in
Epic 7 (FR-9, FR-49)

**Given** `matching-service`'s `PaymentGateway`
**When** this story lands
**Then** it calls `payment-service` instead of signalling authorised immediately
**And** the immediate-authorise implementation is retired from runtime, remaining only for test runs
that exclude payments and for phases before `payment-service` existed
**And** it is never selectable as a degradation for a payment outage, which would dispatch rides
against funds nobody holds (AD-43)

**Given** a ride request
**When** it is admitted
**Then** the ride is persisted `REQUESTED` and its identifier returned immediately
**And** authorization proceeds asynchronously from there (AD-41, FR-9, FR-34)

**Given** the authorization outcome
**When** it resolves
**Then** a landed hold moves the ride to `WAITING_MATCH`
**And** a decline moves it to terminal `PAYMENT_FAILED`, and no driver is ever offered it (FR-9)

**Given** the handler applying that outcome
**When** it runs
**Then** it performs exactly one guarded transition
**And** that alone makes it idempotent, with no dedupe table required (AD-41)

**Given** a ride awaiting authorization
**When** it waits
**Then** it has **no timeout at all**
**And** it is surfaced as a metric and an alert, with the rider's own cancellation as the exit
**And** this is because a timeout would free the rider to request again while their first hold is
outstanding, doubling holds across every stuck rider against a rate-limited provider exactly when the
system can least cope (AD-45, FR-9)

**Given** a rider whose ride is stuck awaiting authorization
**When** they request another
**Then** the one-active-ride rule refuses them, which throttles load naturally during an outage (AD-45, FR-3)

**Given** `payment-service` unavailable
**When** rides are requested
**Then** requests still succeed and rides stall before dispatch
**And** `payment-service` is therefore Tier 2, never promoted onto the Tier 1 request path (AD-48)

### Story 5.4: Ride completion durably triggers capture

As an operator,
I want capture driven by a durable worker triggered by the completion event rather than an inline call,
So that a pod dying mid-pursuit resumes the capture instead of silently writing off a valid hold.

**Acceptance Criteria:**

**Given** a `ride.completed` event
**When** `payment-service` consumes it
**Then** it **stamps** `capture_requested_at` on the payment
**And** ride completion never calls capture inline, so the worker never joins across a service
boundary to learn a ride is `COMPLETED` (AD-58, AD-1)

**Given** that stamp
**When** it is written
**Then** it is a guarded update keyed on the unique `ride_id`, conditioned
`WHERE status = 'AUTHORIZED' AND capture_requested_at IS NULL`
**And** a redelivered `ride.completed` is a no-op that **never resets `attempts` or `next_attempt_at`**,
because redelivery restarting the backoff would reopen the request-rate hole the ceiling closes (AD-58)

**Given** the first stamp
**When** it succeeds
**Then** it also sets `attempts = 0` and `next_attempt_at = :now`, bound from the `Clock` strategy
**And** leaving `next_attempt_at` NULL would make the row never claimable, so nothing would capture
at all while the gauge read a healthy zero (AD-58)

**Given** a stamp affecting zero rows
**When** it is handled
**Then** it means the hold has already ended — a legitimate no-op
**And** it is neither treated as AD-15's rejection nor dead-lettered (AD-58)

**Given** the capture worker
**When** its claim predicate is written
**Then** it is literally `status = 'AUTHORIZED' AND capture_requested_at IS NOT NULL AND
next_attempt_at <= :now`, where `:now` is bound from the `Clock` strategy
**And** dropping the `capture_requested_at` term would capture every hold the moment it is
authorised — before dispatch, on rides that never happened (AD-58)

**Given** the worker
**When** it runs
**Then** it is a durable claim-loop inside `payment-service` claiming with `FOR UPDATE SKIP LOCKED`,
attempting, and re-claiming (AD-58, AD-20)

**Given** retry state
**When** it is stored
**Then** it is columns on the `payments` row — `capture_requested_at`, `attempts`, `next_attempt_at` —
never an in-memory schedule or a delayed task in a running process
**And** a restart therefore resumes the pursuit rather than forgetting it (AD-58, FR-37)

**Given** the circuit breaker
**When** it is evaluated
**Then** it is checked **before claiming**, so an open breaker leaves rows due and untouched rather
than burning attempts on a dependency known to be down (AD-58, AD-34)

**Given** every `now()` in a claim or backoff predicate
**When** it is written
**Then** it is a bind parameter from the `Clock` strategy, never SQL `now()`
**And** otherwise the ceiling and the cooldown could only be tested by waiting, which the Testing
convention forbids and which means the tests that would catch a broken predicate never get written
(AD-58, NFR-9)

### Story 5.5: Capture outcomes are classified and unrecoverable loss is counted

As an operator,
I want every capture attempt classified by what the provider actually said, and genuine loss counted,
So that a slow provider is never mistaken for lost money, and lost money is never buried.

**Acceptance Criteria:**

**Given** a capture attempt
**When** the provider answers
**Then** it classifies exactly three ways: settled → `CAPTURED`; the hold uncapturable →
`CAPTURE_FAILED`; **anything else — unreachable, timeout, 5xx, rate-limited, ambiguous — is not an
outcome and reschedules** (AD-58, AD-50)

**Given** an answer classified as **uncapturable**
**When** it is examined
**Then** the provider has reported the hold **expired**, **revoked**, or **cancelled** — one of those
three, and nothing else qualifies
**And** the system therefore holds a positive statement from the provider rather than an inference
from silence, which is what makes this state terminal rather than a guess (FR-37, AD-50)

**Given** an ambiguous answer
**When** it is classified
**Then** it always reschedules
**And** only an explicit provider verdict is ever terminal (AD-58)

**Given** a provider answering *already captured*
**When** it is classified
**Then** it counts as **settled**, never as ambiguity
**And** read as ambiguity it would be pursued forever and alerted as a loss it is not (AD-58)

**Given** unbounded retries
**When** an attempt is made
**Then** it carries an idempotency key derived from the payment id
**And** a retry after an ambiguous answer therefore cannot capture twice (AD-58)

**Given** backoff
**When** it is applied
**Then** it is exponential with jitter up to a ceiling, then **flat at that ceiling indefinitely**
**And** the bound moves from the *number* of attempts to the *interval* between them, so an unbounded
pursuit still presents a bounded request rate (AD-58, FR-37)

**Given** a capture that cannot proceed
**When** its handling is examined
**Then** there is **no retry cap and therefore no dead-letter path** — AD-34's cap-then-dead-letter
explicitly does not apply here
**And** the row simply stays claimable, watched by the oldest-retrying gauge instead (AD-58, AD-55)

**Given** a payment reaching `CAPTURE_FAILED`
**When** the ride is examined
**Then** the ride stays `COMPLETED`, because the trip did happen
**And** it is never routed to the ride machine's `PAYMENT_FAILED`, which would drop those rides out of
every "completed rides" query (AD-50)

**Given** the transition to `CAPTURE_FAILED`
**When** it is recorded
**Then** `capture_failed_at` is stamped at the transition
**And** it is carried in the `payment.capture_failed` payload (AD-58, AD-32)

**Given** **capture loss**
**When** it is exported
**Then** it is `count(*)` and `sum(amount)` over `CAPTURE_FAILED`, read from the `payments` table
rather than an in-process counter that resets with the pod
**And** it is summed from the stored `DECIMAL` and exported in integer minor units
**And** it is zero in a healthy system and alerts on its own (AD-54, Metrics convention)

**Given** **the age of the oldest capture still retrying**
**When** it is exported
**Then** it is `max(:now − coalesce(capture_requested_at, void_requested_at))` in seconds — with
`:now` bound from the `Clock` strategy, so the gauge itself is assertable under a controlled clock — over
claimable rows — **coalesced, because a void stuck against a broken provider is a live AD-44 breach
that a capture-only gauge cannot see**
**And** it **includes rows an open breaker has left untouched**, since excluding them flattens the
gauge through exactly the outage it exists to lead (AD-54)

**Given** **payment success rate**
**When** it is exported
**Then** it is the proportion of payments reaching a settled outcome against those that did not,
derived from the `payments` table alongside the money gauges
**And** it is throughput rather than money, so it is read and alerted separately from capture loss
(FR-46, AD-54, Metrics convention)

**Given** the two gauges
**When** they are read during an outage
**Then** oldest-retrying is the leading indicator and the one to watch
**And** capture loss only ever confirms the other gauge was read too late, because by the time a
payment is `CAPTURE_FAILED` the money is already unrecoverable (AD-54)

### Story 5.6: Holds are released on rides that delivered no trip

As a rider,
I want any hold released when my ride ends without a trip,
So that I am never left with money reserved against a ride that delivered nothing.

**Acceptance Criteria:**

**Given** a ride reaching `CANCELLED` or `NO_DRIVER`
**When** the event is consumed
**Then** it stamps `void_requested_at` on the payment, guarded
`WHERE status IN ('INITIATED','AUTHORIZED') AND void_requested_at IS NULL`
**And** it sets the same two scheduling columns, so a void is exactly as restart-proof as a capture (AD-44, AD-58)

**Given** a cancellation landing while authorization is still in flight
**When** the authorization later resolves to `AUTHORIZED`
**Then** it does **not** void inline — it leaves the stamped row for the worker
**And** the invariant therefore survives a restart rather than depending on an in-process call
completing (AD-44)

**Given** a *declined* authorization
**When** it resolves against a stamped void
**Then** it resolves to `FAILED` regardless of the stamp, because there is no hold to release
**And** `VOIDED` is therefore reachable only from `AUTHORIZED` (AD-44)

**Given** a `COMPLETED` ride
**When** payment is considered
**Then** it never stamps a void — a delivered trip is captured
**And** the two stamps are mutually exclusive because the ride states writing them are: `COMPLETED`
stamps capture, `CANCELLED` and `NO_DRIVER` stamp void (AD-44, AD-58)

**Given** the settlement worker
**When** it claims voids
**Then** it claims `status = 'AUTHORIZED' AND void_requested_at IS NOT NULL AND next_attempt_at <= :now`,
with `:now` bound from the `Clock` strategy
**And** it is the same worker on the same ceiling as capture (AD-58)

**Given** a void attempt
**When** the provider answers
**Then** it classifies **two** ways: released → `VOIDED`; and **the provider reporting the hold
already gone also settles as `VOIDED`**, because the outcome the void wanted has been achieved
**And** there is no `VOID_FAILED` to invent; anything else reschedules (AD-58)

**Given** the system
**When** stranded-looking holds are considered
**Then** there is **no automatic voiding sweep** — a hold that merely looks stale may belong to a
genuinely long live trip, and voiding it would leave a completed ride with nothing to capture
**And** the worker acts only on an explicit stamp written by a terminal ride event, never on a hold's
age (AD-44)

**Given** any hold
**When** its possible endings are enumerated
**Then** there are exactly three — captured, voided, or uncapturable
**And** a hold never sits in limbo, which is asserted in tests and monitored by an alert (AD-44)

### Story 5.7: Provider webhooks are verified and applied once

As an operator,
I want provider callbacks trusted only when authentic and applied only once,
So that a forged payload changes nothing and a redelivery cannot advance a payment twice.

**Acceptance Criteria:**

**Given** an inbound webhook
**When** it arrives
**Then** the gateway routes it to `payment-service`'s webhook endpoint, which is one of the four
routes the gateway exposes (AD-5)

**Given** a webhook payload
**When** it is received
**Then** its signature is verified
**And** a forged payload is rejected, proven by a test rather than by inspection (FR-38)

**Given** the `webhook_events` table
**When** it is created
**Then** it uses the provider's event id as the dedupe key, alongside type and payload (AD-3)

**Given** the same provider event delivered twice
**When** the second arrives
**Then** it is deduplicated and changes nothing
**And** this is proven by a test (FR-38, NFR-4, AD-36)

**Given** a replayed webhook attempting an illegal transition
**When** it is applied
**Then** the state machine raises rather than no-ops, which is the second line of defence behind the
dedupe (AD-11, AD-50)

### Story 5.8: A captured payment can be refunded

As an operator,
I want a completed, captured payment refundable end to end,
So that money taken in error can be given back even though riders cannot request it themselves.

**Acceptance Criteria:**

**Given** a `CAPTURED` payment
**When** an internal operator-facing call issues a refund
**Then** the refund is issued against the provider
**And** the payment moves to `REFUNDED` (FR-39, AD-50)

**Given** the refund webhook
**When** it arrives
**Then** it is processed idempotently and the result reconciles (FR-39)

**Given** the trigger
**When** it is examined
**Then** there is **no rider-facing way to request a refund** — it is internal only, exercised by
tests and the Simulator (FR-39, Non-goals)

**Given** the refund action
**When** it is recorded
**Then** the acting actor type is `Admin`, which distinguishes an operator-triggered action from a
`SYSTEM` one and is not an authenticated role (Glossary, FR-41 groundwork)

**Given** a ride auto-completed by the system
**When** an operator chooses to refund it
**Then** the refund succeeds through the same path, since `completed_by` is a column rather than a
distinct state (AD-18, FR-14)

**Given** `CAPTURED`
**When** its exits are examined
**Then** a deliberate refund is the only one (AD-50)

### Story 5.9: Reconciliation surfaces what delivery missed

As an operator,
I want missed webhook deliveries and implausibly long-lived holds surfaced,
So that they are caught rather than accumulating silently.

**Acceptance Criteria:**

**Given** a reconciliation task
**When** it runs
**Then** it detects missed or failed webhook deliveries (FR-40)

**Given** a hold outstanding longer than a ride can plausibly live
**When** reconciliation runs
**Then** it is flagged (FR-40)

**Given** either finding
**When** it is surfaced
**Then** **neither is corrected automatically**
**And** this is because a hold that looks stranded may belong to a genuinely long trip, and voiding
it would invent an answer the system does not have (FR-40, AD-44)

**Given** a `CAPTURE_FAILED` payment
**When** reconciliation encounters it
**Then** it is not recovered — the state is terminal for this build
**And** recovering one later is a state-machine change rather than an extension of this task (AD-50)

> **Open decision — the reconciliation mechanism.** To be settled when this story is detailed. This
> story is deliberately thinner than its neighbours because **its source is**: AD-50, AD-58 and AD-59
> specify the payment machine, the settlement worker and the projection down to literal predicates,
> while reconciliation has no AD of its own — FR-40 and one clause of AD-44 are the entire input.
>
> Five questions to answer, and one constraint that binds whatever the answers are:
>
> 1. **Schedule.** What cadence, and driven by the `Clock` so it is testable without waiting (NFR-9)?
> 2. **Missed-webhook detection.** Poll the provider's event list forward from a durable watermark, or
>    compare local `payments` against provider intents? The first needs a stored watermark and
>    inherits AD-29's cursor hazard; the second costs work proportional to open payments.
> 3. **"Longer than a ride can plausibly live."** Derived from AD-46's ordered set — the `IN_PROGRESS`
>    staleness window is already the system's bound on a single trip — or a new constant? If new, it
>    **joins that ordered set and is ordered against it**, never chosen alone (AD-46).
> 4. **What "flagged" means concretely** — a gauge, an alert, a table, or an operator-facing query.
> 5. **Which holds this actually covers.** AD-54's oldest-retrying gauge already watches rows with
>    `capture_requested_at` or `void_requested_at` stamped. A hold with **neither** — an `AUTHORIZED`
>    payment whose ride never reached a terminal state — is watched by nothing, and is precisely the
>    stranded case FR-40 describes. Scope this to that gap rather than duplicating the gauge.
>
> **The binding constraint: reconciliation observes and never corrects** (FR-40, AD-44). It must not
> duplicate or race AD-58's settlement worker, and it must not void a hold that merely looks stale —
> that hold may belong to a genuinely long live trip, and voiding it would leave a completed ride with
> nothing to capture.
>
> **If the answer introduces mechanism, it likely belongs in the spine as an AD** rather than living
> only here — the same gap AD-47 has with storage ceilings.

### Story 5.10: Rider sees how the money ended

As a rider,
I want to see how the money ended on a finished ride,
So that the payment outcome is something I can read rather than infer.

**Acceptance Criteria:**

**Given** a `COMPLETED` ride whose payment settled
**When** the rider reads it
**Then** they see the final fare and whether it was **captured** or **refunded** (FR-7)

**Given** a `COMPLETED` ride whose payment reached `CAPTURE_FAILED`
**When** the rider reads it
**Then** they see that it was left uncaptured (FR-7, FR-37)

**Given** a `PAYMENT_FAILED` ride
**When** the rider reads it
**Then** they see that authorization was declined (FR-7)

**Given** a capture still being retried
**When** the rider reads the ride
**Then** **no outcome is reported yet** — a retry in progress is not an outcome
**And** one appears only once the payment settles (FR-7)

**Given** a rider whose trip was auto-completed and then charged
**When** they look for a remedy
**Then** there is none in the system — no way to contest, no review workflow, no dispute status
**And** this is stated deliberately rather than omitted (Non-goals, FR-14)

### Story 5.11: Ride admission reads the payment-settlement projection

As an operator,
I want ride admission to check a rider's outstanding money against a local projection rather than a live call,
So that one rider cannot stack unlimited unpaid trips, without making ride requests impossible during a payment outage.

**Acceptance Criteria:**

**Given** the three refusal grounds
**When** they are evaluated
**Then** AD-14's one-active-ride index is evaluated **first**, always yielding `ALREADY_EXISTS` → 409
**And** the two payment refusals apply only when no active ride exists (AD-59)

**Given** both payment refusals
**When** they select the ride to read
**Then** both read the rider's **most recent ride**, ordered by the `rides` identity bigint
**And** earlier rides are never considered, or one stuck hold would refuse a rider forever (AD-59)

**Given** the two arms
**When** they are ordered
**Then** arm 1 is evaluated before arm 2, and both read that same anchor ride and its single payment row
**And** exactly one reason is emitted per refused request, so the label set is a true partition rather
than an arbitrary pick between two matching arms (AD-59)

**Given** arm 1 — a most-recent ride `COMPLETED` with its payment still `AUTHORIZED`
**When** a new request arrives
**Then** it is refused until the payment settles, **or until the session-expiry bound lapses measured
from `capture_requested_at`, whichever comes first**
**And** the bound is not optional: capture pursuit is unbounded, so "until it settles" alone would
refuse every rider who completed a trip for the entire length of a provider outage (FR-51, AD-59)

**Given** a most-recent ride that is `CANCELLED`, `NO_DRIVER` or `PAYMENT_FAILED`
**When** a new request arrives
**Then** it is **never** refused on arm 1, because that hold is being voided rather than captured
**And** refusing there would revoke AD-45's promise that the rider's own cancellation is their exit (AD-59)

**Given** arm 2 — the anchor ride's payment reached `CAPTURE_FAILED` inside the 30-minute cooldown
**When** a new request arrives
**Then** it is refused until the window lapses on its own
**And** the window is measured on wall clock from the recorded `capture_failed_at`, restarted by each
new failure and never stacked (FR-51, AD-59, AD-46)

**Given** the facts both arms read
**When** `matching-service` obtains them
**Then** it reads a **local read-model projection fed by the payment topic**
**And** never a synchronous gRPC call on the request path, which would turn a `payment-service`
outage from "rides stall before dispatch" into "no ride can be requested at all" (AD-59, AD-48)

**Given** the projection
**When** a row is absent
**Then** it means "no reason to refuse", **never** "refuse"
**And** cold start, rebuild and consumer lag therefore degrade to AD-14 alone rather than refusing
every rider at once (AD-59, AD-26)

**Given** the projection's shape
**When** it is defined
**Then** it is one row per `ride_id` — `(ride_id, payment_status, capture_requested_at,
capture_failed_at, last_event_id)` — joined to the local `rides` table on read
**And** no `rider_id` is added to payment payloads by a contract change nobody owns (AD-59)

**Given** the projection
**When** it must be rebuilt
**Then** replaying the payment topic from `earliest` reconstructs it (AD-59, AD-36)

**Given** `capture_failed_at`
**When** the projection stores it
**Then** it is stored **verbatim** from the event payload
**And** the projection never substitutes its own receipt time, or a replay-from-`earliest` rebuild
would re-date every historical failure to now and refuse that whole rider population at once (AD-58)

**Given** each of the three refusals
**When** it surfaces
**Then** it carries a reason token in the gRPC error detail, rendered by the façade as a distinct,
stable RFC 9457 `type` URI whose final segment is that same token
**And** `detail` prose is never the discriminator, since `FAILED_PRECONDITION` → 409 alone cannot tell
the two payment refusals apart (AD-59, AD-38)

**Given** those tokens
**When** they are compared with the metric labels
**Then** they are the **same values** AD-54 counts by
**And** a reason added, split or renamed changes both together, so the API and the metric can never
disagree about why a rider was turned away (AD-59)

**Given** the refusal counter
**When** the two payment reasons are added
**Then** they join the closed enum registered in Story 3.3
**And** every alert expression, recording rule and dashboard panel over this counter **groups by
`reason`**, with no aggregation across reasons permitted anywhere (AD-54)

**Given** the three reasons
**When** they are alerted on
**Then** the cooldown reason is **zero in health** and alerts on sustained nonzero; the unsettled-trip
reason has a **nonzero healthy baseline** and alerts on deviation, never on a nonzero absolute; and
the active-ride reason is counted for baseline and **never alerts** (AD-54)

**Given** the projection's consumer lag
**When** it is observed
**Then** it is a gauge on an AD-55-governed consumer
**And** both alerting reasons are read **together with** that gauge, because fail-open drives them
toward zero exactly when the projection is the broken thing (AD-59, AD-54)

**Given** `payment-service` unavailable
**When** riders request rides
**Then** requests still succeed, because the check reads a local projection
**And** the Tier 1 / Tier 2 boundary is preserved rather than quietly crossed (AD-48, AD-59)

## Epic 6: Audit & Analytics — `v0.3`

Every state transition the system ever made is queryable by entity and by actor, bounded in Postgres
and complete in a columnar store — and the location and audit history finally has a consumer.

> **Ordering constraint inside this epic.** The columnar mirror (Story 6.3) lands **before** partition
> dropping (Story 6.4). Enabling retention first would drop history whose only other copy does not
> exist yet. The table is created *partitioned* in Story 6.1 because partitioning is structural and
> cannot be retrofitted cheaply; only the *pruning* waits.

> **Open decision — storage ceilings for both stores.** To be settled when Stories 6.4 and 6.5 are
> detailed. **AD-47's deferred-capacity list does not cover storage** — it names pool sizes, replica
> and partition counts, the outbox bound, backoff base, retry caps and the capture ceiling, but no
> disk bound for Postgres or ClickHouse. This is a gap in the spine, not a number it deferred.
>
> **Where the risk actually sits.** Order-of-magnitude, to be replaced by measurement: audit events
> run ~10k/day at fixture load — roughly 4 MB/day, ~1.5 GB across a 12-month window. Location pings
> run ~1.3M/day at the same load, and at stress scale 20k drivers heartbeating every 2 s produce
> ~864M/day, on the order of 13 GB/day compressed. The two differ by roughly 130:1 by event count,
> so a ceiling on the audit table is near-cosmetic while a ceiling on the ping history is the one
> that matters.
>
> **Two mechanisms, not one.** Time-based retention bounds steady accumulation; a size bound is what
> protects against a burst, and a stress run is precisely a burst that a 12-month window does nothing
> about. The proposed shape is **size-based eviction of the oldest data as the primary mechanism**,
> with a **hard ingest stop only as an alarmed backstop**.
>
> **Why a hard stop cannot be the primary mechanism.** AD-48 grants Tier 3 components the right to be
> disabled and lose no data *because consumer offsets survive and the backlog drains on return* —
> which holds only while the events remain in Kafka. An ingest stop lasting longer than Kafka's
> retention converts a disk problem into permanent, silent data loss.
>
> **Derive rather than guess**, following AD-35's precedent for the outbox bound: ingest rate ×
> the window to retain, with headroom, confirmed under the NFR-2 stress run (AD-47). If a ceiling is
> adopted, AD-47's deferred list should gain it so the spine stops being silent on storage.

### Story 6.1: audit-service records every state transition with its actor

As an operator,
I want every ride, driver and payment state transition recorded with the actor that caused it,
So that the history of the system is reconstructable rather than inferred from logs.

**Acceptance Criteria:**

**Given** the Compose stack
**When** it is brought up
**Then** `audit-service` starts with its own private Postgres database (AD-1, AD-3)
**And** it exposes health and Prometheus metrics like every other service (AD-54)
**And** it carries its own Gradle wrapper and the standard package layout (AD-7, AD-52)

**Given** ride, driver and payment events on the backbone
**When** `audit-service` consumes them
**Then** it writes one immutable `audit_event` per state transition, carrying event id, actor type
and id, entity type and id, action, timestamp and metadata (FR-41)

**Given** `entity_type` and `entity_id`
**When** they are written
**Then** they use the same vocabulary as the event envelope, so one vocabulary spans both tables (AD-31)

**Given** an automated transition
**When** its actor is recorded
**Then** it is `SYSTEM` — for offer expiry, `NO_DRIVER`, auto-completion, hold release, surge
recomputation and session expiry (FR-41)
**And** an internally-triggered refund records `Admin`, which distinguishes an operator action from a
`SYSTEM` one and is not an authenticated role (Glossary, FR-39)

**Given** location heartbeats
**When** audit scope is defined
**Then** they are **never** audited
**And** this is because heartbeats would turn the audit log into a location log at roughly 1.3M
events/day against ~10k/day of state transitions — the wrong story to tell (Constraints)

**Given** a redelivered event
**When** it is consumed
**Then** it is deduplicated on `event_id` and writes no second row (AD-36, NFR-4)

**Given** a message this consumer cannot process
**When** it retries
**Then** backoff is jittered, and on exhaustion the message is routed to a dead-letter topic rather
than blocking its partition or being discarded
**And** dead-lettered volume is a metric that alerts and is zero in health (AD-55)

**Given** `audit-service` subscribing to streams `matching-service`, `driver-service` and
`payment-service` already produce
**When** it is added
**Then** it runs in its own consumer group and **no producer and no other consumer changes**
**And** this is the system's second independent consumer, which is what *proves* decoupling rather
than describing it (FR-33, AD-36)

**Given** the `audit_events` table
**When** it is created
**Then** it is **partitioned by month** from the outset, because partitioning is structural and
cannot be retrofitted cheaply (AD-53)
**And** pruning is not yet enabled — see Story 6.4

**Given** audit ingest
**When** it is observed
**Then** its rate is exposed as an operational metric (FR-46)

**Given** `audit-service` disabled entirely
**When** the rest of the system runs
**Then** nothing breaks
**And** on its return, consumer offsets survive and the backlog drains with no data lost — it is
Tier 3 (AD-48)

**Given** recorded timestamps
**When** they are written
**Then** they are `TIMESTAMPTZ` in UTC, on wall clock, because they are recorded facts rather than
in-process deadlines (Timestamps convention)

### Story 6.2: Audit is queryable by entity and by actor

As an operator,
I want to ask what happened to a given entity, and what a given actor did,
So that a question about the past has an answer that does not involve reading logs.

**Acceptance Criteria:**

**Given** the audit query API
**When** it is exposed
**Then** the gateway routes to it, as one of the four routes the gateway exposes (AD-5)

**Given** an entity type and id
**When** the audit trail is queried
**Then** that entity's transitions are returned in order (FR-42)

**Given** an actor type and id
**When** the audit trail is queried
**Then** that actor's transitions are returned (FR-42)

**Given** a point lookup by entity or actor
**When** it is served
**Then** it is answered from **Postgres**, which is that store's job (AD-53)

**Given** any query
**When** results are returned
**Then** they are bounded by a result-size limit

**Given** an audit event
**When** any mutation is attempted
**Then** no update or delete path exists — audit events are immutable (Glossary)

**Given** an absent entity or actor
**When** it is queried
**Then** the response is 404, never 403 (AD-38)

### Story 6.3: ClickHouse mirrors the full history as a parallel consumer

As an operator,
I want the complete analytical history in a store built for it, fed independently of Postgres,
So that neither store is derived from the other and either can be rebuilt from the log.

**Acceptance Criteria:**

**Given** the Compose stack
**When** this story lands
**Then** ClickHouse 26.3 LTS joins it (Stack)
**And** it is Tier 3 — disabling it breaks nothing and the Postgres audit trail continues (AD-48)

**Given** the columnar store
**When** it is fed
**Then** it consumes **the same Kafka topics** in its own independent consumer group (FR-43, AD-53)

**Given** the feeding mechanism
**When** it is examined
**Then** it is a **parallel consumer, never a Postgres-to-columnar copy job**
**And** neither store is therefore derived from the other (AD-53)

**Given** the columnar store
**When** its retention is considered
**Then** it holds the full logical history indefinitely (NFR-6, AD-53)

**Given** either store lost entirely
**When** it is rebuilt
**Then** replaying the topics from `earliest` reconstructs it (AD-53, AD-36)

**Given** a redelivered event
**When** it is consumed into the columnar store
**Then** it does not produce a duplicate row (AD-36, NFR-4)

### Story 6.4: Audit retention is enforced by dropping partitions

As an operator,
I want old audit data pruned from Postgres by dropping partitions,
So that the table stays bounded without a churn of dead tuples, and without losing the history.

**Acceptance Criteria:**

**Given** the retention window elapsing
**When** pruning runs
**Then** whole monthly partitions are **dropped**
**And** row-level `DELETE` is never used, so retention is a metadata operation rather than a churn of
dead tuples (AD-53)

**Given** the retention window
**When** it is configured
**Then** it is configuration rather than a constant
**And** its default is 12 months, stated as a revisable target rather than a hard commitment (NFR-6)

**Given** a partition due for dropping
**When** pruning evaluates it
**Then** it is dropped only once the same range is present in the columnar store
**And** a drop is therefore never the removal of the only copy (NFR-6, AD-53)

**Given** the columnar store
**When** Postgres partitions are dropped
**Then** the same logical history remains available there indefinitely (NFR-6)

**Given** the retention window
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by waiting (NFR-9)

**Given** a configured **size ceiling** alongside the time window
**When** the table approaches it
**Then** the oldest partitions are dropped ahead of the time window rather than waiting for it
**And** a size bound is what protects against a burst, since a time window bounds only steady
accumulation — see the epic's open decision on storage ceilings

**Given** growth toward either bound
**When** it is observed
**Then** table size and the headroom remaining against the ceiling are exposed as gauges that alert
before the bound is reached, not when it is (AD-54)

### Story 6.5: Location pings reach the columnar store

As an analyst,
I want the driver location history durably retained where it can be queried at scale,
So that the pings the system already collects stop being write-only.

**Acceptance Criteria:**

**Given** driver location events on the backbone
**When** they are consumed into the columnar store
**Then** the position history persists durably (FR-27)

**Given** the location history
**When** its home is examined
**Then** it lives in the columnar store **only**
**And** there is deliberately no Postgres location history at all (Constraints, FR-27)

**Given** ping volume of roughly 1.3M/day at simulator load
**When** the design is reviewed
**Then** this volume is precisely why the history is columnar-only and never enters the audit trail (Constraints)

**Given** a redelivered location event
**When** it is consumed
**Then** it does not produce a duplicate row (AD-36, NFR-4)

**Given** a retained ping
**When** its stored shape is inspected
**Then** it carries driver identity, position and the produce-time timestamp
**And** that is what distance-per-driver and ride-density-by-area are later derived from, so no new
collection is introduced for them (FR-44, FR-27)

**Given** ping volume — the largest data stream in the system by roughly two orders of magnitude
**When** its storage is bounded
**Then** a configured ceiling governs it, enforced by evicting the oldest data first
**And** an ingest stop is a last-resort backstop that alerts rather than the routine mechanism, since
stopping longer than Kafka's retention would lose data permanently (AD-48) — see the epic's open
decision on storage ceilings

**Given** the ceiling
**When** its value is chosen
**Then** it is derived as ingest rate × the window to retain, with headroom, and confirmed by
measurement under the stress run rather than guessed now (AD-47, AD-35)

**Given** stored volume and headroom against the ceiling
**When** they are observed
**Then** both are gauges that alert before the bound is reached (AD-54)

### Story 6.6: Aggregate analytics are computed from data already collected

As an analyst,
I want distance, ride density and driver utilization computed from the history the system already keeps,
So that the collected data has a consumer instead of sitting unread.

**Acceptance Criteria:**

**Given** the mirrored location-ping history
**When** distance traveled per driver is computed
**Then** it is derived from that history (FR-44)

**Given** the same history
**When** ride density by area is computed
**Then** it is derived from that history (FR-44)

**Given** driver utilization — the percentage of time in each status
**When** it is computed
**Then** it is derived from the driver **state-transition audit trail**, not from location pings (FR-44)

**Given** any of these analytics
**When** the data they need is considered
**Then** nothing new is collected for them — each is computed from data already being collected (FR-44)

**Given** a question about the past
**When** the store to answer it is chosen
**Then** point lookups by entity or actor are served from Postgres, and aggregate analytics from the
columnar store
**And** there is exactly one answer to "which store answers this question" (AD-53)

**Given** each aggregate
**When** the migration is delivered
**Then** it is measured on both the row-oriented and the columnar store
**And** the before/after figures are recorded, so the migration ships with a benchmark rather than an
assertion (CAP-33, Goals)

## Epic 7: Real-Time, Live Dashboard & Local Kubernetes — `v1.0`

Drivers stop polling for offers, an operator watches the system's domain state move in real time, and
the whole system runs as an orchestrated deployment under stress-scale load.

### Story 7.1: Drivers receive offers and ride changes over a live push channel

As a driver,
I want offers and the changes I must react to pushed to me rather than polled for,
So that I stop driving toward a pickup that no longer exists.

**Acceptance Criteria:**

**Given** WebSocket
**When** it is admitted to the system
**Then** it is a **third transport**, used only for server-initiated push to a connected client
**And** it is never used for request/response, which stays REST at the edge and gRPC internally
(AD-51, AD-37)

**Given** driver sockets
**When** they are held
**Then** `driver-service` holds them (AD-51)

**Given** an event that must reach a connected driver
**When** it is routed
**Then** **every replica consumes the relevant topic in its own consumer group** and pushes only to
the sockets it currently holds
**And** no replica registry, sticky routing, or shared session store is required (AD-51)

**Given** the push channel
**When** its payloads are defined
**Then** it carries ride offers, rider cancellations, withdrawn or expired offers, and auto-completions
(FR-45)

**Given** a driver connected to no replica
**When** an event concerning them occurs
**Then** they simply receive nothing
**And** they recover on their next session read (Story 3.12), because push is an accelerator over the
polling path and **never the only delivery route for anything correctness-bearing** (AD-51, FR-45)

**Given** the 2 s polling path from Story 3.12
**When** push lands
**Then** polling remains in place rather than being removed (AD-46, AD-51)

**Given** the push consumer
**When** it processes events
**Then** it deduplicates on `event_id` and applies jittered backoff with a dead-letter path like every
other consumer (AD-36, AD-55, NFR-4)

### Story 7.2: The live operational dashboard shows domain state moving

As an operator,
I want a live view of what the system is doing right now, in domain terms,
So that I can watch rides and drivers move rather than reading infrastructure metrics and inferring it.

**Acceptance Criteria:**

**Given** the dashboard
**When** it is served
**Then** it is a lightweight custom web UI (FR-50)

**Given** the dashboard
**When** it renders
**Then** it shows live counts of drivers by status, rides by status, and active riders (FR-50)

**Given** those counts
**When** they change
**Then** they are pushed in real time over the same mechanism as Story 7.1 (FR-50, AD-51)

**Given** the dashboard's sockets
**When** they are held
**Then** the dashboard consumer holds them, consuming in its own consumer group per replica, on the
same fan-out-and-filter routing (AD-51)

**Given** this dashboard and the Grafana dashboards of Story 4.9
**When** they are compared
**Then** they are distinct: this one shows **domain and business state**, those show infrastructure
metrics (FR-50, FR-46)

**Given** the dashboard disabled entirely
**When** the rest of the system runs
**Then** nothing breaks — it is Tier 3 (AD-48)

> **Open question — how the dashboard is reached.** AD-5 lists exactly four gateway routes:
> `rider-service`, `driver-service`, the Stripe webhook, and audit's query API. The dashboard is not
> among them. The consistent reading is that it is reached directly, as Prometheus and Grafana are,
> rather than through the gateway — but AD-5 predates it, so either that reading is confirmed or
> AD-5's route list gains a fifth entry. Settle it when this story is detailed rather than by
> whichever way it is first wired.

### Story 7.3: The Simulator drives synthetic load against the running system

As an engineer,
I want a containerized Simulator generating synthetic riders, drivers and ride traffic at configurable scale,
So that I can watch how the deployed system actually behaves under load that no hand-written scenario reproduces.

**Acceptance Criteria:**

**Given** the Simulator
**When** it is packaged
**Then** it runs as its own container in the stack
**And** it is plain Java rather than Spring Boot (FR-49, Source tree)

**Given** a run
**When** it executes
**Then** it generates synthetic riders, drivers and ride traffic concurrently against the running
system (FR-49)

**Given** simulated drivers
**When** the Simulator drives their heartbeats
**Then** each position advances by straight-line drift toward its destination on each heartbeat
**And** it uses the same haversine math as the ETA formula, never road-network routing (Non-goals)

**Given** an offer reaching a simulated driver
**When** it responds
**Then** it can accept, decline, or deliberately ignore the offer until timeout
**And** all three offer outcomes therefore occur under load (FR-11, FR-22, FR-23)

**Given** ride requests it generates
**When** they are sent
**Then** it supplies payment-method tokens, including deliberately-declining ones, so the
authorization-failure path is exercised under load too (FR-49, FR-9)

**Given** a seed
**When** a run is repeated
**Then** it produces the same sequence of ride requests and driver movements
**And** a failure observed during a run can therefore be re-run rather than merely observed once (NFR-9)

**Given** generation volume
**When** it is configured
**Then** it ramps from fixture scale toward the stress scale of NFR-2 without code changes (FR-49, NFR-2)

**Given** the configured geographic bounds
**When** coordinates are generated
**Then** they remain relative to those bounds rather than to any literal (AD-25)

> **What this is for, and what it is not.** The Simulator exists to surface **emergent** behaviour
> under mixed load — contention, saturation and interactions nobody wrote a scenario for — and to make
> the deployed system watchable while it happens. The deterministic proofs of specific races belong to
> the stories that own those transitions and were delivered there as ordinary test scenarios (see
> Epic 3). Seeded reproducibility here serves re-running a surprise, not asserting a known invariant.

### Story 7.4: The full stack runs on a local Kubernetes cluster

As an operator,
I want the whole system running as an orchestrated deployment,
So that it is a system rather than a pile of local processes.

**Acceptance Criteria:**

**Given** a local kind 1.35.x cluster
**When** the manifests are applied
**Then** the full stack deploys cleanly and runs (FR-47, NFR-7, Stack)

**Given** every service
**When** it is built and run
**Then** it does so in Docker from pinned base images with no host JDK dependency (NFR-7)

**Given** the manifests
**When** they are stored
**Then** they live in the repository under `deploy/` (AD-49)

**Given** the service manifests
**When** they are authored
**Then** services are **generated from one template** rather than copied per service
**And** infrastructure is declared individually (AD-49)

**Given** the deployment tiers
**When** they are exercised
**Then** Tier 1 runs alone; Tier 2 adds payments; Tier 3 adds audit, ClickHouse, Prometheus, Grafana
and the dashboard
**And** disabling Tier 3 breaks nothing and loses no data (AD-48)

**Given** the deployment target
**When** it is examined
**Then** it is a **local** cluster
**And** no cloud vendor is used at any point (NFR-7)

### Story 7.5: Deployment is reconciled from git

As an operator,
I want the cluster reconciled to what the repository says,
So that drift is visible and a rollback is a revert rather than a procedure.

**Acceptance Criteria:**

**Given** Argo CD 3.5.x
**When** it runs
**Then** it reconciles the cluster to the manifests in the repository (AD-49, Stack)

**Given** cluster state drifting from the repository
**When** the controller next reconciles
**Then** the drift is corrected
**And** it was visible rather than silent (AD-49)

**Given** a bad deployment
**When** it must be undone
**Then** rollback is a revert of the manifest change (AD-49)

**Given** sync ordering
**When** the stack comes up
**Then** datastores are healthy before their dependants start (AD-49)

**Given** provider secrets
**When** they are handled
**Then** they are created out-of-band and **excluded from reconciliation**
**And** this is the one documented exception to declarative deployment, because NFR-10 forbids keys in
source (AD-49, NFR-10)

**Given** image promotion
**When** a new version ships
**Then** it is a manual tag bump at milestone cadence
**And** automated promotion stays deferred, since it solves a deploy frequency this project does not
have (Deferred)

### Story 7.6: The stress run produces the capacity numbers

As an engineer,
I want the system driven to stress scale and the bottlenecks it surfaces recorded,
So that every capacity value the architecture deliberately left underived is set by measurement rather than by guess.

**Acceptance Criteria:**

**Given** the Simulator of Story 7.3
**When** it ramps
**Then** it reaches roughly 20k drivers and 200k riders (NFR-2)

**Given** the operating area
**When** the stress scenario runs
**Then** it is widened to roughly 20 km across
**And** driver density therefore stays realistic and the 5 km matching radius still does real work,
rather than covering the whole grid and filtering nothing (AD-25, NFR-2)

**Given** payments during the stress run
**When** the provider is selected
**Then** the **stub** provider is used, keeping `payment-service` and its whole state machine in the
flow
**And** only the outbound provider call is replaced, never the payment path itself (NFR-8, AD-43)

**Given** the run
**When** it executes
**Then** it surfaces named bottlenecks — connection pool exhaustion, missing indexes, Kafka partition
throughput ceilings, cache hot-key contention — rather than falling over silently (NFR-2)

**Given** the measurements
**When** they are taken
**Then** they set the values AD-47 deliberately leaves deferred: pool sizes, replica and partition
counts, the outbox bound, backoff base, the outbox retry cap, and the capture backoff ceiling
**And** the storage ceilings left open in Epic 6 (AD-47, AD-35, AD-58)

**Given** the results
**When** the run finishes
**Then** they are recorded rather than merely observed, because the migration, concurrency, webhook
and scale narratives are the project's stated secondary deliverable (Goals)

**Given** Redis under load
**When** the geo key is the constraint
**Then** read replicas are the first scaling lever, which is safe because the geo set is advisory
**And** per-cell geo partitioning stays deferred until nothing before it has been exhausted (AD-27, Deferred)

**Given** the concurrency guarantees of Epic 3
**When** the system is under stress load
**Then** they still hold — no driver is ever double-booked (NFR-1, FR-17)

**Given** the scale target
**When** its nature is stated
**Then** it is a milestone to reach, not sustained production traffic to hold (NFR-2)

**Given** constrained machine resources
**When** the run is set up
**Then** Tier 3 may be disabled, which is what the tiering exists to permit (AD-48)

> **Decide when detailing: which environment the stress run targets.** The roadmap sequences it after
> the Kubernetes deploy, but a single-machine kind cluster running the full stack *and* 20k simulated
> drivers competes for the same resources the run is trying to measure. Running it against Compose,
> against kind, or against both with the difference recorded are all defensible — the spine does not
> say. Choose deliberately, because the numbers this run produces become the system's configured
> capacity everywhere else.
