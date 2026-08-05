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

## Ticket-Level Source Material

`docs/tickets/pb-1.1.md` through `pb-7.1.md` already carry detailed subtasks, acceptance criteria, and per-ticket out-of-scope notes for Weeks 1–7 (bootstrap through query tuning/indexing). Not reproduced here — feeds directly into `bmad-create-epics-and-stories` rather than this PRD.

## Sizing / Stress-Test Detail (NFR-2)

Target stress scale: ~20k drivers, ~200k riders. The purpose is to surface concrete bottlenecks worth learning from — connection pool exhaustion, missing indexes, Kafka partition throughput ceilings, cache hot-key contention — not to sustain that load indefinitely. Positioned as a late-phase milestone (alongside the local-K8s deploy phase), not a per-phase gate, so early matching-correctness work stays lean.

**Open mechanism question (for Architecture/Epics, not this PRD):** Payments is excluded from the stress test (NFR-8) because Stripe sandbox rate limits are outside Puber's control. The mechanism for that exclusion — a simulator config flag, routing stress-test rides through a stub/no-op payment path, a separate smaller test suite for payment concurrency, or something else — is undecided and belongs to the Architecture or Epics/Stories pass, not here.

## Open Mechanism Questions from the Rider Flow

**Transport for rider-side live driver position (FR-6).** The rider needs the assigned driver's position and ETA to update as the driver moves, but the WebSocket channel (FR-40) is scoped to pushing offers to drivers. Whether the rider polls the ride-read endpoint (FR-5) on an interval, gets a second WebSocket/SSE channel, or something else is an architecture decision, not a product one. Polling is the cheaper starting point and matches the fact that FR-5 already returns ride state; a push channel is the more interesting exercise. Deferred to the Architecture pass.

**Heartbeat staleness windows (FR-27, FR-12, FR-13).** The PRD deliberately fixes no numbers here. The Redis fast path already planned for driver locations makes the idle case nearly free — a TTL on the location key expires dead drivers without a sweep job — but the Postgres-only V1 has no such mechanism and will need either a query-time freshness predicate or a scheduled sweep.

Important: these should almost certainly be **three different windows**, not one shared constant, because the cost of a false positive rises sharply as the ride progresses:

| Case | Consequence of firing wrongly | Implied window |
|---|---|---|
| Idle driver un-matchable (FR-27) | Driver misses offers until they report again; self-healing | Shortest — tens of seconds is fine |
| `MATCHED` ride re-matched (FR-12) | Rider's assigned driver is swapped mid-approach; recoverable but visible | Longer |
| `IN_PROGRESS` ride auto-completed (FR-13) | Trip is ended and charged while it is still happening; **not** recoverable | Longest by a wide margin |

A single 60s window would end live trips every time a driver drove through a tunnel or a dead zone. Size FR-13's window against how long a plausible trip lasts in the simulated world, not against the heartbeat interval. Decide all three during Architecture alongside the 5s retry interval and 10s offer timeout.

**Bounded window before `NO_DRIVER` (FR-11).** The PRD states the ride gives up after a bounded window of failed retries but deliberately does not fix the number. `puber.md` originally floated 60 seconds as an optional behavior. Pick the concrete value during Architecture or Epics, alongside the existing 5s retry interval and 10s offer timeout, so all three are tuned as one set.
