## Epic 3: The Ride Loop

A rider can request a ride and watch a driver approach, accept, drive and complete it — and every way
that loop can go wrong (nobody available, driver goes silent, rider cancels, two actors racing)
resolves to exactly one correct outcome.

### Story 3.1: The ride state machine is an explicit transition table

As a rider,
I want my ride to only ever enter a state that legitimately follows the last one,
So that no out-of-order or replayed event can advance it twice or move it somewhere impossible.

**Acceptance Criteria:**

**Given** the ride lifecycle
**When** it is declared
**Then** it is an enum plus an allowed-transitions map in `model`
**And** validity is never scattered across `if` checks at call sites (AD-11)

**Given** the declared machine
**When** its legal transitions are inspected
**Then** they are exactly: `REQUESTED →` {`WAITING_MATCH`, `PAYMENT_FAILED`, `CANCELLED`};
`WAITING_MATCH →` {`OFFERED`, `NO_DRIVER`, `CANCELLED`}; `OFFERED →` {`MATCHED`, `WAITING_MATCH`,
`CANCELLED`}; `MATCHED →` {`IN_PROGRESS`, `WAITING_MATCH`, `CANCELLED`}; `IN_PROGRESS →`
{`COMPLETED`}
**And** `COMPLETED`, `CANCELLED`, `NO_DRIVER` and `PAYMENT_FAILED` have no outgoing transitions (AD-13)

**Given** any transition not in that table
**When** it is attempted
**Then** a domain exception is raised
**And** it is never a silent no-op (AD-11)

**Given** the machine
**When** `REQUESTED` is examined
**Then** no state transitions back into it, so a ride holding funds can never fall back into
re-authorising them (AD-13, FR-15)

**Given** a first migration
**When** the `rides` table is created
**Then** it holds a UUID id, a plain `rider_id` UUID with no foreign key, pickup and dropoff
coordinates, fare, status, and the timestamps marking each transition
**And** a monotonic bigint identity exists for internal ordering and is never exposed (Identifiers convention)

### Story 3.2: Rider requests a ride and the fare is locked

As a rider,
I want to request a ride and receive its identifier immediately,
So that I am not left waiting on a payment provider before I even know my ride exists.

**Acceptance Criteria:**

**Given** a rider with pickup and dropoff coordinates
**When** they request a ride
**Then** the ride is persisted `REQUESTED` and its identifier is returned immediately
**And** the response does not wait on authorization (FR-2, AD-41)

**Given** the request
**When** the fare is computed
**Then** it is locked at request time from the current fare rules
**And** it is never recomputed at trip end (FR-2, FR-18)

**Given** the `REQUESTED` state
**When** its meaning is asserted
**Then** it means *awaiting authorization* and nothing else (AD-13)

**Given** the `PaymentGateway` Strategy
**When** no `payment-service` exists in this phase
**Then** the immediate-authorise implementation signals authorised
**And** the ride moves to `WAITING_MATCH` (AD-43)

**Given** that implementation
**When** it is wired
**Then** it is confined to phases before `payment-service` exists, and to test runs that exercise the
ride path without payments
**And** it is never selectable as a runtime degradation for a payment outage (AD-43)

> **Scope boundary — the payment-method token is deliberately not here.** FR-2 defines it as part of
> the ride-request contract, but nothing in this phase consumes it: the immediate-authorise gateway
> calls no provider, and AD-42's rule that the token "passes through to `payment-service`" has no
> `payment-service` to pass through to. It joins the request in Epic 5 alongside the component that
> reads it, together with AD-42's masked value type and the leak test that keeps NFR-10 honest.
> Adding it then is additive and safe (AD-33); it is never persisted (AD-42), so no migration is
> deferred with it. Deliberately-declining tokens arrive in that same story as test fixtures, since
> there is nothing to decline before then; the Simulator exercises them under load in Epic 7.

### Story 3.3: A rider may hold only one ride at a time

As a rider,
I want a second request refused while I already have a ride in flight,
So that I cannot accidentally hold two rides — and later two holds — at once.

**Acceptance Criteria:**

**Given** a rider holding a ride in `REQUESTED`, `WAITING_MATCH`, `OFFERED`, `MATCHED` or `IN_PROGRESS`
**When** they request another ride
**Then** it is refused as `ALREADY_EXISTS` → 409 (FR-3, AD-38)

**Given** the rule
**When** it is enforced
**Then** it is a partial unique index on `rides (rider_id)` filtered to those five states
**And** it is never a check-then-insert (AD-14)

