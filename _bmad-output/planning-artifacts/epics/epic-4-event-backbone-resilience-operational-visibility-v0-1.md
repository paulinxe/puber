## Epic 4: Event Backbone, Resilience & Operational Visibility — `v0.1`

Services stop calling each other for anything nobody is waiting on, failures degrade instead of
cascading, and the system's behaviour is visible on a dashboard rather than in logs. Surge starts
moving with real demand.

> **Properties CAP-23 and CAP-24 become standing acceptance criteria from this epic onward.** Neither
> is a story: retry-with-jitter, circuit breaking and dead-lettering attach to every producer and
> consumer story, and idempotency-on-`event_id` attaches to every consumer and externally-triggered
> handler for the rest of the project. See *Testing policy* in the Overview.

### Story 4.1: Domain events are recorded in a transactional outbox

As an operator,
I want every domain event written in the same transaction as the state change it describes,
So that the system can never commit a state change whose event was lost, nor emit an event for a change that never committed.

**Acceptance Criteria:**

**Given** a domain state change
**When** it commits
**Then** an `event_outbox` row is written in the same local transaction (AD-28, FR-31)

**Given** that transaction
**When** it rolls back
**Then** neither the state change nor the outbox row persists (AD-28)

**Given** domain code
**When** it is inspected
**Then** it never publishes to a transport directly — it only writes the outbox row (AD-28)

**Given** a location heartbeat
**When** it is handled
**Then** **no** outbox row is written, because pings are ephemeral telemetry rather than state
transitions and would put the hot-row write pattern back into Postgres (AD-28)

**Given** an outbox row
**When** its envelope is inspected
**Then** it carries `event_id`, `event_type`, `entity_type`, `entity_id`, `actor_type`, `actor_id`,
`schema_version`, `occurred_at` and `correlation_id` as columns
**And** `entity_type`/`entity_id` use the same vocabulary that `audit_events` will use, so one
vocabulary spans both tables (AD-31)

**Given** the payload
**When** it is written
**Then** it is the fully-formed event serialised as JSON at transaction time
**And** it is never the entity stored to be rendered later, since the relay runs seconds afterwards
by which point the ride may have moved on
**And** it never uses native Java serialisation, since consumers are independently built (AD-30)

**Given** each field on an event payload
**When** it is traced to a consumer
**Then** at least one consumer reads it — the event carries what its consumers need inline and nothing
more
**And** this is because a field once published can never be removed (AD-32, AD-33)

**Given** an event type
**When** it is declared
**Then** it is named `<entity>.<past-tense-action>` in lowercase
**And** its prefix matches `entity_type` (Event naming convention)

**Given** the `event_outbox` primary key
**When** it is declared
**Then** it is a `GENERATED ALWAYS AS IDENTITY` bigint, assigned from one authority rather than from
a replica's clock (AD-29)

### Story 4.2: Each service declares the topics it produces and consumes

As an operator,
I want every service to declare its topics and subscriptions in its own configuration, and the broker to be provisioned from those declarations,
So that adding a consumer is a configuration change in one service rather than a coordinated edit across several.

**Acceptance Criteria:**

**Given** the Compose stack
**When** this story lands
**Then** Kafka 4.3.1 in **KRaft mode** joins it, with no ZooKeeper
**And** it is Tier 1 infrastructure — required, never optional (AD-48, Stack)

**Given** the broker
**When** it is configured
**Then** `auto.create.topics.enable` is **false**
**And** a reference to an undeclared topic therefore fails loudly rather than silently creating a
phantom topic with default partitions

**Given** a service
**When** its configuration is inspected
**Then** it declares the topics it **produces** — each with partition count and replication factor —
and the topics it **subscribes** to, each with its consumer group

**Given** a topic
**When** its shape is declared
**Then** only the **producing** service declares partition count and replication factor
**And** a consuming service declares subscription alone, so two services can never disagree about a
topic's shape

**Given** a service starting
**When** a topic it owns is absent
**Then** it is created from the declaration
**And** starting a second time creates nothing and fails nothing

**Given** a new consumer of an existing stream
**When** it is added
**Then** the change is a subscription declaration in that service alone
**And** no producer and no other consumer is touched (FR-33, AD-36)

