# Puber — Learning Project Plan

> **A ride-hailing backend for learning backend engineering at scale.**  
> Three services, real concurrency, no external APIs you don't control.

**Companion:** [plan.md](../plan.md) (job-search strategy, JD topic map).

---

## Ticket Conventions

| Field | Convention |
|--------|------------|
| **Prefix** | `PB` = **Puber** |
| **Format** | `PB-{week}.{n}` where `week` = 1–24, `n` = ticket within that week |
| **Examples** | `PB-1.1` = Bootstrap, `PB-2.1` = Domain Model, `PB-3.2` = Matching Engine + Simulator |
| **Done** | Merged to branch + acceptance criteria met + tests green in Docker |
| **~4 h/week** | Aim for 1–2 tickets per week on heavy weeks; 1 if large (e.g. first Kafka + integration test) |

---

## Ticket Template

Use this format for every ticket so the new agent (and future you) knows exactly what's required:

```markdown
# PB-X.Y — Title

| Field | Value |
|--------|--------|
| **ID** | PB-X.Y |
| **Phase** | Months Y–Z — [phase name] |
| **Week** | X — [week name] |
| **Source** | [puber.md §section](../puber.md) |
| **Depends on** | [PB-X.Y] |

---

## Goal

One paragraph: what this ticket delivers and why it matters.

---

## Context and constraints

- [Key decisions from puber.md §Decisions]
- [Tooling constraints: Docker-only, Java 25, Gradle Wrapper, etc.]

---

## What to deliver

### 1. [Task name]
| Item | Requirement |
|------|-------------|
| [Item] | [Requirement] |

### 2. [Task name]
...

---

## Acceptance criteria (done = all true)

1. [Criterion]
2. [Criterion]
3. [Tests green in Docker]

---

## Explicitly out of scope for this ticket

- [What's NOT included — prevents scope creep]

---

## Suggested completion note

Brief note for PR / changelog: what shipped, what's next.

---

## Next ticket

- [PB-X.Y+1](pb-x-y+1.md) — [title]
```

---

## Why this project?

The original `order-book` was a good vehicle for learning Java + Spring Boot + SQL depth, but the domain felt too abstract. **Puber** keeps every technical milestone from `plan.md` — Postgres, Kafka, resilience, observability, WebSockets, K8s — but frames them around a **tangible, visual, well-understood domain**: requesting a ride, watching a driver approach, and completing a trip.

**Key principle:** *You control every input.* There are no payment gateways, no Google Maps APIs, no SMS providers. "Users" are Java threads firing real HTTP requests with seeded random data. "Money" is a calculated fare stored in Postgres. "Location" is a `(lat, lng)` pair you generate.

---

## Decisions & Constraints

These choices remove ambiguity so you never freeze on "how do I start?"

| Question | Decision | Rationale |
|----------|----------|-----------|
| **How do drivers/riders exist?** | **No registration, no auth.** Drivers and riders are seeded by Flyway fixtures (see §Fixtures). `riderId` is a UUID passed in the request body. | Auth is a 2–3 week rabbit hole (JWT, passwords, sessions) that teaches nothing on the `plan.md` syllabus. |
| **Riders table?** | **No `riders` table in V1.** `rides.rider_id` is a plain `UUID` column, no foreign key. | Keeps schema minimal. You can add a `riders` table in a later migration if you need it for SQL JOIN practice. |
| **How does the matching engine find driver locations in V1?** | The `drivers` table has `current_lat` / `current_lng` columns. `driver-api` updates these on every `POST /drivers/{id}/location`. The matching engine queries `SELECT id, current_lat, current_lng FROM drivers WHERE status = 'AVAILABLE'` to build its in-memory index. | At 30 drivers × 1 location/2s = 15 writes/sec, Postgres handles it trivially. Keeps the architecture simple while we learn matching logic and state machines. |
| **How does the location path evolve in Month 3+?** | `driver-api` produces Kafka `driver.locations` on every heartbeat. A consumer writes latest location to **Redis** (`SETEX driver:{id}:location 60s {lat,lng}`) for the matching engine to read. Another consumer **batch-inserts** into `driver_locations` (Postgres) for history/audit. | Teaches the production pattern: **fast path** (Kafka → Redis) for reads, **slow path** (Kafka → Postgres) for persistence. Matching engine stops querying Postgres for locations. |
| **How does matching-engine notify driver-api before Kafka?** | **Direct HTTP call.** `matching-engine` calls `POST /internal/drivers/{id}/offer {rideId, pickupLat, ...}` on `driver-api`. This is replaced by Kafka `ride.matched` in Month 3. | Teaches inter-service HTTP client patterns (timeouts, retries) now; evolves to event-driven later. |
| **What if no driver is available?** | Ride stays `REQUESTED`. A scheduled task in `matching-engine` re-queries for available drivers every 5 seconds and attempts match again. After 60 seconds, status becomes `NO_DRIVER` (optional; V1 can just leave it `REQUESTED`). | Simple, testable, no external queue needed in V1. |
| **Fare calculation** | Calculated **at request time** (not at trip end). `fare = (base_fare + distance_km × per_km + estimated_min × per_minute) × surge_multiplier` where `distance_km = haversine(pickup, dropoff)`. Stored in `rides.fare` on `INSERT`. | Matches real ride-hailing UX: rider sees price upfront. Simpler than post-trip calculation. |
| **ETA calculation** | `eta_seconds = haversine(pickup, driver_location) / 8.33` (8.33 m/s = 30 km/h). | Same formula for simplicity. |
| **Geo bounds** | All coordinates are within a **4 km × 4 km demo square** centered on a real city (e.g., Lisbon: lat 38.710–38.746, lng −9.160–−9.124). Bigger grid = more realistic distance spreads between drivers and pickups. Haversine is still used for accuracy. | More room to simulate realistic driver dispersion and longer ETAs without needing global-scale geo indexes. |
| **Ride cancellation** | Only the **rider** can cancel, and only while status is `REQUESTED` or `MATCHED` (before `IN_PROGRESS`). Cancelling a `MATCHED` ride resets the driver to `AVAILABLE`. | Simple rule, easy to test, covers the state-machine edge case. |
| **Driver offer timeout?** | If a driver does **not** accept within **10 seconds**, the offer expires. The driver returns to `AVAILABLE`; the ride returns to `REQUESTED`; the `@Scheduled` retry task finds the **next nearest** driver. | Prevents a single slow driver from blocking a ride forever. The 10s window is testable with `Awaitility` or a mocked clock. |
| **Surge pricing (V1)** | One static `fare_rules` row loaded on startup. `surge_multiplier` is always `1.00` in V1. In Month 3–4 you make it dynamic based on `requested_rides / available_drivers` ratio in the demo geo cell. | Defers complexity while keeping the schema ready. |
| **Simulator location for Month 1–2** | Runs as a **test fixture class** (`@Component` in `matching-engine` test profile) or a **standalone Java main** that runs in the same JVM as tests. In Month 3 it becomes a **separate Docker container** that fires HTTP + Kafka. | Don't over-infrastructure the simulator before you have anything to simulate against. |