**Given** two concurrent requests from the same rider
**When** both are submitted
**Then** exactly one succeeds
**And** the other fails on the constraint and becomes a 409 (AD-14, NFR-1)

**Given** a rider whose rides are all terminal
**When** they request a ride
**Then** it is admitted (FR-3)

**Given** the index
**When** the table grows
**Then** it holds only active rides and stays small regardless of total ride volume (AD-14)

**Given** a refusal
**When** it is counted
**Then** it increments a refused-ride-request counter in `matching-service` carrying the `active_ride`
reason label
**And** the label is a closed enum with a single registration point, never free text (AD-54)

**Given** that counter
**When** its storage is inspected
**Then** it is an ordinary in-process monotonic counter exposed on the service's Prometheus endpoint
**And** no table row and no outbox row is written to make a refusal countable, because a refusal
persisted nothing to derive from and this is a Tier-1 request path (Metrics convention)

**Given** a request rejected for any other reason
**When** the counter is examined
**Then** only AD-38's 409 admission refusals have incremented it
**And** a malformed 400 and a shed 503 have not, each keeping its own signal (AD-54, AD-35)

**Given** a client re-attempting a refused request on the 2 s poll cadence
**When** each call reaches the admission check
**Then** the counter increments once per call rather than once per affected rider (AD-54)

**Given** an integration test asserting the counter
**When** it runs after other test classes against the shared stack
**Then** it asserts the **delta across the action under test**, never an absolute value
**And** no metrics-reset hook exists on the service, since truncate-and-reseed does not reset
in-process meters and a reset would break the monotonicity `rate()` depends on (Metrics convention, AD-56)

> **Scope boundary:** AD-54's other two reason labels — unsettled payment and capture-failed cooldown
> — are added in Epic 5 with the projection that can see them. The closed enum and its single
> registration point are established here so they cannot be bolted on as free strings later.

### Story 3.4: Rider reads their rides

As a rider,
I want to find my in-flight ride, read any ride by identifier, and page my history,
So that I always know where my ride is and what happened on the ones before it.

**Acceptance Criteria:**

**Given** a rider with a ride in flight
**When** they look up their active ride by identity alone
**Then** at most one ride is returned (FR-4, FR-3)

**Given** a rider with no ride in flight
**When** they look up their active ride
**Then** nothing is returned
**And** it is not an error (FR-4)

**Given** a ride identifier
**When** the owning rider reads it
**Then** current state and details are returned (FR-5)

**Given** a `COMPLETED` ride
**When** the rider reads it
**Then** the response says whether the driver completed it or the system did
**And** an auto-completed trip is therefore distinguishable **when serving a read**, not only in the
audit trail after the fact (FR-14, AD-18)

**Given** ride detail
**When** it is requested repeatedly
**Then** it is served from an endpoint separate from live driver position
**And** it carries an `ETag` and answers 304 to a matching `If-None-Match` (AD-40)

**Given** a rider identity that does not own the ride
**When** they read it
**Then** the response is 404, never 403
**And** it does not reveal whether the ride exists (AD-38, AD-39)

**Given** ride history
**When** it is requested
**Then** results are most-recent-first and bounded by a result-size limit (FR-8)

**Given** any rider-facing response
**When** it is rendered
**Then** it carries the driver's display name and never a driver identifier (AD-39)

### Story 3.5: Matching offers the ride to the nearest matchable driver

As a rider,
I want the system to find me the nearest driver who is genuinely able to take my ride,
So that I am matched quickly and not held by someone who will never answer.

**Acceptance Criteria:**

**Given** rides awaiting a driver
**When** the matching worker claims work
**Then** it selects `status = 'WAITING_MATCH'` alone (AD-19, FR-10)

**Given** a ride whose funds are not yet held
**When** matching runs
**Then** the ride is *invisible* to dispatch rather than rejected by a guard (AD-19, FR-9)

**Given** matchable drivers
**When** a ride is matched
**Then** the nearest driver within a 5 km radius is chosen
**And** matchability requires `AVAILABLE` **and** a fresh heartbeat (FR-10, FR-29)

**Given** the matching worker
**When** it runs
**Then** it is a small fixed pool claiming batches with `FOR UPDATE SKIP LOCKED`, processing and
re-claiming, waiting only on an empty claim (AD-20)
**And** pool size derives from arrival rate × match time, never from backlog depth (AD-20, AD-47)

**Given** an offer
**When** it is made
**Then** the ride moves `WAITING_MATCH → OFFERED`
**And** the driver's dispatch status is set `BUSY`
**And** the driver's display identity is snapshotted onto the ride (AD-16, AD-24)

**Given** the whole system
**When** offers are made
**Then** they are made in exactly one place — this worker (AD-20)

