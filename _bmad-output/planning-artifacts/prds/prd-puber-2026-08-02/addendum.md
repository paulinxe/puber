# Puber PRD — Addendum

Depth pulled out of the PRD proper because it's technical-how rather than product shape.

**Where authority now sits.** This addendum was written to *feed* the architecture pass. That pass has since run and made its own decisions, several of which overrode what was recorded here. **For technical decisions, the architecture run is the authority, not this file** — see `_bmad-output/planning-artifacts/architecture/architecture-puber-2026-08-03/`. What remains below is either still-current context the PRD depends on, or reasoning about *product* decisions (why something was deferred) that belongs with the PRD rather than the spine.

## Constraints That Still Hold

These predate the architecture run and survived it unchanged.

| Constraint | Rationale |
|---|---|
| No registration/auth; drivers seeded via fixtures; rider identity passed per-request | Auth is a multi-week detour that teaches nothing on the target syllabus |
| No `riders` table; `rides.rider_id` is a plain UUID with no FK | Keeps schema minimal — see the deferral reasoning below for why this held even when a debtor flag was proposed |
| Fare calculated at request time, not trip end | Matches real ride-hailing UX (price shown upfront); simpler than post-trip calculation |
| ETA formula: `haversine(pickup, driver) / 8.33 m/s` (~30 km/h) | Simplicity over routing-API realism |
| Surge: static multiplier initially, later derived from the requested/available ratio | Defers complexity while keeping the schema ready |
| Audit scope = state transitions only, never location heartbeats | Heartbeats would turn the audit log into a location log at roughly 1.3M events/day — the wrong story to tell; state transitions run closer to ~10k/day at simulator load |
| Audit storage: partitioned Postgres first, then ClickHouse, keeping both | The migration narrative is deliberately part of the learning goal — columnar vs. row-oriented learned viscerally rather than from a book |
| No ORM anywhere — explicit SQL, immutable domain objects | Consistent choice across every service |
| Isolation level: Postgres default `READ COMMITTED` | Guarded conditional updates and unique constraints cover the anomalies that matter; `SERIALIZABLE` adds overhead without benefit at this scale |
| Each service fully independent — own build, no shared library, duplicated domain code accepted | Each service should be as buildable as if it lived in its own repo. This is *why* conventions have to be fixed centrally rather than shared as code |
| No host JDK — Docker and pinned base images from day one | Stated constraint for the entire schedule |

## Superseded Here, Decided There

Recorded so a reader of this file does not act on stale material. Each was overridden by the architecture run.

| This addendum originally said | Superseded by |
|---|---|
| One shared database; matching queries `drivers.current_lat/current_lng` directly | Database-per-service; driver identity/location and dispatch state are owned by different services and never queried across |
| Location history batch-inserts to Postgres on a slow path | No Postgres location history at all — pings go to the columnar store only (FR-44), and live position is served from the cache |
| Direct HTTP calls are replaced by Kafka in the second phase | Only *events* move to the backbone (FR-31). Commands and reads stay synchronous permanently — a façade owning no data must call inward to get an id or a rejection |
| Unmatched rides retried on a fixed schedule | Continuous claim-based workers rather than a timer, so latency is not bounded by a tick |
| Geo bounds fixed at a 4km × 4km demo square | The operating area is a scenario parameter (NFR-2) — the demo square cannot hold stress-scale drivers at a sane density |
| `SELECT ... FOR UPDATE` as the concurrency mechanism | Guarded conditional updates plus a partial unique index for the one-active-ride rule; the store enforces the invariant rather than application-held locks |
| A single `drivers` table holding status and location together | Split by owner — identity and position in one service, dispatch state in another |

## Data Model (entities, high level)

Current shape. Ownership and the full column set live in the architecture spine; this is the PRD-level view.