---

## Architecture

```text
services/
  rider-api/          # Spring Boot — REST for riders
    • POST /rides              (request a ride)
    • GET  /rides/{id}         (track status & ETA)
    • GET  /rides/history      (past trips)

  driver-api/         # Spring Boot — REST + WebSocket for drivers
    • POST /drivers/{id}/location      (heartbeat)
    • POST /drivers/{id}/availability  (online / offline / busy)
    • POST /internal/drivers/{id}/offer   (from matching-engine in V1)
    • WS   /drivers/{id}/stream        (incoming ride offers)

  matching-engine/    # Spring Boot — the brain
    • Consumes: ride.requested (Month 3+)
    • Produces: ride.matched, ride.completed (Month 3+)
    • V1: HTTP call to driver-api /internal/offer
    • V1: queries Postgres `drivers` table for locations
    • Month 3+: reads latest driver locations from Redis
    • In-memory: active driver pool (geo + availability)
    • Postgres: rides, drivers, driver_locations, fare_rules

  simulator/          # Java app — generates reproducible load
    • V1: test fixture or standalone main
    • Month 3+: Docker container firing HTTP + Kafka

infra/ (Docker Compose local, K8s later)
  Postgres 17   — rides, drivers, driver_locations (history), fare rules
  Kafka (KRaft) — ride.events, driver.events, driver.locations (Month 3+)
  Redis         — latest driver locations, driver online set, surge multiplier (Month 3+)
  Prometheus    — scrapes /actuator/prometheus from all services
```

---

## Service Ownership

| Endpoint | Method | Implements | Called by | V1 / Month 3+ | Notes |
|----------|--------|-----------|-----------|---------------|-------|
| `/rides` | `POST` | `rider-api` | Simulator / Tests | Both | Public: request a ride |
| `/rides/{id}` | `GET` | `rider-api` | Simulator / Tests | Both | Public: track ride + fare |
| `/rides/history` | `GET` | `rider-api` | Simulator / Tests | Both | Public: past trips (riderId query param) |
| `/drivers/{id}/location` | `POST` | `driver-api` | Simulator | Both | Public: heartbeat |
| `/drivers/{id}/availability` | `POST` | `driver-api` | Simulator | Both | Public: online/offline/busy |
| `/internal/drivers/{id}/offer` | `POST` | `driver-api` | `matching-engine` | **V1 only** | Internal: ride offer from matching engine |
| `/internal/rides/{id}/accept` | `POST` | `matching-engine` | `driver-api` | **V1 only** | Internal: driver accepted |
| `/internal/rides/{id}/complete` | `POST` | `matching-engine` | `driver-api` | **V1 only** | Internal: driver completed |
| `/internal/match` | `POST` | `matching-engine` | `rider-api` | **V1 only** | Internal: trigger matching for a ride |
| `/actuator/health` | `GET` | All services | Prometheus / K8s probes | Both | Spring Boot Actuator — replaces custom `GET /health` |
| `/actuator/prometheus` | `GET` | All services | Prometheus | Month 3+ | Metrics scrape endpoint |
| `/drivers/{id}/stream` | `WS` | `driver-api` | Browser / WS client | Month 5+ | WebSocket: real-time ride offers |

