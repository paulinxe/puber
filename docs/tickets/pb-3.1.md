# PB-3.1 — In-memory Matching Engine, Scheduled Retry/Request Timeout, and Simulator Fixture Integration Test

| Field | Value |
|--------|--------|
| **ID** | PB-3.1 |
| **Phase** | Months 1–2 — Bootstrap + Domain + Matching |
| **Week** | 3 — Matching Engine + Simulator Fixture |
| **Source** | [puber.md §Schedule — Week 3](../puber.md) |
| **Depends on** | [PB-2.1](pb-2.1.md) |

---

## Goal

Make `matching-engine` the brain of the system. Deliver the nearest-driver matching algorithm, fare/ETA calculation, `@Scheduled` retry for unmatched rides, and a 10-second driver-request timeout. Wire the first inter-service HTTP calls (`matching-engine ↔ driver-api`) so a simulator test fixture can request a ride end-to-end and assert the database reaches `rides.status = MATCHED` + `drivers.status = BUSY`.

This ticket is split into **5 sequential subtasks**. Work through them in order.

---

## Context and constraints (apply to all subtasks)

- **No Kafka, no Redis** — all inter-service communication in V1 is direct HTTP. `matching-engine` calls `driver-api` to push requests; `driver-api` calls `matching-engine` to confirm accept/complete.
- **No auth / no registration** — driver identities come from Flyway fixtures; `riderId` is a plain UUID.
- **Fare is already calculated at request time** — `FareCalculator` shipped in PB-2.1 in both `rider-api` and `matching-engine`. This ticket adds `PickupEtaCalculator` in `matching-engine` only.
- **Geo bounds** — Lisbon demo square (lat 38.710–38.746, lng −9.160–−9.124). Haversine for distance.
- **State machine** — `REQUESTED → MATCHED → IN_PROGRESS → COMPLETED`. `CANCELLED` is rider-only and already guarded in PB-2.1.
- **Request timeout** — 10 seconds. If the driver does not accept, the ride returns to `REQUESTED` and the next nearest driver is requested.
- **Scheduled retry** — Every 5 seconds the matching engine retries all `REQUESTED` rides and expires all stale `MATCHED` requests.
- **Max matching radius** — 5 km. If no driver is within 5 km, the ride stays `REQUESTED`.
- **No ORM** — explicit SQL with `JdbcTemplate`, immutable domain classes, `org.jspecify:jspecify:1.0.0` null-safety.
- **No magic numbers** — every numeric literal that carries business meaning must be a `private static final` constant with a descriptive name (e.g., `MAX_MATCHING_RADIUS_KM`, `AVERAGE_SPEED_KMH`, `OFFER_TIMEOUT_SECONDS`). `TimeUnit` conversions are preferred over bare multiplication factors.
- **Functional tests over unit tests** — every subtask is validated by `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` + real Postgres. Cross-service HTTP clients may be mocked with `MockRestServiceServer` or `@MockBean` when the callee service is not running in the same test JVM.
- **Inter-service HTTP is V1-only** — these endpoints are replaced by Kafka in Month 3. Keep the clients simple (Spring `RestTemplate` or `WebClient`); no Resilience4j yet.

---

## Subtask overview

| # | ID | Title | What it delivers | Approx time |
|---|-----|-------|------------------|-------------|
| 1 | **PB-3.1.1** | Matching engine core | `MatchingService`, `PickupEtaCalculator`, `DriverRepository.findAvailable`, `@Scheduled` retry/timeout, state-transition services | ~2h |
| 2 | **PB-3.1.2** | Matching-engine internal endpoints | `POST /internal/match`, `/internal/rides/{id}/accept`, `/internal/rides/{id}/complete`; `DriverRequestClient` HTTP call to `driver-api` | ~45m |
| 3 | **PB-3.1.3** | Driver-api request + accept/complete proxy | `POST /internal/drivers/{id}/request`, `POST /rides/{id}/accept`, `POST /rides/{id}/complete`, `POST /drivers/{id}/availability`, `MatchingEngineClient` | ~45m |
| 4 | **PB-3.1.4** | Rider-api triggers matching | Update `RequestRide` to HTTP POST `matching-engine /internal/match` after DB insert | ~30m |
| 5 | **PB-3.1.5** | Simulator fixture + functional tests | `PuberSimulator` test fixture, end-to-end assertion `MATCHED` + `BUSY`, request-timeout test, no-driver-retry test | ~45m |

---

## PB-3.1.1 — Matching Engine Core: Algorithm, Scheduled Retry, and State Transitions

### Goal

Implement the matching brain inside `matching-engine`. It can find the nearest available driver, calculate ETA, assign a driver to a ride, expire stale requests, and retry unmatched rides — all backed by explicit JDBC queries.

### What to deliver

#### 1. Repository extensions (`matching-engine`)