**Given** a topic that already exists
**When** a partition-count change is proposed
**Then** it is treated as breaking and requires a new topic rather than an edit
**And** this is because AD-36 keys by entity id for ordering, so re-mapping the hash would land one
entity's future events on a different partition from its past ones and break ordering silently (AD-33, AD-36)

**Given** a new consumer group
**When** it first starts
**Then** it starts at `earliest` (AD-36)

**Given** declared partition counts
**When** they are chosen
**Then** the reasoning is recorded alongside them
**And** the concrete values are confirmed by measurement under the stress run rather than guessed
now (AD-47)

**Given** the declarations
**When** the system later deploys to Kubernetes
**Then** they remain the single source for topic shape
**And** topic configuration is not reinvented as a second, divergent description in the manifests (AD-49)

**Given** integration tests exercising the event path
**When** they run
**Then** they run against the real Kafka in the Compose stack
**And** no embedded broker, in-memory substitute or mocked producer is used anywhere (AD-56, AD-10)

**Given** a test class
**When** it starts
**Then** it begins from a known broker state, so events published by an earlier class cannot be
consumed by a later one (AD-56)

> **Note on the division with AD-52.** The versioned contracts directory owns the event *schema* —
> what a `ride.matched` payload contains — and is copied into each service at build time. This
> per-service configuration owns the *wiring*: which topics this service produces to and subscribes
> to. One is the contract, the other is the deployment fact; keeping them apart is what lets a
> service change its subscriptions without touching a shared contract.

### Story 4.3: The relay ships outbox rows to Kafka

As an operator,
I want a relay that claims outbox rows and publishes them,
So that events reach consumers without the domain ever knowing a transport exists.

**Acceptance Criteria:**

**Given** claimable outbox rows
**When** the relay runs
**Then** it selects by claimability, locks with `FOR UPDATE SKIP LOCKED`, publishes, and **deletes**
each row on success (AD-29)
**And** how many rows are claimed per pass is left open — see the note below

**Given** the relay
**When** its progress tracking is inspected
**Then** it never advances a high-water-mark cursor such as `WHERE id > lastSeen` (AD-29)

**Given** a transaction holding a lower identity that commits *after* one holding a higher identity
**When** the relay next runs
**Then** the late-committing row is picked up
**And** it is never skipped permanently — which is exactly the dual-write loss the outbox exists to
prevent (AD-29)

**Given** `occurred_at`
**When** ordering is considered
**Then** it records when the event happened
**And** it is never used for ordering (AD-29)

**Given** a published event
**When** it reaches Kafka
**Then** the partition key is the entity id, so one entity's events never process out of order across
partitions (AD-36)

**Given** the transport
**When** it is wired
**Then** it sits behind the `EventTransport` Strategy (AD-10)

**Given** commands and reads that an actor waits on
**When** this epic lands
**Then** they remain synchronous gRPC calls and are never moved to the backbone, because the edge
services own no data and must reach `matching-service` for an id, a rejection or a result (FR-31, AD-37)

> **Open decision — claim size: batched or single-row.** Deliberately unsettled, to be taken when
> this story is detailed. AD-34 describes a batch that "commits once so successes persist regardless
> of neighbours"; the clause after *so* is the invariant, and the batch is one way of reaching it.
> Single-row processing reaches it structurally instead, at the cost of one transaction per event.
>
> Three inputs for that conversation. **Volume is small**: outbox traffic is state transitions only —
> roughly 10k/day at simulator load against ~1.3M/day of heartbeats, which deliberately bypass the
> outbox (AD-28) — so the throughput case for batching argues about a stream two orders of magnitude
> smaller than the one already excluded. **Batch-of-one is the degenerate case**: if claim size is a
> configuration value rather than a structural choice, single-row needs no separate code path and the
> decision becomes a tuning value settled by the stress run, like every other capacity number
> (AD-47). **The choice should be consistent across all three claim-loop workers** — matching
> (AD-20), this relay (AD-29/AD-34), and settlement (AD-58) share one shape, and three different
> answers would be three things to reason about instead of one.
>
> If a fixed single-row design is chosen rather than a configured claim size, AD-34's wording is
> the authority and should be amended, not silently diverged from.

