---
title: Puber PRD
status: final
created: 2026-08-02
updated: 2026-08-05
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
- Prometheus + Grafana show live infra metrics across all services; a separate live dashboard (FR-45) shows live domain/business counts.
- Stripe sandbox integration works end to end: webhook idempotency and signature verification are proven by tests; refunds work; reconciliation catches missed webhooks.
- Audit service captures every state transition; Postgres retains it for a bounded window with partitioned drop-based retention (see NFR-6); ClickHouse holds the full analytical copy; the migration story is documented with a before/after benchmark.
- Drivers receive ride offers over WebSocket.
- The full system deploys cleanly to a **local** Kubernetes cluster (kind/minikube-style) — this is the final deployment target; no real cloud vendor is used. *(Trade-off, stated honestly: this gives up a "deployed to real cloud" CV bullet in exchange for keeping infra scope local and simple — local K8s still exercises the orchestration, service-discovery, and manifest-authoring skills that are the actual learning target; only the "ran on a real vendor's infra" claim is dropped.)*
- Git tags mark each milestone: `v0.1` (Kafka + observability), `v0.2` (+Stripe), `v0.3` (+Audit+ClickHouse), `v1.0` (+WebSockets+K8s).

The secondary, equally real goal: once this is done, translate the system into concrete CV bullets and interview talking points (the migration narrative, the concurrency-safety story, the idempotent-webhook story, the scale-stress story).

## 3. Features

Grouped by domain. IDs are stable and global.

### A. Rider Requests & Reads (FR-1–FR-8)
- **FR-1:** Rider can request a fare quote for a pickup/dropoff pair — returning fare, distance, and ETA — without creating a ride. This is the rider's entry point: price and time are known before committing. A quote is indicative, not binding: the fare is recomputed and locked at request time (FR-17), so a surge change between quote and request moves the price.
- **FR-2:** Rider requests a ride with pickup/dropoff coordinates; the fare is locked at request time and the rider receives a ride identifier.
- **FR-3:** A rider cannot have two simultaneous active rides — a new request is rejected while one is already in progress (`REQUESTED`/`MATCHED`/`IN_PROGRESS`).
- **FR-4:** Rider can look up their current active ride by rider identity, without needing the ride identifier. Per FR-3 this returns at most one ride, or nothing if the rider has none in flight.
- **FR-5:** Rider can read a single ride by its identifier, returning its current state and details. This is the rider's primary "where is my ride" call throughout the ride's life.
- **FR-6:** While a ride is `MATCHED`, the rider can see who is coming and where they are — the assigned driver's identity, their current position, and an ETA to pickup that updates as the driver moves. This is what makes the Vision's "watch a driver approach" literally true, and gives the location fast path (FR-26) a consumer beyond matching.
- **FR-7:** On a `COMPLETED` ride, the rider can see the final fare and the payment outcome (captured / failed / refunded), so the payment state machine has a rider-visible consequence rather than being purely internal.
- **FR-8:** Rider can query ride history, most recent first, bounded by a result-size limit.

### B. Matching, Fares & Ride State (FR-9–FR-15)
- **FR-9:** System matches each ride to the nearest available driver within a 5km radius; unmatched rides are retried on a scheduled interval.
- **FR-10:** A matched driver has a bounded window to accept; on timeout — or on an explicit decline (FR-21) — the offer is released and re-offered to the next-nearest driver.
- **FR-11:** If no driver can be found within a bounded overall window, the ride transitions to a terminal `NO_DRIVER` state and retrying stops — the rider gets a definitive answer instead of waiting indefinitely.
- **FR-12:** If the driver holding a `MATCHED` ride goes silent past a staleness window before starting the trip, the ride returns to `REQUESTED` and re-enters matching for the next-nearest driver, and the silent driver is released. The rider was never picked up, so the ride is still fulfillable — it is salvaged rather than killed.
- **FR-13:** If the driver on an `IN_PROGRESS` ride goes silent past a staleness window, the system auto-completes the ride, charging the fare locked at request time through the normal payment path (FR-31), and releases the driver. This is a deliberate simplification: the system cannot tell a finished trip from one abandoned mid-route, so it assumes completion — acceptable here because no real money moves. The audit trail records `SYSTEM` rather than `DRIVER` as the completing actor, so auto-completions stay distinguishable from genuine ones after the fact.
- **FR-14:** Full ride state machine: `REQUESTED → MATCHED → IN_PROGRESS → COMPLETED`, plus the terminal states `CANCELLED` and `NO_DRIVER`. Recovery from a silent driver (FR-12, FR-13) reuses existing states rather than adding new ones.
- **FR-15:** Rider may cancel only while `REQUESTED` or `MATCHED`; cancelling a `MATCHED` ride releases the driver back to available. Cancelling with a mismatched rider identity is rejected without revealing whether the ride exists.
- **FR-16:** Matching is race-safe under concurrency — no driver is ever double-booked.
- **FR-17:** Fare is computed at request time from a formula (base + distance + time) × surge multiplier.