| Service | Repository | Methods & SQL |
|---------|-----------|-------------|
| `matching-engine` | `DriverRepository` | `findAvailable()` — `SELECT * FROM drivers WHERE status = 'AVAILABLE'`. Returns `List<Driver>`. |
| `matching-engine` | `DriverRepository` | `findByIdForUpdate(UUID id)` — `SELECT * FROM drivers WHERE id = ? FOR UPDATE`. Returns `Optional<Driver>`. Used inside `@Transactional` to prevent concurrent status changes. |
| `matching-engine` | `DriverRepository` | `save(Driver driver)` — `UPDATE drivers SET name = ?, status = ?, current_lat = ?, current_lng = ?, updated_at = ? WHERE id = ?`. |
| `matching-engine` | `RideRepository` | `findByStatus(RideStatus status)` — `SELECT ... FROM rides r LEFT JOIN drivers d ON r.driver_id = d.id WHERE r.status = ?`. Returns `List<Ride>`. |
| `matching-engine` | `RideRepository` | `findExpiredMatches(Duration timeout)` — `SELECT ... FROM rides r LEFT JOIN drivers d ON r.driver_id = d.id WHERE r.status = 'MATCHED' AND r.matched_at < ?`. Returns `List<Ride>`. Compute cutoff as `NOW() - timeout` via query or in Java. |
| `matching-engine` | `RideRepository` | `findByIdForUpdate(UUID id)` — `SELECT ... FROM rides r LEFT JOIN drivers d ON r.driver_id = d.id WHERE r.id = ? FOR UPDATE`. Returns `Optional<Ride>`. Used inside `@Transactional` to prevent concurrent match attempts on the same ride. |
| `matching-engine` | `RideRepository` | `save(Ride ride)` — `INSERT ... ON CONFLICT (id) DO UPDATE ...` (UPSERT). Handles both new rides (simulator fixture) and existing ride updates (matching flow). |

#### 2. Schema migration: `rides.estimated_duration_minutes`

| Item | Requirement |
|------|-------------|
| `V1_2__add_estimated_duration_minutes.sql` | `ALTER TABLE rides ADD COLUMN estimated_duration_minutes INTEGER NULL;` |
| Location | `src/main/resources/db/migration/` inside `matching-engine`, `rider-api`, and `driver-api`. Identical files in all three services. |
| Rationale | The estimated trip duration (pickup → dropoff) is computed at request time by `FareCalculator` / `EstimateFare` and is now persisted on the `rides` row for receipts and analytics. Kept nullable so V1 rows remain valid. |

#### 3. Domain object update: `Ride`

| Item | Requirement |
|------|-------------|
| New field | `final @Nullable Integer estimatedDurationMinutes` |
| Constructor | Updated to accept `estimatedDurationMinutes`. |
| `create(...)` | Signature: `create(UUID riderId, Location pickup, Location dropoff, BigDecimal fare, @Nullable Integer estimatedDurationMinutes)`. Generates `id`, `status = REQUESTED`, `requestedAt = now()`, `createdAt = now()`, `updatedAt = now()`, `driver = null`, `matchedAt = null`, `completedAt = null`. |
| `from(...)` | Updated to read `rs.getObject("estimated_duration_minutes", Integer.class)` and pass it to the constructor. |
| State transitions | `assignDriver`, `resetToRequested`, `markInProgress`, `markCompleted` all preserve `estimatedDurationMinutes` into the new immutable instance. |

#### 4. Repository SQL updates (`matching-engine` and `rider-api`)

All `RideRepository` methods that touch `rides` columns are updated to include `estimated_duration_minutes`.

| Service | Repository | Change |
|---------|-----------|--------|
| `rider-api` | `RideRepository.save(Ride ride)` | `INSERT` now includes `estimated_duration_minutes` and binds `ride.estimatedDurationMinutes()`. |
| `rider-api` | `RideRepository.findById(UUID id)` | `SELECT` includes `r.estimated_duration_minutes`. `RowMapper` updated. |
| `matching-engine` | `RideRepository.save(Ride ride)` | `INSERT ... ON CONFLICT ... DO UPDATE ...` includes `estimated_duration_minutes` in both clauses. |
| `matching-engine` | `RideRepository.findById(UUID id)` | `SELECT` includes `r.estimated_duration_minutes`. `RowMapper` updated. |
| `matching-engine` | `RideRepository.findByStatus(...)` | `SELECT` includes `r.estimated_duration_minutes`. `RowMapper` updated. |
| `matching-engine` | `RideRepository.findExpiredMatches(...)` | `SELECT` includes `r.estimated_duration_minutes`. `RowMapper` updated. |
| `matching-engine` | `RideRepository.findByIdForUpdate(...)` | `SELECT` includes `r.estimated_duration_minutes`. `RowMapper` updated. |

> `driver-api` does not query `rides` directly in V1, but the migration file must still be present so it can boot against a fresh database.

#### 5. Domain transition methods (immutable copies)

State changes are expressed as methods on immutable domain objects. Each method returns a new instance with the updated field and a fresh `updatedAt`.

