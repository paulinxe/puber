---
id: SPEC-puber
companions:
  - glossary.md
  - state-machines.md
  - roadmap.md
  - ../../planning-artifacts/architecture/architecture-puber-2026-08-03/ARCHITECTURE-SPINE.md
sources:
  - ../../planning-artifacts/prds/prd-puber-2026-08-02/prd.md
  - ../../planning-artifacts/prds/prd-puber-2026-08-02/addendum.md
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate. Source documents listed in frontmatter are for traceability only — consult them only if you need narrative rationale or prose color this contract intentionally omits.

# Puber — Ride-Hailing Backend

`FR-*` / `NFR-*` tags cite the absorbed PRD; `AD-*` tags cite the architecture spine, which is authoritative for mechanism.

## Why

**A vision to realize.** Puber exists to give one solo engineer a domain tangible enough to be watched working — request a ride, see a driver approach, complete a trip — while exercising the production backend patterns that are otherwise only readable about: concurrency-safe matching, an event backbone, resilience, observability, two-phase payments, audit at scale with a row-oriented → columnar migration, real-time push, and container orchestration. The unifying design principle is **you control every input**: no maps API, no SMS provider, no real money, no real users — every actor is a fixture or a simulator thread and every coordinate is generated, which is what makes the system safe to iterate on fast while still producing real races, real state machines, and real distributed-systems failure modes. There is no market, no launch, and no cloud vendor. The deliverable is demonstrable engineering depth and a portfolio narrative that survives senior-engineer interview scrutiny.

## Capabilities

Each capability carries its **kind**, because the three do not decompose into work the same way:

- **slice** — vertical and demoable. Becomes a story; done means done.
- **property** — must hold across many stories and is never one itself. Becomes acceptance criteria on every story that could break it, plus a suite that proves it. A story named after a property has no definition of done.
- **enabler** — must exist before the work that depends on it, regardless of the phase its capability sits in. Built first (see `roadmap.md`), never retrofitted.

### Rider

- **CAP-1** — Fare quote (FR-1) · slice
  - **intent:** A rider can price a pickup/dropoff pair — fare, distance, ETA — before committing to anything.
  - **success:** A quote returns fare and distance always, and omits ETA (rather than erroring) when no driver is available; it creates no ride; the returned fare is indicative — the binding fare is re-locked at request time.

- **CAP-2** — Ride request with a locked fare (FR-2, FR-18) · slice
  - **intent:** A rider can request a ride with pickup/dropoff coordinates and a payment-method token, and get a ride identifier back immediately.
  - **success:** The ride identifier returns without waiting on the payment provider; the fare is computed once at request time as `(base + per-km × distance + per-minute × time) × surge` and never recomputed at trip end; no payment method is stored — the token travels with the request. The request is refused on three distinct grounds — the rider already holds a non-terminal ride, their most recent completed ride has not paid yet, or a capture failed for them within the cooldown — each distinguishable to the caller and counted separately (CAP-36).

- **CAP-3** — Rider reads their own rides (FR-4, FR-5, FR-8) · slice
  - **intent:** A rider can find their in-flight ride by identity alone, read any ride by identifier, and page their history.
  - **success:** The active-ride lookup returns at most one ride or nothing; ride-by-id returns current state and details; history returns most-recent-first under a result-size limit.

- **CAP-4** — Watching the driver approach (FR-6) · slice
  - **intent:** While a ride is `MATCHED`, a rider can see who is coming, where they are, and when they will arrive.
  - **success:** The rider sees the assigned driver's display name, a position that moves as heartbeats land, and an ETA that updates with it; no driver identifier is exposed (AD-39).

