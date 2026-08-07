# Puber PRD — Addendum

Depth pulled out of the PRD proper because it's technical-how (architecture/solution-design material) rather than product shape. Feeds `bmad-architecture` directly.

## Architecture Decisions Already Made (with rationale)

| Decision | Rationale given |
|---|---|
| No registration/auth; drivers/riders seeded via Flyway fixtures; `riderId` passed per-request | Auth is a multi-week detour that teaches nothing on the target learning syllabus |
| No `riders` table in V1; `rides.rider_id` is a plain UUID, no FK | Keeps schema minimal; can add later for JOIN practice |
| Matching engine queries `drivers.current_lat/current_lng` directly in V1 | At ~30 drivers × 1 location/2s = 15 writes/sec, Postgres handles it trivially; keeps V1 simple |
| Driver locations move to Kafka → Redis (fast path, short TTL) + Postgres batch insert (slow path) from the Kafka phase onward | Production pattern: fast path for reads, slow path for persistence/history |
| V1 inter-service calls are direct HTTP, replaced by Kafka events in the second phase | Teaches HTTP client patterns (timeouts, retries) first; evolves to event-driven later |
| Unmatched rides retried on a schedule rather than queued externally in V1 | Simple, testable, no external queue needed yet |
| Fare calculated at request time, not trip end | Matches real ride-hailing UX (price shown upfront); simpler than post-trip calculation |
| ETA formula: `haversine(pickup, driver) / 8.33 m/s` (~30 km/h) | Simplicity over routing-API realism |
| Geo bounds: 4km × 4km demo square (Lisbon-centered) | Room for realistic driver dispersion/ETAs without needing global geo indexes |
| Driver offer timeout ~10s, retry interval ~5s | Prevents one slow driver blocking a ride forever; testable with mocked clocks |
| Surge pricing: static multiplier from a single `fare_rules` row in V1; dynamic later based on requested/available ratio | Defers complexity while keeping the schema ready |
| Payment state machine `INITIATED → AUTHORIZED → CAPTURED → REFUNDED`; full refunds only, partial refunds deferred | Real fintech patterns (webhooks, idempotency, retries with jitter) are directly interview-relevant; sandbox is safe to iterate against |
| Audit scope = state transitions only, not location heartbeats | Heartbeats would turn the audit log into a location log at roughly 1.3M events/day — wrong story to tell; state transitions run closer to ~10k events/day at simulator load |
| Audit storage: Postgres partitioned first, then dual-write to ClickHouse via a Kafka sink; Postgres kept for point lookups + retention, ClickHouse for aggregation | The migration narrative is deliberately part of the learning goal — columnar vs. row-oriented trade-offs learned viscerally, not from a book |
| No ORM anywhere — explicit SQL via JdbcTemplate, immutable domain objects | Consistent choice across all services and tickets |
| Pessimistic row locking (`SELECT ... FOR UPDATE`) rather than advisory locks in V1 | Simpler, idiomatic, teaches SQL transaction depth; advisory locks are better for distributed locking across JVMs — revisit once the matching engine is distributed |
| Isolation level: Postgres default `READ COMMITTED` | `FOR UPDATE` row locks already cover the lost-update anomaly; `SERIALIZABLE` adds overhead without benefit at fixture scale — explicitly flagged for revisit if the system scales to multiple JVMs/distributed matching engines |
| Each microservice fully independent (own build, no shared root build, duplicated domain code acceptable) | Each service should be as buildable/independent as if it lived in its own repo |
| No host JDK — Docker + Gradle Wrapper + Eclipse Temurin images from day one | Stated constraint for the entire schedule |

## Data Model (entities, high level)

- **drivers**: id, name, status (`OFFLINE`/`AVAILABLE`/`BUSY`), current_lat/lng, timestamps
- **rides**: id, rider_id (plain UUID, no FK), driver_id (FK, nullable), pickup/dropoff lat/lng, status, requested_at/matched_at/completed_at, fare, timestamps; later additive columns include `estimated_duration_minutes` and `cancelled_at`
- **driver_locations**: id, driver_id (FK), lat/lng, recorded_at — immutable position history
- **fare_rules**: id, base_fare, per_km, per_minute, surge_multiplier — single static row initially
- **payment_intents**: id, ride_id (unique — one payment per ride), stripe_intent_id (unique), amount, currency, status, refund_pending, timestamps
- **webhook_events**: stripe_event_id (dedupe key), event_type, received_at, payload
- **audit_events**: id, event_id (dedupe key), actor_type/actor_id, entity_type/entity_id, action, timestamp, metadata; Postgres version partitioned by month, ClickHouse version uses a MergeTree-family engine over the same logical columns

## Tech Stack

