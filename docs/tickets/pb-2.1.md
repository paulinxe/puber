# PB-2.1 — Domain Model, Flyway V1 Schema, Fixtures, and First Endpoints

| Field | Value |
|--------|--------|
| **ID** | PB-2.1 |
| **Phase** | Months 1–2 — Bootstrap + Domain + Matching |
| **Week** | 2 — Domain Model + First Endpoints |
| **Source** | [puber.md §Schedule — Week 2](../puber.md) |
| **Depends on** | [PB-1.1](pb-1.1.md) |

---

## Goal

Introduce the core domain model, versioned Postgres schema, and seeded fixtures so the system becomes persistent. Deliver four HTTP endpoints — fare estimation, request a ride, track ride status, and update driver location — across `rider-api` and `driver-api`. `matching-engine` receives the full domain stack so it is ready for Week 3 matching logic.

This ticket is split into **5 sequential subtasks**. Work through them in order; each subtask is independently buildable and testable.

---

## Context and constraints (apply to all subtasks)

- **No auth / no registration** — all driver identities come from Flyway fixtures; `riderId` is a plain UUID passed in the request body.
- **No `riders` table in V1** — `rides.rider_id` is a plain `UUID` column with no foreign key.
- **Fare is calculated at request time** — rider sees the price before a driver is assigned. Formula: `fare = (base + distanceKm × perKm + estimatedMin × perMinute) × surge` where `estimatedMin = distanceKm / 0.5` and `distanceKm = haversine(pickup, dropoff)`.
- **Geo bounds** — all coordinates stay inside the 4 km × 4 km Lisbon demo square (lat 38.710–38.746, lng −9.160–−9.124).
- **No ORM** — domain objects are immutable plain Java classes with `final` fields. SQL is written explicitly. `JdbcTemplate` is used for connection pooling and result-set mapping, but all queries are hand-written.
- **Each service owns its own domain objects and repositories** — there is no shared Gradle library (per PB-1.1). `rider-api`, `driver-api`, and `matching-engine` each declare the domain classes and repository classes they need. Duplication of simple domain code is acceptable.
- **Shared Flyway files** — the same V1 migration and fixture SQL live in every Spring Boot service so that any service can start first and create the schema. Flyway's `flyway_schema_history` prevents duplicate execution.
- **Java 25, Gradle Wrapper, Docker-only** — same constraints as PB-1.1.
- **No inter-service HTTP yet** — `rider-api` does **not** call `matching-engine` when creating a ride in this ticket. Matching is Week 3.
- **Functional tests over unit tests** — every feature is validated by sending a real HTTP request to a running application context (`@SpringBootTest(webEnvironment = RANDOM_PORT)`) with `TestRestTemplate`. Flyway fixtures seed the database automatically. No mocked repositories, no `@WebMvcTest`. The test exercises the full stack: controller → service → repository → JDBC → Postgres → response.
- **Null-safety with JSpecify** — every service uses `org.jspecify:jspecify:1.0.0`. By default, every type is non-null. Anything that may be null must be annotated with `@Nullable`. IDE and compiler null-analysis should flag missing `@Nullable` annotations.

---

## Subtask overview

| # | ID | Title | What it delivers | Approx time |
|---|-----|-------|------------------|-------------|
| 1 | **PB-2.1.1** | Foundation | Dependencies, Flyway V1 schema + fixtures, domain classes, `FareCalculator`, basic repositories, exception shells, matching-engine scaffolding | ~2h |
| 2 | **PB-2.1.2** | `GET /rides/estimate` | `EstimateFare` service + endpoint, first `@ControllerAdvice` handler | ~30m |
| 3 | **PB-2.1.3** | `POST /rides` | `RequestRide` service + endpoint, active-ride guard, `RideRepository.hasActiveRide`, exception handler | ~45m |
| 4 | **PB-2.1.4** | `GET /rides/{id}` | `GetRide` service + endpoint, exception handler | ~30m |
| 5 | **PB-2.1.5** | `POST /drivers/{id}/location` | `UpdateLocation` service + endpoint, `DriverRepository.updateLocation`, exception handler | ~45m |

