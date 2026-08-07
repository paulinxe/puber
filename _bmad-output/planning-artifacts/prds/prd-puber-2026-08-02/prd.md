---
title: Puber PRD
status: final
created: 2026-08-02
updated: 2026-08-06
---

# Puber — Product Requirements Document

## 1. Vision

Puber is a solo-built, multi-service ride-hailing backend that exists to practice production-grade backend engineering patterns — concurrency-safe matching, event-driven architecture (Kafka), resilience (Resilience4j), observability (Prometheus/Grafana), fintech-style payment flows (Stripe sandbox), audit/analytics at scale (Postgres → ClickHouse), real-time delivery (WebSockets), and container orchestration (Kubernetes) — against a domain that's tangible and visually verifiable (request a ride, watch a driver approach, complete a trip), rather than an abstract one.

The unifying design principle is: **you control every input.** No Google Maps API, no SMS provider, no real payment rail — every "user" is a seeded fixture or a synthetic simulator thread, and every location is a generated coordinate. This is what makes the project safe to iterate on quickly while still exercising real concurrency, real state machines, and real distributed-systems failure modes.

It is explicitly **not** a product for real users or launch. There is no real market, no competitors to position against, and no real infrastructure vendor in the loop — everything runs locally. The deliverable is deep, demonstrable engineering experience and a portfolio narrative: a system whose architecture, trade-offs, and migration stories (e.g., Postgres → ClickHouse) hold up under senior-engineer interview scrutiny.

## 2. Goals & Success Criteria

Success is binary and simple: **the system works, end to end, at every milestone.** Concretely:

- Five independent services (`rider-api`, `driver-api`, `matching-engine`, `payment-service`, `audit-service`) each build and run in Docker with no host JDK dependency.
- Postgres schema versioned via Flyway across all migrations; fixture drivers seed automatically; no ORM — explicit SQL throughout.
- The Simulator generates reproducible, concurrent synthetic load (riders, drivers, rides) and can ramp from fixture-scale up to a stress-test scale (see NFR-2); tests assert matching correctness and state-machine integrity under that load.
- Kafka wires the full command → process → event flow; multiple services consume the same topics without coupling.
- Resilience patterns (retry with jitter, circuit breakers) are applied and tested, including on Stripe calls.
- Prometheus + Grafana show live infra metrics across all services; a separate live dashboard (FR-49) shows live domain/business counts.
- Stripe sandbox integration works end to end: authorize-at-request and capture-at-completion both work, holds are released on rides that never deliver a trip, webhook idempotency and signature verification are proven by tests, refunds work, and reconciliation catches missed webhooks. Both failure paths — declined authorization and exhausted capture — are exercised by tests, not just handled in theory.
- Audit service captures every state transition; Postgres retains it for a bounded window with partitioned drop-based retention (see NFR-6); ClickHouse holds the full analytical copy; the migration story is documented with a before/after benchmark.
- Drivers receive ride offers over WebSocket.
- The full system deploys cleanly to a **local** Kubernetes cluster (kind/minikube-style) — this is the final deployment target; no real cloud vendor is used. *(Trade-off, stated honestly: this gives up a "deployed to real cloud" CV bullet in exchange for keeping infra scope local and simple — local K8s still exercises the orchestration, service-discovery, and manifest-authoring skills that are the actual learning target; only the "ran on a real vendor's infra" claim is dropped.)*
- Git tags mark each milestone: `v0.1` (Kafka + observability), `v0.2` (+Stripe), `v0.3` (+Audit+ClickHouse), `v1.0` (+WebSockets+K8s).

The secondary, equally real goal: once this is done, translate the system into concrete CV bullets and interview talking points (the migration narrative, the concurrency-safety story, the idempotent-webhook story, the scale-stress story).

## 3. Features

Grouped by domain. IDs are stable and global.

