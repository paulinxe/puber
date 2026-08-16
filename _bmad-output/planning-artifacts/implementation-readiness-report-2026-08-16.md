---
status: final
readiness: 'READY — conditional (0 critical, 22 actionable issues)'
stepsCompleted:
  - step-01-document-discovery
  - step-02-prd-analysis
  - step-03-epic-coverage-validation
  - step-04-ux-alignment
  - step-05-epic-quality-review
  - step-06-final-assessment
documentsIncluded:
  prd:
    - _bmad-output/planning-artifacts/prds/prd-puber-2026-08-02/prd.md
    - _bmad-output/planning-artifacts/prds/prd-puber-2026-08-02/addendum.md
  spec:
    - _bmad-output/specs/spec-puber/SPEC.md
    - _bmad-output/specs/spec-puber/glossary.md
    - _bmad-output/specs/spec-puber/state-machines.md
    - _bmad-output/specs/spec-puber/roadmap.md
  architecture:
    - _bmad-output/planning-artifacts/architecture/architecture-puber-2026-08-03/ARCHITECTURE-SPINE.md
  epics:
    - _bmad-output/planning-artifacts/epics.md
  ux: []
  flows:
    - _bmad-output/planning-artifacts/prds/prd-puber-2026-08-02/flows.html
  context:
    - docs/puber.md
    - docs/tickets/pb-1.1.md
    - docs/tickets/pb-2.1.md
    - docs/tickets/pb-3.1.md
    - docs/tickets/pb-4.1.md
    - docs/tickets/pb-5.1.md
    - docs/tickets/pb-6.1.md
    - docs/tickets/pb-7.1.md
    - AGENTS.md
  priorReviews:
    - _bmad-output/planning-artifacts/prds/prd-puber-2026-08-02/review-rubric.md
    - _bmad-output/planning-artifacts/architecture/architecture-puber-2026-08-03/reviews/review-rubric.md
    - _bmad-output/planning-artifacts/architecture/architecture-puber-2026-08-03/reviews/review-adversarial.md
    - _bmad-output/planning-artifacts/architecture/architecture-puber-2026-08-03/reviews/review-currency.md
uxStatus: 'N/A — confirmed backend-only product by user on 2026-08-16'
---

# Implementation Readiness Assessment Report

**Date:** 2026-08-16
**Project:** puber

## Step 1: Document Discovery

### Document Inventory

| Type | File | Size | Modified | Role |
|---|---|---|---|---|
| PRD | `prds/prd-puber-2026-08-02/prd.md` | 43.5 KB | 2026-08-14 19:32 | Requirements source of truth |
| PRD | `prds/prd-puber-2026-08-02/addendum.md` | 14.8 KB | 2026-08-14 17:23 | Requirements addendum |
| Spec | `specs/spec-puber/SPEC.md` | 33.0 KB | 2026-08-16 14:51 | Machine contract / traceability keys |
| Spec | `specs/spec-puber/glossary.md` | 5.7 KB | 2026-08-14 16:53 | Term definitions |
| Spec | `specs/spec-puber/state-machines.md` | 9.7 KB | 2026-08-14 17:37 | State transitions |
| Spec | `specs/spec-puber/roadmap.md` | 5.3 KB | 2026-08-16 14:51 | Sequencing |
| Architecture | `architecture/architecture-puber-2026-08-03/ARCHITECTURE-SPINE.md` | 70.2 KB | 2026-08-14 17:43 | Technical invariants |
| Epics/Stories | `planning-artifacts/epics.md` | 158.5 KB | 2026-08-16 14:54 | **Artifact under assessment** |
| UX | — | — | — | **N/A — backend-only product** |
| Context | `docs/puber.md` | — | — | Original source brief |
| Context | `docs/tickets/pb-1.1.md` … `pb-7.1.md` | — | — | 7 pre-existing tickets |
| Context | `AGENTS.md` | — | — | Repo conventions |

### Prior Review Artifacts (evidence, not requirements sources)

- `prds/prd-puber-2026-08-02/review-rubric.md`
- `architecture/.../reviews/review-rubric.md`
- `architecture/.../reviews/review-adversarial.md`
- `architecture/.../reviews/review-currency.md`

Also present but excluded as workflow logs, not specs: `.memlog.md` files under prds/, architecture/, specs/, and party-mode/. Excluded as reconciliation notes: `reconcile-puber-md.md`, `reconcile-tickets.md` (consulted for context only).

### Discovery Findings

- **No duplicate document formats.** No document type exists as both a whole `.md` and a sharded folder. No resolution required.
- **UX document absent — resolved as N/A.** User confirmed on 2026-08-16 that `puber` is a backend-only product with no user-facing surface. UX→epic traceability is therefore out of scope for this assessment and is scored N/A rather than as a gap.
- **Freshness signal:** `SPEC.md`, `roadmap.md` (2026-08-16 14:51) and `epics.md` (2026-08-16 14:54) are the most recently modified artifacts. The PRD (2026-08-14 19:32) and ARCHITECTURE-SPINE (2026-08-14 17:43) are two days older — currency of downstream epics against upstream requirements must be verified in later steps.

**Status:** Document inventory confirmed by user. Proceeding to file validation.

---

## Step 2: PRD Analysis

**Sources read completely:** `prd.md` (final, updated 2026-08-14), `addendum.md`.

Requirements are extracted **decomposed into atomic obligations** rather than copied as prose. Each FR in this PRD bundles several independently-testable obligations inside one paragraph, and coverage gaps hide at that level — an epic can satisfy "FR-16: rider can cancel" while silently dropping "cancel releases a hold that lands *after* the cancellation". The lettered sub-obligations below are the units traced in Step 3. Rationale prose is not reproduced; every normative claim is.

### Functional Requirements Extracted

#### A. Rider Requests & Reads

**FR-1 — Fare quote.** (a) Quote for a pickup/dropoff pair returns fare, distance and ETA; (b) creates no ride; (c) quote is indicative — fare is recomputed and locked at request time, so surge movement between quote and request changes the price; (d) when no driver is available the quote still returns fare and distance with **no ETA**, not an error.

**FR-2 — Request a ride.** (a) Accepts pickup/dropoff coordinates plus a payment-method token; (b) fare locked at request time; (c) ride identifier returned to rider **immediately**; (d) authorization proceeds in the background (FR-9); (e) no payment method is stored — the token travels with the request; (f) refusals under FR-3 and FR-51 are **distinguishable from one another by the caller**.

**FR-3 — One active ride per rider.** (a) A new request is rejected while the rider has a ride in flight; (b) the in-flight set is exactly `REQUESTED`/`WAITING_MATCH`/`OFFERED`/`MATCHED`/`IN_PROGRESS` — every non-terminal state counts.

**FR-4 — Read active ride by rider identity.** (a) Lookup without the ride identifier; (b) returns at most one ride; (c) returns nothing if the rider has none in flight.

**FR-5 — Read ride by identifier.** Returns current state and details.

**FR-6 — Rider sees the approaching driver.** While `MATCHED`: (a) assigned driver's identity; (b) driver's current position; (c) ETA to pickup that **updates as the driver moves**.

**FR-7 — Rider sees payment outcome.** (a) Final fare on a finished ride; (b) whether it was captured, refunded, or left uncaptured as `CAPTURE_FAILED` on a `COMPLETED` ride; (c) that authorization was declined on a `PAYMENT_FAILED` ride; (d) a capture **still retrying is not yet an outcome** and is not surfaced as one.

**FR-8 — Rider ride history.** Most recent first, bounded by a result-size limit.

**FR-51 — Money-outstanding admission guard.** (a) Refuse a request while the rider's previous ride is terminal but its payment is still `INITIATED` or `AUTHORIZED`; (b) refuse while the rider's most recent payment reached `CAPTURE_FAILED` **less than 30 minutes** ago; (c) both refusals and FR-3's are distinguishable by the caller; (d) counted separately by reason (FR-46); (e) the 30-minute window is the **entire** clearing mechanism — self-lapsing, nothing attached to the rider, no operator action, no appeal; (f) measured on **wall clock** from a recorded `capture_failed_at`, comparable across restarts and services (NFR-9); (g) the guard sits at request admission **and nowhere else** — no ride already in flight is delayed by it.

#### B. Matching, Fares & Ride State

**FR-9 — No dispatch before authorization.** (a) Ride persisted as `REQUESTED` the instant the rider commits, meaning *awaiting authorization* and nothing else; (b) hold for the locked fare placed asynchronously; (c) hold lands → `WAITING_MATCH`; (d) authorization declined → terminal `PAYMENT_FAILED` and **no driver is ever offered it**.

**FR-10 — Nearest-driver matching.** (a) Match each ride to the nearest available driver within a **5 km** radius; (b) unmatchable rides retried **continuously** until matched or given up on; (c) matching reads **only** rides in `WAITING_MATCH`.

**FR-11 — Bounded offer window.** (a) Offered driver has a bounded window to accept; ride sits in `OFFERED`; (b) on timeout **or** explicit decline (FR-23) the ride returns to `WAITING_MATCH` and is offered to the next-nearest driver; (c) a driver who has declined or timed out on a ride is **never offered that ride again**.

**FR-12 — `NO_DRIVER` terminal.** (a) Bounded overall window on a `WAITING_MATCH` ride; (b) on expiry → terminal `NO_DRIVER`; (c) retrying stops; (d) the authorization hold is released (FR-36); (e) rider is never charged.

**FR-13 — Silent driver before pickup.** (a) `MATCHED` driver silent past a staleness window before trip start → ride returns to `WAITING_MATCH`; (b) ride re-enters matching for next-nearest; (c) the silent driver is released; (d) returns to `WAITING_MATCH` **not** `REQUESTED`, because the hold must not be re-authorized.