- **CAP-5** — Rider-visible payment outcome (FR-7) · slice
  - **intent:** A rider can see how the money ended on a finished ride.
  - **success:** The outcome hangs off the ride as a sub-resource and is read from the service that **owns** payment state — never from the advisory admission projection, whose fail-open absence means "no reason to refuse" and must never be read as "not paid" (AD-61, AD-59). Exactly four values are outcomes: `CAPTURED`, `REFUNDED`, `CAPTURE_FAILED`, `FAILED`. **A capture still being pursued is not one** — an `AUTHORIZED` payment on a `COMPLETED` ride reports *settlement in progress*, never uncaptured. When the owning service is unavailable the read fails rather than answering: no answer is better than a guessed one.

- **CAP-6** — Cancellation before the trip starts (FR-16) · slice
  - **intent:** A rider can cancel any ride that has not yet started moving.
  - **success:** Cancellation succeeds from `REQUESTED`, `WAITING_MATCH`, `OFFERED`, and `MATCHED`; any assigned driver is released and any outstanding offer withdrawn; any hold is voided — including one that resolves *after* the cancellation; a mismatched rider identity is rejected as 404 without revealing whether the ride exists.

### Matching and ride lifecycle

- **CAP-7** — Authorization gates dispatch (FR-9) · slice
  - **intent:** No driver is dispatched for a ride whose funds are not held.
  - **success:** A ride is persisted `REQUESTED` (meaning *awaiting authorization*, nothing else) and moves to `WAITING_MATCH` when the hold lands or to terminal `PAYMENT_FAILED` when it is declined; matching reads only `WAITING_MATCH`, so an unfunded ride is invisible to dispatch (AD-19).

- **CAP-8** — Nearest-driver matching (FR-10) · slice
  - **intent:** The system matches each waiting ride to the nearest matchable driver within a 5 km radius, retrying continuously until it succeeds or gives up.
  - **success:** Under simulator load, rides match to the nearest matchable driver inside the radius; a ride that finds nobody stays in the pool and is retried without a fixed timer tick bounding its latency.

- **CAP-9** — Bounded offer, accept or decline (FR-11, FR-22, FR-23) · slice
  - **intent:** An offered driver has a bounded window to accept or decline, and only the driver holding the offer can act on it.
  - **success:** Accepting moves the ride `OFFERED → MATCHED`; declining or timing out returns it to `WAITING_MATCH` and offers it to the next-nearest driver; a driver who declined or timed out is never offered that ride again; acting on any other ride, or with no live offer, is rejected.

- **CAP-10** — Giving up as `NO_DRIVER` (FR-12) · slice
  - **intent:** A ride that cannot find a driver within a bounded seeking window gets a definitive answer instead of waiting forever.
  - **success:** The ride reaches terminal `NO_DRIVER`, retrying stops, the hold is voided, and the rider is never charged; the budget counts accumulated time in `WAITING_MATCH` only, never time spent `OFFERED` or `MATCHED` (AD-46).

- **CAP-11** — Salvaging a ride from a silent driver (FR-13) · slice
  - **intent:** A ride whose assigned driver goes silent before pickup is recovered rather than killed.
  - **success:** Past the `MATCHED` staleness window the ride returns to `WAITING_MATCH` (never to `REQUESTED`, so its hold is never re-authorized) and the silent driver is released.

- **CAP-12** — Auto-completing an abandoned trip (FR-14) · slice
  - **intent:** An `IN_PROGRESS` ride whose driver goes silent is completed by the system rather than left hanging.
  - **success:** The ride reaches `COMPLETED`, the fare locked at request time is captured through the normal payment path, and the driver is released; the ride records `completed_by = SYSTEM` and the audit trail records `SYSTEM` as the actor, so auto-completions stay distinguishable both on read and after the fact.

- **CAP-13** — Race-safe concurrency (FR-17, NFR-1) · property
  - **intent:** Concurrent actors never corrupt ride or driver state.
  - **success:** Concurrent test scenarios prove no driver is ever double-booked and no update is lost; a rider cancelling at the same instant a driver accepts resolves to exactly one winner, with the loser rejected rather than both applying.

- **CAP-14** — Demand-derived surge (FR-19) · slice
  - **intent:** The fare multiplier moves with live system state rather than sitting constant.
  - **success:** Surge is a static `1.00` through the early phases and, from the event-backbone phase onward, is recomputed periodically from the ratio of outstanding requests to available drivers and exposed as an operational metric.