### A. Rider Requests & Reads (FR-1–FR-8)
- **FR-1:** Rider can request a fare quote for a pickup/dropoff pair — returning fare, distance, and ETA — without creating a ride. This is the rider's entry point: price and time are known before committing. A quote is indicative, not binding: the fare is recomputed and locked at request time (FR-18), so a surge change between quote and request moves the price. When no driver is available the quote still returns fare and distance, with no ETA rather than an error.
- **FR-2:** Rider requests a ride with pickup/dropoff coordinates and a payment-method token; the fare is locked at request time and the rider receives a ride identifier. Since there are no rider accounts (FR-47), no payment method is stored — the token travels with the request, supplied by the Simulator in practice.
- **FR-3:** A rider cannot have two simultaneous active rides — a new request is rejected while one is already in progress (`REQUESTED`/`MATCHED`/`IN_PROGRESS`).
- **FR-4:** Rider can look up their current active ride by rider identity, without needing the ride identifier. Per FR-3 this returns at most one ride, or nothing if the rider has none in flight.
- **FR-5:** Rider can read a single ride by its identifier, returning its current state and details. This is the rider's primary "where is my ride" call throughout the ride's life.
- **FR-6:** While a ride is `MATCHED`, the rider can see who is coming and where they are — the assigned driver's identity, their current position, and an ETA to pickup that updates as the driver moves. This is what makes the Vision's "watch a driver approach" literally true, and gives the location fast path (FR-28) a consumer beyond matching.
- **FR-7:** Rider can see the payment outcome on a finished ride — the final fare and whether it was captured, failed, or refunded on a `COMPLETED` ride, or that authorization was declined on a `PAYMENT_FAILED` one. The payment state machine has a rider-visible consequence rather than being purely internal.
- **FR-8:** Rider can query ride history, most recent first, bounded by a result-size limit.

### B. Matching, Fares & Ride State (FR-9–FR-19)
- **FR-9:** No driver is dispatched until the fare is authorized. On request the system places a hold for the locked fare (FR-33); if authorization is declined the ride goes straight to a terminal `PAYMENT_FAILED` state and no driver is ever offered it. This is the payment-side counterpart to `NO_DRIVER` — a ride that never happened, with the reason recorded.
- **FR-10:** System matches each ride to the nearest available driver within a 5km radius; unmatched rides are retried on a scheduled interval.
- **FR-11:** A matched driver has a bounded window to accept; on timeout — or on an explicit decline (FR-23) — the offer is released and re-offered to the next-nearest driver.
- **FR-12:** If no driver can be found within a bounded overall window, the ride transitions to a terminal `NO_DRIVER` state, retrying stops, and the authorization hold is released (FR-35) — the rider gets a definitive answer instead of waiting indefinitely, and is never charged.
- **FR-13:** If the driver holding a `MATCHED` ride goes silent past a staleness window before starting the trip, the ride returns to `REQUESTED` and re-enters matching for the next-nearest driver, and the silent driver is released. The rider was never picked up, so the ride is still fulfillable — it is salvaged rather than killed, and the existing hold stays in place.
- **FR-14:** If the driver on an `IN_PROGRESS` ride goes silent past a staleness window, the system auto-completes the ride, capturing the fare locked at request time through the normal payment path (FR-33), and releases the driver. This is a deliberate simplification: the system cannot tell a finished trip from one abandoned mid-route, so it assumes completion — acceptable here because no real money moves, and the rider has no in-system remedy for a wrong charge (see Non-Goals). The audit trail records `SYSTEM` rather than `DRIVER` as the completing actor, so auto-completions stay distinguishable from genuine ones after the fact, and a refund can be issued against one internally (FR-38) if you choose to.
- **FR-15:** Full ride state machine: `REQUESTED → MATCHED → IN_PROGRESS → COMPLETED`, plus the terminal states `CANCELLED`, `NO_DRIVER`, and `PAYMENT_FAILED`. Recovery from a silent driver (FR-13, FR-14) reuses existing states rather than adding new ones.
- **FR-16:** Rider may cancel only while `REQUESTED` or `MATCHED`; cancelling a `MATCHED` ride releases the driver back to available, withdraws any offer still outstanding to them, and releases the authorization hold (FR-35). Cancelling with a mismatched rider identity is rejected without revealing whether the ride exists.
- **FR-17:** Matching is race-safe under concurrency — no driver is ever double-booked. The same guarantee covers a rider cancelling at the same moment a driver accepts: both are guarded transitions on one ride, whichever commits first wins, and the loser is rejected rather than both applying.
- **FR-18:** Fare is computed at request time from a formula (base + distance + time) × surge multiplier.
- **FR-19:** Surge is a multiplier held in configurable fare rules. It starts as a single static value (`1.00`) through the early phases; from the event-backbone phase onward it is recomputed periodically from the ratio of outstanding ride requests to available drivers, giving the fare a reason to move with live system state. The current multiplier is exposed as an operational metric (FR-45). Nothing more sophisticated is in scope — see Non-Goals.