**FR-14 — Silent driver mid-trip.** (a) `IN_PROGRESS` driver silent past a staleness window → system **auto-completes** the ride; (b) captures the fare locked at request time through the normal payment path; (c) releases the driver; (d) the ride records whether it was completed **by the driver or by the system**; (e) the audit trail records the same as the acting actor; (f) auto-completions stay distinguishable both on read and after the fact; (g) completion remains a **single** state — how it completed is recorded alongside, not as a separate state.

**FR-15 — Ride state machine.** `REQUESTED → WAITING_MATCH → OFFERED → MATCHED → IN_PROGRESS → COMPLETED`, plus terminals `CANCELLED`, `NO_DRIVER`, `PAYMENT_FAILED`. (a) `MATCHED` means a driver **accepted**; awaiting an answer is `OFFERED`; (b) `REQUESTED` is entered **exactly once** and never returned to; (c) recovery paths (FR-11, FR-13) land in `WAITING_MATCH`.

**FR-16 — Rider cancellation.** (a) Allowed any time before the trip starts — `REQUESTED`, `WAITING_MATCH`, `OFFERED`, `MATCHED`; (b) releases any assigned driver back to available; (c) withdraws any offer still outstanding; (d) any authorization hold is released (FR-36) **including one that lands after the cancellation**; (e) a mismatched rider identity is rejected **without revealing whether the ride exists**.

**FR-17 — Race safety.** (a) No driver is ever double-booked under concurrency; (b) rider-cancel vs driver-accept on the same ride: whichever commits first wins, the loser is **rejected** rather than both applying.

**FR-18 — Fare formula.** `(base + distance + time) × surge`, computed at request time.

**FR-19 — Surge.** (a) A multiplier held in **configurable fare rules**; (b) static `1.00` through the early phases; (c) from the event-backbone phase onward recomputed **periodically** from outstanding-requests / available-drivers ratio; (d) current multiplier exposed as an operational metric (FR-46).

#### C. Driver Session & Actions

**FR-20 — Driver availability.** (a) Driver sets own availability `AVAILABLE`/`OFFLINE`; (b) the system **never** puts a driver online on their behalf; (c) with an offer outstanding (`OFFERED`), going offline releases the offer and succeeds; (d) once accepted (`MATCHED`/`IN_PROGRESS`) going offline is **refused** until they complete or are released.

**FR-21 — Driver working state, one call.** Returns (a) declared availability status; (b) whether the system is currently hearing their heartbeat; (c) any pending offer with pickup, dropoff, fare and distance-to-pickup; (d) their active ride if one is in flight.

**FR-22 — Accept.** (a) Driver can accept the ride currently offered to them **and only that ride**; (b) acting on any other ride, or with no live offer, is rejected.

**FR-23 — Decline.** (a) Declining immediately releases the offer to the next-nearest driver; (b) driver returns to `AVAILABLE`.

**FR-24 — Start / complete trip.** (a) Explicit start moves `MATCHED → IN_PROGRESS`; (b) explicit complete moves `IN_PROGRESS → COMPLETED`; (c) both restricted to the driver's **own assigned** ride; (d) each valid only from the preceding state; (e) completing returns the driver to `AVAILABLE`.

**FR-25 — Driver ride history.** Own completed rides, most recent first, bounded by a result-size limit.

#### D. Driver Location Tracking

**FR-26 — Heartbeat.** Drivers report location via heartbeat; system tracks current position and availability status.

**FR-27 — Durable location history.** Location updates persist to a durable history (position audit trail).

**FR-28 — Fast-path reads.** Location reads served **sub-second** from a fast path, decoupled from durable slow-path persistence.

**FR-29 — Declared status vs observed reachability.** (a) The two are separate facts; (b) reachability is derived from whether the last heartbeat is inside a bounded staleness window; (c) it **never writes** to declared status; (d) a driver is matchable **only when declared `AVAILABLE` and heartbeat fresh**; (e) a driver who loses and regains signal is matchable again on their next heartbeat, with no action required.

**FR-30 — Session expiry.** (a) An **idle** driver unreachable far longer than any staleness window is set `OFFLINE`; (b) recorded as a **system** action; (c) must explicitly go online again to receive offers; (d) applies **only to idle drivers**; (e) the expiry window is always longer than the FR-13/FR-14 windows, so a driver is never expired mid-ride.

#### E. Event Backbone & Resilience

**FR-31 — Event backbone.** (a) Domain events — ride, driver, payment, audit — flow through Kafka rather than direct service-to-service calls; (b) commands and reads that must return an identifier, rejection or result stay **synchronous for the life of the project**.

**FR-32 — Resilience on the backbone.** Producers and consumers apply retry-with-jitter and circuit-breaking on failure.

**FR-33 — Independent consumers.** Multiple independent consumers subscribe to the same event stream without coupling to each other.

#### F. Payments

**FR-34 — Two-phase lifecycle.** (a) Fare **authorized** as a hold when the ride is requested; (b) **captured** when the ride completes; (c) authorization is asynchronous — ride waits in `REQUESTED` until the hold resolves; (d) nothing is captured for a ride that never delivers a trip.

**FR-35 — Payment state machine.** `INITIATED → AUTHORIZED → CAPTURED → REFUNDED`, plus terminals `FAILED` (authorization declined, nobody charged), `CAPTURE_FAILED` (delivered trip, hold no longer capturable — revenue loss), `VOIDED` (hold released without capture). `FAILED` and `CAPTURE_FAILED` are **separate states**; only the second is counted as revenue loss (FR-46).

**FR-36 — Void on no-trip.** (a) `CANCELLED` (FR-16) or `NO_DRIVER` (FR-12) → hold released, payment `VOIDED`; (b) an authorization that **lands after** the ride reached a terminal state is voided on arrival, never left outstanding; (c) the rider is never charged for a ride that did not happen.

**FR-37 — Capture pursued until settled.** (a) Failed capture retried with jittered backoff under FR-32; (b) payment stays `AUTHORIZED` while retrying; (c) retrying **survives process restarts**; (d) there is **no retry cap**; (e) `CAPTURE_FAILED` is reached **only** when the provider reports the hold is no longer capturable (expired, revoked, cancelled) — an unreachable provider is never an outcome; (f) the outcome is recorded and surfaced to the rider (FR-7); (g) the ride stays `COMPLETED`; (h) a hold always ends captured, voided, or provably uncapturable — never in limbo.

**FR-38 — Webhooks.** Verified by signature and processed idempotently, deduped by provider event ID.

**FR-39 — Refunds.** (a) Refund issued against the provider; (b) payment moved to `REFUNDED`; (c) refund webhook processed idempotently; (d) result reconciled; (e) triggered by an **internal operator-facing call only** — no rider-facing path; (f) exercised by tests and the Simulator; (g) **one payment per ride**.

**FR-40 — Reconciliation.** (a) Catches missed or failed webhook deliveries; (b) flags any hold outstanding longer than a ride can plausibly live; (c) **neither is corrected automatically**.

#### G. Audit & Analytics

**FR-41 — Audit every transition.** Every domain state transition (ride, driver, payment) recorded as an audit event tagged with the causing actor, including `SYSTEM` for offer expiry, `NO_DRIVER`, auto-completion and session expiry.

**FR-42 — Audit queryability & retention.** (a) Queryable by entity; (b) queryable by actor; (c) retained under a partitioning + retention policy (NFR-6).

**FR-43 — Columnar mirror.** Audit data mirrored to a columnar store for analytical queries at scale.

**FR-44 — Aggregate analytics.** (a) Distance traveled per driver — from location-ping history (FR-27) in the columnar store; (b) ride density by area — same source; (c) driver utilization (% time in each status) — from the driver **state-transition audit trail** (FR-41), explicitly **not** from location pings.

#### H. Real-Time & Deployment

**FR-45 — Driver push channel.** (a) WebSocket rather than polling; (b) carries ride offers; (c) carries the state changes a driver must react to — rider cancelled, offer withdrawn or expired, ride auto-completed.

**FR-46 — Health, metrics, dashboards.** (a) All services expose health and metrics; (b) dashboards show live operational KPIs; (c) **match latency** = request → driver accepting; (d) reported **alongside time-to-first-offer**; (e) **drivers online** counts only *matchable* drivers (declared available **and** currently reachable); (f) **capture loss** — count **and summed amount** of `CAPTURE_FAILED`, zero in a healthy system, alerting on its own; (g) **age of the oldest capture still retrying**, alerting on its own; (h) **refused ride requests counted split by reason** — one active ride (FR-3), unsettled payment (FR-51a), recent `CAPTURE_FAILED` (FR-51b) — never as a single total; (i) also surfaced: ride throughput, current surge multiplier, payment success rate, audit ingest rate, rides awaiting authorization, undelivered-event backlog age.

**FR-47 — Local Kubernetes.** All services deploy to a local Kubernetes cluster.

#### I. Identity & Simulation

**FR-48 — No auth.** (a) No authentication or registration; (b) identity passed per-request — rider via header-carried identifier, driver via fixture-seeded identifier; (c) trusted as-is.

**FR-49 — Simulator.** (a) Generates synthetic riders, drivers and ride traffic at configurable scale; (b) deterministic/seeded; (c) runs as an in-process test fixture early; (d) becomes a standalone containerized load generator later; (e) can ramp toward the NFR-2 stress scale; (f) supplies payment-method tokens on ride requests; (g) including **deliberately-declining test tokens** so the authorization-failure path is routinely exercised; (h) generates coordinates relative to the **configured bounds**, not fixed literals.

#### J. Live Operational Dashboard

**FR-50 — Live domain dashboard.** (a) Lightweight custom web UI; (b) live counts of drivers by status, rides by status, active riders; (c) pushed in real time via the same mechanism as FR-45; (d) distinct from the Grafana/Prometheus infra dashboards of FR-46.