### Story 4.4: Outbox rows retry, dead-letter, and respect the breaker

As an operator,
I want one poison event to be unable to stall the stream and a brief outage to be unable to mass-dead-letter the backlog,
So that a single bad message never becomes a total outage.

**Acceptance Criteria:**

**Given** a row that publishes successfully
**When** the batch commits
**Then** that row is deleted (AD-34)

**Given** a row that fails to publish
**When** the attempt completes
**Then** `tries` increments and `next_attempt_at` is pushed out by exponential backoff **with jitter**
(AD-34, FR-32, NFR-3)

**Given** a pass in which some rows publish and others fail
**When** their outcomes are applied
**Then** each row's outcome persists independently of its neighbours'
**And** one failing row never rolls back, discards or skips another row's success — however many rows
a pass claims (AD-34)

**Given** a row reaching the retry cap
**When** it is evaluated
**Then** `dead_at` is stamped
**And** the row stops being claimed (AD-34)

**Given** the circuit breaker
**When** it is evaluated
**Then** it is checked **before touching the database**, so rows that were never fetched cannot have
their `tries` incremented by an outage (AD-34)

**Given** a half-open breaker
**When** the relay probes
**Then** it probes with a deliberately limited volume rather than a full pass, so a still-broken
dependency costs a handful of attempts rather than a backlog's worth (AD-34)

> **Open decision.** This story's retry bookkeeping is per row either way, but whether a pass claims
> one row or many is settled together with Story 4.3 — see the open-decision note there.

**Given** dead rows
**When** they are observed
**Then** they are tracked by their own gauge which alerts, rather than being deleted (AD-34, AD-35)

> **Carried in from Story 1.1 (PUB-1), 2026-08-19 — datasource socket timeouts are unset.**
>
> PUB-1 capped HikariCP's `connection-timeout` (2s) and `validation-timeout` (1s), but left the
> PostgreSQL JDBC driver's own timeouts at their defaults: `connectTimeout=10` seconds and
> **`socketTimeout=0`, meaning no timeout at all.**
>
> Those bound different things. Hikari's `connection-timeout` bounds *acquiring* a connection, not
> *using* one. If a partition happens mid-query on an already-established connection, the socket read
> blocks forever: no Hikari setting covers it, and the request thread hangs indefinitely. PUB-1's AC4
> proved "cannot connect" reports DOWN promptly; nothing covers "connected, then the network vanished",
> which is the failure that actually shows up in a cluster.
>
> It lands here rather than in PUB-1 for two reasons. Choosing a `socketTimeout` needs a view of the
> slowest legitimate query, which did not exist when the service had no queries. And this epic already
> needs controllable failure injection to prove retry, dead-lettering and the breaker — PUB-1
> explicitly deferred Toxiproxy to Epic 4 for that reason — which is also exactly what makes a stalled
> established connection testable rather than asserted.
>
> Set `socketTimeout` deliberately, and align `connectTimeout` with Hikari's acquisition window; today
> the driver keeps trying for up to 10s after Hikari has already given up at 2s.

### Story 4.5: The outbox sheds when its backlog ages

As an operator,
I want new ride requests refused once the outbox backlog has stopped moving,
So that the system never silently accumulates unpublished truth while appearing healthy.

**Acceptance Criteria:**

**Given** a backlog past its bound
**When** a new ride request arrives
**Then** it is shed with `UNAVAILABLE` → 503 rather than accepting work the system cannot record
(AD-35, AD-38)

**Given** the shed trigger
**When** it is evaluated
**Then** it is backlog **age**, not depth — a large backlog draining healthily is fine, while a small
one that has not moved is broken (AD-35)

**Given** the age metric
**When** it is computed
**Then** it counts only claimable rows (`dead_at IS NULL`) (AD-35)

**Given** a single dead-lettered poison event
**When** the age metric is read
**Then** it does not age the backlog
**And** one bad message therefore cannot shed every ride request permanently (AD-35)

**Given** a shed request
**When** metrics are examined
**Then** it is **not** counted as an admission refusal — a 503 keeps its own signal, separate from
the 409 refusal counter (AD-54)