### C. Driver Session & Actions (FR-18–FR-23)
- **FR-18:** Driver can set their own availability — going online (`AVAILABLE`) to start receiving offers, or offline (`OFFLINE`) to stop. A driver cannot go offline while on an active ride; they must complete or be released from it first. Going offline with a pending unaccepted offer releases that offer back for re-matching rather than leaving it to dangle.
- **FR-19:** Driver can read their current working state in one call — this is the driver's entry point: their availability status, any pending offer awaiting response (with pickup, dropoff, fare, and distance to pickup), and their active ride if one is in flight.
- **FR-20:** Driver can accept the ride currently offered to them — and only that ride. Acting on any other ride, or on no live offer at all, is rejected.
- **FR-21:** Driver can decline a pending offer, immediately releasing it to the next-nearest driver instead of the rider waiting out the offer timeout. Declining returns the driver to `AVAILABLE`.
- **FR-22:** Driver explicitly starts the trip once the rider is aboard, moving the ride `MATCHED → IN_PROGRESS`, and later completes it, moving it `IN_PROGRESS → COMPLETED`. Both actions are restricted to the driver's own assigned ride, and each is only valid from the preceding state. Completing a ride returns the driver to `AVAILABLE`.
- **FR-23:** Driver can query their own completed ride history, most recent first, bounded by a result-size limit — the driver-side counterpart to FR-8.

### D. Driver Location Tracking (FR-24–FR-27)
- **FR-24:** Drivers report location via a heartbeat; system tracks current position and availability status.
- **FR-25:** Location updates persist to a durable history (position audit trail).
- **FR-26:** Location reads are served from a fast path (sub-second) decoupled from durable slow-path persistence.
- **FR-27:** A driver whose last heartbeat is older than a bounded staleness window is treated as unavailable — they stop being matchable while idle, and any ride they are holding is recovered per FR-12 or FR-13 — so silent or dead drivers neither absorb offers they will never answer nor strand rides indefinitely.

### E. Event Backbone & Resilience (FR-28–FR-30)
- **FR-28:** Inter-service ride/driver/payment/audit events flow through an event backbone (Kafka), replacing direct service-to-service HTTP calls.
- **FR-29:** Producers and consumers apply retry-with-jitter and circuit-breaking on failure.
- **FR-30:** Multiple independent consumers can subscribe to the same event stream without coupling to each other.

### F. Payments (FR-31–FR-35)
- **FR-31:** On ride completion, the system creates a Stripe sandbox PaymentIntent for the fare.
- **FR-32:** Payment state machine: `INITIATED → AUTHORIZED → CAPTURED → REFUNDED` (plus `FAILED`).
- **FR-33:** Stripe webhooks are verified by signature and processed idempotently (deduped by event ID).
- **FR-34:** Full refund flow is supported (system/admin-triggered); one payment per ride.
- **FR-35:** A reconciliation task catches missed or failed webhook deliveries.

### G. Audit & Analytics (FR-36–FR-39)
- **FR-36:** Every domain state transition (ride, driver, payment) is recorded as an audit event, tagged with the actor that caused it — including `SYSTEM` for automated transitions such as offer expiry, `NO_DRIVER`, and auto-completion.
- **FR-37:** Audit events are queryable by entity and by actor; retained under a partitioning + retention policy (see NFR-6).
- **FR-38:** Audit data is mirrored to a columnar store for analytical queries at scale.
- **FR-39:** Aggregate analytics are computed from data already being collected, giving it an actual consumer instead of sitting unread: distance traveled per driver and ride density by area, computed from the location-ping history (FR-25) mirrored into the columnar store; driver utilization (% time in each status) computed from the existing driver state-transition audit trail (FR-36), not from location pings.