**Total FRs: 51** (FR-1 – FR-51), decomposing to **≈190 atomic obligations**.

### Non-Functional Requirements Extracted

**NFR-1 (Concurrency correctness).** Matching race-safe under concurrent load — no double-booked drivers, no lost updates — **proven via concurrent test scenarios, not just code review**.

**NFR-2 (Scale ambition, phased).** (a) Functionally proven at fixture scale (~30 drivers) through the core phases; (b) Simulator must ramp to ~**20k drivers / 200k riders** as a **late-phase** milestone; (c) load must surface real bottlenecks — connection pools, index gaps, Kafka partition throughput, cache hot-key contention; (d) not a sustained-production-traffic requirement; (e) **the operating area is a scenario parameter, not a constant** — the stress scenario widens the grid so "nearest driver" stays a real question; (f) payments explicitly excluded (NFR-8).

**NFR-3 (Resilience).** Kafka producers/consumers and Stripe API calls apply retry-with-jitter and circuit-breaking; failures degrade gracefully via **dead-letter queue** rather than cascading.

**NFR-4 (Idempotency & consistency).** (a) Delivery is **at-least-once throughout** — backbone redelivers, provider webhooks retry, outbound calls retried; (b) duplicate processing is normal, not exceptional; (c) **every** event consumer and externally-triggered handler must be idempotent, deduplicating on a stable event identifier; (d) payment webhooks are one instance of this rule, not its whole scope; (e) ride and payment state machines **reject invalid transitions**, so a replayed event cannot advance state twice.

**NFR-5 (Observability).** (a) Every service exposes health and metrics **from day one**; (b) dashboards make match latency, throughput and error rates visible without log-diving; (c) money watched separately from throughput — capture loss and oldest-retrying-capture age both surfaced and alerting independently; (d) refused ride requests counted **split by reason**.

**NFR-6 (Data retention & queryability).** (a) Audit data in Postgres retained for a bounded window — proposed default **12 months**; (b) dropped **by partition** thereafter; (c) same logical history preserved **indefinitely** in ClickHouse; (d) the 12-month figure is a stated, revisable target.

**NFR-7 (Deployability, local only).** (a) Every service builds and runs in Docker with **no host JDK dependency**; (b) final deployment target is a **local** Kubernetes cluster; (c) no real cloud vendor at any point.

**NFR-8 (Payments scale boundary).** (a) Payments correctness proven at normal/small concurrent scale, not at NFR-2 volume; (b) the exclusion is achieved by **swapping the payment provider, not skipping the payment path** — the service, its state machine and every transition still run, only the outbound Stripe call is replaced; (c) the same swap lets the system run before payments are built and in CI without provider credentials.

**NFR-9 (Determinism & time control).** (a) Simulator runs reproducible — same seed, same sequence of ride requests and driver movements; (b) **every** bounded time window (offer timeout, retry interval, `NO_DRIVER` window, staleness windows of FR-13/FR-14/FR-29, session expiry, capture backoff, FR-51 capture cooldown) exercisable under a **controlled clock**, testable in seconds without flakiness; (c) elapsed durations and deadlines measured against a **monotonic** clock, never wall-clock arithmetic; (d) **wall-clock** timestamps for recorded facts — audit event times, `requested_at`, retention partitioning, `capture_failed_at`; (e) the clock abstraction must satisfy both: controllable in tests, monotonic where durations are involved.

**NFR-10 (Payment data handling).** (a) Payment-method tokens never written to logs, echoed in API responses, or persisted beyond what the provider integration requires; (b) provider API keys live in environment configuration, never in source or fixtures; (c) sandbox tokens only, but the handling discipline is practised as though real.

**Total NFRs: 10** (NFR-1 – NFR-10), decomposing to **≈35 atomic obligations**.

### Additional Requirements & Constraints (not FR/NFR-numbered)

From §2 Goals, §6 Roadmap and `addendum.md` — these are binding but carry no requirement ID, which makes them the likeliest to be dropped silently:

**C-1 (Service topology).** Exactly five services — `rider-service`, `driver-service`, `matching-service`, `payment-service`, `audit-service` — each building and running in Docker with no host JDK.
**C-2 (Gateway isolation).** All traffic behind a single gateway; **no service directly addressable**; `matching-service` **never publicly routable at all**.
**C-3 (Schema management).** Postgres schema versioned via **Flyway** across all migrations; fixture drivers seed automatically.
**C-4 (No ORM).** Explicit SQL throughout; immutable domain objects.
**C-5 (Milestone tags).** Git tags `v0.1` (Kafka + observability), `v0.2` (+Stripe), `v0.3` (+Audit+ClickHouse), `v1.0` (+WebSockets+K8s).
**C-6 (Migration benchmark).** The Postgres → ClickHouse migration story is **documented with a before/after benchmark**.
**C-7 (Portfolio output).** Translate the system into CV bullets and interview talking points — the migration narrative, concurrency-safety, idempotent-webhook, and scale-stress stories.
**C-8 (Database-per-service).** Architecture-run override of the addendum's shared-DB assumption; driver identity/location and dispatch state owned by different services and never queried across.
**C-9 (No Postgres location history).** Superseding the addendum: pings go to the columnar store only; live position served from cache. *(Note: this changes how FR-27 must be read — flagged in Step 3.)*
**C-10 (Isolation level).** Postgres default `READ COMMITTED`; guarded conditional updates plus a partial unique index for the one-active-ride rule — **not** `SELECT ... FOR UPDATE`, and not application-held locks.
**C-11 (Service independence).** Each service fully independent — own build, no shared library, duplicated domain code accepted.
**C-12 (Continuous claim-based matching).** Superseding the addendum: claim-based workers, not a fixed-schedule timer, so latency is not bounded by a tick.
**C-13 (Outbox).** Per service: domain events written in the same transaction as the state change they describe, relayed and then removed.
**C-14 (Tech stack).** Java/Spring Boot, Postgres, Kafka, Redis, Stripe sandbox, ClickHouse, Prometheus + Grafana, Resilience4j, WebSockets, HAProxy at the edge, gRPC between services, Kubernetes with GitOps delivery.
**C-15 (Time-constant ordering).** The staleness windows are one ordered set whose **ordering must survive any retuning**: idle < matched < in-progress < session expiry, all comfortably larger than clock skew plus delivery lag; the `NO_DRIVER` window ≈ six offer attempts' worth of time.
**C-16 (Hold-leak invariant).** Asserted in tests plus a metric and an alert — deliberately **not** an automatic sweep. The cancel-while-authorizing race handled by a flag on the payment, not a cross-service query.
**C-17 (No authorization timeout).** A ride waiting on authorization has **no timeout at all**; FR-3 is the admission control instead; a metric and alert replace the mechanism.
**C-18 (Phase ordering).** Five phases / 32 weeks: (1) Bootstrap+Domain+Matching W1–7, (2) Kafka+Resilience+Observability W8–16, (3) Stripe W17–20, (4) Audit+ClickHouse W21–24, (5) Real-Time+K8s W25–32.
**C-19 (Stale tickets are not a source).** `docs/tickets/pb-1.1.md`–`pb-7.1.md` are explicitly **not authoritative** and must not be reconciled against. Epics must derive from PRD + architecture spine only.

### PRD Completeness Assessment

**Overall: strong — among the more rigorous PRDs I have assessed.** Specific observations that shape the rest of this review:

**Strengths**
- **§8 Open Questions is genuinely empty**, and the one question it carried was closed by the architecture pass and folded into NFR-8. The addendum's "Still Open" section likewise confirms nothing product-level blocks epics.
- Requirement IDs are **stable and global**, and FR-51 was appended rather than renumbered — traceability keys survive edits.
- The PRD anticipates its own failure modes: FR-51 is explicitly reasoned against the deferred "debtor standing", FR-37 against a retry cap, C-17 against an authorization timeout. Each records **why the rejected alternative is wrong**, which is what stops a story author quietly reintroducing it.
- Terminology is pinned in §7 Glossary, including the two collision-prone pairs (ride `PAYMENT_FAILED` vs payment `FAILED`; declared status vs derived reachability).
- Non-Goals are unusually specific and cross-referenced, including the honest admission that FR-14 can charge for a trip that did not finish with **no in-system rider remedy**.

**Risks carried into Steps 3–5**
1. **Requirement obligations are buried in prose.** The PRD's density is a strength for correctness and a hazard for coverage: a single FR paragraph can carry seven distinct obligations (FR-14, FR-37, FR-46). Epics that trace at FR granularity will look complete while missing sub-obligations. This is the primary thing Step 3 must test, and it is why the extraction above is decomposed.
2. **FR-27 vs C-9 tension.** FR-27 says location updates "persist to a durable history (position audit trail)"; the architecture run replaced Postgres location history entirely with columnar-store-only writes. The FR text was not updated to match. Not a contradiction in intent — the durable history still exists — but the PRD text implies a store the architecture removed. Step 4 must confirm the epics implement the architecture's reading, not the FR's literal one.
3. **Unnumbered constraints (C-1 – C-19) have no traceability handle.** Nineteen binding constraints live in §2 Goals, §6 Roadmap and the addendum with no ID for an epic to reference. Gateway isolation (C-2), `matching-service` never publicly routable (C-2), Flyway (C-3), the milestone tags (C-5), the migration benchmark (C-6) and the time-constant ordering (C-15) are all real acceptance criteria that no FR number points at. I have assigned C-IDs above **for this assessment only** so Step 3 can trace them; they do not exist in the source documents.
4. **NFR-2's "operating area is a scenario parameter" (NFR-2e) and FR-49h are the same obligation stated twice** in different sections. Harmless, but it means a story satisfying one may be assumed to satisfy both without anyone checking.
5. **NFR-9's dual-clock rule is the subtlest requirement in the document** — monotonic for durations, wall-clock for recorded facts, with FR-51 as the case that needs both. It is stated once, in an NFR, and it constrains nearly every timing-related story. High risk of being dropped into a single "make time testable" story that misses the monotonic-vs-wall-clock split.
6. **The 12-month retention figure is explicitly revisable** (NFR-6d) — stories must not hard-code it as a fixed acceptance criterion without noting it is a tunable default.