**Health strategy:** No custom `GET /health` controller anywhere. All services rely on **Spring Boot Actuator** (`spring-boot-starter-actuator`) for health, info, and Prometheus metrics. This is introduced in Month 1 (basic) and expanded in Month 3 (`/actuator/prometheus`).

---

## Core Flows

### 1. Request a Ride (V1 — No Kafka)

```text
Rider (simulator) → POST /rides {pickupLat, pickupLng, dropoffLat, dropoffLng, riderId}
                      ↓
                  rider-api calculates fare from pickup/dropoff + fare_rules
                      ↓
                  rider-api INSERT INTO rides (status = REQUESTED, fare = calculated)
                      ↓
                  rider-api HTTP POST → matching-engine /internal/match {rideId}
                      ↓
                  matching-engine queries drivers WHERE status = 'AVAILABLE'
                      ↓
                  Finds nearest driver (in-memory geo index)
                      ↓
                  Postgres: UPDATE rides SET driver_id=X, status=MATCHED
                            UPDATE drivers SET status='BUSY'
                      ↓
                  matching-engine HTTP POST → driver-api /internal/drivers/{id}/offer
                      ↓
                  driver-api holds offer in memory (or Redis) for driver polling/WS
```

**Fare is shown upfront:** `GET /rides/{id}` immediately returns `fare` so the rider sees the price before the driver accepts.

**If no driver available:** `matching-engine` returns `202 Accepted` to `rider-api` but ride stays `REQUESTED`. A `@Scheduled` task retries every 5s.

### 2. Driver Accepts (V1)

```text
Driver (simulator) → POST /rides/{id}/accept {driverId}
                      ↓
                  driver-api validates driver owns the offer
                      ↓
                  driver-api HTTP POST → matching-engine /internal/rides/{id}/accept
                      ↓
                  matching-engine: UPDATE rides SET status=IN_PROGRESS
                      ↓
                  rider-api GET /rides/{id} now returns driver + ETA
```

### 3. Driver Offer Timeout (V1)

```text
matching-engine @Scheduled(fixedRate = 1_000) // every 1 second
    ↓
Find all rides with status = MATCHED where matched_at > 10 seconds ago
    ↓
For each expired ride:
    UPDATE rides SET status = REQUESTED, driver_id = NULL, matched_at = NULL
    UPDATE drivers SET status = 'AVAILABLE' WHERE id = expired_driver_id
    matching-engine finds next nearest driver and re-offers
```

**Key detail:** The 10-second timeout is checked by the same `@Scheduled` task that retries unmatched rides. In tests, use `Awaitility.await().atMost(12, SECONDS).until(...)` or a `Clock` test double to avoid real sleeps.

### 4. Driver Completes (V1)

```text
Driver (simulator) → POST /rides/{id}/complete {driverId}
                       ↓
                   driver-api HTTP POST → matching-engine /internal/rides/{id}/complete
                       ↓
                   matching-engine:
                     UPDATE rides SET status=COMPLETED, completed_at=NOW()
                     UPDATE drivers SET status='AVAILABLE'
                       ↓
                   rider-api GET /rides/{id} shows receipt (fare was already known)
```

**Fare was set at request time** — no recalculation on completion.

### 4. Location Streaming (V1)

```text
simulator thread → POST /drivers/{id}/location {lat, lng}
                       ↓
                   driver-api: UPDATE drivers SET current_lat=?, current_lng=?, updated_at=NOW()
                   driver-api: INSERT INTO driver_locations (...)
                       ↓
                   matching-engine reads from `drivers` table on next match query
```

### 5. Location Streaming (Month 3+ — Kafka + Redis)

```text
simulator thread → POST /drivers/{id}/location {lat, lng}
                       ↓
                   driver-api produces Kafka: driver.locations
                       ↓
               ┌───────┴────────────────┐
               ▼                        ▼
        Redis consumer            Postgres consumer
        SETEX driver:{id}         INSERT INTO driver_locations (batch)
        {lat,lng} EX 60s          every 5–10 seconds
               ↓
        matching-engine reads Redis for nearest-driver query
        (no longer queries Postgres for latest location)
```

**Why this split:**
- **Redis** = O(1) read/write, auto-expires dead drivers, no table bloat. The matching engine needs *latest* location only.
- **Postgres** = immutable history for `EXPLAIN` practice, analytics, and audit. Written in batches so it doesn't block the hot path.
- **Kafka** = decouples `driver-api` from both stores. A new consumer (e.g., analytics, fraud detection) can be added without touching the producer.

### 6. Kafka Evolution (Month 3+)

In Month 3, the HTTP calls between services are **replaced by Kafka**:

- `rider-api` produces `ride.requested` instead of calling `matching-engine`
- `matching-engine` consumes `ride.requested`, produces `ride.matched`
- `driver-api` consumes `ride.matched` to push WS offers
- `driver-api` produces `ride.accepted`, `ride.completed`
- `matching-engine` consumes those to update Postgres

The V1 HTTP endpoints become **internal compatibility shims** or are removed.

---

## Data Model (Postgres)

### V1 Schema (Flyway V1)

```sql
-- drivers: seeded by fixtures, updated by location heartbeats
CREATE TABLE drivers (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    status VARCHAR(20) CHECK (status IN ('OFFLINE','AVAILABLE','BUSY')),
    current_lat DECIMAL(10,8),
    current_lng DECIMAL(11,8),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL
);

-- rides: the core entity
CREATE TABLE rides (
    id UUID PRIMARY KEY,
    rider_id UUID NOT NULL,          -- plain UUID, no FK to riders table
    driver_id UUID REFERENCES drivers(id),
    pickup_lat DECIMAL(10,8) NOT NULL,
    pickup_lng DECIMAL(11,8) NOT NULL,
    dropoff_lat DECIMAL(10,8) NOT NULL,
    dropoff_lng DECIMAL(11,8) NOT NULL,
    status VARCHAR(20) CHECK (
        status IN ('REQUESTED','MATCHED','IN_PROGRESS','COMPLETED','CANCELLED')
    ),
    requested_at TIMESTAMPTZ NOT NULL,
    matched_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    fare DECIMAL(10,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- driver_locations: audit trail / time-series
CREATE TABLE driver_locations (
    id BIGSERIAL PRIMARY KEY,
    driver_id UUID REFERENCES drivers(id),
    lat DECIMAL(10,8) NOT NULL,
    lng DECIMAL(11,8) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- fare_rules: static config, one row in V1
CREATE TABLE fare_rules (
    id SERIAL PRIMARY KEY,
    base_fare DECIMAL(10,2) NOT NULL,
    per_km DECIMAL(10,2) NOT NULL,
    per_minute DECIMAL(10,2) NOT NULL,
    surge_multiplier DECIMAL(3,2) DEFAULT 1.00 NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### Fixture Data (Flyway V1 — after tables, seed data)

```sql
-- 10 demo drivers spread across the 4 km × 4 km Lisbon grid
INSERT INTO drivers (id, name, status, current_lat, current_lng, created_at, updated_at) VALUES
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'Driver 1', 'AVAILABLE', 38.712, -9.158, NOW(), NOW()),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12', 'Driver 2', 'AVAILABLE', 38.740, -9.130, NOW(), NOW()),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13', 'Driver 3', 'AVAILABLE', 38.720, -9.140, NOW(), NOW()),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14', 'Driver 4', 'AVAILABLE', 38.735, -9.155, NOW(), NOW()),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a15', 'Driver 5', 'AVAILABLE', 38.715, -9.125, NOW(), NOW()),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a16', 'Driver 6', 'OFFLINE',   38.730, -9.145, NOW(), NOW()),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a17', 'Driver 7', 'AVAILABLE', 38.742, -9.132, NOW(), NOW()),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a18', 'Driver 8', 'AVAILABLE', 38.710, -9.150, NOW(), NOW()),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a19', 'Driver 9', 'BUSY',      38.728, -9.138, NOW(), NOW()),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a1a', 'Driver 10','AVAILABLE', 38.718, -9.160, NOW(), NOW());

-- One fare rule
INSERT INTO fare_rules (base_fare, per_km, per_minute, surge_multiplier, created_at, updated_at)
  VALUES (2.50, 1.20, 0.30, 1.00, NOW(), NOW());
```

### V2 Migration (Example — Week 4)

```sql
-- Add estimated_duration column for ETA caching (expand-only)
ALTER TABLE rides ADD COLUMN estimated_duration_seconds INTEGER NULL;
```

### Indexing Targets (for SQL-7.x EXPLAIN Week)

- `rides(rider_id, requested_at DESC)` — history queries
- `rides(driver_id, status)` — driver's active ride lookup
- `drivers(status)` — find all available drivers (used by matching engine)
- `driver_locations(driver_id, recorded_at DESC)` — latest location history

---

## Fixtures & Seeding

**No registration endpoint exists.** The following are true on a fresh database:

- **10 drivers** exist with known UUIDs (see §Data Model).
- **No riders** exist as rows; `rider_id` in `POST /rides` is any UUID you pass (e.g., `randomUUID()` in the simulator).
- **The simulator** uses the **known driver UUIDs** from fixtures. It does not create drivers dynamically.
- **Tests** can reference drivers by hardcoded UUIDs because the fixtures are deterministic.

**Why this works:** The matching engine doesn't care *who* the rider is; it only cares about `pickup`, `dropoff`, and `rider_id` for the receipt. The simulator provides `riderId = UUID.randomUUID()` for each request.

---

## Matching Engine

### Algorithm

```java
@Service
public class MatchingService {