**Given** the bound
**When** it is derived
**Then** it is arrival rate × the outage duration to ride out
**And** drain rate exceeds arrival rate with headroom (AD-35, AD-47)

**Given** every queue in the request path
**When** it is observed
**Then** it exposes depth and age as gauges, and the tightest bound sits at HAProxy where there is
visibility, so overflow sheds rather than piling up inside the JVM (AD-6, AD-54)

### Story 4.6: Driver location events reach matching-service over the backbone

As a driver,
I want my position to reach the matching system over the event backbone,
So that the two services stop calling each other for something nobody is waiting on.

**Acceptance Criteria:**

**Given** a heartbeat
**When** `driver-service` handles it
**Then** it publishes a location event **directly** to the backbone, not through the outbox (AD-28)

**Given** that event
**When** it is produced
**Then** the timestamp is stamped at produce time
**And** no consumer re-stamps it on receipt, so consumer lag cannot make a vanished driver look
freshly heard (AD-23)

**Given** the event
**When** it is published
**Then** the partition key is the driver id (AD-36)

**Given** `driver-service` producing heartbeats
**When** they are published
**Then** every heartbeat goes **exactly once** onto a **single** location topic
**And** it is never re-published onto a second topic, which is where AD-23's produce-time stamp would
be lost or re-stamped and consumer lag would start masking staleness again (AD-60)

**Given** `matching-service` consuming location events
**When** an event arrives
**Then** it is deduplicated on a key **derived from the payload — `(driver_id, occurred_at)`**
**And** **a ping carries no `event_id`**, so no generated identifier is looked for on this stream; a
redelivery presents the same derived key with nothing to remember (AD-60, AD-36, NFR-4)

**Given** every reader of driver location
**When** it consumes
**Then** it does so as an **independent consumer group over that same topic**, in AD-53's
parallel-consumer shape (AD-60)

**Given** a redelivered event
**When** it is processed again
**Then** nothing changes, because at-least-once delivery makes duplicates normal rather than
exceptional (NFR-4, CAP-24)

**Given** a consumer that cannot process a message
**When** it retries
**Then** backoff is **jittered**, never synchronised — or every replica retries in lockstep and
hammers the dependency in waves (AD-55)

**Given** retries exhausted
**When** the message is finally handled
**Then** it is routed to a dead-letter topic
**And** it neither blocks its partition nor is silently discarded (AD-55)

**Given** dead-lettered volume
**When** it is observed
**Then** it is a metric that alerts and is zero in health (AD-55)

**Given** a new consumer group
**When** it starts
**Then** it starts at `earliest` (AD-36)

**Given** the gRPC location forward introduced in Story 2.3
**When** this story lands
**Then** it is removed
**And** the `DriverLocationIndex` seam is unchanged — only the transport behind it (AD-10)

### Story 4.7: Redis serves the matchable geo index and every driver's position

As a rider,
I want driver positions served from a store built for proximity search,
So that matching stays fast and my live driver position stays sub-second as the system grows.

**Acceptance Criteria:**

**Given** the `DriverLocationIndex` Strategy
**When** its implementation is swapped to Redis
**Then** no caller changes
**And** no caller branches on which implementation is active or special-cases its behaviour (AD-10, AD-57)

**Given** Redis
**When** its structures are inspected
**Then** there are two with deliberately different populations: a geo set holding **matchable drivers
only**, searched with `GEOSEARCH`; and a per-driver position key covering **every** driver, TTL'd
longer than the longest staleness window (AD-26)

**Given** a heartbeat
**When** it is applied
**Then** it performs two pipelined Redis writes
**And** **zero** Postgres writes (AD-26)

**Given** a stale member encountered during a geo search
**When** it is found
**Then** it is removed lazily on encounter rather than by an eager sweep (AD-26)

**Given** a driver whose position key is absent
**When** staleness is evaluated
**Then** absence is **never** evidence of staleness and no sweep acts on a missing key
**And** this is because Redis holds no persistence, so after a restart every key is absent while the
drivers behind them are perfectly healthy — treating that as staleness would auto-complete and
capture every in-flight ride at once (AD-26, AD-27)