| Class | Method | Description |
|-------|--------|-------------|
| `Driver` | `setBusy()` | Returns new `Driver` with `status = BUSY`, `updatedAt = now()`. |
| `Driver` | `setAvailable()` | Returns new `Driver` with `status = AVAILABLE`, `updatedAt = now()`. |
| `Driver` | `setOffline()` | Returns new `Driver` with `status = OFFLINE`, `updatedAt = now()`. |
| `Ride` | `assignDriver(Driver driver, Instant matchedAt)` | Returns new `Ride` with `driver = driver`, `status = MATCHED`, `matchedAt = matchedAt`, `updatedAt = now()`, preserving `estimatedDurationMinutes`. |
| `Ride` | `resetToRequested()` | Returns new `Ride` with `driver = null`, `status = REQUESTED`, `matchedAt = null`, `updatedAt = now()`, preserving `estimatedDurationMinutes`. |
| `Ride` | `markInProgress()` | Returns new `Ride` with `status = IN_PROGRESS`, `updatedAt = now()`, preserving `estimatedDurationMinutes`. |
| `Ride` | `markCompleted(Instant completedAt)` | Returns new `Ride` with `status = COMPLETED`, `completedAt = completedAt`, `updatedAt = now()`, preserving `estimatedDurationMinutes`. |

#### 6. `PickupEtaCalculator` service

| Item | Requirement |
|------|-------------|
| Location | `com.puber.matching.services.PickupEtaCalculator` |
| Constants | `private static final double AVERAGE_SPEED_KMH = 30.0;` |
| Method | `long calculateEtaMinutes(Location pickup, Location driverLocation)` |
| Logic | `distanceKm = haversineKm(pickup, driverLocation)`; `hours = distanceKm / AVERAGE_SPEED_KMH`; return `Math.round(hours * 60.0)`. Result is in whole minutes. |

#### 7. Coordinator service

**`MatchEngine`**

| Item | Requirement |
|------|-------------|
| Location | `com.puber.matching.services.MatchEngine` |
| Injection | `RideRepository`, `FindNearestDriver`, `AssignDriver` |
| `@Transactional` | **Yes** — the whole match flow is atomic. |
| Method | `boolean execute(UUID rideId)` |
| Logic | (1) Load ride via `rideRepository.findByIdForUpdate(rideId)`; if not found throw `RideNotFoundException`; if status != `REQUESTED` throw `RideAlreadyMatchedException`; (2) `var match = findNearestDriver.execute(ride.pickup())`; (3) if `match.isEmpty()` return `false` (ride stays `REQUESTED`, scheduler will retry); (4) `assignDriver.execute(ride, match.get())`; (5) return `true`. |
| Return value | `true` = driver found and assigned; `false` = no available driver within radius. |

> The controller is a thin HTTP adapter: it delegates to `matchEngine.execute(rideId)` and maps the boolean to `200 OK` or `202 Accepted`. No orchestration logic lives in the controller.

#### 8. Worker services (one per responsibility)

Each operation is its own `@Service` with a single public `execute(...)` method. No god-class. `MatchEngine` composes them; the scheduler and controller do not.

**`FindNearestDriver`**

| Item | Requirement |
|------|-------------|
| Location | `com.puber.matching.services.FindNearestDriver` |
| Injection | `DriverRepository` |
| Constant | `private static final double MAX_MATCHING_RADIUS_KM = 5.0;` |
| Method | `Optional<DriverMatch> execute(Location pickup)` |
| Logic | Query `driverRepository.findAvailable()`; map each to `DriverMatch(driver, haversineKm(pickup, driver.currentLocation()))`; filter `distanceKm <= MAX_MATCHING_RADIUS_KM`; return `Optional<DriverMatch>` with minimum distance. If `currentLocation` is `null`, skip that driver. |

**`AssignDriver`**

| Item | Requirement |
|------|-------------|
| Location | `com.puber.matching.services.AssignDriver` |
| Injection | `DriverRepository`, `RideRepository`, `PickupEtaCalculator`, `DriverRequestClient` |
| `@Transactional` | **Yes** |
| Method | `void execute(Ride ride, DriverMatch match)` |
| Logic | (1) Re-lock driver via `driverRepository.findByIdForUpdate(match.driver().id())` — if now `OFFLINE` or `BUSY`, abort (another thread won the race); (2) `var updatedDriver = match.driver().setBusy()`; (3) `driverRepository.save(updatedDriver)`; (4) `var updatedRide = ride.assignDriver(updatedDriver, Instant.now())`; (5) `rideRepository.save(updatedRide)`; (6) `var etaMinutes = pickupEtaCalculator.calculateEtaMinutes(updatedRide.pickup(), updatedDriver.currentLocation())`; (7) `driverOfferClient.sendRequest(updatedDriver.id(), updatedRide.id(), updatedRide.pickup(), etaMinutes)`. |

**`AcceptRide`**

| Item | Requirement |
|------|-------------|
| Location | `com.puber.matching.services.AcceptRide` |
| Injection | `RideRepository` |
| `@Transactional` | **Yes** |
| Method | `void execute(UUID rideId)` |
| Logic | Load ride via `rideRepository.findByIdForUpdate(rideId)`; if not found throw `RideNotFoundException`; if status != `MATCHED` throw `RideAlreadyMatchedException`; `var updated = ride.markInProgress()`; `rideRepository.save(updated)`. |

**`CompleteRide`**