---

## PB-2.1.1 — Foundation: Dependencies, Schema, Domain Model, Repositories

### Goal

Make the system persistent. Every Spring Boot service can compile, boot, connect to Postgres, and have the schema + fixtures automatically applied by Flyway. Domain classes are immutable with validation in constructors. Basic repositories can read and write rows via `JdbcTemplate`.

### What to deliver

#### 1. JDBC and Flyway dependencies

| Item | Requirement |
|------|-------------|
| `build.gradle.kts` update (`rider-api`, `driver-api`, `matching-engine`) | Add `spring-boot-starter-jdbc`, `flyway-core`, `flyway-database-postgresql`, `org.postgresql:postgresql`, and `org.jspecify:jspecify:1.0.0` to dependencies. Keep existing `spring-boot-starter-web`, `spring-boot-starter-actuator`, and `spring-boot-starter-test`. |
| `application.yml` update (all three services) | `spring.datasource.url: jdbc:postgresql://postgres:5432/puber`, `username: puber`, `password: puber`, `driver-class-name: org.postgresql.Driver`. `spring.flyway.enabled: true`. No `spring.jpa.*` properties. |

#### 2. Flyway V1 schema and fixtures

| Item | Requirement |
|------|-------------|
| `V1__init.sql` | Creates `drivers`, `rides`, `driver_locations`, and `fare_rules` exactly per [puber.md §Data Model](../puber.md). Include `CHECK` constraints on `status` columns. All tables have `created_at` and `updated_at` columns (`TIMESTAMPTZ NOT NULL DEFAULT NOW()`). |
| `V1_1__seed_fixtures.sql` | Seeds 10 drivers (same UUIDs as puber.md, including `created_at` and `updated_at`) and 1 fare rule row (`base_fare=2.50`, `per_km=1.20`, `per_minute=0.30`, `surge_multiplier=1.00`, `created_at=NOW()`, `updated_at=NOW()`). |
| Location | `src/main/resources/db/migration/` inside `matching-engine`, `rider-api`, and `driver-api`. Identical files in all three services. |

#### 3. Domain classes and enums

All domain classes are **immutable** — all fields are `final`, no setters. Construction happens via **static factory methods** (`create(...)` for new instances, `from(...)` for reconstitution from DB rows). Cross-field validation lives in the private constructor.

| Item | Requirement |
|------|-------------|
| `Location` | Immutable class with `final BigDecimal lat`, `final BigDecimal lng`. Private constructor validates lat/lng are inside the Lisbon demo bounds; throws `IllegalArgumentException` if outside. Public static `create(double lat, double lng)` factory. |
| `RideStatus` enum | `REQUESTED`, `MATCHED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`. |
| `DriverStatus` enum | `OFFLINE`, `AVAILABLE`, `BUSY`. |
| `FareRule` | Immutable class: `final Integer id`, `final BigDecimal baseFare`, `final BigDecimal perKm`, `final BigDecimal perMinute`, `final BigDecimal surgeMultiplier`, `final Instant createdAt`, `final Instant updatedAt`. Private constructor. Public static `from(...)` factory for reconstitution from DB. |
| `Ride` | Immutable class: `final UUID id`, `final UUID riderId`, `final @Nullable Driver driver`, `final Location pickup`, `final Location dropoff`, `final RideStatus status`, `final Instant requestedAt`, `final @Nullable Instant matchedAt`, `final @Nullable Instant completedAt`, `final BigDecimal fare`, `final Instant createdAt`, `final Instant updatedAt`. Private constructor validates non-null fields. `driver`, `matchedAt`, `completedAt` are `@Nullable`. Public static `create(UUID riderId, Location pickup, Location dropoff, BigDecimal fare)` — generates `id`, `status = REQUESTED`, `requestedAt = now()`, `createdAt = now()`, `updatedAt = now()`, `driver = null`, `matchedAt = null`, `completedAt = null`. Public static `from(...)` for reconstitution from DB. |
| `Driver` | Immutable class: `final UUID id`, `final String name`, `final DriverStatus status`, `final @Nullable Location currentLocation`, `final Instant createdAt`, `final Instant updatedAt`. Private constructor. `currentLocation` is `@Nullable`. Public static `from(...)` for reconstitution from DB. |
| `DriverLocation` | Immutable class: `final @Nullable Long id`, `final Driver driver`, `final Location location`, `final Instant recordedAt`, `final Instant createdAt`, `final Instant updatedAt`. Private constructor. `id` is `@Nullable`. Public static `create(Driver driver, Location location)` — generates `recordedAt = now()`, `createdAt = now()`, `updatedAt = now()`, `id = null`. Public static `from(...)` for reconstitution from DB. |

