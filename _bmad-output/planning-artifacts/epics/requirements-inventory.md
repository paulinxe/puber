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

