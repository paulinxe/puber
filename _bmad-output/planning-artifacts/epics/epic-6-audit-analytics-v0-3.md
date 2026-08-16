## Epic 6: Audit & Analytics — `v0.3`

Every state transition the system ever made is queryable by entity and by actor, bounded in Postgres
and complete in a columnar store — and the location and audit history finally has a consumer.

> **Ordering constraint inside this epic.** The columnar mirror (Story 6.3) lands **before** partition
> dropping (Story 6.4). Enabling retention first would drop history whose only other copy does not
> exist yet. The table is created *partitioned* in Story 6.1 because partitioning is structural and
> cannot be retrofitted cheaply; only the *pruning* waits.

> **Open decision — storage ceiling *values*.** To be settled when Stories 6.4 and 6.5 are detailed.
> **The mechanism is no longer open**: AD-60 fixes it for the ping history — a configured ceiling
> enforced by **evicting the oldest data first**, with an ingest stop as an **alarmed backstop only,
> never the routine mechanism** — and AD-47's deferred-capacity list now covers storage. What remains
> is the numbers, and applying the same shape to the Postgres audit table.
>
> **Where the risk actually sits.** Order-of-magnitude, to be replaced by measurement: audit events
> run ~10k/day at fixture load — roughly 4 MB/day, ~1.5 GB across a 12-month window. Location pings
> run ~1.3M/day at the same load, and at stress scale 20k drivers heartbeating every 2 s produce
> ~864M/day, on the order of 13 GB/day compressed. The two differ by roughly 130:1 by event count,
> so a ceiling on the audit table is near-cosmetic while a ceiling on the ping history is the one
> that matters.
>
> **Two mechanisms, not one.** Time-based retention bounds steady accumulation; a size bound is what
> protects against a burst, and a stress run is precisely a burst that a 12-month window does nothing
> about. The proposed shape is **size-based eviction of the oldest data as the primary mechanism**,
> with a **hard ingest stop only as an alarmed backstop**.
>
> **Why a hard stop cannot be the primary mechanism.** AD-48 grants Tier 3 components the right to be
> disabled and lose no data *because consumer offsets survive and the backlog drains on return* —
> which holds only while the events remain in Kafka. An ingest stop lasting longer than Kafka's
> retention converts a disk problem into permanent, silent data loss.
>
> **Derive rather than guess**, following AD-35's precedent for the outbox bound: ingest rate ×
> the window to retain, with headroom, confirmed under the NFR-2 stress run (AD-47, AD-60).

### Story 6.1: audit-service records every state transition with its actor

As an operator,
I want every ride, driver and payment state transition recorded with the actor that caused it,
So that the history of the system is reconstructable rather than inferred from logs.

**Acceptance Criteria:**

**Given** the Compose stack
**When** it is brought up
**Then** `audit-service` starts with its own private Postgres database (AD-1, AD-3)
**And** it exposes health and Prometheus metrics like every other service (AD-54)
**And** it carries its own Gradle wrapper and the standard package layout (AD-7, AD-52)

**Given** ride, driver and payment events on the backbone
**When** `audit-service` consumes them
**Then** it writes one immutable `audit_event` per state transition, carrying event id, actor type
and id, entity type and id, action, timestamp and metadata (FR-41)

**Given** `entity_type` and `entity_id`
**When** they are written
**Then** they use the same vocabulary as the event envelope, so one vocabulary spans both tables (AD-31)

**Given** an automated transition
**When** its actor is recorded
**Then** it is `SYSTEM` — for offer expiry, `NO_DRIVER`, auto-completion, hold release, surge
recomputation and session expiry (FR-41)
**And** an internally-triggered refund records `Admin`, which distinguishes an operator action from a
`SYSTEM` one and is not an authenticated role (Glossary, FR-39)

**Given** location heartbeats
**When** audit scope is defined
**Then** they are **never** audited
**And** this is because heartbeats would turn the audit log into a location log at roughly 1.3M
events/day against ~10k/day of state transitions — the wrong story to tell (Constraints)

**Given** a redelivered event
**When** it is consumed
**Then** it is deduplicated on `event_id` and writes no second row (AD-36, NFR-4)