**Given** a transaction touching both `rides` and `drivers`
**When** it runs
**Then** it takes `rides` first, then `drivers` (AD-15)

**Given** many concurrent match attempts against the same driver
**When** they run
**Then** exactly one succeeds and the rest are rejected
**And** no driver is ever double-booked (FR-17, NFR-1)

**Given** a match attempt that loses its race
**When** it is handled
**Then** it maps to `ABORTED`, is retried internally against a re-search
**And** it is never surfaced to the caller (AD-38, AD-4)

### Story 3.6: Driver accepts the offer

As a driver,
I want to accept the ride currently offered to me,
So that I commit to a passenger and begin the trip.

**Acceptance Criteria:**

**Given** a driver holding a live offer
**When** they accept
**Then** the ride moves `OFFERED → MATCHED` (FR-22, AD-13)

**Given** the `MATCHED` state
**When** it is asserted
**Then** it means the driver has *accepted* and is en route
**And** it is distinct from `OFFERED`, which means a driver is still deciding (AD-13)

**Given** a driver acting on a ride not offered to them
**When** they accept
**Then** it is rejected (FR-22)

**Given** a driver with no live offer at all
**When** they accept anything
**Then** it is rejected (FR-22)

**Given** the accept
**When** it is written
**Then** it is a guarded conditional update carrying expected prior state `OFFERED` and the acting
driver identity
**And** zero rows affected is a rejection, never a silent success (AD-15)

**Given** a rejected accept
**When** it surfaces
**Then** wrong state maps to `FAILED_PRECONDITION` → 409
**And** identity mismatch or absent maps to `NOT_FOUND` → 404 (AD-38)

**Given** two drivers accepting the same offered ride concurrently
**When** both commit
**Then** exactly one succeeds and the other is rejected
**And** the ride carries exactly one assigned driver (FR-17, NFR-1)

### Story 3.7: Offer is declined or expires

As a rider,
I want a driver who declines or ignores my ride to release it immediately to the next-nearest driver,
So that I am not left waiting on someone who was never going to come.

**Acceptance Criteria:**

**Given** a driver with a pending offer
**When** they decline
**Then** the ride returns to `WAITING_MATCH` immediately
**And** the driver returns to `AVAILABLE` (FR-23)

**Given** an offer unanswered for the offer timeout of 10 s
**When** the timeout is reached
**Then** the ride returns to `WAITING_MATCH`
**And** the driver is released (FR-11, AD-46)

**Given** a decline or a timeout
**When** it occurs
**Then** the driver's id is appended to the ride's `declined_by` array (AD-17)

**Given** a ride carrying `declined_by`
**When** matching next runs
**Then** those drivers are filtered out and never offered that ride again (AD-17, FR-11)

**Given** the decline and timeout paths
**When** they run
**Then** they **release only** — neither makes an offer, because offers are made in exactly one
place (AD-20)

**Given** the design
**When** offer history is considered
**Then** no separate offers table is created — the durable record is the audit trail (AD-17)

**Given** the offer timeout
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by sleeping (NFR-9)

### Story 3.8: Driver goes silent before pickup

As a rider,
I want a ride whose driver vanished before reaching me to be handed to another driver,
So that my ride is salvaged rather than killed by someone else's lost signal.

**Acceptance Criteria:**

**Given** a `MATCHED` ride whose driver's last heartbeat is older than the `MATCHED` staleness window of 90 s
**When** the recovery sweep runs
**Then** the ride returns to `WAITING_MATCH`
**And** the silent driver is released to `AVAILABLE` (FR-13, AD-46)

**Given** that recovery
**When** the ride transitions
**Then** it returns to `WAITING_MATCH` and never to `REQUESTED`, because its hold is already in place
and must not be authorised a second time (FR-13, AD-13)

**Given** a driver with no recorded position at all
**When** the sweep runs
**Then** absence is not treated as evidence of staleness
**And** the sweep does not act on them (AD-26)

**Given** the recovered ride
**When** it re-enters matching
**Then** it is offered to the next-nearest matchable driver (FR-13)

**Given** the staleness window
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by sleeping (NFR-9)

### Story 3.9: Driver runs the trip

As a driver,
I want to explicitly start the trip once my passenger is aboard and complete it at the end,
So that the ride reflects what is actually happening on the road.

**Acceptance Criteria:**

**Given** a `MATCHED` ride
**When** its own assigned driver starts the trip
**Then** it moves `MATCHED → IN_PROGRESS` (FR-24)