Java / Spring Boot, Postgres, Kafka, Redis, Stripe (sandbox), ClickHouse, Prometheus + Grafana, Resilience4j, WebSockets, Kubernetes (local only — see PRD NFR-7 for the explicit cloud-deployment override).

## Ticket-Level Source Material — Historical, Superseded

`docs/tickets/pb-1.1.md` through `pb-7.1.md` were written before this PRD existed, at an early stage of the project. **They are not authoritative and should not be reconciled against.** When epics and stories are generated, the PRD plus the architecture spine are the source; these tickets are reference material at most.

They are already stale in ways that matter: the driver status enum, the payment flow (no two-phase authorize/capture, no `PAYMENT_FAILED` or `VOIDED`), the ride state machine (no `NO_DRIVER`, no explicit start-trip, no decline), the absence of driver availability and session reads, and a shared-database assumption the architecture run has since overridden with database-per-service.

What was worth keeping from them has already been extracted into this PRD during input reconciliation — the one-active-ride guard, the anti-enumeration cancel behaviour, and the driver accept/complete guard all became FRs that way. The remaining value is narrative: PB-6.1's isolation-level decision record and PB-7.1's `EXPLAIN`/indexing work document reasoning worth re-reading before redoing that work, and PB-4.1's expand-only migration discipline is a convention worth carrying forward even though it is deliberately not a PRD-level NFR.

## Sizing / Stress-Test Detail (NFR-2)

Target stress scale: ~20k drivers, ~200k riders. The purpose is to surface concrete bottlenecks worth learning from — connection pool exhaustion, missing indexes, Kafka partition throughput ceilings, cache hot-key contention — not to sustain that load indefinitely. Positioned as a late-phase milestone (alongside the local-K8s deploy phase), not a per-phase gate, so early matching-correctness work stays lean.

**Open mechanism question (for Architecture/Epics, not this PRD):** Payments is excluded from the stress test (NFR-8) because Stripe sandbox rate limits are outside Puber's control. The mechanism for that exclusion — a simulator config flag, routing stress-test rides through a stub/no-op payment path, a separate smaller test suite for payment concurrency, or something else — is undecided and belongs to the Architecture or Epics/Stories pass, not here.

## Why Rider Accounts and "Debtor" Standing Were Deferred

Considered and deliberately not built: persisting rider accounts, marking a rider a *debtor* when a capture fails, and blocking their future ride requests until they settle. Recorded here because the reasoning is the useful part — the idea is sound, it is the sequencing that is wrong.

1. **The pre-authorization hold (FR-33) largely eliminates the triggering scenario.** A hold reserves the funds; capturing against a valid hold does not fail for insufficient funds. Capture can still fail — card cancelled or reported stolen between authorization and capture, issuer revoking the hold, a hold left to expire — but "the rider had no money" is precisely the case the hold prevents. Building a debt system for it would mean building for a case the design already handles.
2. **A debtor flag is a one-way door without a clearing mechanism.** Marking a rider requires some way to un-mark them, or the rider is permanently locked out. Clearing means either charging outside any ride context — a payment flow with no ride to hang off — or an operator action, which is the review/approval workflow already deferred above. The exit costs more than the entrance.
3. **The learning payoff is thin relative to its cost.** Debtor standing is product policy: a flag, a gate at request time, a lifecycle. It exercises a cross-aggregate invariant and little else, competing for the same weeks as Kafka, the ClickHouse migration, and the scale work — all of which sit closer to this project's stated purpose.
4. **Payments is already over-subscribed.** The phase grew from five FRs to seven when the two-phase lifecycle and both failure paths landed; Weeks 17–20 was sized before that.

**The hook, if this is picked up later:** FR-35 already drives a failed capture to terminal `FAILED` with a full audit trail. A debtor feature would read that state rather than needing anything re-architected — and a riders table (also deferred) is the natural home for the flag. Nothing about the current design forecloses it.

## Open Mechanism Questions from the Rider Flow

**Transport for rider-side live driver position (FR-6).** The rider needs the assigned driver's position and ETA to update as the driver moves, but the WebSocket channel (FR-44) is scoped to pushing offers to drivers. Whether the rider polls the ride-read endpoint (FR-5) on an interval, gets a second WebSocket/SSE channel, or something else is an architecture decision, not a product one. Polling is the cheaper starting point and matches the fact that FR-5 already returns ride state; a push channel is the more interesting exercise. Deferred to the Architecture pass.

**Heartbeat staleness windows (FR-29, FR-13, FR-14).** The PRD deliberately fixes no numbers here. The Redis fast path already planned for driver locations makes the idle case nearly free — a TTL on the location key expires dead drivers without a sweep job — but the Postgres-only V1 has no such mechanism and will need either a query-time freshness predicate or a scheduled sweep.