#### 4. `FareCalculator` service

| Item | Requirement |
|------|-------------|
| Location | `com.puber.rider.services.FareCalculator` in `rider-api`; `com.puber.matching.services.FareCalculator` in `matching-engine`. Independent copies are fine. |
| Haversine | `static double haversineKm(Location a, Location b)` using the standard haversine formula for km. |
| `calculateFare(Location pickup, Location dropoff)` | Loads the single `FareRule` row from the DB; computes `distanceKm` via haversine; `estimatedMin = distanceKm / 0.5`; returns `BigDecimal` rounded to 2 decimal places using the formula from §Context. |

**Java implementation of `haversineKm`:**

```java
public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
    final double R = 6371.0; // Earth radius in km

    double dLat = Math.toRadians(lat2 - lat1);
    double dLng = Math.toRadians(lng2 - lng1);

    double radLat1 = Math.toRadians(lat1);
    double radLat2 = Math.toRadians(lat2);

    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
             + Math.cos(radLat1) * Math.cos(radLat2)
             * Math.sin(dLng / 2) * Math.sin(dLng / 2);

    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

    return R * c; // distance in kilometers
}
```

#### 5. JDBC repositories (explicit SQL, `JdbcTemplate`)

Use `org.springframework.jdbc.core.JdbcTemplate` and `RowMapper<T>` for result-set mapping. Each repository is a `@Repository` class with a constructor-injected `JdbcTemplate`.

| Service | Repository | Methods & SQL |
|---------|-----------|-------------|
| `rider-api` | `RideRepository` | `save(Ride ride)` — `INSERT INTO rides (id, rider_id, driver_id, pickup_lat, pickup_lng, dropoff_lat, dropoff_lng, status, requested_at, matched_at, completed_at, fare, created_at, updated_at) VALUES (...)`. `findById(UUID id)` — `SELECT ... FROM rides r LEFT JOIN drivers d ON r.driver_id = d.id WHERE r.id = ?`. Returns `Optional<Ride>`. |
| `rider-api` | `FareRuleRepository` | `findCurrent()` — `SELECT * FROM fare_rules LIMIT 1`. Returns `Optional<FareRule>`. |
| `driver-api` | `DriverRepository` | `findById(UUID id)` — `SELECT * FROM drivers WHERE id = ?`. Returns `Optional<Driver>`. |
| `driver-api` | `DriverLocationRepository` | `save(DriverLocation loc)` — `INSERT INTO driver_locations (driver_id, lat, lng, recorded_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)`. `id` is omitted (database generates it). |
| `matching-engine` | All of the above, so it is ready for Week 3. |

**RowMappers:** each repository defines a private static `RowMapper<DomainType>` that maps columns to the domain class via the `from(...)` static factory. Use `rs.getObject("col", UUID.class)` and handle `null` explicitly for `@Nullable` fields (e.g. `driver_id`, `matched_at`, `completed_at`). The mapper calls `DomainClass.from(...)` with `null` passed for absent `@Nullable` columns.