### Driver

- **CAP-15** — Driver controls their own availability (FR-20) · slice
  - **intent:** A driver decides when they are working; the system never puts them online.
  - **success:** Going online sets `AVAILABLE` and starts offers, going offline stops them; the go-offline guard reads the *ride's* state — with an offer outstanding the offer is released and the driver goes offline, once accepted (`MATCHED`/`IN_PROGRESS`) it is refused until they complete or are released.

- **CAP-16** — Driver session read (FR-21) · slice
  - **intent:** A driver can see their whole working state in one call.
  - **success:** One response carries declared status, whether the system is currently hearing their heartbeat, any pending offer (with pickup, dropoff, fare, and distance to pickup), and their active ride if any — so a driver who is `AVAILABLE` but unreachable can see why nothing arrives.

- **CAP-17** — Driver runs the trip (FR-24) · slice
  - **intent:** A driver explicitly starts the trip once the rider is aboard and completes it at the end.
  - **success:** `MATCHED → IN_PROGRESS` and `IN_PROGRESS → COMPLETED` each succeed only from the preceding state and only for the driver's own assigned ride; completion returns the driver to `AVAILABLE`.

- **CAP-18** — Driver reads their history (FR-25) · slice
  - **intent:** A driver can review their own completed rides.
  - **success:** Most-recent-first, bounded by a result-size limit, scoped to that driver.

- **CAP-19** — Location heartbeat and fast-path reads (FR-26, FR-27, FR-28) · slice
  - **intent:** Drivers report position by heartbeat; the system serves current position fast and keeps the history for analytics.
  - **success:** Position reads are sub-second and served off a path decoupled from durable persistence; heartbeats are stamped at produce time so consumer lag cannot mask staleness (AD-23). A ping is **telemetry, not a domain event** (AD-60): produced exactly once onto one location stream keyed by driver, with no outbox row, no Postgres history anywhere, and never an audit event. Every reader is an independent consumer — the fast-path index and the columnar ping history alike — reading that one stream rather than a re-publish of it, since a second hop is where the produce-time stamp is lost. Because a ping carries no event identifier, idempotency uses a key **derived from the payload** — `(driver_id, occurred_at)` — not a generated one. The retained history is bounded by a configured storage ceiling enforced by **evicting oldest-first**, with an ingest stop as an alarmed backstop only, never the routine mechanism. **The history begins with the stream and is never backfilled** — before the swap onto the backbone there is no ping history at all.

- **CAP-20** — Declared status vs. observed reachability (FR-29) · property
  - **intent:** Losing signal must not end a shift, and a silent driver must not absorb offers they cannot answer.
  - **success:** Matchability requires `AVAILABLE` **and** a heartbeat inside the staleness window; a lost signal never writes to declared status; a driver who regains signal becomes matchable again on their next heartbeat with no action and no transition.

- **CAP-21** — Session expiry on prolonged absence (FR-30) · slice
  - **intent:** A driver who vanished long enough that it is plainly a new shift must choose to work again.
  - **success:** An **idle** driver unreachable past the session-expiry window is set `OFFLINE` with a `SYSTEM` audit event and receives no offers until they explicitly go online; a driver holding a ride is resolved by CAP-11/CAP-12 first, and the expiry window always exceeds those staleness windows, so no driver is ever expired mid-ride.

### Event backbone and resilience

- **CAP-22** — Domain events propagate over the backbone (FR-31, FR-33) · slice
  - **intent:** Ride, driver, payment, and audit events reach interested services without those services calling each other.
  - **success:** Multiple independent consumers subscribe to the same stream and can be added or removed without touching producers or each other; commands and reads that an actor waits on stay synchronous calls.