**Given** an `IN_PROGRESS` ride
**When** its own assigned driver completes it
**Then** it moves `IN_PROGRESS → COMPLETED`
**And** the driver returns to `AVAILABLE` (FR-24)

**Given** a driver acting on a ride that is not their own
**When** they start or complete it
**Then** it is rejected as 404 (FR-24, AD-38)

**Given** a ride not in the immediately preceding state
**When** start or complete is attempted
**Then** it is rejected as 409 (FR-24, AD-38)

**Given** a driver-completed ride
**When** completion is recorded
**Then** `completed_by = DRIVER` is written as a column
**And** it is never expressed as a distinct ride state (AD-18)

### Story 3.10: Abandoned trip is auto-completed

As a rider,
I want a trip whose driver went silent mid-route to reach a definite end,
So that my ride does not hang unresolved forever.

**Acceptance Criteria:**

**Given** an `IN_PROGRESS` ride whose driver's last heartbeat is older than the `IN_PROGRESS`
staleness window of 10 minutes
**When** the sweep runs
**Then** the ride is completed
**And** the driver is released (FR-14, AD-46)

**Given** that completion
**When** it is recorded
**Then** the ride ends in `COMPLETED` with `completed_by = SYSTEM`
**And** it is not a separate state (AD-18)

**Given** the transition
**When** its actor is recorded
**Then** it is `SYSTEM` (FR-14, FR-41 groundwork)

**Given** a "completed rides" query
**When** it runs
**Then** it returns both driver-completed and system-completed rides by matching one status value (AD-18)

**Given** a driver holding a ride who has gone unreachable
**When** the session-expiry sweep runs
**Then** it does not expire them — the ride is resolved by this story or Story 3.8 first
**And** the session-expiry window is strictly longer than both staleness windows, so no driver is
ever expired mid-ride (AD-22, AD-46, FR-30)

**Given** the staleness window
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by sleeping (NFR-9)

> **Scope boundary:** FR-14 also requires the locked fare to be captured through the normal payment
> path. With no `payment-service` yet, this story delivers the ride-side completion and the `SYSTEM`
> provenance; the capture it triggers arrives in Epic 5, which routes system-completed rides through
> exactly the same path as driver-completed ones.

### Story 3.11: Driver goes offline mid-engagement

As a driver,
I want going offline to be refused once I have accepted a ride but permitted while I am only deciding,
So that I can stop working without stranding a passenger who is already expecting me.

**Acceptance Criteria:**

**Given** a driver with an outstanding `OFFERED` ride
**When** they go offline
**Then** the offer is released back to `WAITING_MATCH`
**And** the driver goes `OFFLINE` (FR-20, AD-16)

**Given** a driver on a `MATCHED` or `IN_PROGRESS` ride
**When** they go offline
**Then** it is refused until they complete the ride or are released (FR-20, AD-16)

**Given** the guard
**When** it is evaluated
**Then** it reads the **ride's** state, not the dispatch status
**And** this is because `BUSY` spans the whole engagement from offer through completion, making
status alone unable to distinguish the two cases (AD-16)

**Given** a refusal
**When** it surfaces
**Then** wrong state maps to `FAILED_PRECONDITION` → 409 (AD-38)

### Story 3.12: Driver session shows pending offer and active ride

As a driver,
I want my session read to show the offer awaiting my answer and the ride I am on,
So that one call tells me everything I need to act on.

**Acceptance Criteria:**

**Given** a driver with a pending offer
**When** they read their session
**Then** the response carries that offer with pickup, dropoff, fare and distance to pickup (FR-21)

**Given** a driver on an active ride
**When** they read their session
**Then** the response carries that ride (FR-21)

**Given** a driver with neither
**When** they read their session
**Then** declared status and reachability are returned with no offer and no ride (FR-21)

**Given** the response
**When** it is assembled
**Then** it is delivered by one call, not several (FR-21)

**Given** the 2 s driver poll cadence
**When** it is compared with the 10 s offer timeout
**Then** poll is comfortably shorter, so an offer is never missed between polls (AD-46)

### Story 3.13: Ride gives up as NO_DRIVER

As a rider,
I want a definitive answer when no driver can be found,
So that I am not left waiting indefinitely on a ride that is never coming.

**Acceptance Criteria:**

**Given** a `WAITING_MATCH` ride whose accumulated seeking budget of 60 s is exhausted
**When** the sweep runs
**Then** the ride moves to terminal `NO_DRIVER`
**And** retrying stops (FR-12, AD-46)

**Given** the seeking budget
**When** it is accumulated
**Then** it counts time in `WAITING_MATCH` only
**And** never time spent `OFFERED` or `MATCHED` (AD-46)