#### 6. Exception shells (custom unchecked exceptions)

| Exception | Where |
|-----------|-------|
| `RideNotFoundException` | `com.puber.rider.exceptions` |
| `DriverNotFoundException` | `com.puber.driver.exceptions` |
| `RiderAlreadyHasActiveRideException` | `com.puber.rider.exceptions` |

All extend `RuntimeException`. Empty constructors for now — handlers and messages are added in their respective subtasks.

#### 7. `matching-engine` scaffolding

| Item | Requirement |
|------|-------------|
| Service package | `com.puber.matching.services.*` holds all domain classes, enums, value objects, `FareCalculator`, and JDBC repositories. |
| No public endpoints | No controllers other than Actuator. The service must still compile, boot, and pass tests. |

### Acceptance criteria (done = all true)

1. `cd services/matching-engine && ./gradlew test` compiles and passes (at minimum one context-load smoke test).
2. `cd services/rider-api && ./gradlew test` compiles and passes (at minimum one context-load smoke test).
3. `cd services/driver-api && ./gradlew test` compiles and passes (at minimum one context-load smoke test).
4. `docker compose -f infra/docker-compose.yml up -d postgres` is healthy.
5. `docker compose -f infra/docker-compose.yml up --build -d rider-api driver-api matching-engine` starts all three services; Flyway migrations apply without error; fixtures are present in Postgres.
6. Querying `fare_rules` shows exactly one row with `base_fare = 2.50`. Querying `drivers` shows 10 rows including the seeded UUIDs.
7. No business endpoints exist yet (no `GET /rides/estimate`, no `POST /rides`, no `GET /rides/{id}`, no `POST /drivers/{id}/location`).
8. No JPA, Hibernate, or `spring-boot-starter-data-jpa` dependency exists in any service.
9. `org.jspecify:jspecify:1.0.0` is on the classpath of every service. All `@Nullable` annotations are present on class fields and repository return values. IDE null-analysis shows zero warnings for missing `@Nullable` annotations.

### Explicitly out of scope for this subtask

- HTTP controllers, services, or endpoints of any kind.
- `@ControllerAdvice` or exception handlers.
- Functional tests against HTTP endpoints.

---

## PB-2.1.2 — `GET /rides/estimate`

### Goal

Let a rider preview the fare before requesting a ride. This is the first public endpoint; it validates the `@ControllerAdvice` pattern and proves the `FareCalculator` + `Location` validation works end-to-end.

### What to deliver

#### 1. `EstimateFare` service

| Item | Requirement |
|------|-------------|
| Location | `com.puber.rider.services.EstimateFare` |
| Injection | `FareCalculator` |
| `@Transactional` | **No** — read-only, no DB writes. |
| Method | `execute(double pickupLat, double pickupLng, double dropoffLat, double dropoffLng)` |
| Logic | (1) `Location.create(pickupLat, pickupLng)` and `Location.create(dropoffLat, dropoffLng)`; (2) `fareCalculator.calculateFare(pickup, dropoff)`; (3) compute `distanceKm = haversineKm(pickup, dropoff)` and `estimatedMinutes = Math.round(distanceKm / 0.5)`; (4) return a `FareEstimate` DTO/record. |

**`FareEstimate` DTO:**
```java
public record FareEstimate(
    Location pickup,
    Location dropoff,
    BigDecimal fare,
    double distanceKm,
    long estimatedMinutes
) {}
```

#### 2. `RiderApiController` — `GET /rides/estimate`

| Item | Requirement |
|------|-------------|
| Path | `GET /rides/estimate?pickupLat={lat}&pickupLng={lng}&dropoffLat={lat}&dropoffLng={lng}` |
| Delegation | Calls `estimateFare.execute(...)` and returns `200 OK` with `FareEstimate` JSON. |

#### 3. Exception handler (first `@ControllerAdvice` method)