- **CAP-23** — Failures degrade instead of cascading (FR-32, NFR-3) · property
  - **intent:** Producers, consumers, and outbound provider calls survive a dependency being slow or down.
  - **success:** Retry-with-jitter and circuit breaking are applied and proven by tests on both the backbone and provider calls; exhausted messages land in a dead-letter path rather than blocking a partition or being dropped, and dead-lettered volume is a metric that alerts and is zero in health. **Settlement — capture and hold-release alike — is the one deliberate exception**: it carries no retry cap to exhaust and therefore no dead-letter path (CAP-26, CAP-27); work that cannot proceed stays live and claimable, watched by CAP-36's oldest-still-retrying gauge instead.

- **CAP-24** — Duplicate delivery is safe (NFR-4) · property
  - **intent:** At-least-once delivery is the normal case, so reprocessing changes nothing.
  - **success:** Every event consumer and externally-triggered handler is idempotent — by deduplicating on a stable event identifier, or by a guarded state transition that supplies idempotency structurally wherever the handler's whole job is one such transition. Where a stream carries **no** identifier at all — location pings (CAP-19) — the key is derived from the payload, so a redelivery presents the same key with nothing to remember. State machines reject invalid transitions, so a replayed event provably cannot advance state a second time.

### Payments

- **CAP-25** — Two-phase authorize/capture lifecycle (FR-34, FR-35) · slice
  - **intent:** The fare is held when the ride is requested and taken when the trip completes.
  - **success:** Authorization is asynchronous and gates dispatch (CAP-7); capture happens on completion, including system auto-completion; nothing is ever captured for a ride that delivered no trip; exactly one payment exists per ride.

- **CAP-26** — Holds released on rides that never happened (FR-36) · slice
  - **intent:** A rider is never left with money reserved against a ride that delivered nothing.
  - **success:** `CANCELLED` and `NO_DRIVER` rides move their payment to `VOIDED`, including when the authorization resolves *after* the ride is already terminal — the late result voids on arrival rather than settling.

- **CAP-27** — Capture is pursued until it settles, and unrecoverable loss is counted (FR-37) · slice
  - **intent:** A delivered trip is never written off while its money is still collectable, and the money that is genuinely lost is known rather than buried.
  - **success:** A failing capture retries with CAP-23's jittered backoff, stays `AUTHORIZED` while it does, and keeps retrying across process restarts — no retry cap discards a valid hold. The payment becomes terminal `CAPTURE_FAILED` only when the provider reports the hold is no longer capturable (expired, revoked, or cancelled); the ride stays `COMPLETED`, the outcome surfaces to the rider (CAP-5), and no collections or remedy path follows. Every `CAPTURE_FAILED` is counted by number and summed by amount as revenue loss (CAP-36).

- **CAP-28** — Webhooks are verified and idempotent (FR-38) · slice
  - **intent:** Provider callbacks are trusted only when authentic and applied only once.
  - **success:** Signature verification rejects forged payloads and redelivery of the same provider event ID is deduped — both proven by tests, not by inspection.

- **CAP-29** — Full refund flow (FR-39) · slice
  - **intent:** A completed, captured payment can be refunded end to end.
  - **success:** The refund is issued against the provider, the payment moves to `REFUNDED`, the refund webhook is processed idempotently, and the result reconciles; triggered only by an internal operator-facing call, exercised by tests and the Simulator.

- **CAP-30** — Reconciliation surfaces what delivery missed (FR-40) · slice
  - **intent:** Missed or failed webhook deliveries and implausibly long-lived holds are caught rather than silently accumulating.
  - **success:** A reconciliation task detects both and flags them; neither is corrected automatically, because a hold that looks stranded may belong to a genuinely long trip (AD-44).

### Audit and analytics

- **CAP-31** — Every transition is audited with its actor (FR-41) · property
  - **intent:** The history of the system is reconstructable from an immutable record of what changed and who changed it.
  - **success:** Every ride, driver, and payment state transition writes an audit event carrying actor type/ID and entity type/ID, with `SYSTEM` recorded for offer expiry, `NO_DRIVER`, auto-completion, hold release, surge recomputation, and session expiry.