| Item | Requirement |
|------|-------------|
| Location | `com.puber.matching.services.CompleteRide` |
| Injection | `RideRepository`, `DriverRepository` |
| `@Transactional` | **Yes** |
| Method | `void execute(UUID rideId)` |
| Logic | Load ride via `rideRepository.findByIdForUpdate(rideId)`; if not found throw `RideNotFoundException`; if status != `IN_PROGRESS` throw `RideAlreadyMatchedException`; `var updatedRide = ride.markCompleted(Instant.now())`; `rideRepository.save(updatedRide)`; re-lock driver via `driverRepository.findByIdForUpdate(updatedRide.driver().id())`; `var updatedDriver = updatedRide.driver().setAvailable()`; `driverRepository.save(updatedDriver)`. |

**`ReleaseDriver`**

| Item | Requirement |
|------|-------------|
| Location | `com.puber.matching.services.ReleaseDriver` |
| Injection | `DriverRepository`, `RideRepository` |
| `@Transactional` | **Yes** |
| Method | `void execute(Ride ride)` |
| Logic | Re-lock driver via `driverRepository.findByIdForUpdate(ride.driver().id())`; `var updatedDriver = ride.driver().setAvailable()`; `driverRepository.save(updatedDriver)`; `var updatedRide = ride.resetToRequested()`; `rideRepository.save(updatedRide)`. |

**`DriverMatch` value object:**
```java
public record DriverMatch(Driver driver, double distanceKm) {}
```

#### 9. Scheduler services (one per responsibility)

Each scheduled task is its own `@Service` with a single `@Scheduled` method. No god-class scheduler.

**`RetryUnmatchedRides`**

| Item | Requirement |
|------|-------------|
| Location | `com.puber.matching.schedulers.RetryUnmatchedRides` |
| Constant | `private static final long RETRY_INTERVAL_MS = 5_000L;` |
| Injection | `RideRepository`, `MatchEngine` |
| Annotation | `@Scheduled(fixedRate = RETRY_INTERVAL_MS)` |
| Method | `void execute()` |
| Logic | `var requested = rideRepository.findByStatus(REQUESTED)`; for each, `matchEngine.execute(ride.id())` — boolean result is ignored (no driver = stays REQUESTED). |

**`ExpireStaleRequests`**

| Item | Requirement |
|------|-------------|
| Location | `com.puber.matching.schedulers.ExpireStaleRequests` |
| Constants | `private static final long CHECK_INTERVAL_MS = 5_000L;` and `private static final long OFFER_TIMEOUT_SECONDS = 10L;` |
| Injection | `RideRepository`, `ReleaseDriver`, `MatchEngine` |
| Annotation | `@Scheduled(fixedRate = CHECK_INTERVAL_MS)` |
| Method | `void execute()` |
| Logic | `var expired = rideRepository.findExpiredMatches(Duration.ofSeconds(OFFER_TIMEOUT_SECONDS))`; for each, `releaseDriver.execute(ride)`; then immediately `matchEngine.execute(ride.id())` — try the next driver. |

#### 10. Exception shells (hierarchy, not flat)

Custom exceptions extend abstract base exceptions by HTTP status. The `@ControllerAdvice` catches the **base** class, so adding a new entity-specific exception never requires touching the handler.

**Base exceptions (defined once per service, reused by all domain exceptions):**

```java
public abstract class NotFoundException extends RuntimeException {
    protected NotFoundException(String message) { super(message); }
}

public abstract class ConflictException extends RuntimeException {
    protected ConflictException(String message) { super(message); }
}
```

**Concrete exceptions in `matching-engine`:**

| Exception | Extends | Purpose |
|-----------|---------|---------|
| `RideNotFoundException` | `NotFoundException` | Ride ID does not exist. |
| `RideAlreadyMatchedException` | `ConflictException` | Ride is not in `REQUESTED` state when match is attempted. |
| `NoAvailableDriverException` | `RuntimeException` (optional) | `findNearestDriver` returned empty. Handled silently in `MatchEngine` (returns `false`), so no handler needed. |

#### 11. Concurrency: pessimistic row locking (`SELECT ... FOR UPDATE`)

V1 runs in a single Docker Compose stack, but concurrent HTTP requests and the `@Scheduled` task can race. We prevent double-booking with Postgres row-level pessimistic locks inside `@Transactional` boundaries.

| Race scenario | How it is prevented |
|---------------|---------------------|
| Two threads try to match the same ride simultaneously | `MatchEngine` loads the ride with `findByIdForUpdate` (ride row locked). The second thread blocks until the first commits, then sees status != `REQUESTED` and throws `RideAlreadyMatchedException`. |
| Two different rides compete for the same driver | `AssignDriver` re-locks the driver with `findByIdForUpdate` after `FindNearestDriver` returns. If the driver is now `BUSY`, the assignment aborts silently and the ride stays `REQUESTED`. |
| Scheduler expires a request while the driver is accepting it | `ReleaseDriver` locks the driver row before setting `AVAILABLE`; `AcceptRide` locks the ride row before setting `IN_PROGRESS`. The slower thread blocks, then sees the state has already moved and throws or skips. |
| Scheduler retries a ride while an HTTP match is in progress | Same as scenario 1 — the ride row lock serializes the two `MatchEngine` calls. |