**Given** a message this consumer cannot process
**When** it retries
**Then** backoff is jittered, and on exhaustion the message is routed to a dead-letter topic rather
than blocking its partition or being discarded
**And** dead-lettered volume is a metric that alerts and is zero in health (AD-55)

**Given** `audit-service` subscribing to streams `matching-service`, `driver-service` and
`payment-service` already produce
**When** it is added
**Then** it runs in its own consumer group and **no producer and no other consumer changes**
**And** this is the system's second independent consumer, which is what *proves* decoupling rather
than describing it (FR-33, AD-36)

**Given** the `audit_events` table
**When** it is created
**Then** it is **partitioned by month** from the outset, because partitioning is structural and
cannot be retrofitted cheaply (AD-53)
**And** pruning is not yet enabled — see Story 6.4

**Given** audit ingest
**When** it is observed
**Then** its rate is exposed as an operational metric (FR-46)

**Given** `audit-service` disabled entirely
**When** the rest of the system runs
**Then** nothing breaks
**And** on its return, consumer offsets survive and the backlog drains with no data lost — it is
Tier 3 (AD-48)

**Given** recorded timestamps
**When** they are written
**Then** they are `TIMESTAMPTZ` in UTC, on wall clock, because they are recorded facts rather than
in-process deadlines (Timestamps convention)

### Story 6.2: Audit is queryable by entity and by actor

As an operator,
I want to ask what happened to a given entity, and what a given actor did,
So that a question about the past has an answer that does not involve reading logs.

**Acceptance Criteria:**

**Given** the audit query API
**When** it is exposed
**Then** the gateway routes to it — audit's query API is one of the actor-facing routes the gateway
carries, alongside `rider-service`, `driver-service` and the Stripe webhook (AD-5)

**Given** an entity type and id
**When** the audit trail is queried
**Then** that entity's transitions are returned in order (FR-42)

**Given** an actor type and id
**When** the audit trail is queried
**Then** that actor's transitions are returned (FR-42)

**Given** a point lookup by entity or actor
**When** it is served
**Then** it is answered from **Postgres**, which is that store's job (AD-53)

**Given** any query
**When** results are returned
**Then** they are bounded by a result-size limit

**Given** an audit event
**When** any mutation is attempted
**Then** no update or delete path exists — audit events are immutable (Glossary)

**Given** an absent entity or actor
**When** it is queried
**Then** the response is 404, never 403 (AD-38)

### Story 6.3: ClickHouse mirrors the full history as a parallel consumer

As an operator,
I want the complete analytical history in a store built for it, fed independently of Postgres,
So that neither store is derived from the other and either can be rebuilt from the log.

**Acceptance Criteria:**

**Given** the Compose stack
**When** this story lands
**Then** ClickHouse 26.3 LTS joins it (Stack)
**And** it is Tier 3 — disabling it breaks nothing and the Postgres audit trail continues (AD-48)

**Given** the columnar store
**When** it is fed
**Then** it consumes **the same Kafka topics** in its own independent consumer group (FR-43, AD-53)

**Given** the feeding mechanism
**When** it is examined
**Then** it is a **parallel consumer, never a Postgres-to-columnar copy job**
**And** neither store is therefore derived from the other (AD-53)

**Given** the columnar store
**When** its retention is considered
**Then** it holds the full logical history indefinitely (NFR-6, AD-53)

**Given** either store lost entirely
**When** it is rebuilt
**Then** replaying the topics from `earliest` reconstructs it (AD-53, AD-36)

**Given** a redelivered event
**When** it is consumed into the columnar store
**Then** it does not produce a duplicate row (AD-36, NFR-4)

### Story 6.4: Audit retention is enforced by dropping partitions

As an operator,
I want old audit data pruned from Postgres by dropping partitions,
So that the table stays bounded without a churn of dead tuples, and without losing the history.

**Acceptance Criteria:**

**Given** the retention window elapsing
**When** pruning runs
**Then** whole monthly partitions are **dropped**
**And** row-level `DELETE` is never used, so retention is a metadata operation rather than a churn of
dead tuples (AD-53)

**Given** the retention window
**When** it is configured
**Then** it is configuration rather than a constant
**And** its default is 12 months, stated as a revisable target rather than a hard commitment (NFR-6)

**Given** a partition due for dropping
**When** pruning evaluates it
**Then** it is dropped only once the same range is present in the columnar store
**And** a drop is therefore never the removal of the only copy (NFR-6, AD-53)