- **rides** — identity, rider, assigned driver and their snapshotted display name, pickup/dropoff, state, the timestamps marking each transition, fare, how it was completed (driver or system), and which drivers have already refused it
- **drivers (identity)** — id, name, current position
- **drivers (dispatch)** — declared status and the ride currently engaging them; a separate table in a separate service from the above
- **fare_rules** — base, per-km, per-minute, surge multiplier
- **payments** — one per ride, provider intent id, amount, state, plus the timestamps that drive settlement: a void-requested stamp, written on **any** ride that ends without delivering a trip regardless of whether the authorization has resolved yet; a capture-requested stamp; and the retry bookkeeping the pursuit runs on — attempt count, next-attempt time, and the capture-failed time the FR-51 cooldown is measured from. Retry state is columns on the row, never an in-process schedule, so a restart resumes the pursuit rather than forgetting it. (Named for the domain concept, not the provider — the provider is swappable, so the id it issues is a column rather than the table's identity.)
- **webhook_events** — provider event id as the dedupe key, type, payload
- **audit_events** — event id, actor type/id, entity type/id, action, timestamp, metadata; partitioned by month in Postgres, mirrored to the columnar store
- **event_outbox** — per service: domain events written in the same transaction as the state change they describe, relayed and then removed

## Tech Stack

Java / Spring Boot, Postgres, Kafka, Redis, Stripe (sandbox), ClickHouse, Prometheus + Grafana, Resilience4j, WebSockets, HAProxy at the edge, gRPC between services, Kubernetes with GitOps delivery (local only — see PRD NFR-7 for the explicit cloud-deployment override).

## Ticket-Level Source Material — Historical, Superseded

`docs/tickets/pb-1.1.md` through `pb-7.1.md` were written before this PRD existed, at an early stage of the project. **They are not authoritative and should not be reconciled against.** When epics and stories are generated, the PRD plus the architecture spine are the source; these tickets are reference material at most.

They are already stale in ways that matter: the driver status enum, the payment flow (no two-phase authorize/capture, no `PAYMENT_FAILED` or `VOIDED`), the ride state machine (no `NO_DRIVER`, no explicit start-trip, no decline), the absence of driver availability and session reads, and a shared-database assumption the architecture run has since overridden with database-per-service.

What was worth keeping from them has already been extracted into this PRD during input reconciliation — the one-active-ride guard, the anti-enumeration cancel behaviour, and the driver accept/complete guard all became FRs that way. The remaining value is narrative: PB-6.1's isolation-level decision record and PB-7.1's `EXPLAIN`/indexing work document reasoning worth re-reading before redoing that work, and PB-4.1's expand-only migration discipline is a convention worth carrying forward even though it is deliberately not a PRD-level NFR.

## Sizing / Stress-Test Detail (NFR-2)

Target stress scale: ~20k drivers, ~200k riders. The purpose is to surface concrete bottlenecks worth learning from — connection pool exhaustion, missing indexes, Kafka partition throughput ceilings, cache hot-key contention — not to sustain that load indefinitely. Positioned as a late-phase milestone (alongside the local-K8s deploy phase), not a per-phase gate, so early matching-correctness work stays lean.

**How that exclusion works — decided by the architecture pass.** Payments is excluded from the stress test (NFR-8) because Stripe sandbox rate limits are outside Puber's control. The mechanism is to **swap the provider, not skip the path**: `payment-service` and its state machine run exactly as they do in production, and only the outbound provider call is replaced by a stub. The alternatives once listed here — a simulator flag, or routing stress-test rides around payments entirely — were rejected for the same reason: they would stress-test a code path that does not exist in production, so whatever the run proved would not be about the real system. The same swap covers running before payments are built, and running the suite with no provider credentials configured. Full reasoning in the answered-questions table below.

## Why Rider Accounts and "Debtor" Standing Were Deferred

Considered and deliberately not built: persisting rider accounts, marking a rider a *debtor* when a capture fails, and blocking their future ride requests until they settle. Recorded here because the reasoning is the useful part — the idea is sound, it is the sequencing that is wrong.

A narrower guard *is* in scope, and it is worth being precise about the difference. FR-51 refuses a ride request while the rider's most recent ride is a delivered trip whose payment has not yet settled — bounded, so an outage cannot refuse them indefinitely — or for 30 minutes after that ride's payment reached `CAPTURE_FAILED`. What it does not do is mark anybody: there is no flag, no standing, and no record of the rider beyond the payments they already have. It is a query over payment state at admission time, and it clears itself. Everything below is about the *persistent standing*, which remains deferred.

1. **The pre-authorization hold (FR-34) largely eliminates the triggering scenario.** A hold reserves the funds; capturing against a valid hold does not fail for insufficient funds. Capture can still fail — card cancelled or reported stolen between authorization and capture, issuer revoking the hold, a hold left to expire — but "the rider had no money" is precisely the case the hold prevents. Building a debt system for it would mean building for a case the design already handles.
2. **A debtor flag is a one-way door without a clearing mechanism.** Marking a rider requires some way to un-mark them, or the rider is permanently locked out. Clearing means either charging outside any ride context — a payment flow with no ride to hang off — or an operator action, which is the review/approval workflow already deferred above. The exit costs more than the entrance. FR-51 sidesteps the whole problem by taking a third route neither of those covers: it clears on *time*, which needs no flow and no operator. That works precisely because it never marks the rider in the first place — there is nothing to un-mark, so there is no door to be one-way.
3. **The learning payoff is thin relative to its cost.** Debtor standing is product policy: a flag, a gate at request time, a lifecycle. It exercises a cross-aggregate invariant and little else, competing for the same weeks as Kafka, the ClickHouse migration, and the scale work — all of which sit closer to this project's stated purpose.
4. **Payments is already over-subscribed.** The phase grew from five FRs to seven when the two-phase lifecycle and both failure paths landed; Weeks 17–20 was sized before that.

**The hook, if this is picked up later:** FR-37 already drives an uncapturable hold to the terminal `CAPTURE_FAILED` state with a full audit trail — and that state means exactly what a debtor flag would need it to mean: a delivered trip whose money is gone, not merely a provider that has yet to answer. A debtor feature would read that state rather than needing anything re-architected — and a riders table (also deferred) is the natural home for the flag. FR-51 has since taken the same reading and turned it into a self-clearing admission check, so the cheap half of the idea is already built; what a debtor feature would add on top is persistence and an operator lifecycle, which is exactly the expensive half. Nothing about the current design forecloses it.


## Questions This File Once Held Open — Now Answered

Every mechanism question the PRD deferred to the architecture pass has been decided. Summarised here so this file stops advertising them as open; the reasoning in full lives in the architecture run's log.

| Question | Answer |
|---|---|
| How Payments is excluded from the stress test (NFR-8) | Swap the provider, keep the path. The payment service and its state machine run normally; only the outbound provider call is replaced. Skipping the path would stress-test code that does not exist in production. The same swap covers running before payments are built, and running the suite with no provider credentials configured |
| Transport for the rider's live driver position (FR-6) | Polling, revisited when the push channel lands late in the plan. The driver's position only changes when a heartbeat arrives, so pushing more often carries no new information — both approaches converge on the same update rate |
| The staleness windows (FR-13, FR-14, FR-29) | Sized as one ordered set with every other time constant. The ordering is the part that matters and must survive any retuning: idle < matched < in-progress < capture-failed cooldown < session expiry, with the cost of a false positive rising at each step, and all of them comfortably larger than clock skew plus delivery lag |
| The bounded window before `NO_DRIVER` (FR-12) | Fixed as part of the same set, at roughly six offer attempts' worth of time |
| The hold-leak invariant (FR-34, FR-36) | Kept as an invariant to assert in tests, plus a metric and an alert — deliberately **not** an automatic sweep. An authorization that looks stranded may belong to a genuinely long trip, and voiding it automatically would invent an answer the system does not have. The one genuine race (cancelling while authorization is in flight) is handled by a flag on the payment rather than a cross-service query |
| Whether a ride waiting on authorization should time out | **No timeout at all.** Timing out would free the rider to request again and place a second hold while the first is outstanding — during a provider outage that manufactures a hold storm against the very dependency the exclusion exists to protect. Instead the one-active-ride rule becomes natural admission control: riders with a stuck ride cannot generate more. A metric and an alert replace the mechanism |

**The principle worth carrying forward**, extracted from the last two rows: bound the windows where the system can determine an outcome; do not bound a wait for external truth it will eventually receive. `NO_DRIVER` is legitimate because "we looked and found nobody" is an answer the system genuinely holds. "We do not know yet" is not, and inventing an answer there is what causes damage.

## Still Open

- **Concrete numbers below the spine** — outbox retry caps and backoff, batch sizes, connection-pool and replica counts, Kafka partition counts. The sizing *method* is settled (arrival rate × service time, drain must exceed arrival with headroom); the values are deliberately left to story time, and the stress run exists to produce them.
- **Nothing product-level.** No open question in this file blocks epics or stories.