### H. Real-Time & Deployment (FR-40–FR-42)
- **FR-40:** Drivers receive ride offers via a live push channel (WebSocket), not polling.
- **FR-41:** All services expose health and metrics; dashboards show live operational KPIs (match latency, ride throughput, driver online count, payment success rate, audit ingest rate).
- **FR-42:** All services deploy to a local Kubernetes cluster.

### I. Identity & Simulation (FR-43–FR-44)
- **FR-43:** No authentication or registration exists; identity is passed per-request (rider: header-carried identifier; driver: fixture-seeded identifier) and trusted as-is. This is a deliberate scope decision, not an oversight — auth is out of scope for this project's learning goals.
- **FR-44:** A Simulator component generates synthetic riders, drivers, and ride traffic at configurable scale (deterministic/seeded) to exercise the system under concurrent load. It runs as an in-process test fixture early on and becomes a standalone containerized load generator later, with the ability to ramp generation volume up toward the stress-test scale in NFR-2.

### J. Live Operational Dashboard (FR-45)
- **FR-45:** A lightweight custom web UI shows live counts of system state — drivers by status, rides by status, active riders — pushed in real time via the same mechanism as FR-40. This is distinct from the Grafana/Prometheus dashboards (FR-41), which track infrastructure metrics, not domain/business state.

## 4. Non-Functional Requirements

Unusual framing: most of these are learning targets to prove out, not hard production requirements.

- **NFR-1 (Concurrency correctness):** Matching must be race-safe under concurrent load — no double-booked drivers, no lost updates — proven via concurrent test scenarios, not just code review.
- **NFR-2 (Scale ambition, phased):** The system is functionally proven at fixture scale (~30 drivers) through the core phases, but the Simulator must be able to ramp to a stress-test scale — roughly 20k drivers and 200k riders — as a late-phase milestone, generating enough concurrent load to surface real bottlenecks (connection pools, index gaps, Kafka partition throughput, cache hot-key contention) worth learning from. This is not a sustained-production-traffic requirement. **Payments is explicitly excluded from this stress test** (see NFR-8) — the scale target applies to the ride/matching/location pipeline.
- **NFR-3 (Resilience):** Kafka producers/consumers and Stripe API calls apply retry-with-jitter and circuit-breaking; failures degrade gracefully (dead-letter queue) rather than cascading.
- **NFR-4 (Idempotency & consistency):** Payment webhook processing is idempotent; ride and payment state machines reject invalid transitions.
- **NFR-5 (Observability):** Every service exposes health and metrics from day one; dashboards must make match latency, throughput, and error rates visible without log-diving.
- **NFR-6 (Data retention & queryability):** Audit data in Postgres is retained for a bounded window — proposed default of 12 months, dropped by partition thereafter — with the same logical history preserved indefinitely in ClickHouse for analytics. The 12-month figure is a stated, revisable target, not a hard commitment.
- **NFR-7 (Deployability, local only):** Every service builds and runs in Docker with no host JDK dependency. The final deployment target is a **local** Kubernetes cluster — no real cloud vendor is used at any point in this project.
- **NFR-8 (Payments scale boundary):** Stripe sandbox has its own rate limits, independent of anything Puber controls. Payments correctness (idempotency, webhook handling, refunds) is proven at normal/small concurrent scale, not exercised at the NFR-2 stress-test volume — the stress test targets the ride/matching/location pipeline only, so Stripe's external limits never gate the milestone.

## 5. Non-Goals / Out of Scope

- Real authentication, registration, or account management for riders or drivers
- Real payment processing (Stripe sandbox only — no real cards or money)
- Real maps or routing APIs (straight-line/haversine distance and a fixed-speed ETA formula stand in)
- Real mobile apps (clients are curl, a browser, Java tests, or the Simulator)
- Multi-city operation or geographic sharding
- Advanced/ML-driven surge pricing
- Real-time turn-by-turn navigation — instead, the Simulator advances a driver's position via straight-line drift toward the destination on each location heartbeat (FR-24), using the same haversine math as the ETA formula, not real road-network routing (this location history is not write-only, though — see FR-39 for its analytics use)
- Rider profiles or ratings
- Driver onboarding or document verification
- Dispatch logic beyond nearest-available-driver
- Driver-initiated cancellation — only riders can cancel (FR-15); a driver's exits are declining an offer (FR-21) or completing the ride (FR-22)
- Multiple vehicle types
- Any real cloud vendor deployment (see NFR-7)