> **Why not advisory locks in V1?** Advisory locks are better for distributed locking across multiple JVMs / Kafka consumers. In V1, `FOR UPDATE` is simpler, idiomatic, and teaches SQL transaction depth. We revisit distributed locking when Kafka arrives in Month 3.

### Acceptance criteria (done = all true)

1. `cd services/matching-engine && ./gradlew test` compiles and passes (context-load smoke test at minimum).
2. Flyway V1.2 migration applies cleanly; `rides.estimated_duration_minutes` exists and is nullable.
3. `Ride.create(...)` accepts `estimatedDurationMinutes`; all state-transition methods carry it forward.
4. `RideRepository` `SELECT` / `INSERT` / `UPSERT` statements include `estimated_duration_minutes`.
5. `FindNearestDriver.execute` returns the correct fixture driver when multiple are available (unit-style test via `@SpringBootTest` is fine).
6. `AssignDriver.execute` updates the DB so that `rides.status = 'MATCHED'`, `rides.driver_id = X`, `rides.matched_at` is set, and `drivers.status = 'BUSY'`.
7. `ReleaseDriver.execute` reverts the ride to `REQUESTED` and the driver to `AVAILABLE`.
8. `RetryUnmatchedRides` and `ExpireStaleRequests` beans are registered and their `@Scheduled` methods exist (a test can verify the beans are present; real timing tests are in PB-3.1.5).

### Explicitly out of scope for this subtask

- HTTP controllers or inter-service clients.
- Rider-api changes.
- Driver-api changes.
- Simulator fixture.

---

## PB-3.1.2 — Matching-Engine Internal Endpoints + Driver-Request HTTP Client

### Goal

Expose the matching logic via internal REST endpoints and implement the outbound HTTP call from `matching-engine` to `driver-api` to push ride requests.

### What to deliver

#### 1. `DriverRequestClient` (inter-service HTTP)

| Item | Requirement |
|------|-------------|
| Location | `com.puber.matching.clients.DriverRequestClient` |
| Tech | Spring `RestTemplate` or `WebClient`. For determinism in tests, `RestTemplate` is simpler to mock with `MockRestServiceServer`. |
| Config | `driver-api.base-url` in `application.yml` (default `http://localhost:8082`). |
| Method | `void sendRequest(UUID driverId, UUID rideId, Location pickup, long etaMinutes)` |
| HTTP | `POST {baseUrl}/internal/drivers/{driverId}/request` with body: `{ "rideId": "...", "pickupLat": ..., "pickupLng": ..., "etaMinutes": ... }`. |

#### 2. `MatchingEngineController` — thin HTTP adapter

The controller does not orchestrate. It validates the request shape, delegates to a single service, and maps exceptions to HTTP status codes via `@ControllerAdvice`.

| Item | Requirement |
|------|-------------|
| Path | `POST /internal/match` |
| Body | `{ "rideId": "uuid" }` |
| Logic | `var matched = matchEngine.execute(rideId)`; if `matched` → `200 OK`; else → `202 Accepted`. All validation (ride exists, status is `REQUESTED`) lives inside `MatchEngine` and surfaces as exceptions caught by `@ControllerAdvice`. |
| Path | `POST /internal/rides/{id}/accept` |
| Body | `{ "driverId": "uuid" }` |
| Logic | `acceptRide.execute(rideId)`; `200 OK`. Validation (ride exists, status is `MATCHED`) lives inside `AcceptRide`. |
| Path | `POST /internal/rides/{id}/complete` |
| Body | `{ "driverId": "uuid" }` |
| Logic | `completeRide.execute(rideId)`; `200 OK`. Validation (ride exists, status is `IN_PROGRESS`) lives inside `CompleteRide`. |

#### 3. Exception handler (`matching-engine`)

The handler catches **base** exceptions, not concrete ones. Adding a new entity-specific exception never requires touching this class.

| Item | Requirement |
|------|-------------|
| Class | `@ControllerAdvice` in `com.puber.matching.exceptions` |
| Handler | `@ExceptionHandler(NotFoundException.class)` → `404 Not Found` with `ErrorResponse`. |
| Handler | `@ExceptionHandler(ConflictException.class)` → `409 Conflict` with `ErrorResponse`. |

#### 4. Functional tests: `MatchingEngineInternalEndpointsTest`

| Test | What it asserts |
|------|----------------|
| `POST /internal/match` valid ride | Seeds a `REQUESTED` ride, mocks `DriverRequestClient` (verifies it is called with correct args), calls endpoint, asserts `200`, DB shows `MATCHED` + `BUSY`. |
| `POST /internal/match` no driver available | Sets all drivers `OFFLINE`, seeds `REQUESTED` ride, calls endpoint, asserts `202 Accepted`, ride stays `REQUESTED`, mock client never called. |
| `POST /internal/match` already matched | Seeds `MATCHED` ride, calls endpoint, asserts `409`. |
| `POST /internal/rides/{id}/accept` | Seeds `MATCHED` ride, calls endpoint, asserts `200`, DB shows `IN_PROGRESS`. |
| `POST /internal/rides/{id}/complete` | Seeds `IN_PROGRESS` ride, calls endpoint, asserts `200`, DB shows `COMPLETED` + driver `AVAILABLE`. |