**No blocking PRD defects found.** The PRD is complete enough to assess epics against. Proceeding to epic coverage validation.

---

## Step 3: Epic Coverage Validation

**Source read completely:** `epics.md` (3,158 lines / 158.5 KB) — 7 epics, **59 stories**, plus a Requirements Inventory, an FR Coverage Map, an NFR coverage table, an Additional Requirements section carrying the architecture constraints, and an explicit testing policy.

The epics document ships its **own** FR Coverage Map claiming all 51 FRs are mapped. That claim is not taken at face value: the matrix below re-derives coverage from the **story acceptance criteria**, not from the map's self-assertion, and traces at the atomic-obligation level established in Step 2.

### Epic & Story Inventory

| Epic | Title | Stories | Milestone |
|---|---|---|---|
| 1 | Foundations & Fare Quote | 1.1 – 1.4 (4) | — |
| 2 | Driver Presence & Location Tracking | 2.1 – 2.7 (7) | — |
| 3 | The Ride Loop | 3.1 – 3.16 (16) | — |
| 4 | Event Backbone, Resilience & Operational Visibility | 4.1 – 4.9 (9) | `v0.1` |
| 5 | Payments | 5.1 – 5.11 (11) | `v0.2` |
| 6 | Audit & Analytics | 6.1 – 6.6 (6) | `v0.3` |
| 7 | Real-Time, Live Dashboard & Local Kubernetes | 7.1 – 7.6 (6) | `v1.0` |
| | **Total** | **59** | |

### Coverage Matrix

Verified against story ACs. "Split" means an FR is deliberately delivered across epics with an explicit scope boundary in the earlier story.

| FR | Claimed Epic(s) | Verified in stories | Status |
|---|---|---|---|
| FR-1 | 1 | 1.4 (no-driver branch), 2.6 (ETA branch) | ✓ Covered (split, boundary stated) |
| FR-2 | 3 + 5 | 3.2, 5.3 (token) | ✓ Covered |
| FR-3 | 3 | 3.3 | ✓ Covered |
| FR-4 | 3 | 3.4 | ✓ Covered |
| FR-5 | 3 | 3.4 | ✓ Covered |
| FR-6 | 3 | 3.16 | ✓ Covered |
| FR-7 | 5 | 5.10 | ✓ Covered |
| FR-8 | 3 | 3.4 | ✓ Covered |
| FR-9 | 3 + 5 | 3.2 (gate), 5.3 (async auth) | ✓ Covered |
| FR-10 | 3 | 3.5 | ✓ Covered |
| FR-11 | 3 | 3.7 | ✓ Covered |
| FR-12 | 3 | 3.13 (ride side), 5.6 (hold void) | ✓ Covered (split, boundary stated) |
| FR-13 | 3 | 3.8 | ✓ Covered |
| FR-14 | 3 | 3.10, 5.4 (capture), 6.1 (actor) | ⚠️ **Partial — FR-14(f) read-side gap** |
| FR-15 | 3 | 3.1 | ✓ Covered |
| FR-16 | 3 + 5 | 3.14, 5.6 (late-landing void) | ✓ Covered |
| FR-17 | 3 | 3.3, 3.5, 3.6, 3.14 | ✓ Covered |
| FR-18 | 1 | 1.3 | ✓ Covered |
| FR-19 | 1 + 4 | 1.3 (static), 4.8 (demand-derived + metric) | ✓ Covered |
| FR-20 | 2 | 2.2, 3.11 (engagement guard) | ✓ Covered |
| FR-21 | 2 + 3 | 2.5 (status+reachability), 3.12 (offer+ride) | ✓ Covered |
| FR-22 | 3 | 3.6 | ✓ Covered |
| FR-23 | 3 | 3.7 | ✓ Covered |
| FR-24 | 3 | 3.9 | ✓ Covered |
| FR-25 | 3 | 3.15 | ✓ Covered |
| FR-26 | 2 | 2.3 | ✓ Covered |
| FR-27 | 6 | 6.5 | ✓ Covered |
| FR-28 | 2 + 4 | 2.3 (seam), 4.7 (Redis, sub-second) | ✓ Covered |
| FR-29 | 2 | 2.4 | ✓ Covered |
| FR-30 | 2 | 2.7, 3.10 (never mid-ride) | ✓ Covered |
| FR-31 | 4 | 4.1, 4.3 | ✓ Covered |
| FR-32 | 4 | 4.4, 4.6 | ✓ Covered |
| FR-33 | 4 + 6 | 4.2, 6.1 (second independent consumer) | ✓ Covered |
| FR-34 | 5 | 5.3, 5.4 | ✓ Covered |
| FR-35 | 5 | 5.1 | ✓ Covered |
| FR-36 | 5 | 5.6 | ✓ Covered |
| FR-37 | 5 | 5.4, 5.5 | ✓ Covered |
| FR-38 | 5 | 5.7 | ✓ Covered |
| FR-39 | 5 | 5.8, 5.1 (one payment per ride) | ✓ Covered |
| FR-40 | 5 | 5.9 | ✓ Covered |
| FR-41 | 6 | 6.1 | ✓ Covered |
| FR-42 | 6 | 6.2, 6.4 | ✓ Covered |
| FR-43 | 6 | 6.3 | ✓ Covered |
| FR-44 | 6 | 6.6 | ✓ Covered |
| FR-45 | 7 | 7.1 | ✓ Covered |
| FR-46 | 1+4+5+6 | 1.1/1.4/2.1/5.1/6.1 (health), 4.9 (KPIs), 5.5 (money gauges), 3.3+5.11 (refusals by reason), 6.1 (ingest rate) | ⚠️ **Partial — payment success rate missing** |
| FR-47 | 7 | 7.4 | ✓ Covered |
| FR-48 | 1 | 1.4, 2.1 | ✓ Covered |
| FR-49 | 7 | 7.3 | ⚠️ Covered with a stated deviation (FR-49c) |
| FR-50 | 7 | 7.2 | ✓ Covered |
| FR-51 | 5 | 5.11 | ⚠️ Covered with an **unrecorded scope change** |

**NFR coverage — all 10 verified:**

| NFR | Verified in | Status |
|---|---|---|
| NFR-1 | 3.3, 3.5, 3.6, 3.14, 7.6 | ✓ |
| NFR-2 | 7.3, 7.6 | ⚠️ see driver-population ambiguity below |
| NFR-3 | 4.4, 4.6, 6.1 | ✓ |
| NFR-4 | 4.6, 5.7, 6.1, 6.3, 6.5, 7.1 + standing AC | ✓ |
| NFR-5 | 1.1, 4.9 | ✓ |
| NFR-6 | 6.3, 6.4 | ✓ |
| NFR-7 | 1.1, 7.4 | ✓ |
| NFR-8 | 5.2, 7.6 | ✓ |
| NFR-9 | 1.2 + standing AC on every windowed story | ✓ |
| NFR-10 | 5.2, 5.3 | ✓ |

**Unnumbered constraint coverage (C-1 – C-19 from Step 2):** 17 of 19 fully covered. C-5 and C-7 are partial — see findings below. C-9's supersession is correctly implemented (6.5), C-15's ordering is asserted by a test (2.7), C-16's no-auto-sweep rule is explicit (5.6), C-17's no-timeout rule is explicit (5.3), and C-19 is stated verbatim in the authority chain.

### Missing Requirements

#### Critical Missing FRs

**None.** No FR is absent from the epics, and no FR appears in the epics that is not in the PRD. Every one of the 51 FRs has at least one story with acceptance criteria that deliver it.

#### High Priority — sub-obligation gaps

**GAP-1 — FR-46: "payment success rate" is promised to Epic 5 and delivered by no story.**
- *Requirement:* FR-46(i) names payment success rate among the KPIs the dashboards surface.
- *Evidence:* the epics' own Requirements Inventory restates it (line 137). Story 4.9's scope boundary explicitly defers it: *"Payment success rate and the two money gauges — capture loss, and the age of the oldest capture still retrying — arrive in Epic 5."* Story 5.5 then delivers **only** the two money gauges. No AC in 5.1–5.11 mentions payment success rate, and Epic 5's FR list records only "FR-46 (money gauges)".
- *Impact:* a named, explicitly-deferred KPI has no landing story. This is the specific failure mode a scope boundary is supposed to prevent — the deferral was recorded, the receipt was not. It will surface as a missing Grafana panel at `v0.2` with no ticket behind it.
- *Recommendation:* add an AC to **Story 5.5** defining payment success rate (a ratio over payment terminal outcomes) and its source, or add it to Story 4.9's panel list with a stated zero-until-Epic-5 reading as was done for *rides awaiting authorization*.

**GAP-2 — FR-14(f): auto-completion is not distinguishable when serving a read.**
- *Requirement:* FR-14(f) — auto-completions stay distinguishable from genuine ones **"both when serving a read and after the fact."** Two halves, deliberately stated as two.
- *Evidence:* `completed_by` is written as a column in Stories 3.9 (`DRIVER`) and 3.10 (`SYSTEM`), and the audit trail records `SYSTEM` (6.1) — the *after the fact* half is fully covered. The *when serving a read* half is not: Story 3.4 (rider reads ride detail and history) has no AC surfacing `completed_by`, and Story 3.15 (driver history) states only that system-completed rides *appear alongside* driver-completed ones matched by one status value — which is non-exclusion, not distinguishability. Story 5.8 reads the column internally for refunds, which is not a read-side surface either.
- *Impact:* a rider or driver reading a finished ride cannot tell a genuine completion from an auto-completion. Given FR-14's honest admission that auto-completion can charge for a trip that did not finish, and that the rider has no in-system remedy, whether the rider can even *see* that the system completed their trip is a product decision, not an implementation detail.
- *Recommendation:* add an AC to **Story 3.4** (and mirror on 3.15) stating whether `completed_by` is exposed on ride reads. If the deliberate answer is "not exposed to riders", say so explicitly in an AC — an intentional omission recorded is fine; a silent one is the defect.