- **CAP-32** — Audit is queryable and bounded (FR-42, NFR-6) · slice
  - **intent:** Point questions about an entity or an actor are answerable, without the audit table growing forever.
  - **success:** Queries by entity and by actor are served from Postgres; retention is enforced by dropping monthly partitions, never row-level `DELETE`; the window is configuration.

- **CAP-33** — Columnar mirror for analytics at scale (FR-43) · slice
  - **intent:** The full analytical history lives in a store built for it, and the migration itself is a documented outcome.
  - **success:** The columnar store holds the complete history, fed independently from the same event stream so neither store is derived from the other; the migration ships with a before/after benchmark.

- **CAP-34** — Aggregate analytics from data already collected (FR-44) · slice
  - **intent:** The location and audit history has a consumer instead of sitting unread.
  - **success:** Distance traveled per driver and ride density by area are computed from the columnar ping history (CAP-19), which is their sole source and covers only the period since that stream began; driver utilization (% time in each status) is computed from the driver state-transition audit trail, never from pings (AD-60).

### Real-time, operations, deployment

- **CAP-35** — Live push to drivers (FR-45) · slice
  - **intent:** A driver learns about an offer and about changes they must react to without polling for them.
  - **success:** Ride offers, rider cancellations, withdrawn or expired offers, and auto-completions arrive over a push channel; a driver connected to nothing misses only the acceleration and recovers on their next session read (CAP-16) — push is never the only delivery route for anything correctness-bearing (AD-51).

- **CAP-36** — Health, metrics, and operational dashboards (FR-46, NFR-5) · enabler
  - **intent:** The system's behaviour is visible without log-diving, from every service's first commit.
  - **success:** Every service exposes health and metrics; dashboards show ride throughput, current surge multiplier, payment success rate, audit ingest rate, rides awaiting authorization, undelivered-event backlog age, and error rates. Two payment gauges are money, not throughput, and alert on their own: **capture loss** — the count and summed amount of `CAPTURE_FAILED` payments, zero in health — and **oldest capture still retrying**, which is the leading indicator, since by the time a payment is `CAPTURE_FAILED` the money is already unrecoverable. **Refused ride requests are counted by reason, one increment per refused call** — one active ride, unsettled payment, recent `CAPTURE_FAILED` — counting calls rather than affected riders, since a rider re-polling contributes hundreds an hour and the two readings differ by orders of magnitude. Split by reason because the first is ordinary rider behaviour while a spike in either of the others is the visible signature of a payment provider failing or recovering, seen from the one place a rider actually feels it. Two definitions are fixed: **match latency** is request → driver accepting (what the rider waits), reported alongside time-to-*first*-offer so a matching problem is separable from drivers ignoring offers; **drivers online** counts only *matchable* drivers (declared available **and** currently reachable).

- **CAP-37** — Live operational dashboard (FR-50) · slice
  - **intent:** Domain state is watchable in real time, distinct from infrastructure metrics.
  - **success:** A lightweight web UI shows live counts of drivers by status, rides by status, and active riders, pushed over the same mechanism as CAP-35.

- **CAP-38** — Local Kubernetes deployment (FR-47, NFR-7) · slice
  - **intent:** The whole system runs as an orchestrated deployment, not a pile of local processes.
  - **success:** Every service builds and runs in Docker with no host JDK and the full stack deploys cleanly to a local (kind/minikube-style) cluster from manifests in the repository.

### Simulation and testability

- **CAP-39** — Simulator generates the load (FR-49, NFR-2) · slice
  - **intent:** Synthetic riders, drivers, and traffic stand in for real users at any scale the system needs to be proven at.
  - **success:** Runs as a containerized generator against automatically seeded fixture drivers; supplies payment-method tokens — including deliberately-declining test tokens so the authorization-failure path is routinely exercised; generates coordinates relative to the configured bounds; ramps from fixture scale (~30 drivers) to the stress scale of NFR-2 (~20k drivers, ~200k riders) and surfaces concrete bottlenecks — connection pools, index gaps, Kafka partition throughput, cache hot-key contention.