### Acceptance criteria (done = all true)

1. `cd services/matching-engine && ./gradlew test` compiles and passes.
2. `POST /internal/match` with a `REQUESTED` ride returns `200` and triggers a mocked `DriverRequestClient` call.
3. `POST /internal/match` when no driver is available returns `202` and does not change ride status.
4. `POST /internal/rides/{id}/accept` transitions ride to `IN_PROGRESS`.
5. `POST /internal/rides/{id}/complete` transitions ride to `COMPLETED` and driver to `AVAILABLE`.

---

## PB-3.1.3 — Driver-api: Request Storage, Accept/Complete Proxy, and Availability

### Goal

`driver-api` becomes an active participant: it receives requests from `matching-engine`, holds them in memory, and proxies driver accept/complete actions back to `matching-engine`.

### What to deliver

#### 1. `MatchingEngineClient` (inter-service HTTP)

| Item | Requirement |
|------|-------------|
| Location | `com.puber.driver.clients.MatchingEngineClient` |
| Tech | `RestTemplate` or `WebClient`. |
| Config | `matching-engine.base-url` in `application.yml` (default `http://localhost:8080`). |
| Methods | `void acceptRide(UUID rideId, UUID driverId)` → `POST {baseUrl}/internal/rides/{rideId}/accept`; `void completeRide(UUID rideId, UUID driverId)` → `POST {baseUrl}/internal/rides/{rideId}/complete`. |

#### 2. In-memory request repository

YAGNI — only one implementation exists in V1. When Redis arrives in Month 3+, extract an interface then (OCP via "extract interface" refactoring, not upfront abstraction).

| Item | Requirement |
|------|-------------|
| Class | `com.puber.driver.repositories.DriverRequestRepository` — `@Repository` class wrapping `ConcurrentHashMap<UUID, DriverRequest>`. |
| Methods | `void save(UUID driverId, DriverRequest request)`; `Optional<DriverRequest> findByDriverId(UUID driverId)`; `void deleteByDriverId(UUID driverId)` |
| TTL | No TTL needed in V1 — the 10-second timeout lives in `matching-engine`'s scheduler. `driver-api` simply holds the latest request. |

**`DriverRequest` record:** `{ UUID rideId, double pickupLat, double pickupLng, long etaMinutes }`

#### 3. `RequestDriver` service

| Item | Requirement |
|------|-------------|
| Location | `com.puber.driver.services.RequestDriver` |
| Injection | `DriverRequestRepository` |
| Method | `void execute(UUID driverId, DriverRequest request)` |
| Logic | `driverRequestRepository.save(driverId, request)`. |

> The controller is a thin HTTP adapter: it delegates to `requestDriver.execute(driverId, request)`. No storage logic lives in the controller.

#### 4. `DriverApiController` — new endpoints

| Item | Requirement |
|------|-------------|
| `POST /internal/drivers/{id}/request` | Body = `DriverRequest`. `requestDriver.execute(id, request)`. `200 OK`. |
| `POST /drivers/{id}/availability` | Body = `{ "status": "AVAILABLE" \| "OFFLINE" \| "BUSY" }`. `driverRepository.updateStatus(id, status)`. `200 OK`. Return `404` if driver not found. |
| `POST /rides/{id}/accept` | Body = `{ "driverId": "uuid" }`. (1) `driverRequestRepository.findByDriverId(driverId)`; if empty → `409` (no active request); (2) verify `request.rideId()` equals path `id`; if not → `409`; (3) `matchingEngineClient.acceptRide(rideId, driverId)`; (4) `driverRequestRepository.deleteByDriverId(driverId)`; (5) `200 OK`. |
| `POST /rides/{id}/complete` | Body = `{ "driverId": "uuid" }`. (1) `driverRepository.findById(driverId)`; if empty → `404`; (2) `matchingEngineClient.completeRide(rideId, driverId)`; (3) `200 OK`. |

#### 4. `UpdateAvailability` service

| Item | Requirement |
|------|-------------|
| Location | `com.puber.driver.services.UpdateAvailability` |
| Injection | `DriverRepository` |
| `@Transactional` | **Yes** |
| Method | `execute(UUID driverId, DriverStatus status)` |
| Logic | Validate driver exists; `updateStatus(id, status)`. |

#### 5. Exception handler (`driver-api`)

The same hierarchy approach as `matching-engine`. The handler catches base exceptions; concrete exceptions extend them.

| Item | Requirement |
|------|-------------|
| Refactor | `DriverNotFoundException` (from PB-2.1) now extends `NotFoundException`. |
| New exception | `NoActiveRequestException` — extends `ConflictException`. |
| Handler | `@ExceptionHandler(NotFoundException.class)` → `404 Not Found`. |
| Handler | `@ExceptionHandler(ConflictException.class)` → `409 Conflict`. |

#### 6. Functional tests: `DriverApiMatchingFlowTest`