**Deferred (explicitly out of the current plan, may resurface later):** a real riders table with foreign-key relationships, multiple vehicle types, scheduled/recurring rides, promo codes, a driver earnings dashboard.

## 6. Roadmap

Five phases across an 8-month / 32-week plan. Detailed week-by-week ticket breakdowns live in `docs/tickets/`; this is the phase-level shape only.

1. **Bootstrap + Domain + Matching** (Weeks 1–7): Core services stand up; domain model, Postgres persistence, and fixtures land; in-memory nearest-driver matching works end to end against the Simulator, with no Kafka yet.
2. **Kafka + Resilience + Observability** (Weeks 9–16): Direct inter-service HTTP calls are replaced by an event backbone; resilience patterns and full observability (metrics + dashboards) land; the driver-location fast path is introduced.
3. **Stripe Payments** (Weeks 17–20): A dedicated payment service consumes ride-completion events, integrates Stripe sandbox PaymentIntents, and proves out idempotent webhook handling, the payment state machine, and refunds. Milestone `v0.2`.
4. **Audit + ClickHouse** (Weeks 21–24): An audit service captures state-transition events across all domains; storage starts in partitioned Postgres and migrates to ClickHouse for analytics, with the migration benchmarked; the same ClickHouse pipeline is reused to compute driver utilization, distance-traveled, and ride-density analytics (FR-39) from location and audit history. Milestone `v0.3`.
5. **Real-Time + Local Kubernetes** (Weeks 25–32): Drivers receive ride offers over WebSocket; the live operational dashboard (FR-45) ships; the full system is deployed to a local Kubernetes cluster; the stress-test scale target (NFR-2) is exercised. Milestone `v1.0`. *(Local K8s only — no cloud vendor; see NFR-7.)*

## 7. Glossary

- **Rider** — the actor requesting a ride; identified per-request by a passed identifier (FR-43), no account.
- **Driver** — the actor fulfilling rides; fixture-seeded, tracked by location and status, and responsible for their own availability (FR-18).
- **Admin** — the actor triggering system-level actions (e.g., refunds); not a real authenticated role, just an actor-type label.
- **System** — the scheduler/automated actor (retry tasks, offer-timeout expiry, silent-driver recovery, auto-completion) acting without a human trigger; recorded as the actor on the audit events it causes (FR-36).
- **Silent driver** — a driver whose last heartbeat is older than the staleness window (FR-27); treated as unavailable, with any ride they hold recovered per FR-12 or FR-13.
- **Simulator** — the synthetic load-generation component standing in for real riders and drivers (FR-44).
- **Ride states** — `REQUESTED → MATCHED → IN_PROGRESS → COMPLETED`, plus two terminal states: `CANCELLED` (rider-initiated, from `REQUESTED`/`MATCHED`) and `NO_DRIVER` (system-initiated, when matching gives up).
- **Driver status** — `OFFLINE` / `AVAILABLE` / `BUSY`.
- **Payment states** — `INITIATED → AUTHORIZED → CAPTURED → REFUNDED`, or `FAILED`.
- **Offer** — a time-bounded proposal of a ride to a specific driver; released to the next-nearest driver if declined, if the driver goes offline, or if it is not accepted in time.
- **Quote** — a read-only fare/distance/ETA estimate for a pickup/dropoff pair that creates no ride (FR-1).
- **Fare** — the price computed at request time: (base + distance + time) × surge multiplier.
- **ETA** — estimated arrival time of the matched driver at pickup, derived from haversine distance and a fixed assumed speed (not the ride's own trip duration).
- **Audit event** — an immutable record of a single domain state transition, tagged with an actor type/ID and entity type/ID.

## 8. Open Questions

- **Non-blocking:** How exactly is Payments excluded from the NFR-2/NFR-8 stress test (simulator flag, stubbed payment path, separate smaller test suite, etc.)? Deliberately left open for the Architecture or Epics/Stories pass — see `addendum.md` for context. Doesn't block starting either.

No other open items at this time — additional ones get added here as they surface during Architecture or Epics/Stories work.