- **CAP-40** — Reproducible runs under a controlled clock (NFR-9) · enabler
  - **intent:** A race or timing failure can be re-run rather than merely observed once, and no test waits in real time.
  - **success:** The same seed produces the same sequence of ride requests and driver movements; every bounded window — offer timeout, retry interval, the `NO_DRIVER` budget, the CAP-11/CAP-12/CAP-20 staleness windows, session expiry, capture backoff — is exercisable by advancing a clock abstraction, so timing behaviour is tested in seconds and without flakiness.

## Constraints

- A rider may hold at most one non-terminal ride; a second request while one is in `REQUESTED`/`WAITING_MATCH`/`OFFERED`/`MATCHED`/`IN_PROGRESS` is rejected (FR-3). This doubles as admission control during a provider outage (AD-45).
- **A rider whose most recent ride is `COMPLETED` with its payment still `AUTHORIZED` cannot request a new ride** (FR-51) — refused until the payment settles, or until the session-expiry bound lapses **measured from the moment capture was first requested**, whichever comes first — that stamp is the anchor because without it the lapse is uncomputable. Once the hold is confirmed the ride stops waiting on payment entirely — the two machines advance independently (AD-2, AD-41) — and the coupling reappears only at the *next* request. Without this, unbounded capture pursuit (CAP-27) would let one rider stack up an unlimited number of unpaid **delivered** trips during a provider outage.
- **Two deliberate limits on that guard, each closing a contradiction it would otherwise create.** It is bounded, because capture pursuit is uncapped: an unbounded block would refuse every rider who completed a trip for the entire length of a provider outage, which is the same total stoppage the guard avoids by reading a local projection instead of calling `payment-service` (AD-59, AD-48). And it never fires on a `CANCELLED` ride awaiting its void, because blocking there would revoke AD-45's promise that the rider's own cancellation is their exit. Throttling past the bound is the `CAPTURE_FAILED` cooldown's job, not this one's.
- **A `CAPTURE_FAILED` payment blocks that rider for 30 minutes from the moment it failed, then stops blocking on its own.** An unreachable provider never produces `CAPTURE_FAILED` — that is just the next retry — so the state arrives in a burst when the provider returns and reports the holds that expired meanwhile. The cooldown covers that aftermath: the riders whose money was just lost do not immediately stack new uncollectable trips onto it.
- **The 30-minute window is the clearing mechanism, and that is what keeps this admission control rather than debtor standing.** Nothing is attached to the rider, no operator action releases them, and there is no standing to appeal — the clock clears it. Measured from a recorded `capture_failed_at` against wall clock, since it is a stored fact compared across restarts and services rather than an in-process deadline. The value joins AD-46's tuned time set and must be ordered against it rather than chosen alone.
- **All three refusal grounds read one anchor — the rider's most recent ride — and are evaluated in a fixed order**, unsettled payment before recent capture failure. Anchoring the arms differently (most recent ride, most recent payment, most recent `CAPTURE_FAILED`) lets a rider match two grounds at once or neither depending on the reading, so two conforming builds would refuse different populations and their refusal counts would stop summing to refusals. One anchor plus a fixed order makes the three grounds a true partition (FR-51, AD-59).
- No authentication, registration, or accounts. Identity is passed per request — rider via header, driver fixture-seeded — and trusted as-is (FR-48). No `riders` table; `rides.rider_id` is a plain UUID with no foreign key.
- Distance is haversine, and one fixed assumed speed of 30 km/h — exactly 2 minutes per kilometre —
  derives **both** the driver-to-pickup ETA and the trip duration the fare prices; no maps, routing,
  or navigation API is used anywhere.