**Given** Redis deployment
**When** it is configured
**Then** it is one instance with no cluster, no persistence, and `noeviction` with generous headroom
**And** it is never a source of truth (AD-27)

**Given** a candidate selected from the geo set
**When** a state change is decided
**Then** the decision is made by a conditional `UPDATE` against the owning row
**And** the loser re-searches, because a cache may only select candidates (AD-4)

**Given** position reads
**When** they are served
**Then** they are sub-second and decoupled from durable persistence (FR-28)
**And** the rider's live position endpoint returns coordinates **and** ETA from a single Redis read (AD-40)

### Story 4.8: Surge is recomputed from live demand

As a rider,
I want the fare multiplier to reflect how busy the system actually is,
So that the price I am quoted moves with real conditions rather than sitting at a constant.

**Acceptance Criteria:**

**Given** the surge multiplier
**When** the system runs from this phase onward
**Then** it is recomputed periodically from the ratio of outstanding ride requests to available
drivers (FR-19)

**Given** the surge scheduler
**When** it is placed
**Then** it lives in `matching-service`'s `dispatch` package, which sits above both `ride` and `fare`
(AD-9)

**Given** a recomputation
**When** it happens
**Then** the acting actor is recorded as `SYSTEM` (FR-41 groundwork)

**Given** the current multiplier
**When** it is observed
**Then** it is exposed as an operational metric (FR-19, FR-46)

**Given** a fare
**When** it is computed at request time
**Then** it uses the multiplier current at that moment
**And** it is never recomputed later, so a surge change between quote and request moves the price
while a change after request does not (FR-18, FR-1)

**Given** the surge recomputation
**When** its inputs are inspected
**Then** they are exactly two — the count of outstanding ride requests and the count of available
drivers — and it produces one multiplier
**And** rider identity, time of day, and any geographic cell are not inputs, so no demand forecasting,
machine learning, per-rider or time-of-day pricing can be present (Non-goals, Deferred)

**Given** the recomputation interval
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by sleeping (NFR-9)

### Story 4.9: Operational dashboards show the fixed KPIs

As an operator,
I want the system's behaviour visible on a dashboard with its key definitions fixed,
So that I can see what is happening without log-diving, and two people reading the same panel mean the same thing.

**Acceptance Criteria:**

**Given** the stack
**When** it runs
**Then** Prometheus scrapes every service and Grafana renders dashboards
**And** match latency, throughput and error rates are visible without log-diving (FR-46, NFR-5)

**Given** **match latency**
**When** it is defined
**Then** it is the time from request to a driver **accepting** — what the rider actually waits
**And** it is reported alongside time-to-**first-offer**, because the gap between them separates a
matching problem from drivers ignoring offers (FR-46, AD-54)

**Given** **drivers online**
**When** it is defined
**Then** it counts only *matchable* drivers — declared available **and** currently reachable
**And** never everyone whose last declared status was available (FR-46, AD-54)

**Given** the dashboards
**When** they are assembled
**Then** they also show ride throughput, the current surge multiplier, rides awaiting authorization,
undelivered-event backlog age, and error rates (FR-46)

**Given** every queue, pool and backlog named in AD-6, AD-34 and AD-35
**When** it is observed
**Then** each exposes depth and age as gauges, because an unmeasured bound cannot be tuned (AD-54)

**Given** a request crossing services
**When** it is traced
**Then** the correlation id minted at the gateway is propagated over gRPC metadata, carried in the
event envelope, and present in every log line and error response — so a trace does not end where the
request does (AD-54, AD-31)

**Given** each metric
**When** its source is chosen
**Then** durable state is read from the owning table, process-local state from the process, and
events as in-process monotonic counters
**And** no record is ever persisted solely to make it countable (Metrics convention)

> **Scope boundary:** three panels named by FR-46 cannot be populated yet and land with the data that
> feeds them. **Payment success rate and the two money gauges** — capture loss, and the age of the
> oldest capture still retrying — arrive in Epic 5, as do the second and third refusal reasons.
> **Audit ingest rate** arrives in Epic 6. *Rides awaiting authorization* is built here and reads a
> constant zero until Epic 5, since the immediate-authorise gateway never leaves a ride waiting —
> which is itself the correct reading for this phase.