**Given** the columnar store
**When** Postgres partitions are dropped
**Then** the same logical history remains available there indefinitely (NFR-6)

**Given** the retention window
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by waiting (NFR-9)

**Given** a configured **size ceiling** alongside the time window
**When** the table approaches it
**Then** the oldest partitions are dropped ahead of the time window rather than waiting for it
**And** a size bound is what protects against a burst, since a time window bounds only steady
accumulation — see the epic's open decision on storage ceilings

**Given** growth toward either bound
**When** it is observed
**Then** table size and the headroom remaining against the ceiling are exposed as gauges that alert
before the bound is reached, not when it is (AD-54)

### Story 6.5: Location pings reach the columnar store

As an analyst,
I want the driver location history durably retained where it can be queried at scale,
So that the pings the system already collects stop being write-only.

**Acceptance Criteria:**

**Given** driver location events on the backbone
**When** they are consumed into the columnar store
**Then** the position history persists durably (FR-27)

**Given** the location history
**When** its home is examined
**Then** it lives in the columnar store **only**
**And** there is deliberately no Postgres location history at all (Constraints, FR-27)

**Given** ping volume of roughly 1.3M/day at simulator load
**When** the design is reviewed
**Then** this volume is precisely why the history is columnar-only and never enters the audit trail (Constraints)

**Given** a redelivered location event
**When** it is consumed
**Then** it does not produce a duplicate row, deduplicated on the payload-derived key
**`(driver_id, occurred_at)`** rather than on an `event_id`, which this stream does not carry
(AD-60, AD-36, NFR-4)

**Given** the ping history
**When** its start is considered
**Then** it **begins with the topic** and is never backfilled
**And** before the HTTP→Kafka swap there is no ping history at all, since Redis holds none (AD-60, AD-27)

**Given** `audit-service` owning the columnar ping table
**When** ownership is examined
**Then** owning the **table** makes it no second owner of the **fact** — `driver-service` remains the
sole producer of a driver's location
**And** reading this history is never a substitute for reading Redis (AD-60, AD-3)

**Given** a retained ping
**When** its stored shape is inspected
**Then** it carries driver identity, position and the produce-time timestamp
**And** that is what distance-per-driver and ride-density-by-area are later derived from, so no new
collection is introduced for them (FR-44, FR-27)

**Given** ping volume — the largest data stream in the system by roughly two orders of magnitude
**When** its storage is bounded
**Then** a configured ceiling governs it, enforced by evicting the oldest data first
**And** an ingest stop is a last-resort backstop that alerts rather than the routine mechanism, since
stopping longer than Kafka's retention would lose data permanently (AD-48) — see the epic's open
decision on storage ceilings

**Given** the ceiling
**When** its value is chosen
**Then** it is derived as ingest rate × the window to retain, with headroom, and confirmed by
measurement under the stress run rather than guessed now (AD-47, AD-35)

**Given** stored volume and headroom against the ceiling
**When** they are observed
**Then** both are gauges that alert before the bound is reached (AD-54)

### Story 6.6: Aggregate analytics are computed from data already collected

As an analyst,
I want distance, ride density and driver utilization computed from the history the system already keeps,
So that the collected data has a consumer instead of sitting unread.

**Acceptance Criteria:**

**Given** the mirrored location-ping history
**When** distance traveled per driver is computed
**Then** it is derived from that history (FR-44)

**Given** the same history
**When** ride density by area is computed
**Then** it is derived from that history (FR-44)

**Given** driver utilization — the percentage of time in each status
**When** it is computed
**Then** it is derived from the driver **state-transition audit trail**, not from location pings (FR-44)

**Given** any of these analytics
**When** the data they need is considered
**Then** nothing new is collected for them — each is computed from data already being collected (FR-44)

**Given** a question about the past
**When** the store to answer it is chosen
**Then** point lookups by entity or actor are served from Postgres, and aggregate analytics from the
columnar store
**And** there is exactly one answer to "which store answers this question" (AD-53)

**Given** each aggregate
**When** the migration is delivered
**Then** it is measured on both the row-oriented and the columnar store
**And** the before/after figures are recorded, so the migration ships with a benchmark rather than an
assertion (CAP-33, Goals)