#### Medium Priority — divergences and ambiguities

**GAP-3 — FR-51 arm 1 was narrowed and given a second clearing mechanism; the PRD was not updated.**
Story 5.11 makes two changes to FR-51(a) that the PRD does not record:
1. **Narrowed anchor.** PRD FR-51(a) refuses when *"the rider's previous ride is terminal but its payment has not settled, sitting in `INITIATED` or `AUTHORIZED`."* Story 5.11 refuses only when the most-recent ride is `COMPLETED` with payment `AUTHORIZED`, and states that a most-recent ride that is `CANCELLED`, `NO_DRIVER` or `PAYMENT_FAILED` is **never** refused on arm 1. The reasoning (that hold is being voided, and refusing there would revoke AD-45's promise that cancellation is the rider's exit) is sound and traceable to AD-59 — but it means the PRD's `INITIATED` case is effectively dropped, and the PRD still reads as though it applies.
2. **New clearing mechanism.** Story 5.11 adds *"or until the session-expiry bound lapses measured from `capture_requested_at`, whichever comes first."* PRD FR-51(e) states the 30-minute cooldown is **"the entire clearing mechanism"**; arm 1's only stated exit in the PRD is the payment settling. A second, time-based exit now exists.
- *Impact:* both changes are improvements — the epics reasoning is better than the PRD's. The defect is that the PRD is now wrong, and the PRD is what a story author reads first. FR-51 is also the requirement most likely to be re-derived from scratch by someone who did not read Story 5.11.
- *Recommendation:* amend PRD FR-51 to match Story 5.11, and add both items to PRD §8 Open Questions' change record.

**GAP-4 — no story states how the driver population reaches stress scale.**
- NFR-2 requires ~20k drivers; Story 7.6 asserts the ramp *"reaches roughly 20k drivers and 200k riders."* Riders need no provisioning (identity is passed per request, FR-48). Drivers do: FR-48 makes driver identity **fixture-seeded**, and Story 2.1 seeds fixture drivers with a seed and geographic bounds but **no count parameter**. Story 7.3 says the Simulator *"generates synthetic riders, drivers and ride traffic"* — but the Simulator cannot create driver identities, because there is no registration (FR-48) and `driver-service` owns them (AD-3).
- *Impact:* at detailing time this becomes "who creates 19,970 more drivers?", answered ad hoc. The plausible fix (make the fixture seed count configurable) is a one-line AC on Story 2.1 — cheap now, a migration and a re-seed later.
- *Recommendation:* add an AC to **Story 2.1** making the fixture driver count a configuration value, and an AC to **7.6** stating the stress scenario sets it.

**GAP-5 — four open decisions surfaced during epics work were not added to PRD §8.**
PRD §8 states *"None outstanding"* and instructs: *"Additional items get added here as they surface during Epics/Stories work."* Four surfaced and were recorded only inside `epics.md`:
1. **Outbox claim size** — batched vs single-row (Stories 4.3 / 4.4), to be settled together and consistently across all three claim-loop workers.
2. **Storage ceilings for Postgres and ClickHouse** (Epic 6) — the epics correctly identify this as *"a gap in the spine, not a number it deferred"*, since AD-47's deferred-capacity list names no disk bound.
3. **How the live dashboard is reached** (Story 7.2) — AD-5 lists four gateway routes and the dashboard is not among them.
4. **Which environment the stress run targets** (Story 7.6) — Compose, kind, or both.
- *Impact:* low individually — each is well-framed, has a named owner-moment ("settle when this story is detailed"), and none blocks starting Epic 1. The defect is that the PRD advertises zero open questions while four exist, so anyone reading only the PRD is misinformed about the state of the plan.
- *Recommendation:* mirror all four into PRD §8 with a pointer to the story that owns each.

#### Low Priority

**GAP-6 — C-5: milestone git tags are binding but no story creates them.** `v0.1`/`v0.2`/`v0.3`/`v1.0` appear in epic titles and the document calls the milestone tags *binding*, but no story AC tags the repository. Process rather than product; worth one AC on the last story of each of Epics 4, 5, 6, 7.

**GAP-7 — C-7: the CV/interview-narrative deliverable has only partial coverage.** §2 of the PRD names it the *"secondary, equally real goal."* Story 7.6's final AC records the stress-run results *"because the migration, concurrency, webhook and scale narratives are the project's stated secondary deliverable"*, and Story 6.6 delivers the migration benchmark — but nothing produces the narrative itself. Arguably outside software scope; flagged so the decision is deliberate.

**GAP-8 — FR-49(c) deviation, stated and justified.** FR-49 says the Simulator *"runs as an in-process test fixture early on and becomes a standalone containerized load generator later."* The epics deliver it once, in Epic 7, and argue the early form should be ordinary per-story test fixtures instead. The reasoning is strong and consistent with the testing policy. Not a gap — but the PRD text still describes a component that will not be built in that form, and should be amended.

#### Scope additions found in the epics but absent from the PRD

Noted for completeness — each is architecture-driven and correct, but none is traceable to a PRD requirement:

- **Story 4.5 — backlog-age shedding.** New ride requests are refused with `503` once the outbox backlog ages (AD-35). The PRD's FR-2(f) enumerates ride-request refusals as FR-3 and FR-51 only; a third refusal shape now exists. The epics handle it well (the 503 deliberately keeps its own signal, separate from the 409 refusal counter), but the PRD does not know it exists.
- **Stories 6.4 / 6.5 — size-based storage ceilings** with eviction-of-oldest as primary and a hard ingest stop as an alarmed backstop. Nothing in the PRD or NFR-6 anticipates a size bound; the epics reason it out from AD-48's Tier-3 guarantee and flag the spine's silence.
- **Story 3.4 — `ETag`/`304` on ride detail** (AD-40). Harmless; no PRD requirement.

### Coverage Statistics

- **Total PRD FRs:** 51
- **FRs covered in epics:** 51
- **FR-level coverage:** **100%**
- **Atomic FR obligations traced:** ~190
- **Obligation-level gaps:** 2 (GAP-1, GAP-2)
- **Obligation-level coverage:** **≈98.9%**
- **Total PRD NFRs:** 10 — **10 covered (100%)**
- **Unnumbered constraints (C-1 – C-19):** 17 fully covered, 2 partial — **89%**
- **FRs in epics but not in PRD:** 0
- **Undocumented scope additions:** 3
- **Unrecorded PRD divergences:** 3 (GAP-3 ×2, GAP-8)