### C. Driver Session & Actions (FR-20–FR-25)
- **FR-20:** Driver can set their own availability — going online (`AVAILABLE`) to start receiving offers, or offline (`OFFLINE`) to stop. Both directions are explicit driver actions; the system never puts a driver online on their behalf. A driver cannot go offline while on an active ride; they must complete or be released from it first. Going offline with a pending unaccepted offer releases that offer back for re-matching rather than leaving it to dangle.
- **FR-21:** Driver can read their current working state in one call — this is the driver's entry point: their declared availability status, whether the system is currently hearing their heartbeat (FR-29), any pending offer awaiting response (with pickup, dropoff, fare, and distance to pickup), and their active ride if one is in flight. Surfacing reachability matters because a driver who is declared `AVAILABLE` but unreachable receives nothing, and should be able to see why.
- **FR-22:** Driver can accept the ride currently offered to them — and only that ride. Acting on any other ride, or on no live offer at all, is rejected.
- **FR-23:** Driver can decline a pending offer, immediately releasing it to the next-nearest driver instead of the rider waiting out the offer timeout. Declining returns the driver to `AVAILABLE`.
- **FR-24:** Driver explicitly starts the trip once the rider is aboard, moving the ride `MATCHED → IN_PROGRESS`, and later completes it, moving it `IN_PROGRESS → COMPLETED`. Both actions are restricted to the driver's own assigned ride, and each is only valid from the preceding state. Completing a ride returns the driver to `AVAILABLE`.
- **FR-25:** Driver can query their own completed ride history, most recent first, bounded by a result-size limit — the driver-side counterpart to FR-8.

### D. Driver Location Tracking (FR-26–FR-29)
- **FR-26:** Drivers report location via a heartbeat; system tracks current position and availability status.
- **FR-27:** Location updates persist to a durable history (position audit trail).
- **FR-28:** Location reads are served from a fast path (sub-second) decoupled from durable slow-path persistence.
- **FR-29:** Declared availability and observed reachability are separate facts. A driver's status (FR-20) records only what they chose; whether their last heartbeat is inside a bounded staleness window is derived and never writes to that status. **A driver is matchable only when they are declared `AVAILABLE` and their heartbeat is fresh** — so a silent driver absorbs no offers they will never answer, and any ride they hold is recovered per FR-13 or FR-14. Because their declared status is untouched, a driver who loses signal and regains it becomes matchable again on their next heartbeat, with no action required: a tunnel does not end a shift, and only the driver ends a shift.

### E. Event Backbone & Resilience (FR-30–FR-32)
- **FR-30:** Inter-service ride/driver/payment/audit events flow through an event backbone (Kafka), replacing direct service-to-service HTTP calls.
- **FR-31:** Producers and consumers apply retry-with-jitter and circuit-breaking on failure.
- **FR-32:** Multiple independent consumers can subscribe to the same event stream without coupling to each other.

### F. Payments (FR-33–FR-39)
- **FR-33:** Payment follows a two-phase lifecycle against Stripe sandbox: the fare is **authorized** as a hold when the ride is requested, and **captured** when the ride completes. Nothing is captured for a ride that never delivers a trip.
- **FR-34:** Payment state machine: `INITIATED → AUTHORIZED → CAPTURED → REFUNDED`, plus `FAILED` (authorization declined or capture exhausted) and `VOIDED` (hold released without capture).
- **FR-35:** When a ride ends without a trip being delivered — `CANCELLED` (FR-16) or `NO_DRIVER` (FR-12) — any authorization hold is released and the payment moves to `VOIDED`. The rider is never charged for a ride that did not happen.
- **FR-36:** A capture that fails is retried with jitter under the resilience patterns of FR-31; if retries are exhausted the payment moves to terminal `FAILED` and the outcome is recorded and surfaced to the rider (FR-7). The ride itself stays `COMPLETED` — the trip did happen — and there is no in-system remedy or collections process (see Non-Goals).
- **FR-37:** Stripe webhooks are verified by signature and processed idempotently (deduped by event ID).
- **FR-38:** Full refund flow is supported end to end — refund issued against the provider, payment moved to `REFUNDED`, refund webhook processed idempotently, and the result reconciled. Refunds are triggered by an internal operator-facing call and exercised by tests and the Simulator; there is no rider-facing way to request one (see Non-Goals). One payment per ride.
- **FR-39:** A reconciliation task catches missed or failed webhook deliveries.