- The matching radius is 5 km, but the operating area is a **scenario parameter, not a constant** — the fixture-scale demo square cannot hold stress-scale drivers at a density where "nearest driver" is still a real question (NFR-2, AD-25).
- Bound outcomes the system can determine; never bound a wait for external truth. `NO_DRIVER` is bounded; a ride awaiting authorization has **no timeout at all** — it is surfaced by metric and alert, and the rider's own cancellation is the exit (AD-45).
- Time constants form one tuned set whose **ordering is the invariant**: poll ≪ offer timeout; idle < `MATCHED` < `IN_PROGRESS` < capture-failed cooldown < session expiry; every staleness window comfortably exceeds clock skew plus delivery lag; no seeking budget is consumed by time spent not seeking (AD-46).
- Elapsed durations and deadlines are measured against a **monotonic** clock; recorded facts (audit times, `requested_at`, retention partitioning) use **wall clock**. One clock abstraction satisfies both and is controllable in tests (NFR-9).
- Delivery is at-least-once system-wide; idempotency is a rule for every consumer and externally-triggered handler, not a payments concern — satisfied by a stable event ID, by a guarded transition, or by a payload-derived key on a stream that carries no ID (NFR-4).
- Payment-method tokens are transit-only: never logged, never echoed in responses, and **never persisted at all** — the token passes through to the payment integration and is stored nowhere. Provider keys live in environment configuration, never in source or fixtures (NFR-10, AD-42). No real card data exists anywhere — sandbox tokens only.
- Payments is excluded from the NFR-2 stress test by **swapping the provider, not skipping the path** — the payment service and its state machine run exactly as in production and only the outbound call is replaced (NFR-8, AD-43). The same swap covers running before payments exist and **running the suite with no provider credentials configured**; the stub must be able to produce every settlement outcome — the three-way capture answer and the two-way void answer — or the paths that exclusion exists to preserve are never exercised. The stress target itself is a late-phase milestone, not a per-phase gate.
- Five independent services (`rider-service`, `driver-service`, `matching-service`, `payment-service`, `audit-service`) behind a single gateway that carries **actor-facing traffic only**. No service is directly addressable, `matching-service` is never publicly routable, and `payment-service` is routable for **the provider webhook alone**. **Operator and observability surfaces are deliberately not routes** — the refund trigger (CAP-29), the live dashboard (CAP-37), and the metrics stack are reached directly inside the cluster, which is a stronger boundary than a route rather than a looser one; each mints its own request id at entry, since no gateway did it for them (AD-5).
- Each service is independently buildable with its own build and no shared library; duplicated domain code across services is accepted, which is precisely why conventions are fixed centrally (AD-52).
- No ORM anywhere — explicit SQL, immutable domain objects, Flyway-versioned schema, expand-only migrations. Postgres runs at `READ COMMITTED`; correctness comes from guarded conditional updates and unique indexes, not a stricter isolation level.
- Audit scope is **state transitions only, never location heartbeats** — heartbeats would turn the audit log into a location log at ~1.3M events/day against ~10k/day of transitions. The ping history is columnar-only: **no location history in Postgres anywhere**, and it is never a partition of the audit trail (AD-60).
- Audit stays in partitioned Postgres for a bounded window (12-month revisable default, dropped by partition) while the columnar store keeps the same logical history indefinitely; **both are kept** — the migration narrative is part of the goal (NFR-6).
- No host JDK: every service builds and runs in Docker from pinned base images, from day one (NFR-7).
- The final deployment target is a **local** Kubernetes cluster. No cloud vendor is used at any point — knowingly trading a "deployed to real cloud" CV bullet for local scope (NFR-7).
- Concrete capacity values — pool, replica, and partition counts, outbox bound, backoff base, the outbox retry cap, and the capture backoff ceiling — are derived by measurement under the stress run, never guessed upfront (AD-47). The capture path has a ceiling, never a cap: the bound is on request *rate*, not on attempt count (AD-58).
- **Authority:** `ARCHITECTURE-SPINE.md` governs every technical decision; where the absorbed PRD addendum disagrees with it, the spine wins.
- **`docs/tickets/pb-*.md` and `docs/puber.md` are historical and non-authoritative.** They predate the PRD, are stale on the driver status enum, the payment flow, the ride state machine, and the database topology. Downstream derives from this SPEC and its companions only; those files are narrative reference at most (their surviving value: PB-6.1's isolation-level record, PB-7.1's `EXPLAIN`/indexing work, PB-4.1's expand-only migration discipline).

## Non-goals

- Real authentication, registration, or account management for riders or drivers.
- Real payment processing — Stripe sandbox only, no real cards and no real money.
- Real maps or routing APIs; straight-line/haversine distance and a fixed-speed ETA stand in.
- Real mobile apps — clients are curl, a browser, Java tests, and the Simulator.
- Real-time turn-by-turn navigation. The Simulator drifts a driver's position straight-line toward the destination on each heartbeat, using the same haversine math as the ETA.
- Multi-city operation or geographic sharding.
- Advanced surge pricing — no demand forecasting, no ML, no per-rider or time-of-day pricing. Surge is CAP-14's single ratio-derived multiplier and nothing beyond it.
- Rider profiles or ratings; driver onboarding or document verification.
- Dispatch logic beyond nearest-available-driver.
- Driver-initiated cancellation — only riders cancel (CAP-6); a driver's exits are declining an offer and completing the ride.
- Collections, dunning, or any pursuit of money once the hold is gone — a payment whose hold the provider reports uncapturable is recorded `CAPTURE_FAILED` and the system moves on. This is a non-goal about *unrecoverable* money only: while a hold is still valid, capture keeps being retried (CAP-27), and abandoning one is never a non-goal.
- Rider-facing refund requests or dispute handling. Refunds exist (CAP-29) but only as an internal trigger. Stated plainly because CAP-12 can charge for a trip that did not finish, and a rider has **no in-system remedy** for that — accepted because no real money moves.
- Multiple vehicle types.
- Any real cloud vendor deployment.
- **Deferred, may resurface:** a real riders table with foreign keys, scheduled/recurring rides, promo codes, a driver earnings dashboard, rider-initiated refunds with a review workflow, and rider "debtor" standing blocking further requests after a failed capture. The last is deferred rather than rejected: the pre-authorization hold already eliminates most of its triggering scenario, a debtor flag is a one-way door without a clearing mechanism, and CAP-27's terminal `CAPTURE_FAILED` state is the hook a later implementation would read. What *is* in scope is the narrower guard in Constraints — an unsettled payment blocks until it settles, a `CAPTURE_FAILED` one for 30 minutes — which is a self-clearing cooldown rather than standing attached to a rider. Also deferred: **recovering a `CAPTURE_FAILED` payment by reconciliation** once a provider comes back. It is terminal for this build, and the hook is the same reconciliation task that already catches missed webhooks (CAP-30).

## Success signal

At `v1.0`, the full stack runs on a local Kubernetes cluster while the Simulator drives seeded synthetic load: a rider requests a ride, funds are held, the nearest matchable driver is offered it over a live push channel and accepts, the rider watches them approach on a moving position and ETA, the trip completes, the fare captures, and every transition of it is queryable from the audit trail and the columnar store — with the live dashboard showing the counts move as it happens. The same system, re-run at stress scale, surfaces named bottlenecks rather than falling over silently, and its concurrency suite proves no driver was ever double-booked. Milestone tags `v0.1` (Kafka + observability), `v0.2` (+Stripe), `v0.3` (+audit + ClickHouse), and `v1.0` (+WebSockets + K8s) are each cut on a system that works end to end.

## Assumptions

- Capability IDs are this SPEC's own and are stable from here. `FR-*`/`NFR-*` tags are retained inline purely for traceability back to the absorbed PRD; several FRs describing one testable capability were merged into a single CAP (rider reads, the offer lifecycle, location tracking), and FRs stating invariants rather than actions (FR-3, FR-15, FR-35, FR-48) landed in Constraints or the state-machine companion instead.
- The architecture run's visual artifacts (`flows.html`, `architecture-map.html`, `SOLUTION-DESIGN.html`) are treated as narrative aids owned by their originating runs, not as companions of this contract; the spine references them.