Important: these should almost certainly be **three different windows**, not one shared constant, because the cost of a false positive rises sharply as the ride progresses:

| Case | Consequence of firing wrongly | Implied window |
|---|---|---|
| Idle driver un-matchable (FR-29) | Driver misses offers until they report again; self-healing | Shortest — tens of seconds is fine |
| `MATCHED` ride re-matched (FR-13) | Rider's assigned driver is swapped mid-approach; recoverable but visible | Longer |
| `IN_PROGRESS` ride auto-completed (FR-14) | Trip is ended and the held fare captured while the trip is still happening; **not** recoverable | Longest by a wide margin |

A single 60s window would end live trips every time a driver drove through a tunnel or a dead zone. Size FR-14's window against how long a plausible trip lasts in the simulated world, not against the heartbeat interval. Decide all three during Architecture alongside the 5s retry interval and 10s offer timeout.

**Declared status vs. observed reachability (FR-20, FR-29).** These are deliberately two facts, not one field, and the architecture must keep them apart. Status is the driver's declared intent and is written only by their own action or the ride lifecycle; reachability is derived from heartbeat freshness and is never persisted onto status. Matchability is the conjunction.

This falls out naturally once locations live in Redis: the declared status sits in the owning service's Postgres row, and reachability is simply whether the driver's Redis location key still exists under its TTL. Two stores, two lifetimes, no reconciliation between them — an absent key means unreachable, not offline. Note the architecture run's dispatch enum (`OFFLINE | AVAILABLE | OFFERED | ACCEPTED_RIDE | IN_RIDE`) has no staleness member and should not gain one; staleness is orthogonal to every value in it.

The V1 shortcut needs care here for the same reason the staleness window does: with no Redis yet, freshness has to come from a `last_heartbeat_at` column compared at query time, and the matching query must filter on *both* conditions. A query that filters only on status will happily dispatch to drivers who vanished an hour ago.

**Monotonic vs. wall clock, and where the boundary falls (NFR-9).** NFR-9 requires durations to be measured monotonically, but a monotonic clock is process-local — its readings are meaningless outside the JVM that produced them. That splits Puber's timers into two kinds:

- **In-process deadlines** — the offer timeout and the retry interval are set and evaluated inside matching-engine. Monotonic applies cleanly; no cross-service comparison happens.
- **Cross-service staleness** — a heartbeat originates at driver-api and its freshness is judged by matching-engine. Monotonic cannot span that boundary, so this necessarily compares wall-clock timestamps taken on two machines, and must tolerate clock skew between them. The staleness window should be comfortably larger than any plausible skew, which is a second reason (alongside tunnels and dead zones) not to make these windows tight.

Once locations move to Redis with a TTL, the TTL becomes the staleness mechanism and Redis's own clock becomes the single reference — which removes the skew question rather than answering it. Worth noting as another way the Kafka-era design is simpler than the V1 shortcut it replaces.

**Authorization is asynchronous, and that creates a race (FR-9, FR-16, FR-35).** The architecture pass split the old single pre-match state into `REQUESTED` (awaiting authorization) and `WAITING_MATCH` (funds held, in the matching pool). The payoff is that "never dispatch an unfunded ride" stops being a guard someone must remember to write and becomes a property of the query: matching reads only `WAITING_MATCH`, so an unfunded ride is not merely rejected, it is invisible.

The cost is a new window. A ride can now be cancelled while its authorization is still in flight, so the authorization result may arrive at a ride that has already reached a terminal state. The handler that receives it must therefore be written to void rather than assume the ride is still live — it cannot treat a successful authorization as implying a matchable ride. This is the same shape as the webhook-idempotency problem (NFR-4) and should reuse the same discipline: the arriving result is applied against current state, not against the state that existed when the call was made.

**Authorization hold lifetime (FR-33, FR-35).** Stripe holds expire on their own after several days, which is far longer than any Puber ride, so expiry is not a real concern at this scale — but it is worth knowing the hold is not indefinite. The more relevant question is whether a hold placed at request time can outlive the ride's own bounded windows (`NO_DRIVER` timeout, staleness windows): every terminal path must void or capture, or holds leak. Worth an explicit sweep or invariant check during Architecture — "no terminal ride has an outstanding `AUTHORIZED` payment" is a cheap and highly testable assertion.

**Bounded window before `NO_DRIVER` (FR-12).** The PRD states the ride gives up after a bounded window of failed retries but deliberately does not fix the number. `puber.md` originally floated 60 seconds as an optional behavior. Pick the concrete value during Architecture or Epics, alongside the existing 5s retry interval and 10s offer timeout, so all three are tuned as one set.