### G. Audit & Analytics (FR-40–FR-43)
- **FR-40:** Every domain state transition (ride, driver, payment) is recorded as an audit event, tagged with the actor that caused it — including `SYSTEM` for automated transitions such as offer expiry, `NO_DRIVER`, and auto-completion.
- **FR-41:** Audit events are queryable by entity and by actor; retained under a partitioning + retention policy (see NFR-6).
- **FR-42:** Audit data is mirrored to a columnar store for analytical queries at scale.
- **FR-43:** Aggregate analytics are computed from data already being collected, giving it an actual consumer instead of sitting unread: distance traveled per driver and ride density by area, computed from the location-ping history (FR-27) mirrored into the columnar store; driver utilization (% time in each status) computed from the existing driver state-transition audit trail (FR-40), not from location pings.

### H. Real-Time & Deployment (FR-44–FR-46)
- **FR-44:** Drivers receive a live push channel (WebSocket) rather than polling, carrying both ride offers and the state changes a driver must react to — the rider cancelled, the offer was withdrawn or expired, the ride was auto-completed. Without this a driver keeps driving toward a pickup that no longer exists until they next read their state (FR-21).
- **FR-45:** All services expose health and metrics; dashboards show live operational KPIs (match latency, ride throughput, driver online count, current surge multiplier, payment success rate, audit ingest rate).
- **FR-46:** All services deploy to a local Kubernetes cluster.

### I. Identity & Simulation (FR-47–FR-48)
- **FR-47:** No authentication or registration exists; identity is passed per-request (rider: header-carried identifier; driver: fixture-seeded identifier) and trusted as-is. This is a deliberate scope decision, not an oversight — auth is out of scope for this project's learning goals.
- **FR-48:** A Simulator component generates synthetic riders, drivers, and ride traffic at configurable scale (deterministic/seeded) to exercise the system under concurrent load. It runs as an in-process test fixture early on and becomes a standalone containerized load generator later, with the ability to ramp generation volume up toward the stress-test scale in NFR-2. It also supplies payment-method tokens on ride requests (FR-2), including deliberately-declining test tokens so the authorization-failure path (FR-9) is routinely exercised rather than theoretical.

### J. Live Operational Dashboard (FR-49)
- **FR-49:** A lightweight custom web UI shows live counts of system state — drivers by status, rides by status, active riders — pushed in real time via the same mechanism as FR-44. This is distinct from the Grafana/Prometheus dashboards (FR-45), which track infrastructure metrics, not domain/business state.

## 4. Non-Functional Requirements

Unusual framing: most of these are learning targets to prove out, not hard production requirements.

- **NFR-1 (Concurrency correctness):** Matching must be race-safe under concurrent load — no double-booked drivers, no lost updates — proven via concurrent test scenarios, not just code review.
- **NFR-2 (Scale ambition, phased):** The system is functionally proven at fixture scale (~30 drivers) through the core phases, but the Simulator must be able to ramp to a stress-test scale — roughly 20k drivers and 200k riders — as a late-phase milestone, generating enough concurrent load to surface real bottlenecks (connection pools, index gaps, Kafka partition throughput, cache hot-key contention) worth learning from. This is not a sustained-production-traffic requirement. **Payments is explicitly excluded from this stress test** (see NFR-8) — the scale target applies to the ride/matching/location pipeline.
- **NFR-3 (Resilience):** Kafka producers/consumers and Stripe API calls apply retry-with-jitter and circuit-breaking; failures degrade gracefully (dead-letter queue) rather than cascading.
- **NFR-4 (Idempotency & consistency):** Delivery is at-least-once throughout — the event backbone redelivers, provider webhooks retry, and outbound calls are retried — so duplicate processing is normal rather than exceptional. Every event consumer and externally-triggered handler must be idempotent, deduplicating on a stable event identifier; payment webhooks are one instance of this rule, not its whole scope. Ride and payment state machines reject invalid transitions, so a replayed event cannot advance state a second time.
- **NFR-5 (Observability):** Every service exposes health and metrics from day one; dashboards must make match latency, throughput, and error rates visible without log-diving.
- **NFR-6 (Data retention & queryability):** Audit data in Postgres is retained for a bounded window — proposed default of 12 months, dropped by partition thereafter — with the same logical history preserved indefinitely in ClickHouse for analytics. The 12-month figure is a stated, revisable target, not a hard commitment.
- **NFR-7 (Deployability, local only):** Every service builds and runs in Docker with no host JDK dependency. The final deployment target is a **local** Kubernetes cluster — no real cloud vendor is used at any point in this project.
- **NFR-8 (Payments scale boundary):** Stripe sandbox has its own rate limits, independent of anything Puber controls. Payments correctness (idempotency, webhook handling, refunds) is proven at normal/small concurrent scale, not exercised at the NFR-2 stress-test volume — the stress test targets the ride/matching/location pipeline only, so Stripe's external limits never gate the milestone.
- **NFR-9 (Determinism & time control):** Simulator runs are reproducible — the same seed produces the same sequence of ride requests and driver movements — so a concurrency or race-condition failure can be re-run rather than merely observed once. Every bounded time window in the system (offer timeout, retry interval, the `NO_DRIVER` window, the three staleness windows of FR-13/FR-14/FR-29, and capture backoff) must be exercisable under a controlled clock rather than by waiting in real time, so timing behaviour is testable in seconds and without flakiness. Time is used deliberately in two distinct ways: **elapsed durations and deadlines are measured against a monotonic clock**, never wall-clock arithmetic, so an NTP correction or a daylight-saving shift cannot make a timeout fire early or never fire at all; **wall-clock timestamps** remain the right choice for recorded facts — audit event times, `requested_at`, retention partitioning — where the value must stay meaningful across restarts and across services. The clock abstraction must satisfy both: controllable in tests, monotonic where durations are involved in production.
- **NFR-10 (Payment data handling):** Payment-method tokens (FR-2) are never written to logs, echoed back in API responses, or persisted beyond what the provider integration requires; provider API keys live in environment configuration, never in source or fixtures. No real card data exists anywhere in this project — sandbox tokens only — but the handling discipline is practised as though it did, because that is the habit worth building.

