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