**Assessment:** this is unusually strong traceability. Every FR lands, every split is deliberate with an explicit scope boundary, and the epics repeatedly catch things the upstream documents left silent (the storage-ceiling gap in the spine, the dashboard's missing gateway route, the `AD-34` retry-cap rule that must *not* apply to capture). The two real gaps are both **sub-obligations inside a bundled FR paragraph** — exactly the failure mode Step 2 predicted — and both are cheap to close before Epic 1 starts, since neither is in Epic 1.

---

## Step 4: UX Alignment Assessment

### Discovery correction

Step 1's search patterns were markdown-only. Reading the architecture spine's frontmatter surfaced three HTML artifacts that the `*.md` sweep missed:

| File | Size | Modified | Role |
|---|---|---|---|
| `prds/prd-puber-2026-08-02/flows.html` | 48.7 KB | 2026-08-14 | **"Puber — Flow & Status Map"**; a declared **source** of the architecture spine |
| `architecture/.../architecture-map.html` | 56.4 KB | 2026-08-14 | Spine companion |
| `architecture/.../SOLUTION-DESIGN.html` | 33.2 KB | 2026-08-13 | Spine companion |

`flows.html` is the material one. Its section headings — *Ride state machine · Payments, and when money actually moves · The rider's session · The driver's session · What happens when something goes wrong · Where the requirements sit* — make it the nearest thing this project has to a journey/flow artifact, and it references FR-1 through FR-51, so it is current with the post-FR-51 PRD.

### UX Document Status

**Not Found — and correctly so.** No UX specification, design system, component library, accessibility contract, or responsive breakpoint set exists. Confirmed with the user on 2026-08-16: `puber` is a backend system. The epics document reaches the same conclusion independently and records it in a dedicated *UX Design Requirements* section rather than leaving it implicit — the right handling.

**UX is not implied by the PRD**, with one bounded exception. Checked explicitly:
- Rider and driver clients are `curl`, a browser, Java tests, or the Simulator (PRD §5 Non-Goals: *"Real mobile apps"*). AD-37 confirms: *"curl, a browser and the Simulator are first-class clients."*
- No rider-facing or driver-facing UI is specified anywhere in FR-1 – FR-49 or FR-51.
- **The exception is FR-50** — *"a lightweight custom web UI"* showing live counts of drivers by status, rides by status, and active riders. This is a real, if minimal, user interface, and it is the only one.

### Alignment: FR-50 ↔ PRD ↔ Architecture

The one UI surface was traced through all three layers.

| Requirement | Architecture support | Epic support | Verdict |
|---|---|---|---|
| Lightweight custom web UI (FR-50a) | AD-51 admits WebSocket as a third transport | Story 7.2 | ✓ Aligned |
| Live counts pushed in real time (FR-50b,c) | AD-51 fan-out-and-filter, dashboard consumer holds its own sockets, own consumer group per replica — no replica registry, sticky routing, or shared session store | Story 7.2 | ✓ Aligned |
| Distinct from Grafana infra dashboards (FR-50d) | Capability map row: *"Live operational dashboard (FR-50) → dashboard consumer → AD-51, AD-48"*, separate from *"Health, metrics, dashboards (FR-46) → every service → AD-54"* | Story 7.2 explicit AC | ✓ Aligned |
| Disableable without breaking anything | AD-48 places the dashboard in Tier 3 | Story 7.2 AC, Story 7.4 tier exercise | ✓ Aligned |

**The architecture supports the one UI the PRD asks for.** No UI component is specified that the architecture cannot deliver, and no architectural capability is built for a UI that was never asked for.

### Alignment Issues

**UX-1 (Medium) — the dashboard has no route to reach it.**
AD-5 enumerates exactly four gateway routes: `rider-service`, `driver-service`, the Stripe webhook, and audit's query API. The dashboard is not among them, and AD-5 predates FR-50's delivery decision. So the system's only user interface currently has no specified way for a user to reach it. The epics catch this themselves — Story 7.2 carries an open question stating the consistent reading is that the dashboard is reached directly, as Prometheus and Grafana are, *"but AD-5 predates it, so either that reading is confirmed or AD-5's route list gains a fifth entry."* Correctly identified, correctly deferred to detailing time, and not a blocker for Epic 1. It is the single unresolved question about the one UI, so it should not be allowed to be settled by whichever way it is first wired.

**UX-2 (Low) — "active riders" is undefined.**
FR-50(b) requires live counts of *"drivers by status, rides by status, active riders."* The first two map onto enumerated state machines with fixed vocabularies (FR-15, glossary "Driver status"). **"Active riders" maps onto nothing** — the PRD glossary defines *Rider*, but no term "active rider", and there are no rider accounts (FR-48) or riders table (addendum constraint) to count. The obvious reading is "distinct `rider_id`s holding a non-terminal ride", which makes it a restatement of the rides-by-status counts rather than an independent figure. Story 7.2 restates FR-50's wording verbatim without resolving it.
- *Impact:* small — one panel on a Tier 3 dashboard in the final epic. But it is the only requirement in the entire PRD whose subject has no definition in a document that otherwise pins every term.
- *Recommendation:* define it in the PRD glossary, or add an AC to Story 7.2 stating the counting rule.

**UX-3 (Low) — no failure-state or cadence requirement for the dashboard.**
Neither FR-50 nor Story 7.2 says what the dashboard shows when its socket drops, when the consumer lags, or when Tier 3 is disabled — the states an operator is most likely to encounter. Consistent with the "lightweight" framing and not worth a UX pass, but worth one AC so the answer is chosen rather than inherited from whatever the first implementation happens to do.

### Warnings

**⚠️ W-1 — `flows.html` was a source for the architecture but not for the epics.**
The spine declares `flows.html` in its `sources`. The epics document's `inputDocuments` frontmatter lists the PRD, addendum, spine, SPEC, state-machines, glossary and roadmap — **not `flows.html`**. So the flow-and-status map informed the architecture but was not consulted when epics and stories were written. Given that Step 3 found FR-level coverage complete, nothing appears to have been lost — but this is an unvalidated input, and `flows.html` contains the two session views (*The rider's session*, *The driver's session*) and the *What happens when something goes wrong* flow set that are exactly the material a coverage check would want. **Recommendation:** skim `flows.html` against the Epic 3 and Epic 5 stories before starting Epic 5, and add it to the epics' input list if it is adopted.

**⚠️ W-2 — the architecture spine does not know FR-51 exists.**
The spine's frontmatter reads `binds: FR-1–FR-50` and its `scope` states *"the system specified by PRD FR-1–FR-50 and NFR-1–NFR-10."* The string `FR-51` appears **zero times** in the 70 KB spine. Yet AD-59 — *"Ride admission reads a local payment-settlement projection, never a synchronous call"* — exists precisely to implement FR-51, describes both of its arms in detail ("AD-59 arm 1", "AD-59 arm 2"), and is cited by Story 5.11 throughout.
- *Impact:* the mechanism is fully designed; only the requirement **identifier** is missing. The FR-51 → architecture link therefore exists **only through `epics.md`**. Anyone auditing traceability from the spine, or regenerating epics from the spine alone, would find FR-51 unaccounted for. This is metadata staleness from FR-51 being appended after the spine's binds line was authored, not a design gap.
- *Recommendation:* update the spine's `binds` and `scope` to `FR-1–FR-51`, and add the FR-51 citation to AD-59. One-line fix; it removes a traceability break that will otherwise outlive everyone's memory of why it is there.

**No warning is issued for the absence of a UX document.** UX is genuinely not implied by this product beyond FR-50, the epics reached that conclusion independently and documented it, and the user has confirmed it. **UX alignment is scored N/A rather than as a gap.**

---

## Step 5: Epic Quality Review

Validated against create-epics-and-stories standards. Measured baseline: **59 stories, 414 Given/When/Then acceptance-criteria blocks (~7 per story), 59/59 stories carrying a full As-a/I-want/So-that stem, 10 explicit scope-boundary notes, 5 recorded open decisions.**

### Best Practices Compliance Checklist

| Check | Epic 1 | Epic 2 | Epic 3 | Epic 4 | Epic 5 | Epic 6 | Epic 7 |
|---|---|---|---|---|---|---|---|
| Delivers user value | ⚠️ | ✅ | ✅ | ⚠️ | ✅ | ✅ | ⚠️ |
| Functions independently | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Stories appropriately sized | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ | ✅ |
| No forward dependencies | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Tables created when needed | ⚠️ | ❌ | ⚠️ | ✅ | ✅ | ✅ | n/a |
| Clear acceptance criteria | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ | ✅ |
| Traceability to FRs maintained | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

### A. Epic User-Value Focus

**No epic is a pure technical milestone.** Each of the seven carries a stated user or operator outcome, and none is named after a layer, a datastore or a framework. Three warrant comment:

- **Epic 1 — "Foundations & Fare Quote"** is half bootstrap, half product. Stories 1.1 (containerized service, test harness) and 1.2 (injectable clock) deliver no end-user value on their own; 1.3 and 1.4 deliver FR-18 and FR-1. This is **compliant**, on two independent grounds: greenfield projects are expected to carry an initial project-setup story, and the epics justify 1.1/1.2 as SPEC `enabler` capabilities (CAP-36, CAP-40) that must land first regardless of subject matter. The epic is nonetheless anchored by a rider-visible capability — pricing a trip through the gateway — rather than ending at "the stack starts", which is what keeps it on the right side of the line.
- **Epic 4 — "Event Backbone, Resilience & Operational Visibility"** is the most technical epic in the set. Its user-facing content is real but thin: surge starts moving with live demand (FR-19, rider-visible price) and driver position becomes genuinely sub-second (FR-28, rider-visible). Its centre of gravity is Kafka, the outbox and the dashboards. **Accepted, not flagged**, because this project's stated purpose *is* the engineering patterns — PRD §1 names the event backbone as a primary learning target, and an epic that refused to be technical here would be refusing the product.
- **Epic 7 — "Real-Time, Live Dashboard & Local Kubernetes"** bundles four different kinds of work: a driver-facing push channel (FR-45), an operator dashboard (FR-50), a deployment target (FR-47), and a load-generation-plus-measurement exercise (FR-49, NFR-2). It is coherent only as *"the `v1.0` milestone"*. Defensible given the binding phase sequence, but it is the epic whose goal statement does the least work.

### B. Epic Independence

**Verified: no epic requires a later epic to function.** Traced explicitly:

- Epic 1 stands alone — a rider prices a trip end to end through the gateway.
- Epic 2 uses only Epic 1's output (gateway, clock, health/metrics pattern, test harness).
- Epic 3 uses 1 and 2, and runs on the immediate-authorise `PaymentGateway` with **no Kafka and no payment-service** (AD-43).
- Epic 4 changes how services communicate; the system worked before it.
- Epic 5 is Tier 2 — without it rides stall before dispatch, and ride requests still succeed (AD-48, AD-59's fail-open projection).
- Epic 6 is Tier 3 — disabling it breaks nothing and loses no data.
- Epic 7 builds on all of them.

The epics document asserts this itself in an *Epic independence and file overlap* section, and the assertion holds under checking. The deliberate `matching-service` file overlap across Epics 2–5 is real, was assessed, and consolidation was rejected for stated reasons (binding milestone tags, genuine feedback points, and a consolidated epic exceeding 40 stories). That reasoning is sound.

### C. Forward Dependencies

**No violations found.** Ten stories carry an explicit *Scope boundary* note deferring part of an FR to a later epic. Each was checked against the "does this story wait on future work to function?" test, and **all ten pass** — in every case the earlier story delivers a complete, phase-appropriate slice, and the later addition is additive:

| Story | Deferred to | Earlier story still complete? |
|---|---|---|
| 1.4 (quote, no-driver branch) | 2.6 (ETA branch) | ✅ no drivers exist yet, so only that branch is reachable |
| 2.2 (go offline) | 3.11 (engagement guard) | ✅ no rides exist, so offline always legitimately succeeds |
| 2.5 (session read) | 3.12 (offer + ride fields) | ✅ status and reachability delivered in full |
| 2.7 (session expiry) | 3.10 (idle-only restriction) | ✅ every driver is idle until rides exist |
| 3.2 (ride request) | 5.3 (payment token) | ✅ nothing consumes the token in this phase |
| 3.3 (refusal counter) | 5.11 (2 more reason labels) | ✅ closed enum established so labels can't be bolted on as strings |
| 3.10 (auto-completion) | 5.4 (capture) | ✅ ride-side completion + SYSTEM provenance delivered |
| 3.13 (`NO_DRIVER`) | 5.6 (hold void) | ✅ terminal state delivered |
| 3.14 (cancel) | 5.6 (late-landing void) | ✅ cancellation delivered |
| 4.9 (dashboards) | 5.5 / 6.1 (3 panels) | ✅ *rides awaiting authorization* correctly reads zero this phase |

This is the strongest structural feature of the document. Splitting an FR across phases **and writing down where the other half went** is precisely what prevents a half-delivered requirement from being marked done — the opposite of a forward dependency.

**One near-miss (Minor):** Story 2.2's AC *"Given a status transition, When it is caused by the system rather than the driver, Then the acting actor is recorded as `SYSTEM`"* is untestable when 2.2 lands, because the first system-caused driver transition (session expiry) arrives in Story 2.7. It is groundwork stated as an AC. Harmless; move it to 2.7 or mark it as a convention rather than a criterion.

### D. Database / Entity Creation Timing

**This is where the review found real defects.** Most tables are correctly created by the story that first needs them — `rides` (3.1), `payments` (5.1), `webhook_events` (5.7), `audit_events` (6.1, partitioned from the outset), `event_outbox` (4.1), `payment_standing` (5.11), `drivers` identity (2.1). Three are not:

**🟠 QUAL-1 (Major) — `matching-service`'s dispatch `drivers` table is created by no story.**
Epic 2's preamble states the epic *"creates `matching-service`'s dispatch `drivers` table carrying declared status, a position snapshot and `last_seen_at`"* — but that sentence lives in the epic's prose, not in any story's acceptance criteria. Story 2.2's ACs then read *"the dispatch driver row is set `AVAILABLE`"* as though the table already exists; Story 2.4 queries its `last_seen_at`; Story 2.6 reads from it. **No AC anywhere creates it or defines its columns.**
- *Impact:* the single most-referenced table in Epics 2–5 has no owning story. A dev agent implementing 2.2 must infer the schema from prose in a section it may not be given.
- *Recommendation:* add explicit table-creation ACs to **Story 2.2** (or a new 2.1b), naming the columns exactly as `rides` is named in Story 3.1.

**🟠 QUAL-2 (Major) — the `current_ride_id` expand-only migration is owned by no story.**
The same preamble says *"Epic 3 extends it with `current_ride_id` by expand-only migration."* No Epic 3 story has an AC performing that migration, though Story 3.5 sets the driver `BUSY` and the whole engagement model depends on the column.
- *Recommendation:* attach the migration AC to **Story 3.5**, which is the first story that needs it.

**🟡 QUAL-3 (Minor) — `fare_rules` creation is implied, not stated.**
Story 1.3 reads *"Given a `fare_rules` row carrying base, per-km, per-minute and surge"* and *"Given a first start, When `fare_rules` is seeded"*. Creation is implied by seeding but never stated, and the column set is never declared as an AC the way `rides` is. Weaker than the surrounding standard.

### E. Story Sizing

Median story carries ~7 AC blocks — well-sized. Two outliers:

**🟡 QUAL-4 (Minor) — Story 5.11 is oversized at 17 AC blocks**, roughly 2.5× the median and the largest in the document. It delivers, in one story: the three-way refusal ordering, both FR-51 arms with their anchor-selection rule, the read-model projection's shape, its rebuild semantics, its `capture_failed_at` verbatim-storage rule, the reason-token → RFC 9457 `type` URI mapping, the metric label contract, the three reasons' *differing alerting semantics*, and the projection-lag gauge. Each is genuinely coupled to the others, which is the argument for keeping it whole — but it is the story most likely to exceed what one dev agent completes cleanly.
- *Recommendation:* consider splitting into "the projection and its rebuild semantics" and "the two admission arms, their reason tokens and their alerting" — the seam is clean and the second half depends only on the first's output.

Stories 6.1, 5.5 and 4.2 at 12 blocks each are large but coherent. Epic 3 at 16 stories is the largest epic; the document explains the consolidation reasoning and it holds.

### F. Acceptance Criteria Quality

**Format:** 414/414 AC blocks use Given/When/Then(/And). No exceptions. Traceability citations (`FR-n`, `AD-n`, `NFR-n`, or a named convention) appear on essentially every block — this is materially better than typical.

**Specificity:** outstanding in the main. Criteria name exact values (5 km radius, 10 s offer timeout, 60 s seeking budget, 90 s / 10 min staleness, 30 min cooldown, 1 h session expiry, `DECIMAL(10,8)`/`DECIMAL(11,8)`, Temurin 25, Kafka 4.3.1), exact SQL predicates (Story 5.4's claim predicate is written out literally), and exact error mappings (404 never 403; `FAILED_PRECONDITION`/`ALREADY_EXISTS` → 409). Several ACs specify not just the behaviour but the **prohibited** implementation — never a cursor, never `SELECT ... FOR UPDATE`, never row-level `DELETE`, never SQL `now()`, never an in-memory schedule.

**🟠 QUAL-5 (Major) — 20 AC blocks are rationale, not criteria.**
Roughly 5% of acceptance criteria have a *When* clause describing a **review activity** rather than a system event: *"When it is examined"* (×5), *"When its meaning is asserted"* (×2), *"When their separation is questioned"*, *"When its finality is asserted"*, *"When its justification is examined"*, *"When the design is reviewed"*, *"When offer history is considered"*, *"When stranded-looking holds are considered"*, *"When they are read during an outage"*, *"When its nature is stated"*, *"When they are compared"* (×2), *"When its exits are examined"*, *"When its possible endings are enumerated"*, *"When the store to answer it is chosen"*, *"When it is considered"*.
- *Examples:* Story 5.1 — *"Given `FAILED` and `CAPTURE_FAILED`, When their separation is questioned, Then they remain distinct states."* Story 3.13 — *"Given the bounded window, When its justification is examined, Then it bounds an outcome the system can determine."* Story 6.5 — *"Given ping volume of roughly 1.3M/day, When the design is reviewed, Then this volume is precisely why the history is columnar-only."*
- *Impact:* none of these can be verified by a test, yet they sit in the same list as criteria that can. A dev agent asked to satisfy all ACs cannot close them, and a reviewer cannot fail a story on them. The content is genuinely valuable — it is the reasoning that stops a later engineer "simplifying" the design — but it is documentation wearing an AC's clothes, and it inflates the apparent criteria count by ~5%.
- *Recommendation:* move them into the `>` note blocks the document already uses well, or restate the testable ones as assertions (Story 5.1's becomes *"Then a `CAPTURE_FAILED` payment is not counted by any query that counts `FAILED`, and vice versa"*).

**🟠 QUAL-6 (Major) — Story 5.9 (Reconciliation) is materially under-specified.**
It carries 4 AC blocks that restate FR-40 almost verbatim — *"Given a reconciliation task, When it runs, Then it detects missed or failed webhook deliveries"* — with **no mechanism at all**: no statement of what is compared against what, no cadence, no source of truth for "was a webhook missed", no output surface (log? metric? query API?). Every other Epic 5 story specifies its mechanism down to the SQL predicate. This one specifies nothing.
- *Impact:* Story 5.9 is the least implementation-ready story in the document by a wide margin, and it is the one delivering FR-40 in full.
- *Recommendation:* before Epic 5 starts, add ACs covering the comparison source (provider query vs. local `payments`/`webhook_events` state), the cadence and its `Clock` derivation, the "implausibly long-lived" threshold and where it comes from in AD-46's constant set, and how findings are surfaced given AD-54's rule that nothing is persisted solely to make it countable.

**🟡 QUAL-7 (Minor) — missing error paths in three stories.**
- **Story 5.8 (refund)** covers only the `CAPTURED` happy path. FR-35 and AD-50 make `REFUNDED` reachable *only* from `CAPTURED`, so refunding an `AUTHORIZED`, `VOIDED` or `CAPTURE_FAILED` payment must be rejected — no AC states it. Given that refunds are the one operator-triggered mutation in the system, the rejection path deserves an AC.
- **Story 2.3 (heartbeat)** has no AC for a heartbeat from an unknown driver, from an `OFFLINE` driver, or with out-of-bounds coordinates.
- **Story 2.6 (quote ETA)** inherits Story 1.4's malformed-request handling implicitly but does not restate it; acceptable.

**🟡 QUAL-8 (Minor) — no story creates a CI pipeline, yet an AC depends on one.**
Story 5.2 carries *"Given CI with no provider credentials, When the suite runs, Then it runs against the stub and passes."* CI is referenced four more times across Stories 3.2, 5.2 and 5.3 as a first-class environment. **No story stands CI up.** Story 7.5 delivers GitOps deployment (Argo CD), which is delivery, not integration. The PRD does not require CI, so this is not an FR gap — but a greenfield project with a test harness this central, and an AC that presumes CI exists, should own it somewhere. Recommend either an AC on Story 1.1 or an explicit "CI is out of scope" note so the presumption is deliberate.

### G. Special Implementation Checks

**Starter template — ✅ compliant, and handled unusually well.** The architecture specifies **no** starter template and mandates a hand-built structure instead: per-service Gradle wrappers (9.x), **no root build**, each service directory independently buildable as if it lived in its own repo (AD-52). The epics record this explicitly in *Additional Requirements* with the warning that it *"cannot be satisfied by scaffolding a monorepo template"*, and Story 1.1 delivers it with ACs asserting the wrapper, the absence of a root build, the package layout, and a dependency-direction test. This is exactly right: the check exists to catch a missing setup story, and the setup story is present, correct, and correctly shaped for a no-template architecture.

**Greenfield indicators — ✅ mostly present.** Initial project setup (1.1) ✅; development environment via the Compose stack (1.1) ✅; CI/CD pipeline ⚠️ — see QUAL-8. No brownfield integration or migration stories, correctly, since this is greenfield.

### Quality Findings by Severity

#### 🔴 Critical Violations
**None.** No technical epic without user value; no forward dependency breaking independence; no epic-sized story that cannot be completed.

#### 🟠 Major Issues
| ID | Issue | Location | Fix cost |
|---|---|---|---|
| QUAL-1 | Dispatch `drivers` table created by no story's ACs | Epic 2 (2.2) | Low |
| QUAL-2 | `current_ride_id` expand-only migration owned by no story | Epic 3 (3.5) | Low |
| QUAL-5 | 20 AC blocks are rationale, not testable criteria | Throughout | Low |
| QUAL-6 | Story 5.9 (reconciliation) specifies no mechanism | Epic 5 | Medium |
| QUAL-8 | An AC presumes a CI environment no story creates | Epic 5 (5.2) | Low |

#### 🟡 Minor Concerns
| ID | Issue | Location |
|---|---|---|
| QUAL-3 | `fare_rules` creation implied, not stated | Story 1.3 |
| QUAL-4 | Story 5.11 oversized at 17 AC blocks | Epic 5 |
| QUAL-7 | Missing error paths (refund rejection, heartbeat edge cases) | 5.8, 2.3 |
| — | Story 2.2's `SYSTEM`-actor AC is untestable until 2.7 | Epic 2 |

### Overall Epic Quality Verdict

**Strong — materially above the standard this review normally finds.** The document does several things most epic breakdowns do not: it states an authority chain and names which documents are *non*-authoritative; it forbids test-only stories with a stated diagnostic for the forbidden shape; it declares standing acceptance criteria once rather than restating them 59 times; it records ten scope boundaries so no split FR can be silently half-delivered; and it flags five open decisions with the story that owns each rather than resolving them prematurely.

The defects found are concentrated and cheap. Four of the five Major issues (QUAL-1, -2, -5, -8) are one-to-three-line additions. Only QUAL-6 — Story 5.9's missing mechanism — requires actual design work, and it sits in Epic 5, four epics away from where implementation starts.

---

## Summary and Recommendations

### Overall Readiness Status

# ✅ READY — conditional

**Puber's planning artifacts are implementation-ready. Nothing blocks starting Epic 1.**

That verdict is earned, not granted. Every one of the 51 FRs and all 10 NFRs has a story with acceptance criteria behind it; every epic stands independently; there are zero forward dependencies and zero critical violations. The PRD closed its own open questions, the architecture pass overrode the addendum where it needed to and said so, and the epics document repeatedly caught things its own inputs had left silent.

The condition is this: **22 issues were found, none critical, and 6 of them should be fixed before the first commit** — not because they block Epic 1, but because five are one-line document corrections that get exponentially more expensive once code exists to contradict them.

### Findings by Severity

| Severity | Count | Blocks Epic 1? |
|---|---|---|
| 🔴 Critical | **0** | — |
| 🟠 High / Major | 7 | No |
| 🟡 Medium | 5 | No |
| 🔵 Low / Minor | 10 | No |
| ℹ️ Informational (undocumented scope additions) | 3 | No |
| **Total actionable** | **22** | |

### Critical Issues Requiring Immediate Action

**There are none.** In the interest of not softening the message the other way either: this is the rare assessment where the honest answer is that the plan is sound and the reviewer's job is to hand back a punch list, not a stop order.

The two findings closest to consequential are both **sub-obligations buried inside a bundled FR paragraph** — exactly the failure mode predicted in Step 2 — and neither lands before Epic 3:

1. **GAP-1 — FR-46's "payment success rate" is deferred to Epic 5 by Story 4.9 and delivered by no Epic 5 story.** The deferral was recorded; the receipt was not. This is the precise failure a scope boundary exists to prevent, and it happened anyway — which is worth noting, because it means the scope-boundary discipline needs a closing check, not just an opening one.
2. **GAP-2 — FR-14 requires auto-completions to be distinguishable "both when serving a read and after the fact"; only the second half is covered.** A rider whose trip was auto-completed and charged cannot see that the system, not their driver, ended it — and FR-14 already concedes they have no remedy. Whether that is exposed is a product call; right now nobody has made it.

### Recommended Next Steps

**Before the first commit** — six document corrections, roughly an hour total:

1. **Amend PRD FR-51 to match Story 5.11** (GAP-3). The epics narrowed arm 1 to `COMPLETED` rides only and added a session-expiry-bounded escape measured from `capture_requested_at`. Both changes are improvements on the PRD; the PRD is now simply wrong, and it is what a story author reads first.
2. **Update the architecture spine's `binds` and `scope` to `FR-1–FR-51`, and cite FR-51 in AD-59** (W-2). The string `FR-51` appears **zero times** in the 70 KB spine, yet AD-59 exists to implement it. The FR-51 → architecture link currently exists only through `epics.md`.
3. **Mirror the four open decisions into PRD §8** (GAP-5): outbox claim size (4.3/4.4), storage ceilings (Epic 6), the dashboard's gateway route (7.2), and the stress-run target environment (7.6). §8 currently advertises "None outstanding" while four exist.
4. **Amend PRD FR-49** to drop the "in-process test fixture early" form (GAP-8), which the epics deliberately and correctly do not build.
5. **Add an explicit `fare_rules` creation AC to Story 1.3** (QUAL-3) — the only Epic 1 defect, and a five-minute fix.
6. **Decide CI** (QUAL-8): either add a pipeline AC to Story 1.1 or record "CI is out of scope" — Story 5.2 already has an AC that presumes CI exists.

**Before Epic 2:**

7. **Add table-creation ACs for the dispatch `drivers` table to Story 2.2** (QUAL-1). The most-referenced table in Epics 2–5 is described only in epic prose and created by no story.
8. **Make the fixture driver count a configuration value in Story 2.1** (GAP-4). NFR-2 needs ~20k drivers; FR-48 makes driver identity fixture-seeded and the Simulator cannot create it. Nobody currently owns getting from 30 to 20,000.

**Before Epic 3:**

9. **Attach the `current_ride_id` expand-only migration to Story 3.5** (QUAL-2).
10. **Decide and record whether `completed_by` is exposed on ride reads** (GAP-2). An intentional "not exposed to riders" recorded in an AC is a fine answer; a silent omission is not.

**Before Epic 5** — the epic carrying most of the remaining debt, and the one the roadmap already warns was sized before it grew from five capabilities to seven:

11. **Specify Story 5.9's reconciliation mechanism** (QUAL-6) — the only finding needing genuine design work. What is compared against what, at what cadence, against which threshold, surfaced where.
12. **Land FR-46's payment success rate** (GAP-1), on Story 5.5 or as a zero-until-Epic-5 panel on 4.9.
13. **Consider splitting Story 5.11** (QUAL-4) at the projection / admission-arms seam — 17 AC blocks, 2.5× the median.
14. **Add the refund rejection path to Story 5.8** (QUAL-7) — `REFUNDED` is reachable only from `CAPTURED`; the rejection is untested.
15. **Read `flows.html` against the Epic 3 and 5 stories** (W-1). It was a declared source for the architecture and is absent from the epics' input list.

**Before Epic 7:**

16. **Settle how the dashboard is reached** (UX-1) — AD-5's four gateway routes do not include the system's only UI. Decide it; do not let it be settled by whichever way it is first wired.
17. **Define "active riders"** (UX-2) — the one term in FR-50 that the glossary does not pin, in a document that pins everything else.

**Housekeeping — throughout:**

18. **Relocate the 20 rationale-shaped AC blocks** (QUAL-5) into the `>` note blocks the document already uses well. *"When their separation is questioned"* is not a system event, and a dev agent cannot close it.

### What Is Genuinely Strong Here

Stated because a punch list of 22 items misrepresents the artifact if left unqualified:

- **Traceability is near-total.** 414 acceptance criteria, essentially every one citing an FR, AD, NFR or named convention. FR-level coverage is 100%; obligation-level coverage is ~98.9%.
- **The authority chain is explicit**, including which documents are *not* authoritative (`docs/tickets/pb-*.md`, and the addendum's superseded rows) — the single most common cause of an epic quietly implementing a decision that was overturned.
- **Ten scope boundaries** record where each split FR's other half went. No requirement can be silently half-delivered.
- **Test-only stories are forbidden with a stated diagnostic** — *"a story must deliver a requirement, not merely prove one"* — and SPEC `property` capabilities are correctly refused story status on the grounds that a story named after a property has no definition of done.
- **The epics repeatedly out-reason their own inputs**: they catch that AD-47's deferred-capacity list is silent on storage ("a gap in the spine, not a number it deferred"), that AD-5's route list predates FR-50, and that AD-34's cap-then-dead-letter rule must *not* apply to capture retries.
- **Five open decisions are recorded with the story that owns each**, rather than resolved prematurely or hidden.

### Final Note

This assessment identified **22 issues across 3 categories** — requirements coverage and traceability (8), document currency and UX alignment (5), and epic/story quality (9) — plus 3 undocumented scope additions carried in from the architecture.

**No issue is critical, and none blocks implementation.** Sixteen of the 22 are one-to-three-line corrections. One (Story 5.9's reconciliation mechanism) needs real design work, and it sits four epics away from where work starts.

Address items 1–6 before the first commit; the rest are sequenced against the epic that first needs them. You may also choose to proceed as-is — the plan will survive it — but items 1–3 in particular are corrections to *upstream* documents, and upstream errors are the ones that get re-derived by whoever reads them next.

---

**Assessment date:** 2026-08-16
**Assessed by:** Implementation Readiness workflow (Product Manager review)
**Artifacts assessed:** PRD (`prd.md` + `addendum.md`), SPEC kernel, `ARCHITECTURE-SPINE.md`, `epics.md` (7 epics / 59 stories / 414 acceptance criteria), `flows.html`
**UX:** N/A — backend-only product, confirmed by user