## 5. Non-Goals / Out of Scope

- Real authentication, registration, or account management for riders or drivers
- Real payment processing (Stripe sandbox only — no real cards or money)
- Real maps or routing APIs (straight-line/haversine distance and a fixed-speed ETA formula stand in)
- Real mobile apps (clients are curl, a browser, Java tests, or the Simulator)
- Multi-city operation or geographic sharding
- Advanced surge pricing — no demand forecasting, no machine learning, no per-rider or time-of-day pricing; surge is the single ratio-derived multiplier of FR-19 and nothing beyond it
- Real-time turn-by-turn navigation — instead, the Simulator advances a driver's position via straight-line drift toward the destination on each location heartbeat (FR-26), using the same haversine math as the ETA formula, not real road-network routing (this location history is not write-only, though — see FR-43 for its analytics use)
- Rider profiles or ratings
- Driver onboarding or document verification
- Dispatch logic beyond nearest-available-driver
- Driver-initiated cancellation — only riders can cancel (FR-16); a driver's exits are declining an offer (FR-23) or completing the ride (FR-24)
- Collections, dunning, or any pursuit of a failed capture (FR-36) — a payment that exhausts its retries is recorded as `FAILED` and the system moves on; the trip is not clawed back and the rider is not chased
- Multiple vehicle types
- Any real cloud vendor deployment (see NFR-7)
- Rider-facing refund requests or dispute handling — refunds exist as a capability (FR-38) but can only be triggered internally. Stated plainly because FR-14 can charge for a trip that did not finish, and a rider has **no in-system remedy** for that: no way to contest a charge, no review workflow, no dispute status. Accepted because no real money moves here; the refund *execution* path is kept for its payment-pattern value, while the intake workflow is deferred.

**Deferred (explicitly out of the current plan, may resurface later):** a real riders table with foreign-key relationships, multiple vehicle types, scheduled/recurring rides, promo codes, a driver earnings dashboard, rider-initiated refund requests with a review/approval workflow, and rider accounts carrying a "debtor" standing that blocks further ride requests after a failed capture (see `addendum.md` for why this was deferred rather than built).

## 6. Roadmap

Five phases across an 8-month / 32-week plan. Detailed week-by-week ticket breakdowns live in `docs/tickets/`; this is the phase-level shape only.

