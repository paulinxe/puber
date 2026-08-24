## Epic 2: Driver Presence & Location Tracking

A driver can start and end a shift, be located, and see their own working state — and the system can
tell a driver who chose to stop from one who merely lost signal.

> **Seam decision carried by this epic.** The `DriverLocationIndex` Strategy (AD-10) lives in
> `matching-service`, which is the consumer of proximity search and the eventual owner of AD-26's
> Redis structures. `driver-service` owns driver identity and location (AD-3) and forwards each
> heartbeat over gRPC to the index. Epic 4 replaces the forward with a Kafka event and the Postgres
> columns with Redis — the seam does not move, only its implementation. This epic therefore creates
> `matching-service`'s dispatch `drivers` table carrying declared status, a position snapshot and
> `last_seen_at`; **Story 3.5** extends it with `current_ride_id` by expand-only migration, in the
> first story that needs the column.

### Story 2.1: Fixture drivers exist with identities and positions

As an engineer,
I want `driver-service` running with a seeded set of fixture drivers,
So that there are real drivers to place, track and later dispatch, without any registration flow.

**Acceptance Criteria:**

**Given** the Compose stack
**When** it is brought up
**Then** `driver-service` starts with its own private Postgres database (AD-1)
**And** it exposes health and Prometheus metrics exactly as the existing services do (AD-54)
**And** it carries its own Gradle wrapper and the standard package layout (AD-7, AD-52)

**Given** a first start
**When** Flyway runs
**Then** a `drivers` identity table is created holding id, display name and current position
**And** fixture drivers are seeded automatically

**Given** the configured geographic bounds and a seed
**When** fixture drivers are seeded
**Then** every driver's position falls within the configured bounds
**And** the same seed produces the same drivers and positions (AD-25, NFR-9)

**Given** a seeded driver
**When** their identity is read
**Then** the identifier is a UUID and is fixture-known rather than registered (FR-48, Identifiers convention)

**Given** two test classes that both drive the same fixture driver through a state change
**When** the suite runs
**Then** neither observes state left behind by the other
**And** the suite produces identical results regardless of class execution order (AD-56)

### Story 2.2: Driver goes online and offline

As a driver,
I want to declare when I am working and when I have stopped,
So that I receive offers only when I have chosen to be available.

**Acceptance Criteria:**

**Given** a first start after this story
**When** `matching-service`'s Flyway migrations run
**Then** its dispatch `drivers` table is created, holding driver id, declared status
(`OFFLINE | AVAILABLE | BUSY`), the driver's display identity, a position snapshot and `last_seen_at`
**And** it is a separate table in a separate database from `driver-service`'s identity table, with no
foreign key between them (AD-1, AD-3, AD-16)

**Given** a seeded driver who is `OFFLINE`
**When** they go online
**Then** `driver-service` calls `matching-service` over gRPC
**And** the dispatch driver row is set `AVAILABLE`
**And** the driver's display identity travels on that call so no later fetch is needed (AD-24, AD-37)

**Given** an `AVAILABLE` driver
**When** they go offline
**Then** the dispatch driver row is set `OFFLINE`
**And** they receive no further offers

**Given** any status change
**When** it is written
**Then** it is a guarded conditional update carrying the expected prior state and the acting driver identity
**And** zero rows affected is a rejection, never a silent success (AD-15)

**Given** the system
**When** any code path attempts to set a driver online
**Then** only the driver's own action can do so — the system never puts a driver online (FR-20)

**Given** this first driver-facing gRPC contract
**When** it is defined
**Then** it is segregated by *consumer*, not by owner — no single service definition carries both
rider-facing and driver-facing methods
**And** a driver-side change therefore cannot force a rider-side rebuild (AD-57)

**Given** a status transition
**When** it is caused by the system rather than the driver
**Then** the acting actor is recorded as `SYSTEM` on the transition (FR-41 groundwork; the audit
event itself lands in Epic 6)

> **Scope boundary:** with no rides yet, going offline always succeeds. AD-16's guard — which reads
> the *ride's* state, refusing while `MATCHED`/`IN_PROGRESS` and releasing an outstanding `OFFERED`
> — is added in Story 3.11 when those states exist.

### Story 2.3: Driver reports position by heartbeat

As a driver,
I want my position reported continuously while I work,
So that the system knows where I am and can tell that I am still reachable.

**Acceptance Criteria:**

**Given** a working driver
**When** they send a location heartbeat
**Then** `driver-service` persists the current position
**And** forwards position and timestamp to `matching-service`'s `DriverLocationIndex` (FR-26, AD-10)

**Given** a heartbeat
**When** it is produced
**Then** the timestamp is stamped by `driver-service` at produce time
**And** no consumer ever re-stamps it on receipt, so lag cannot mask staleness (AD-23)

**Given** a heartbeat
**When** it is handled
**Then** it does **not** write an `event_outbox` row — location pings are ephemeral telemetry, not
state transitions (AD-28)