| Item | Requirement |
|------|-------------|
| Class | `com.puber.rider.exceptions.GlobalExceptionHandler` (or `com.puber.shared.exceptions.GlobalExceptionHandler` — pick one service, it only lives in `rider-api` for now). Annotated with `@ControllerAdvice`. |
| Handler | `@ExceptionHandler(IllegalArgumentException.class)` → `ResponseEntity<ErrorResponse>` with `400 Bad Request`. |
| `ErrorResponse` | Simple record: `record ErrorResponse(int status, String message, Instant timestamp)`. |

#### 4. Functional test: `EstimateFareTest`

| Test | What it asserts |
|------|----------------|
| `GET /rides/estimate` valid coordinates | `200 OK`, body contains `fare > 0`, `distanceKm > 0`, `estimatedMinutes > 0`. |
| `GET /rides/estimate` out-of-bounds lat/lng | `400 Bad Request`, error body has `status: 400`. |

### Acceptance criteria (done = all true)

1. `cd services/rider-api && ./gradlew test` compiles and passes — `EstimateFareTest` asserts the endpoint against the real Postgres database.
2. `GET /rides/estimate?pickupLat=38.711&pickupLng=-9.140&dropoffLat=38.735&dropoffLng=-9.125` returns `200` with `fare > 0`, `distanceKm > 0`, `estimatedMinutes > 0`.
3. `GET /rides/estimate` with out-of-bounds coordinates returns `400 Bad Request`.
4. `IllegalArgumentException` thrown by `Location.create(...)` is caught by `@ControllerAdvice` and mapped to `400` with an `ErrorResponse` body.
5. No other endpoints exist yet in `rider-api` besides Actuator and `GET /rides/estimate`.

---

## PB-2.1.3 — `POST /rides`

### Goal

Allow a rider to request a ride with an upfront fare. Guard against duplicate active rides per rider. Extend the exception handler with the custom domain exception.

### What to deliver

#### 1. `RideRepository` extension

| Item | Requirement |
|------|-------------|
| `hasActiveRide(UUID riderId)` | `SELECT COUNT(*) FROM rides WHERE rider_id = ? AND status NOT IN ('CANCELLED', 'COMPLETED')`. Returns `boolean`. |

#### 2. `RequestRide` service

| Item | Requirement |
|------|-------------|
| Location | `com.puber.rider.services.RequestRide` |
| Injection | `FareCalculator`, `RideRepository` |
| `@Transactional` | **Yes** — writes to DB. |
| Method | `execute(RequestRideDto dto)` |
| Logic | (1) `rideRepository.hasActiveRide(dto.riderId())` — if `true`, throw `RiderAlreadyHasActiveRideException`; (2) `Location.create(dto.pickupLat(), dto.pickupLng())` and `Location.create(dto.dropoffLat(), dto.dropoffLng())`; (3) `fareCalculator.calculateFare(pickup, dropoff)`; (4) `Ride.create(riderId, pickup, dropoff, fare)`; (5) `rideRepository.save(ride)`; (6) return the created `Ride`. |

**`RequestRideDto`** (record): `{ "riderId": "uuid", "pickupLat": 38.711, "pickupLng": -9.140, "dropoffLat": 38.735, "dropoffLng": -9.125 }`

#### 3. `RiderApiController` — `POST /rides`

| Item | Requirement |
|------|-------------|
| Path | `POST /rides` |
| Body | `RequestRideDto` |
| Response | `201 Created` with ride JSON (must include `id`, `riderId`, `status`, `fare`, `pickup`, `dropoff`, `requestedAt`). |

#### 4. Exception handler extension

| Item | Requirement |
|------|-------------|
| Add to existing `@ControllerAdvice` | `@ExceptionHandler(RiderAlreadyHasActiveRideException.class)` → `ResponseEntity<ErrorResponse>` with `409 Conflict`. |

#### 5. Functional test: `RequestRideTest`