1. **Bootstrap + Domain + Matching** (Weeks 1–7): Core services stand up; domain model, Postgres persistence, and fixtures land; in-memory nearest-driver matching works end to end against the Simulator, with no Kafka yet.
2. **Kafka + Resilience + Observability** (Weeks 9–16): Direct inter-service HTTP calls are replaced by an event backbone; resilience patterns and full observability (metrics + dashboards) land; the driver-location fast path is introduced; surge becomes dynamic (FR-19), computed from live demand now that the signal exists.
3. **Stripe Payments** (Weeks 17–20): A dedicated payment service integrates Stripe sandbox PaymentIntents across the full two-phase lifecycle — authorize at ride request, capture at completion, void on rides that never deliver — and proves out idempotent webhook handling, the payment state machine, both failure paths, and refunds. Milestone `v0.2`.
4. **Audit + ClickHouse** (Weeks 21–24): An audit service captures state-transition events across all domains; storage starts in partitioned Postgres and migrates to ClickHouse for analytics, with the migration benchmarked; the same ClickHouse pipeline is reused to compute driver utilization, distance-traveled, and ride-density analytics (FR-43) from location and audit history. Milestone `v0.3`.
5. **Real-Time + Local Kubernetes** (Weeks 25–32): Drivers get a live push channel for offers and ride-state changes; the live operational dashboard (FR-49) ships; the full system is deployed to a local Kubernetes cluster; the stress-test scale target (NFR-2) is exercised. Milestone `v1.0`. *(Local K8s only — no cloud vendor; see NFR-7.)*

## 7. Glossary

- **Rider** — the actor requesting a ride; identified per-request by a passed identifier (FR-47), no account.
- **Driver** — the actor fulfilling rides; fixture-seeded, tracked by location and status, and responsible for their own availability (FR-20).
- **Admin** — an audit `actor_type` label recorded on internally-triggered actions such as refunds (FR-38). Not an authenticated role, not a person with a UI, and not a distinct set of capabilities — with no auth (FR-47) and no review workflow in scope, it exists only to distinguish an operator-triggered action from a `SYSTEM` one in the audit trail.
- **System** — the scheduler/automated actor (retry tasks, offer-timeout expiry, silent-driver recovery, auto-completion, hold release, surge recomputation) acting without a human trigger; recorded as the actor on the audit events it causes (FR-40).
- **Silent driver** — a driver whose last heartbeat is older than the staleness window (FR-29). Not matchable while silent, and any ride they hold is recovered per FR-13 or FR-14 — but their declared status is untouched, so they recover automatically on the next heartbeat.
- **Simulator** — the synthetic load-generation component standing in for real riders and drivers (FR-48).
- **Ride states** — `REQUESTED → MATCHED → IN_PROGRESS → COMPLETED`, plus three terminal states: `CANCELLED` (rider-initiated, from `REQUESTED`/`MATCHED`), `NO_DRIVER` (system-initiated, when matching gives up), and `PAYMENT_FAILED` (system-initiated, when authorization is declined at request and no driver is ever dispatched).
- **Driver status** — `OFFLINE` / `AVAILABLE` / `BUSY`; the driver's *declared* intent, set by their own action (FR-20) or by the ride lifecycle, never by loss of signal.
- **Reachable** — the system has heard a heartbeat from the driver inside the staleness window. Derived, not declared, and orthogonal to status: matchability requires `AVAILABLE` **and** reachable (FR-29).
- **Payment states** — `INITIATED → AUTHORIZED → CAPTURED → REFUNDED`, plus `FAILED` (authorization declined, or capture retries exhausted) and `VOIDED` (hold released without capture).
- **Hold** — an authorization placed on the locked fare when a ride is requested (FR-33); captured if the trip completes, voided if the ride ends without one.
- **Offer** — a time-bounded proposal of a ride to a specific driver; released to the next-nearest driver if declined, if the driver goes offline, or if it is not accepted in time, and withdrawn outright if the rider cancels.
- **Quote** — a read-only fare/distance/ETA estimate for a pickup/dropoff pair that creates no ride (FR-1).
- **Fare** — the price computed at request time: (base + distance + time) × surge multiplier.
- **Surge** — the fare multiplier (FR-19); a static `1.00` in the early phases, later recomputed from the ratio of outstanding requests to available drivers.
- **ETA** — estimated arrival time of the matched driver at pickup, derived from haversine distance and a fixed assumed speed (not the ride's own trip duration).
- **Audit event** — an immutable record of a single domain state transition, tagged with an actor type/ID and entity type/ID.

## 8. Open Questions

- **Non-blocking:** How exactly is Payments excluded from the NFR-2/NFR-8 stress test (simulator flag, stubbed payment path, separate smaller test suite, etc.)? Deliberately left open for the Architecture or Epics/Stories pass — see `addendum.md` for context. Doesn't block starting either.

No other open items at this time — additional ones get added here as they surface during Architecture or Epics/Stories work.
