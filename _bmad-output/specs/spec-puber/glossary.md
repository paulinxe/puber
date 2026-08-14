# Glossary — Puber

Companion to `SPEC.md`. One vocabulary for every downstream consumer; state values are defined in `state-machines.md`.

## Actors

- **Rider** — the actor requesting a ride. Identified per request by a passed identifier; no account, no profile.
- **Driver** — the actor fulfilling rides. Fixture-seeded, tracked by location and status, and responsible for declaring their own availability.
- **Admin** — an audit `actor_type` label recorded on internally-triggered actions such as refunds. Not an authenticated role, not a person with a UI, and not a distinct set of capabilities — with no auth and no review workflow in scope, it exists only to distinguish an operator-triggered action from a `SYSTEM` one in the audit trail.
- **System** — the automated actor: retry workers, offer-timeout expiry, silent-driver recovery, auto-completion, hold release, surge recomputation, session expiry. Recorded as the actor on every audit event it causes.
- **Simulator** — the synthetic load-generation component standing in for real riders and drivers. In-process test fixture early, standalone containerized generator later.

## Ride and money

- **Quote** — a read-only fare/distance/ETA estimate for a pickup/dropoff pair that creates no ride. Indicative, not binding.
- **Fare** — the price computed once at request time: `(base + per-km × distance + per-minute × time) × surge`. Never recomputed at trip end.
- **Surge** — the fare multiplier held in `fare_rules`. A static `1.00` in the early phases; later recomputed periodically from the ratio of outstanding requests to available drivers. Global, never per-cell or per-rider.
- **ETA** — estimated arrival of the *matched driver at pickup*, from haversine distance and a fixed assumed speed (~30 km/h / 8.33 m/s). Not the trip's own duration.
- **Hold** — the authorization placed on the locked fare when a ride is requested. Captured if the trip completes, voided if the ride ends without one, and abandoned only when the provider reports it is no longer capturable — a hold is never given up on because retries ran out.
- **Offer** — a time-bounded proposal of a ride to one specific driver, during which the ride sits in `OFFERED`. Released back to the pool if declined, if the driver goes offline, or if it is not answered in time; withdrawn outright if the rider cancels. A driver who has refused or ignored a ride is never offered it again.

## Location and availability

- **Heartbeat** — a driver's periodic location report, stamped at produce time so consumer lag cannot make a vanished driver look freshly heard. Ephemeral telemetry, never a state transition, and therefore never an audit event or an outbox row.
- **Reachable** — the system has heard a heartbeat from the driver inside the staleness window. Derived, never declared, and orthogonal to status.
- **Matchable** — declared `AVAILABLE` **and** reachable. The conjunction is the whole test; either half alone is insufficient.
- **Silent driver** — a driver whose last heartbeat is older than the staleness window. Not matchable while silent, and any ride they hold is recovered (salvaged before pickup, auto-completed mid-trip) — but their declared status is untouched, so they recover automatically on the next heartbeat. A tunnel does not end a shift.
- **Session expiry** — distinct from silence: an **idle** driver unreachable for far longer than any staleness window is set `OFFLINE` by the system and must explicitly go online again. Never applies to a driver holding a ride.

## Data and events

- **Audit event** — an immutable record of a single domain state transition, tagged with actor type/ID and entity type/ID. State transitions only; location heartbeats are never audited.
- **Event envelope** — the routing header every event carries: `event_id`, `event_type`, `entity_type`, `entity_id`, `actor_type`, `actor_id`, `schema_version`, `occurred_at`, `correlation_id`. `entity_type`/`entity_id` deliberately match `audit_events`, so one vocabulary spans both.
- **Outbox** — per-service table holding domain events written in the same local transaction as the state change they describe, relayed and then deleted. Never used for heartbeats.
- **Fixture scale / stress scale** — fixture scale is ~30 seeded drivers in a small demo grid, where the system is functionally proven. Stress scale is ~20k drivers and ~200k riders across a grid roughly 20 km wide, a late-phase milestone whose purpose is to surface bottlenecks, not to sustain traffic.

## Entities (PRD-level view; ownership and columns live in the architecture spine)

- **rides** — identity, rider, assigned driver and their snapshotted display name, pickup/dropoff, state, per-transition timestamps, fare, `completed_by`, and the drivers who have already refused it.
- **drivers (identity)** — id, name, current position. Owned by `driver-service`.
- **drivers (dispatch)** — declared status and the ride currently engaging them. A separate table in a separate service from the above.
- **fare_rules** — base, per-km, per-minute, surge multiplier.
- **payments** — one per ride: the provider's intent id, amount, state, plus flags for a refund or a void requested before the authorization resolved. Named for the domain concept, not the provider's — the provider is swappable (AD-43) and the identifier it issues is a column, not the table's identity.
- **webhook_events** — provider event id as the dedupe key, type, payload.
- **audit_events** — event id, actor type/id, entity type/id, action, timestamp, metadata. Partitioned by month in Postgres, mirrored to the columnar store.
- **event_outbox** — per service; see **Outbox** above.