| Test | What it asserts |
|------|----------------|
| `POST /rides` valid coordinates | `201 Created`, body contains `id` (UUID), `status: "REQUESTED"`, `fare > 0`. |
| `POST /rides` out-of-bounds lat/lng | `400 Bad Request` (from `Location.create`). |
| `POST /rides` by a rider who already has a `REQUESTED` ride | `409 Conflict` (from `RiderAlreadyHasActiveRideException`). |

### Acceptance criteria (done = all true)

1. `cd services/rider-api && ./gradlew test` compiles and passes — `RequestRideTest` asserts the endpoint against the real Postgres database.
2. `POST /rides` with valid coordinates returns `201` and the response body contains a `UUID id`, `status: "REQUESTED"`, and a `fare` greater than `0`.
3. `POST /rides` with out-of-bounds lat/lng returns `400`.
4. `POST /rides` by a rider who already has an active ride returns `409 Conflict`.
5. `RiderAlreadyHasActiveRideException` is caught by `@ControllerAdvice` and mapped to `409` with an `ErrorResponse` body.

---

## PB-2.1.4 — `GET /rides/{id}`

### Goal

Let a rider track their ride status. Extend the exception handler with the ride-not-found exception.

### What to deliver

#### 1. `GetRide` service

| Item | Requirement |
|------|-------------|
| Location | `com.puber.rider.services.GetRide` |
| Injection | `RideRepository` |
| `@Transactional` | **No** — read-only. |
| Method | `execute(UUID id)` |
| Logic | `rideRepository.findById(id)`. If empty, throw `RideNotFoundException`. If `driver` is non-null, include driver `id` and `name` in the response DTO. Return a `RideResponse` DTO. |

**`RideResponse` DTO:** mirrors `Ride` fields but flattens `driver` into `driverId` + `driverName` when present.

#### 2. `RiderApiController` — `GET /rides/{id}`

| Item | Requirement |
|------|-------------|
| Path | `GET /rides/{id}` where `{id}` is a UUID. |
| Response | `200 OK` with `RideResponse` JSON; `404 Not Found` if absent. |

#### 3. Exception handler extension

| Item | Requirement |
|------|-------------|
| Add to existing `@ControllerAdvice` | `@ExceptionHandler(RideNotFoundException.class)` → `ResponseEntity<ErrorResponse>` with `404 Not Found`. |

#### 4. Functional test: `GetRideTest`

| Test | What it asserts |
|------|----------------|
| `GET /rides/{id}` for an existing ride | `200 OK` with matching body. |
| `GET /rides/{randomUUID}` | `404 Not Found`, error body has `status: 404`. |

### Acceptance criteria (done = all true)

1. `cd services/rider-api && ./gradlew test` compiles and passes — `GetRideTest` asserts the endpoint against the real Postgres database.
2. `GET /rides/{id}` for a ride created by `POST /rides` returns `200` and the same ride JSON.
3. `GET /rides/{randomUUID}` returns `404 Not Found`.
4. `RideNotFoundException` is caught by `@ControllerAdvice` and mapped to `404` with an `ErrorResponse` body.

---

## PB-2.1.5 — `POST /drivers/{id}/location`

### Goal

Allow a driver to update their location. Writes to both `drivers` and `driver_locations` atomically. Extend the exception handler with the driver-not-found exception.

### What to deliver

#### 1. `DriverRepository` extension

| Item | Requirement |
|------|-------------|
| `updateLocation(UUID id, Location loc)` | `UPDATE drivers SET current_lat = ?, current_lng = ?, updated_at = ? WHERE id = ?`. Returns `int` (rows updated). |

#### 2. `UpdateLocation` service

| Item | Requirement |
|------|-------------|
| Location | `com.puber.driver.services.UpdateLocation` |
| Injection | `DriverRepository`, `DriverLocationRepository` |
| `@Transactional` | **Yes** — writes to two tables. |
| Method | `execute(UUID driverId, UpdateLocationDto dto)` |
| Logic | (1) `driverRepository.findById(id)` — if empty, throw `DriverNotFoundException`; (2) `Location.create(dto.lat(), dto.lng())`; (3) `driverRepository.updateLocation(id, location)`; (4) `driverLocationRepository.save(DriverLocation.create(driver, location))`. Both DB operations run inside the same transaction. |