| Test | What it asserts |
|------|----------------|
| `POST /internal/drivers/{id}/request` | Stores request; a subsequent `findByDriverId` returns it. |
| `POST /drivers/{id}/availability` | Valid fixture UUID + `AVAILABLE` → `200`; DB row updated. |
| `POST /rides/{id}/accept` no request | Returns `409`. |
| `POST /rides/{id}/accept` with request | Mocks `MatchingEngineClient` (verifies call), asserts `200`. |

> **Note:** Tests for `POST /rides/{id}/accept` and `POST /rides/{id}/complete` mock `MatchingEngineClient` because `matching-engine` is not running in `driver-api`'s test JVM. The real cross-service wire is verified in PB-3.1.5 via Docker or the simulator fixture.

### Acceptance criteria (done = all true)

1. `cd services/driver-api && ./gradlew test` compiles and passes.
2. `POST /internal/drivers/{id}/request` stores a request in `DriverRequestRepository`.
3. `POST /drivers/{id}/availability` updates `drivers.status` in Postgres.
4. `POST /rides/{id}/accept` with a stored request proxies to mocked `MatchingEngineClient` and returns `200`.
5. `POST /rides/{id}/accept` without a stored request returns `409`.

---

## PB-3.1.4 — Rider-api Triggers Matching After Ride Creation

### Goal

After `rider-api` persists a new `REQUESTED` ride, it immediately tells `matching-engine` to start matching via HTTP.

### What to deliver

#### 1. `MatchingEngineClient` in `rider-api`

| Item | Requirement |
|------|-------------|
| Location | `com.puber.rider.clients.MatchingEngineClient` |
| Tech | `RestTemplate` or `WebClient`. |
| Config | `matching-engine.base-url` in `application.yml` (default `http://localhost:8080`). |
| Method | `void triggerMatch(UUID rideId)` → `POST {baseUrl}/internal/match` with body `{ "rideId": "..." }`. |

#### 2. Update `RequestRide` service

| Item | Requirement |
|------|-------------|
| `estimatedDurationMinutes` | Compute from the same haversine distance used for fare: `distanceKm = haversineKm(pickup, dropoff)`; `estimatedMinutes = Math.round(distanceKm / 0.5)`; pass into `Ride.create(...)`. This is the same value already returned by `EstimateFare` (trip duration, not driver ETA). |
| `RideResponse` | Add `estimatedDurationMinutes` field. `GET /rides/{id}` now returns it. |
| Trigger matching | After `rideRepository.save(ride)` and before returning, call `matchingEngineClient.triggerMatch(ride.id())`. |
| Failure handling | If the HTTP call fails (e.g., connection refused in local dev), log a `WARN` but do **not** roll back the transaction — the `@Scheduled` retry in `matching-engine` will eventually pick up the `REQUESTED` ride. |

#### 3. Functional test update: `RequestRideTest`

| Test | What it asserts |
|------|----------------|
| `POST /rides` still creates ride | Same assertions as PB-2.1 (`201`, `REQUESTED`, `fare > 0`), plus `estimatedDurationMinutes > 0`. |
| `POST /rides` triggers match | Mock `MatchingEngineClient`; verify `triggerMatch(rideId)` is called exactly once with the newly created ride's UUID. |

### Acceptance criteria (done = all true)

1. `cd services/rider-api && ./gradlew test` compiles and passes.
2. `POST /rides` still returns `201 Created` with the ride payload; response includes `estimatedDurationMinutes > 0`.
3. `RequestRide` invokes `matchingEngineClient.triggerMatch(rideId)` after DB insert.
4. If `triggerMatch` throws (simulated mock exception), the ride is still persisted and the test passes (exception is caught and logged).
5. `GET /rides/{id}` for a newly created ride returns `estimatedDurationMinutes` equal to the value from `POST /rides`.

---

## PB-3.1.5 — Simulator Fixture + Functional Integration Tests

### Goal

Prove the matching flow end-to-end with a deterministic simulator test fixture. The fixture exercises `matching-engine` directly (via repositories and `TestRestTemplate`) so no sibling service needs to boot in the same JVM.

### What to deliver

#### 1. `PuberSimulator` test fixture

| Item | Requirement |
|------|-------------|
| Location | `src/test/java/com/puber/matching/simulator/PuberSimulator.java` inside `matching-engine` |
| Annotations | `@Component` (test-scoped bean, not `@TestComponent` unless you need profile filtering) |
| Injection | `RideRepository`, `DriverRepository`, `MatchEngine`, `AcceptRide`, `CompleteRide`, `ReleaseDriver`, `RetryUnmatchedRides`, `ExpireStaleRequests`, `TestRestTemplate` (optional) |
| Seeded `Random` | `new Random(12345)` for deterministic coordinates. |
| Known driver UUIDs | Same 10 fixture UUIDs from `puber.md` and PB-2.1. |
| `requestRide(UUID riderId, Location pickup, Location dropoff)` | Computes `distanceKm = haversineKm(pickup, dropoff)` and `estimatedMinutes = Math.round(distanceKm / 0.5)`. Saves a `Ride` directly via `RideRepository` (status `REQUESTED`, including `estimatedDurationMinutes`), then calls `matchEngine.execute(ride.id())` or calls `POST /internal/match` via `TestRestTemplate`. |
| `setDriverStatus(UUID driverId, DriverStatus status)` | Loads driver via `driverRepository.findById(driverId)`, applies the domain transition method (e.g., `driver.setAvailable()`), and calls `driverRepository.save(updated)`. |
| `acceptRequest(UUID driverId, UUID rideId)` | Calls `acceptRide.execute(rideId)` directly or via `TestRestTemplate` on `POST /internal/rides/{id}/accept`. |
| `completeRide(UUID driverId, UUID rideId)` | Calls `completeRide.execute(rideId)` directly or via `TestRestTemplate`. |