    public Optional<DriverMatch> findNearestDriver(Location pickup) {
        var available = driverRepository.findAvailable(); // SELECT * FROM drivers WHERE status='AVAILABLE'
        return available.stream()
            .map(d -> new DriverMatch(d, haversine(pickup, d.location())))
            .filter(m -> m.distanceKm() <= 5.0) // max 5km radius
            .min(Comparator.comparingDouble(DriverMatch::distanceKm));
    }

    public BigDecimal calculateFare(Location pickup, Location dropoff) {
        double distanceKm = haversine(pickup, dropoff);
        double estimatedMin = distanceKm / 0.5; // 30 km/h = 0.5 km/min
        var rule = fareRuleRepository.getCurrent();
        return rule.baseFare()
            .add(rule.perKm().multiply(BigDecimal.valueOf(distanceKm)))
            .add(rule.perMinute().multiply(BigDecimal.valueOf(estimatedMin)))
            .multiply(rule.surgeMultiplier());
    }

    public long calculateEtaSeconds(Location pickup, Location driverLocation) {
        double distanceKm = haversine(pickup, driverLocation);
        return Math.round((distanceKm * 1000) / 8.33); // 8.33 m/s = 30 km/h
    }
}
```

### Scheduled Retry & Offer Timeout

One `@Scheduled` task handles both:

```java
@Scheduled(fixedRate = 5_000) // every 5 seconds
public void processPendingRides() {
    // 1. Retry rides that never got a driver
    var requested = rideRepository.findByStatus(Status.REQUESTED);
    for (var ride : requested) {
        findNearestDriver(ride.pickup())
            .ifPresent(driver -> assignDriver(ride, driver));
    }

    // 2. Expire offers where driver didn't accept within 10s
    var expired = rideRepository.findExpiredMatches(Duration.ofSeconds(10));
    for (var ride : expired) {
        releaseDriver(ride.driverId());          // driver -> AVAILABLE
        resetRideToRequested(ride);              // ride -> REQUESTED, driver_id = NULL
        findNearestDriver(ride.pickup())         // try next driver
            .ifPresent(driver -> assignDriver(ride, driver));
    }
}
```

**In tests:**
- **No driver available:** Create a ride when all drivers are `OFFLINE`, assert status stays `REQUESTED`, flip one driver to `AVAILABLE`, wait for the scheduled task, assert `MATCHED`.
- **Offer timeout:** Create a ride, assert it gets `MATCHED` to driver D-1, do NOT accept, wait 12s, assert ride is `REQUESTED` again and D-1 is `AVAILABLE`, assert it gets `MATCHED` to D-2.

---

## Simulator (V1 — Test Fixture)

In Months 1–2, the simulator is a **JUnit test fixture** or a **Spring test component**, not a separate service.

```java
@TestComponent
@Profile("simulation")
public class PuberSimulator {
    private final WebClient riderClient = WebClient.create("http://localhost:8080");
    private final WebClient driverClient = WebClient.create("http://localhost:8081");
    private final Random rng = new Random(12345);

    // Known from Flyway fixtures
    private final List<UUID> driverIds = List.of(
        UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"),
        // ... all 10
    );

    public void runScenario(int rideCount) {
        var pool = Executors.newFixedThreadPool(10);

        // Start all drivers as AVAILABLE and stream locations
        for (UUID driverId : driverIds) {
            pool.submit(() -> {
                setAvailability(driverId, "AVAILABLE");
                while (!Thread.interrupted()) {
                    updateLocation(driverId, randomLocationInGrid());
                    sleep(2_000);
                }
            });
        }

        // Request rides concurrently
        for (int i = 0; i < rideCount; i++) {
            pool.submit(() -> {
                var rideId = requestRide(randomLocationInGrid(), randomLocationInGrid());
                sleep(rng.nextInt(3_000));
                if (rng.nextDouble() < 0.15) cancelRide(rideId);
            });
        }

        // Drivers poll for offers and accept
        for (UUID driverId : driverIds) {
            pool.submit(() -> {
                while (!Thread.interrupted()) {
                    var offer = pollForOffer(driverId);
                    if (offer.isPresent()) {
                        acceptRide(driverId, offer.get());
                        sleep(rng.nextInt(5_000) + 2_000); // simulate trip duration
                        completeRide(driverId, offer.get());
                    }
                    sleep(500);
                }
            });
        }
    }