**`UpdateLocationDto`** (record): `{ "lat": 38.720, "lng": -9.130 }`

#### 3. `DriverApiController` — `POST /drivers/{id}/location`

| Item | Requirement |
|------|-------------|
| Path | `POST /drivers/{id}/location` where `{id}` is a fixture driver UUID. |
| Body | `UpdateLocationDto` |
| Response | `200 OK` with no body (or `{ "updated": true }`). Return `404` if driver UUID does not exist. Return `400` if coordinates are out of bounds. |

#### 4. Exception handler extension

| Item | Requirement |
|------|-------------|
| Add to existing `@ControllerAdvice` | `@ExceptionHandler(DriverNotFoundException.class)` → `ResponseEntity<ErrorResponse>` with `404 Not Found`. |

#### 5. Functional test: `UpdateLocationTest`

| Test | What it asserts |
|------|----------------|
| `POST /drivers/{fixtureId}/location` valid coordinates | `200 OK`; query `driver_locations` and `drivers` tables to confirm the audit row and `updated_at` refresh. |
| `POST /drivers/{unknownUUID}/location` | `404 Not Found`. |
| `POST /drivers/{fixtureId}/location` out-of-bounds coordinates | `400 Bad Request` (from `Location.create`). |

### Acceptance criteria (done = all true)

1. `cd services/driver-api && ./gradlew test` compiles and passes — `UpdateLocationTest` asserts the endpoint against the real Postgres database.
2. `POST /drivers/a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11/location` with valid coordinates returns `200`; a subsequent query to `driver_locations` table shows a new row, and `drivers.updated_at` is refreshed.
3. `POST /drivers/{unknownUUID}/location` returns `404 Not Found`.
4. `POST /drivers/{fixtureId}/location` with out-of-bounds coordinates returns `400`.
5. `DriverNotFoundException` is caught by `@ControllerAdvice` and mapped to `404` with an `ErrorResponse` body.

---

## Explicitly out of scope for the whole ticket

- `GET /rides/history` (scheduled for Week 7/8).
- Matching engine algorithm, nearest-driver query, ETA calculation, `@Scheduled` retry, or offer timeout (Week 3).
- Inter-service HTTP endpoints (`/internal/match`, `/internal/drivers/{id}/offer`, `/internal/rides/{id}/accept`, `/internal/rides/{id}/complete`) — all Week 5.
- `POST /drivers/{id}/availability` — driver status changes outside of direct DB updates via location endpoint are not yet exposed via REST.
- Kafka, Redis, WebSockets, or any message broker.
- Simulator threads, WebClient, or random-location generation (Week 3).
- Custom Actuator endpoints or Prometheus metrics beyond the default `/actuator/health`.
- Cloud deployment, K8s, CI/CD.
- Input DTO validation frameworks beyond manual bounds checks or basic Spring `@Valid`.

---

## Suggested completion note

> Shipped the core domain model (`Ride`, `Driver`, `Location`, `FareRule`), Flyway V1 schema + 10 driver fixtures, and four endpoints: `GET /rides/estimate` for upfront fare preview, `POST /rides` with active-ride guard and upfront fare calculation, `GET /rides/{id}` for tracking, and `POST /drivers/{id}/location` with audit trail. All three Spring Boot services share the same Flyway migrations independently. Domain objects are immutable plain Java classes with `final` fields and static factory methods; persistence is via explicit SQL with `JdbcTemplate`. `matching-engine` is compiled and ready for Week 3 matching logic.

---

## Next ticket

- [PB-3.1](pb-3.1.md) — In-memory matching engine: nearest-driver query, fare & ETA services, `@Scheduled` retry for unmatched rides, and simulator fixture integration test that asserts `rides.status = MATCHED`