**Given** a ride salvaged from a silent driver by Story 3.8
**When** its budget is evaluated
**Then** the time it spent `MATCHED` did not consume the budget
**And** the salvage path is therefore reachable rather than killed on the next sweep (AD-46)

**Given** the bounded window
**When** its justification is examined
**Then** it bounds an outcome the system can determine — "we looked and found nobody"
**And** it is never applied to a wait on external truth the system will eventually receive (AD-45)

**Given** the seeking budget
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by sleeping (NFR-9)

> **Scope boundary:** FR-12 also requires the authorization hold to be released on `NO_DRIVER`. The
> ride-side terminal state lands here; the void it stamps arrives in Epic 5 (FR-36, AD-44).

### Story 3.14: Rider cancels before the trip starts

As a rider,
I want to cancel any ride that has not yet started moving,
So that I have a way out right up until the moment the trip begins.

**Acceptance Criteria:**

**Given** a ride in `REQUESTED`, `WAITING_MATCH`, `OFFERED` or `MATCHED`
**When** the owning rider cancels
**Then** it moves to terminal `CANCELLED` (FR-16, AD-13)

**Given** a ride with an assigned driver
**When** it is cancelled
**Then** the driver is released back to `AVAILABLE` (FR-16)

**Given** a ride with an offer still outstanding
**When** it is cancelled
**Then** the offer is withdrawn (FR-16)

**Given** a ride already `IN_PROGRESS` or terminal
**When** cancel is attempted
**Then** it is refused as 409 (FR-16, AD-38)

**Given** a mismatched rider identity
**When** cancel is attempted
**Then** it is rejected as 404
**And** the response does not reveal whether the ride exists (FR-16, AD-38, AD-39)

**Given** the cancellation
**When** it is written
**Then** it is a guarded conditional update carrying the expected prior state and the acting rider
identity (AD-15)

**Given** a rider cancelling at the same instant the assigned driver accepts
**When** both commit
**Then** exactly one wins — whichever commits first
**And** the loser is rejected rather than both applying, because both are guarded transitions on one
ride (FR-17, AD-17)

> **Scope boundary:** FR-16 also requires any hold to be voided, including one that lands *after* the
> cancellation. That race is Epic 5's, resolved by AD-44's stamped `void_requested_at` rather than by
> anything this story can do without a payment to void.

### Story 3.15: Driver reads their ride history

As a driver,
I want to review the rides I have completed,
So that I can see the work I have done.

**Acceptance Criteria:**

**Given** a driver
**When** they query their history
**Then** their completed rides are returned most-recent-first
**And** the result is bounded by a result-size limit (FR-25)

**Given** rides completed by the system rather than by the driver
**When** history is read
**Then** they appear alongside driver-completed ones, matched by one status value (AD-18)

**Given** a driver identity
**When** history is requested
**Then** only that driver's own rides are returned (FR-25)

### Story 3.16: Rider watches the driver approach

As a rider,
I want to see who is coming, where they are, and when they will arrive,
So that waiting for my ride is something I can watch rather than guess at.

**Acceptance Criteria:**

**Given** a `MATCHED` ride
**When** the rider reads the live position
**Then** they receive the assigned driver's current position and an ETA to pickup (FR-6)

**Given** repeated reads
**When** the driver's heartbeats land
**Then** the position and the ETA both update (FR-6)

**Given** the live position endpoint
**When** it is served
**Then** it is separate from ride detail
**And** it returns coordinates **and** ETA from a single read of the location index (AD-40)

**Given** the response
**When** it is rendered
**Then** it carries the driver's display name and never their identifier (AD-39)

**Given** a ride not in `MATCHED`
**When** live position is requested
**Then** no position is returned
**And** it is not an error (FR-6)

> **CAP-13 (race-safe concurrency) has no story of its own, by policy.** Its proof is distributed
> across the stories whose behaviour it constrains: concurrent match attempts and the lost-race path
> in Story 3.5, concurrent accepts in Story 3.6, concurrent requests from one rider in Story 3.3, and
> the cancel-versus-accept race in Story 3.14. See *Testing policy* in the Overview.
>
> **Those races are proven by deterministic test scenarios, not by synthetic traffic.** A hand-written
> scenario — two threads, one ride, one latch, the `Clock` advanced deliberately — is *more*
> deterministic than a seeded generator, because it contains no randomness at all to reproduce. The
> Simulator arrives in Epic 7 for the opposite job: surfacing emergent behaviour under mixed load
> that nobody wrote a scenario for. The fixtures each of these stories needs ship inside that story,
> as every other test does.