> **Why inside `matching-engine` tests?** The fixture needs direct access to repositories and services to set up deterministic state. Cross-service HTTP tests require all three services to be running simultaneously; that is out of scope for V1 unit/functional tests and is validated manually via Docker Compose or in Week 5.

#### 2. Functional tests

| Test class | What it asserts |
|-----------|----------------|
| `EndToEndMatchTest` | (1) Seed 3 drivers `AVAILABLE`; (2) simulator `requestRide`; (3) assert `rides.status = MATCHED`, `drivers.status = BUSY` for the nearest driver; (4) assert `DriverRequestClient` mock received the request call. |
| `NoDriverAvailableRetryTest` | (1) Set all 10 drivers `OFFLINE`; (2) simulator `requestRide`; (3) assert ride is `REQUESTED`; (4) flip one driver to `AVAILABLE`; (5) manually invoke `retryUnmatchedRides.execute()` or wait with `Awaitility.atMost(6, SECONDS)` for the `@Scheduled` task; (6) assert ride is `MATCHED`. |
| `RequestTimeoutTest` | (1) Create ride, match to driver D-1; (2) do **not** accept; (3) advance time by mocking the `Clock` bean OR wait `12` seconds with `Awaitility`; (4) assert ride is `REQUESTED` again, D-1 is `AVAILABLE`; (5) assert D-2 receives the next request (mock client verification). |
| `RaceConditionTest` | (1) One driver available; (2) two concurrent `requestRide` calls (e.g., `@RepeatedTest(10)` with parallel threads); (3) assert exactly one ride is `MATCHED`, the other stays `REQUESTED`. |

**Mocking the clock for timeout tests:**
If you want to avoid real 12-second sleeps, inject a `java.time.Clock` bean:
```java
@Bean
public Clock clock() { return Clock.systemUTC(); }
```
In tests, replace it with `Clock.fixed(...)` and invoke the scheduler method directly. If you prefer real time, `Awaitility.await().atMost(12, SECONDS).until(...)` is acceptable.

### Acceptance criteria (done = all true)

1. `cd services/matching-engine && ./gradlew test` passes all of the above tests.
2. `EndToEndMatchTest` asserts `rides.status = MATCHED` and `drivers.status = BUSY` after a single ride request.
3. `NoDriverAvailableRetryTest` asserts a `REQUESTED` ride becomes `MATCHED` after a driver flips to `AVAILABLE` and the scheduler runs.
4. `RequestTimeoutTest` asserts a stale `MATCHED` request reverts to `REQUESTED` and the driver returns to `AVAILABLE`.
5. `RaceConditionTest` asserts no double-booking of a single available driver.
6. `PuberSimulator` is committed as a reusable test-scoped `@Component`.

---

## Explicitly out of scope for the whole ticket

- `GET /rides/history` (Week 7/8).
- `GET /rides/{id}` changes — it already works from PB-2.1; no new DTO fields needed yet.
- WebSocket request streaming (`/drivers/{id}/stream`) — Month 5+.
- Kafka, Redis, or any message broker.
- Resilience4j, retries with jitter, or circuit breakers on HTTP clients.
- Docker Compose cross-service integration test that boots all three services simultaneously (validate manually if desired, but the automated test suite stays per-service).
- Prometheus metrics or custom Actuator endpoints.
- Cloud deployment, K8s, CI/CD.
- Full simulator as a standalone container or multi-threaded process (Month 3+).

---

## Suggested completion note

> Shipped the in-memory matching engine with nearest-driver haversine search, 5-second `@Scheduled` retry for unmatched rides, and 10-second request timeout with next-driver fallback. Wired the first inter-service HTTP calls: `rider-api → matching-engine /internal/match`, `matching-engine → driver-api /internal/drivers/{id}/request`, and `driver-api → matching-engine /internal/rides/{id}/accept|complete`. `driver-api` gained `POST /drivers/{id}/availability` and in-memory request storage. Added `PickupEtaCalculator` and proved the full state machine with a deterministic `PuberSimulator` test fixture: ride requests → `MATCHED` + `BUSY`, no-driver retry, request timeout, and concurrent race-condition guard.

---

## Next ticket

- [PB-4.1](pb-4.1.md) — Flyway V2 additive migration (`rides.estimated_duration_seconds` nullable), clean DB from scratch, verify V1 + V2 apply in order and fixtures re-seed