**Given** the configured cadence
**When** a driver heartbeats normally
**Then** the interval is 2 seconds, and it is drawn from the shared time-constant set rather than a
local literal (AD-46)

**Given** a driver's position
**When** it is read back
**Then** it reflects the most recent heartbeat received

### Story 2.4: Matchability is derived from status and heartbeat freshness

As a rider,
I want only drivers who are both available and currently reachable to be considered for my ride,
So that my request is never held by a driver who has vanished and will never answer.

**Acceptance Criteria:**

**Given** a driver declared `AVAILABLE` whose last heartbeat is inside the staleness window
**When** matchable drivers are queried
**Then** that driver is returned (FR-29)

**Given** a driver declared `AVAILABLE` whose last heartbeat is older than the staleness window
**When** matchable drivers are queried
**Then** that driver is **not** returned
**And** their declared status remains `AVAILABLE`, unchanged (AD-21, CAP-20)

**Given** a driver who went silent and is later heard again
**When** their next heartbeat lands
**Then** they become matchable again immediately
**And** no status transition is written and no action is required of them (AD-21)

**Given** the system
**When** a driver's signal is lost
**Then** nothing writes to their declared status — status is written only by the driver's own action,
the ride lifecycle, or session expiry (AD-21)

**Given** a driver with **no** recorded position at all
**When** staleness is evaluated
**Then** absence is not treated as evidence of staleness
**And** the driver is simply not matchable, with no sweep acting on them (AD-26)

**Given** the staleness window
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by sleeping (NFR-9)

### Story 2.5: Driver reads their working state in one call

As a driver,
I want to see my whole working state in a single call,
So that when nothing is arriving I can tell whether it is because I am offline or because I am unreachable.

**Acceptance Criteria:**

**Given** a driver
**When** they read their session
**Then** one response carries their declared status and whether the system is currently hearing their
heartbeat (FR-21)

**Given** a driver declared `AVAILABLE` whose heartbeat has gone stale
**When** they read their session
**Then** the response shows `AVAILABLE` **and** not currently reachable
**And** the two facts are distinguishable rather than collapsed into one field (FR-21, AD-21)

**Given** a driver reading a session
**When** the identity does not match a known driver
**Then** the response is 404, never 403 (AD-38)

**Given** the response
**When** it is rendered
**Then** the poll cadence it is designed for is 2 seconds, drawn from the shared time-constant set (AD-46)

> **Scope boundary:** the pending-offer and active-ride fields of FR-21 are added in Story 3.12, when
> offers and rides exist. This story delivers the status and reachability halves in full.

### Story 2.6: Quote ETA reflects the nearest matchable driver

As a rider,
I want my quote to tell me how long a driver will take to reach me,
So that I know both the price and the wait before I commit.

**Acceptance Criteria:**

**Given** at least one matchable driver within the matching radius of the pickup point
**When** a quote is requested
**Then** the response carries fare, distance **and** ETA
**And** the ETA is haversine distance from the nearest matchable driver to pickup, divided by 30 km/h — the same named constant Story 1.3 introduced, **moved** into `shared` rather than copied (FR-1)

**Given** no matchable driver within the radius
**When** a quote is requested
**Then** fare and distance are returned and ETA is omitted
**And** the response is a success, never an error (FR-1)

**Given** a driver who is `AVAILABLE` but unreachable
**When** a quote is requested near them
**Then** they are not considered for the ETA (FR-29)

**Given** a quote
**When** it is served
**Then** no ride is created and no driver is reserved (FR-1)

### Story 2.7: A shift ends after prolonged absence

As a driver,
I want a shift I plainly ended to not still be running days later,
So that I never start receiving offers on a day I did not choose to work.

**Acceptance Criteria:**

**Given** an idle driver declared `AVAILABLE` who has been unreachable for the session-expiry window
**When** the expiry sweep runs
**Then** the driver is set `OFFLINE`
**And** the transition records `SYSTEM` as the acting actor (AD-22, FR-30)

**Given** a driver expired this way
**When** they heartbeat again
**Then** they do **not** become matchable
**And** they must explicitly go online again to receive offers (FR-30)

**Given** an idle driver unreachable for less than the session-expiry window
**When** the sweep runs
**Then** their status is untouched (AD-21)

**Given** the time-constant set
**When** an ordering test runs
**Then** session expiry (1 h) is strictly greater than every staleness window
**And** the ordering `idle < MATCHED < IN_PROGRESS < capture-failed cooldown < session expiry` is
asserted by that test rather than left to review (AD-46)

**Given** the session-expiry window
**When** it is exercised in a test
**Then** it is reached by advancing the `Clock`, never by sleeping (NFR-9)

> **Scope boundary:** with no rides yet, every driver is idle. AD-22's restriction to *idle* drivers —
> a driver holding a ride is resolved by Stories 3.8 and 3.10 first, never expired mid-ride — is
> asserted in Story 3.10 where `IN_PROGRESS` exists to test it against.