    private Location randomLocationInGrid() {
        // Lisbon demo grid: lat 38.710-38.746, lng -9.160 to -9.124
        double lat = 38.710 + rng.nextDouble() * 0.036;
        double lng = -9.160 + rng.nextDouble() * 0.036;
        return new Location(lat, lng);
    }
}
```

**Key point:** The simulator uses **known fixture driver IDs**, not dynamically created ones. It calls real HTTP endpoints against the running services. The `Random` is **seeded** so every test run produces the same coordinates and the same matching outcomes.

---

## Schedule — 6 Months, ~4h/week

Assumes **no host JDK** — Docker + Gradle Wrapper + Eclipse Temurin images from day 0.

### Months 1–2 — Bootstrap + Domain + Matching (Weeks 1–8)

**Outcome:** `matching-engine` with domain model, in-memory matching, Postgres persistence, and seeded fixtures. `rider-api` and `driver-api` as HTTP facades. Simulator test fixture proves matching works end-to-end.

| Week | Session ~2h | Session ~2h |
|------|-------------|-------------|
| **1** | Docker + Gradle Wrapper + Spring Boot skeleton for `matching-engine`; `spring-boot-starter-actuator` + `GET /actuator/health`; root `docker-compose.yml` with Postgres | Multi-stage `Dockerfile` (Eclipse Temurin JDK → JRE); non-root user; `./gradlew test` runs in container |
| **2** | Domain model: `Ride`, `Driver`, `Location`, status enums, `FareRule`. Fixture seeding in Flyway V1 | `POST /rides` (creates `REQUESTED`), `POST /drivers/{id}/location` (updates `drivers` table), `GET /rides/{id}` |
| **3** | In-memory matching engine: nearest available driver query, fare calculation, ETA. `@Scheduled` retry for unmatched rides | Integration test: simulator fixture requests ride → assert `rides.status = MATCHED` and `drivers.status = BUSY` |
| **4** | Flyway V2: additive change (e.g. `rides.estimated_duration_seconds` nullable); practice expand-only | Clean DB from scratch; V1 + V2 apply in order; verify fixtures re-seed |
| **5** | Spring Boot skeleton for `rider-api` and `driver-api`; HTTP inter-service calls from `matching-engine` to `driver-api` /internal/offer | Push to GitHub; root README + per-service README + `docs/architecture.md` stub |
| **6** | SQL theory: ACID + isolation notes in `docs/sql/`; which isolation level for `rides` + `drivers` concurrent updates and why | One external article/video; 5-bullet summary |
| **7** | Seed simulator data; EXPLAIN on 2–3 queries: available drivers, ride history for rider, latest driver location | Tune one query with an index; before/after latency in `docs/sql/` |
| **8** | N+1: `GET /rides/history` returning rides + driver details (bug) → JOIN fix; query count diff | Matching logic unit tests: closest driver wins, no driver available stays REQUESTED, cancellation resets driver; tag `v0.1` *(optional)* |

**Milestone:** "`matching-engine` matches rides to seeded drivers; `rider-api` and `driver-api` handle HTTP; simulator fixture proves it in tests; no Kafka yet."

### Months 3–4 — Kafka + Resilience + Observability (Weeks 9–16)

**Outcome:** Kafka replaces HTTP inter-service calls. Resilience4j on producers and consumers. Actuator + Micrometer + Prometheus + Grafana.

| Week | Session ~2h | Session ~2h |
|------|-------------|-------------|
| **9** | Kafka in Compose (KRaft); define JSON schemas in `docs/` for `ride.requested`, `ride.matched`, `ride.accepted` | `matching-engine` consumer: `ride.requested` → match → produce `ride.matched` |
| **10** | `rider-api` produces `ride.requested` instead of HTTP call to matching-engine; `driver-api` consumes `ride.matched` | Idempotency: `clientRideId` on `POST /rides`; dedup in matching-engine (DB unique constraint) |
| **11** | Resilience4j: timeout + retry with jitter on Kafka producer; document why jitter | Circuit breaker on a simulated "fare enrichment service" (dummy HTTP client); log state transitions |
| **12** | Spring Boot Actuator + Micrometer; expose `/actuator/prometheus`; custom metric: `rides.matched.total` | Prometheus in Compose scrapes all services; verify targets are up |
| **13** | Grafana dashboard: rides requested/min, average match latency, driver online count | Kafka vs SQS one-pager; update `docs/architecture.md` with full command/event diagram |
| **14** | Extract simulator to standalone Docker container; publishes to Kafka directly | Buffer / mock system-design interview: "Design Uber backend" using your own architecture |
| **15** | Redis: latest driver locations (`SETEX` with TTL); matching engine reads from Redis instead of Postgres. Fallback to Postgres if Redis miss. | Surge pricing: simple multiplier based on `requested / available` ratio in the demo geo cell |
| **16** | Read K8s: Pods, Deployments, Services, probes; local `kind` or `k3d` install | Deploy nginx trivial pod; `kubectl get nodes` working |

**Milestone:** "Kafka is the nexus: commands in, events out; matching engine is the only ride mutator; Grafana shows live metrics; simulator runs as separate container."

### Months 5–6 — WebSockets + K8s + Cloud Deploy (Weeks 17–24)

**Outcome:** Driver receives ride offers via WebSocket. Local K8s manifests. `puber` running on a hosted platform with HTTPS and managed Postgres.

| Week | Session ~2h | Session ~2h |
|------|-------------|-------------|
| **17** | WebSocket server in `driver-api`: `/drivers/{id}/stream`; push `ride.matched` events from Kafka consumer | Test: two WS clients; one rider requests, driver client receives offer within 1s |
| **18** | WS reconnection: missed events buffer (Redis) for 30s | Load `puber` images into local K8s cluster; `curl /actuator/health` via Service |
| **19** | Readiness probe: DB + Kafka reachable; liveness probe: simple ping | Pick cloud host + managed Postgres; deploy `rider-api` + `driver-api` + `matching-engine` |
| **20** | Cloud: secrets via provider (not in git); HTTPS; `/actuator/health` public | Cloud: logs, restart policy, basic alerting if provider supports it |
| **21** | K8s: `Deployment` + `Service` for all three services; `ConfigMap` for env vars | K8s: HPA concept on matching-engine based on CPU |
| **22** | Simulator runs in cloud too (or locally against cloud); load test and watch Grafana | N+1 revisit: does cloud DB suffer the same query shapes? Tune if needed |
| **23** | E2E smoke: simulator requests ride → WS driver accepts → rider sees COMPLETED | Document full deployment runbook in README |
| **24** | Final `docs/architecture.md`: command/event flow + WS + K8s + cloud diagram | CV bullets: Kafka, ride matching, real-time WS, Postgres tuning, cloud deploy |

**Milestone:** "Puber in prod or strong local+cloud story; drivers get ride offers over WebSocket; metrics visible in Grafana."

---

## Hosting Options (Pick at Weeks 19–20)

No decision needed until then.

### App / Container Hosting

- [Fly.io](https://fly.io)
- [Render](https://render.com)
- [Railway](https://railway.app)
- [Google Cloud Run](https://cloud.google.com/run)
- [AWS App Runner](https://aws.amazon.com/apprunner/)
- [DigitalOcean App Platform](https://www.digitalocean.com/products/app-platform)

### Managed PostgreSQL

- [Neon](https://neon.tech)
- [Supabase](https://supabase.com)
- Provider-native: AWS RDS, Google Cloud SQL, DigitalOcean Managed Databases

Pick **one** app host and **one** Postgres. Stay on that pair so you finish instead of migrating.

---

## Explicitly Out of Scope

| Not Building | Why |
|--------------|-----|
| **Driver/rider registration or authentication** | Auth (JWT, passwords, sessions) is a 2–3 week rabbit hole that teaches nothing on the `plan.md` syllabus. Fixtures provide all needed identities. |
| **Real payment processing** | Fare is calculated and stored; never charged to a real card |
| **Real maps / routing API** | Distance = Haversine; ETA = distance / 30 km/h |
| **Real mobile apps** | Clients are `curl`, browser, Java tests, or the simulator |
| **Multi-city / geo-sharding** | Single 4 km² demo area is enough |
| **Surge pricing algorithm** | Simple multiplier based on ratio; no ML or demand forecasting |
| **Real-time navigation** | Driver location is simulated drift; no turn-by-turn |
| **Rider profiles / ratings** | No `riders` table in V1; ratings deferred |
| **Driver onboarding / document verification** | All drivers are fixtures from day 0 |
| **Dispatch algorithm beyond nearest-driver** | Nearest-driver-first is enough for V1–V2 |
| **Multi-vehicle types** | One vehicle type; one fare rule |

---

## Testability Matrix

| Concern | How You Test It |
|---------|-----------------|
| **Concurrency** | 10-thread simulator + JUnit `@RepeatedTest(10)` with `@Execution(CONCURRENT)` |
| **Race conditions** | Two threads request rides when only one driver is available → assert only one `MATCHED`, the other stays `REQUESTED` |
| **No-driver-available** | Set all drivers `OFFLINE`, request ride, assert `REQUESTED`; flip one to `AVAILABLE`, wait for `@Scheduled` retry, assert `MATCHED` |
| **Fare calculation** | `POST /rides` with known pickup/dropoff → assert response contains `fare` = expected `(base + km*rate + min*rate) * surge`; assert `rides.fare` is NOT NULL in DB |
| **State machine** | Try to cancel `IN_PROGRESS` ride → assert rejection; complete `MATCHED` ride → assert `COMPLETED` + driver `AVAILABLE` |
| **Driver offer timeout** | Request ride → driver matched → do NOT accept → wait 12s → assert ride is `REQUESTED` again and driver is `AVAILABLE`; then second driver gets the offer |
| **Kafka (Month 3+)** | `@SpringBootTest` + `KafkaTemplate` send → `await` until consumer updates DB |
| **Resilience** | Inject failing mock HTTP client, assert retry count / circuit breaker state |
| **WebSocket** | Two Java WS clients in test; one posts ride, other receives `ride.matched` event |
| **SQL / N+1** | Count queries via datasource proxy; assert JOIN reduces round-trips |
| **Determinism** | Seeded `Random(12345)` → same coordinates, same matching outcomes every run |
| **Fixtures** | `@SpringBootTest` with `@Sql` or Flyway clean + migrate → assert 10 drivers exist with known UUIDs |

---

## How This Maps to plan.md Gaps

| plan.md Gap | Where It Lives in Puber |
|-------------|-------------------------|
| **Java 25 + Spring Boot** | All three services |
| **Postgres + Flyway + SQL depth** | `rides`, `drivers`, `driver_locations`; EXPLAIN weeks; N+1 fix; index tuning |
| **Transactions & isolation** | Matching ride + updating driver status in one TX; concurrent booking race; fare set at INSERT time |
| **Kafka command/event** | `ride.requested` → match → `ride.matched` → WS push |
| **Resilience4j** | Retry + jitter on Kafka producer; circuit breaker on fare enrichment client |
| **Redis & caching** | Driver availability set; latest driver locations (CQRS split); surge multiplier; WS reconnection buffer |
| **REST API design** | Rider history, driver status, ride tracking, internal service endpoints |
| **WebSockets / real-time** | Driver ride-offer stream |
| **Observability** | Actuator + Prometheus + Grafana; business metrics (rides/min, match latency) |
| **Docker + K8s** | Multi-service Compose from week 1; local K8s + cloud deploy |
| **System design** | Matching engine, geo-indexing, surge pricing, event-driven state machine |

---

## Post-MVP Ideas

> *These are deliberately out of scope for the 6-month timeline but documented so you don't lose the thought.*  
> *Pick one after the project is done if you want to keep the repo alive.*

### 1. `riders` Table with Fixtures

Add a `riders` table (similar to `drivers`) and populate it with Flyway fixtures. Add a foreign key `rides.rider_id → riders(id)`. Enables:
- `GET /riders/{id}/rides` with real JOINs
- Rider profile fields (`name`, `email`, `created_at`) for SQL practice
- `rider_locations` table mirroring `driver_locations` for symmetry exercises
- Ratings (`riders.rating`, `drivers.rating`) for aggregate query practice

**Migration example:**
```sql
-- V3__add_riders.sql
CREATE TABLE riders (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE rides ADD CONSTRAINT fk_rides_rider
    FOREIGN KEY (rider_id) REFERENCES riders(id);

INSERT INTO riders (id, name, created_at) VALUES
  ('b1eebc99-9c0b-4ef8-bb6d-6bb9bd380b21', 'Rider Alice', NOW()),
  ('b1eebc99-9c0b-4ef8-bb6d-6bb9bd380b22', 'Rider Bob',   NOW());
```

### 2. Multi-Vehicle Types

Add `vehicle_type` to `drivers` (`STANDARD`, `XL`, `PREMIUM`). `fare_rules` becomes per-vehicle-type. Enables:
- More complex matching (filter by vehicle type)
- More interesting SQL aggregations (revenue by vehicle type)

### 3. Scheduled / Recurring Rides

`rides` gains a `schedule_type` (`IMMEDIATE`, `DAILY_8AM`). Requires:
- Cron job to materialize scheduled rides
- Cancellation rules for recurring rides

### 4. Promo Codes

`promo_codes` table with `code`, `discount_percent`, `expiry_date`, `max_uses`. `rides` gains `promo_code_id`. Enables:
- SQL exercise: "how many rides used promo X?"
- Validation logic (expired? max uses reached?)

### 5. Driver Earnings Dashboard

Materialized view or cached aggregation of `SUM(fare)` per driver per week. Enables:
- Time-windowed SQL practice
- Redis caching for read-heavy dashboard
- Background job to refresh aggregates

---

## Definition of Done (Whole Project)

- [ ] Three Spring Boot services run in Docker with no host JDK.
- [ ] Postgres schema versioned with Flyway (V1 + V2); fixture drivers seeded automatically.
- [ ] No auth/registration — all identities come from fixtures.
- [ ] Simulator generates reproducible concurrent load; tests assert matching correctness and state machine transitions.
- [ ] Kafka wires command → process → event flow (Month 3+).
- [ ] Resilience patterns (retry, jitter, circuit breaker) applied and tested.
- [ ] Prometheus + Grafana dashboard shows live metrics.
- [ ] Driver receives ride offers via WebSocket.
- [ ] Local K8s manifests exist and deploy cleanly.
- [ ] At least one service deployed to cloud with HTTPS + managed Postgres.
- [ ] `docs/` contains architecture diagram, SQL EXPLAIN artifacts, and Kafka schema notes.

---

## Risk Notes

- **Slip rule ([project.md](../project.md)):** If a week vanishes, repeat it or drop the optional slice. Do not stack debt.
- **Scope honesty:** This is a learning project, not Uber. One bounded 4 km × 4 km area, 10 fixture drivers, one fare rule, seeded random data.
- **Tests in Docker:** Integration tests need Postgres (and later Kafka) reachable from the test container. Run `./gradlew test` inside a service container with `depends_on` postgres, or use a dedicated test Compose profile.
- **V1 simplicity:** Months 1–2 intentionally use HTTP inter-service calls, Postgres for locations, and a test-fixture simulator. Don't feel pressure to add Kafka, Redis, or standalone simulator before Month 3.
- **Location path evolution:** V1 writes driver locations directly to Postgres (fine at 15 writes/sec). Month 3 introduces Kafka + Redis for the hot path. This is a deliberate teaching progression — not a mistake to fix in V1.

---

*Next step: Detail Week 1 tickets (PB-1.1 Bootstrap, PB-1.2 Domain Model + Fixtures) or bootstrap the repository structure.*
