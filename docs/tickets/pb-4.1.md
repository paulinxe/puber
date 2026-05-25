# PB-4.1 — Ride Cancellation: Flyway V2 `rides.cancelled_at`, State-Machine Guard, and Releasing a Matched Driver

| Field | Value |
|--------|--------|
| **ID** | PB-4.1 |
| **Phase** | Months 1–2 — Bootstrap + Domain + Matching |
| **Week** | 4 — Cancellation Flow + Expand-Only Schema Evolution |
| **Source** | [puber.md §Schedule — Week 4](../puber.md) |
| **Depends on** | [PB-3.1](pb-3.1.md) |

---

## Goal

Implement rider-side ride cancellation. Deliver `POST /rides/{id}/cancel` with a state-machine guard: a ride can only be cancelled while its status is `REQUESTED` or `MATCHED`. Cancelling a `MATCHED` ride must atomically reset the driver to `AVAILABLE`. The schema change is a Flyway V2 additive migration (`rides.cancelled_at TIMESTAMPTZ NULL`) — no breaking changes, no `NOT NULL`, no column drops.

This ticket is split into **2 sequential subtasks**. Work through them in order.

---

## Context and constraints (apply to all subtasks)

- **Expand-only migrations** — New columns must be `NULL`able. No `ALTER ... DROP COLUMN`, no `RENAME`, no changing existing `CHECK` constraints. Old code must still work against the new schema.
- **Cancellation rule** — Only the rider can cancel, and only while `status IN ('REQUESTED', 'MATCHED')`. Cancelling `IN_PROGRESS` or `COMPLETED` is rejected.
- **Matched-ride cancellation** — If `status = MATCHED`, the cancellation must release the driver: `drivers.status = AVAILABLE` and `rides.driver_id = NULL` (via `resetToRequested()` domain method already shipped in PB-3.1).
- **No auth** — `riderId` is passed in the request body for simplicity; compare it to `rides.rider_id`.
- **Shared Flyway files** — V2 lives in `src/main/resources/db/migration/` inside `matching-engine`, `rider-api`, and `driver-api`. Identical files in all three services.
- **Immutable domain objects** — `Ride.markCancelled(Instant cancelledAt)` returns a new instance with `status = CANCELLED`, `cancelledAt = cancelledAt`, `updatedAt = now()`. If the ride was `MATCHED`, the driver is released via the existing `ReleaseDriver` service (PB-3.1).
- **No ORM** — explicit SQL with `JdbcTemplate`, immutable domain classes, `org.jspecify:jspecify:1.0.0` null-safety.
- **Functional tests over unit tests** — `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` + real Postgres.

---

## Subtask overview

| # | ID | Title | What it delivers | Approx time |
|---|-----|-------|------------------|-------------|
| 1 | **PB-4.1.1** | V2 migration + domain + cancel service | Flyway V2 SQL, updated `Ride` domain object, `CancelRide` service, exception shells | ~1h |
| 2 | **PB-4.1.2** | Endpoint + tests + clean-DB verification | `POST /rides/{id}/cancel`, `RideResponse` with `cancelledAt`, functional tests, tear-down / rebuild check | ~1h 30m |

---

## PB-4.1.1 — V2 Migration, Domain Update, and `CancelRide` Service

### Goal

Add the cancellation column, update the immutable `Ride` domain object, and implement the cancellation business logic with proper state-machine guards.

### What to deliver

#### 1. Flyway V2 migration

| Item | Requirement |
|------|-------------|
| `V2__add_cancelled_at.sql` | `ALTER TABLE rides ADD COLUMN cancelled_at TIMESTAMPTZ NULL;` |
| Location | `src/main/resources/db/migration/` inside `matching-engine`, `rider-api`, and `driver-api`. Identical files in all three services. |
| No data backfill | Intentionally leave existing rows `NULL`. This proves the expand-only rule: old data is valid, new code handles `NULL`. |

#### 2. Domain object update: `Ride`

| Item | Requirement |
|------|-------------|
| New field | `final @Nullable Instant cancelledAt` |
| Constructor | Updated to accept `cancelledAt`. |
| `create(...)` | Signature unchanged from PB-3.1; `cancelledAt` is `null` for new rides. |
| `from(...)` | Updated to read `rs.getObject("cancelled_at", Instant.class)` (handle `null`) and pass it to the constructor. |
| `markCancelled(Instant cancelledAt)` | Returns new `Ride` with `status = CANCELLED`, `cancelledAt = cancelledAt`, `updatedAt = now()`. Preserves `estimatedDurationMinutes`. |
| State transitions | `assignDriver`, `resetToRequested`, `markInProgress`, `markCompleted` all preserve `cancelledAt` (always `null` on those paths, but carried forward for completeness). |

#### 3. Repository SQL updates (`rider-api` and `matching-engine`)

All `RideRepository` methods that touch `rides` columns are updated to include `cancelled_at`.

| Service | Repository | Change |
|---------|-----------|--------|
| `rider-api` | `RideRepository.save(Ride ride)` | `INSERT` now includes `cancelled_at` and binds `ride.cancelledAt()`. |
| `rider-api` | `RideRepository.findById(UUID id)` | `SELECT` includes `r.cancelled_at`. `RowMapper` updated. |
| `matching-engine` | `RideRepository.save(Ride ride)` | `INSERT ... ON CONFLICT ... DO UPDATE ...` includes `cancelled_at` in both clauses. |
| `matching-engine` | `RideRepository.findById(UUID id)` | `SELECT` includes `r.cancelled_at`. `RowMapper` updated. |
| `matching-engine` | `RideRepository.findByStatus(...)` | `SELECT` includes `r.cancelled_at`. `RowMapper` updated. |
| `matching-engine` | `RideRepository.findExpiredMatches(...)` | `SELECT` includes `r.cancelled_at`. `RowMapper` updated. |
| `matching-engine` | `RideRepository.findByIdForUpdate(...)` | `SELECT` includes `r.cancelled_at`. `RowMapper` updated. |

> `driver-api` does not query `rides` directly in V1, but the migration file must still be present so it can boot against a fresh database.

#### 4. `CancelRide` service (`matching-engine` only)

| Item | Requirement |
|------|-------------|
| Location | `com.puber.matching.services.CancelRide` |
| Injection | `RideRepository`, `ReleaseDriver` (from PB-3.1) |
| `@Transactional` | **Yes** — cancellation + driver release must be atomic in a single Postgres transaction. |
| Method | `Ride execute(UUID rideId, UUID riderId)` |
| Logic | (1) Load ride via `rideRepository.findByIdForUpdate(rideId)`; if not found throw `RideNotFoundException`. (2) If `ride.riderId()` does not equal `riderId`, throw `RideNotFoundException` (do not leak existence). (3) If `ride.status()` is `IN_PROGRESS` or `COMPLETED` or `CANCELLED`, throw `RideCannotBeCancelledException`. (4) If `ride.status() = MATCHED`, call `releaseDriver.execute(ride)` to reset driver to `AVAILABLE` and ride to `REQUESTED`. (5) `var cancelled = ride.markCancelled(Instant.now())` (or `ride.resetToRequested().markCancelled(...)` if step 4 already reset it). (6) `rideRepository.save(cancelled)`; (7) return cancelled ride. |

> **Why in `matching-engine`?** `matching-engine` is the only service that holds both `RideRepository` and `DriverRepository` in the same JVM, so the cancellation + driver-release can be wrapped in a single `@Transactional` boundary. `rider-api` does not own driver state.

#### 5. Exception shells (`matching-engine`)

| Exception | Extends | Purpose |
|-----------|---------|---------|
| `RideCannotBeCancelledException` | `ConflictException` | Ride is `IN_PROGRESS`, `COMPLETED`, or already `CANCELLED`. |

### Acceptance criteria (done = all true)

1. `cd services/matching-engine && ./gradlew test` compiles and passes.
2. `cd services/rider-api && ./gradlew test` compiles and passes.
3. Flyway V2 migration applies cleanly; `rides.cancelled_at` exists and is nullable.
4. `Ride.markCancelled(...)` returns a new immutable instance with `status = CANCELLED`.
5. `CancelRide` in `matching-engine` rejects cancellation of `IN_PROGRESS` and `COMPLETED` rides with a `409 Conflict`.
6. `rider-api` `MatchingEngineClient` has `cancelRide(...)` method that `POST`s to `matching-engine` `/internal/rides/{id}/cancel`.

### Explicitly out of scope for this subtask

- HTTP controller or endpoint.
- Functional tests against the HTTP endpoint.
- Clean-DB teardown / rebuild verification.

---

## PB-4.1.2 — Endpoint, Response DTO, Functional Tests, and Clean-DB Verification

### Goal

Expose cancellation via the public API, return `cancelledAt` in ride responses, and prove the schema evolution is reproducible from a clean database.

### What to deliver

#### 1. `MatchingEngineClient` extension in `rider-api`

| Item | Requirement |
|------|-------------|
| Existing client | `com.puber.rider.clients.MatchingEngineClient` (from PB-3.1.4) |
| New method | `void cancelRide(UUID rideId, UUID riderId)` → `POST {baseUrl}/internal/rides/{rideId}/cancel` with body `{ "riderId": "..." }`. |

#### 2. `RiderApiController` — `POST /rides/{id}/cancel`

| Item | Requirement |
|------|-------------|
| Path | `POST /rides/{id}/cancel` where `{id}` is a UUID. |
| Body | `{ "riderId": "uuid" }` |
| Delegation | `matchingEngineClient.cancelRide(id, dto.riderId())` |
| Response | `200 OK` with updated `RideResponse` JSON (status `CANCELLED`, `cancelledAt` set). |
| Errors | `404` if ride not found or riderId mismatch. `409` if ride cannot be cancelled. |
| Failure handling | If the HTTP call fails (connection refused), log `WARN`. |

#### 3. `MatchingEngineController` — `POST /internal/rides/{id}/cancel`

| Item | Requirement |
|------|-------------|
| Path | `POST /internal/rides/{id}/cancel` where `{id}` is a UUID. |
| Body | `{ "riderId": "uuid" }` |
| Delegation | `cancelRide.execute(rideId, dto.riderId())` |
| Response | `200 OK` with cancelled ride JSON. |
| Errors | `404` → `RideNotFoundException` (including riderId mismatch). `409` → `RideCannotBeCancelledException`. |

#### 4. `RideResponse` DTO update

| DTO | Change |
|-----|--------|
| `RideResponse` | Add `@Nullable Instant cancelledAt` field. |

#### 5. `GetRide` service update

Map `ride.cancelledAt()` into `RideResponse`.

#### 6. Functional tests: `CancelRideTest`

| Test | What it asserts |
|------|----------------|
| `POST /rides/{id}/cancel` on `REQUESTED` ride | Mock `MatchingEngineClient`; assert `cancelRide(...)` called with correct args; assert `200 OK` response contains `status = CANCELLED`, `cancelledAt IS NOT NULL`. |
| `POST /rides/{id}/cancel` on `MATCHED` ride | Mock `MatchingEngineClient`; assert `cancelRide(...)` called; assert `200 OK`; driver release happens inside `matching-engine` (verified in matching-engine tests). |
| `POST /rides/{id}/cancel` when matching-engine returns `409` | Rider-api propagates `409 Conflict`. |
| `POST /rides/{id}/cancel` when matching-engine returns `404` | Rider-api propagates `404 Not Found`. |
| `POST /rides/{id}/cancel` failure handling | Simulate `MatchingEngineClient` throwing `RuntimeException`; assert rider-api logs `WARN` and still returns `200` (HTTP call is best-effort, not transactional). |

#### 7. Clean-DB verification

| Step | Command / Action |
|------|------------------|
| 1. Stop and remove | `docker compose -f infra/docker-compose.yml down -v` |
| 2. Start fresh | `docker compose -f infra/docker-compose.yml up -d postgres rider-api` |
| 3. Inspect Flyway history | `SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;` |
| 4. Verify fixtures | `SELECT COUNT(*) FROM drivers;` → `10`. `SELECT COUNT(*) FROM fare_rules;` → `1`. |

| Assertion | Expected result |
|-----------|-----------------|
| `flyway_schema_history` | V1, V1.1, V1.2, V2 in order, all `success = true`. |
| `\d rides` | Shows `cancelled_at` column with type `timestamptz` and no `NOT NULL`. |
| `\d drivers` / `\d fare_rules` | Unchanged from V1. |

#### 8. Mixed-migration data test

| Test | What it asserts |
|------|----------------|
| `MixedMigrationDataTest` (new) | (1) Create a ride via `POST /rides`. (2) `GET /rides/{id}` shows `cancelledAt = null`. (3) Cancel it via `POST /rides/{id}/cancel`. (4) `GET /rides/{id}` shows `cancelledAt IS NOT NULL`. This proves old and new data coexist. |

### Acceptance criteria (done = all true)

1. `docker compose -f infra/docker-compose.yml down -v && docker compose -f infra/docker-compose.yml up -d postgres rider-api` boots without Flyway errors.
2. `flyway_schema_history` contains V1, V1.1, V1.2, and V2 in order with `success = true`.
3. `drivers` table has 10 rows and `fare_rules` has 1 row (fixtures re-seeded).
4. `rides.cancelled_at` column exists and is nullable.
5. `cd services/rider-api && ./gradlew test` passes — `CancelRideTest` mocks `MatchingEngineClient` and asserts the controller delegates correctly.
6. `cd services/matching-engine && ./gradlew test` passes — `CancelRide` service tested with real Postgres (ride + driver atomic release).

### Explicitly out of scope for this subtask

- Automated CI pipeline (out of scope for all Month 1–2 tickets).
- Data backfill script for V1 rows.
- Making `cancelled_at` `NOT NULL` (that would be a V3 contract change, not expand-only).
- Driver-side cancellation (not supported in V1).

---

## Explicitly out of scope for the whole ticket

- Modifying the fare-calculation formula or `FareRule` table structure.
- Dropping, renaming, or changing the type of any existing column.
- Adding `CHECK` constraints that would invalidate existing rows.
- Making the new column `NOT NULL` or adding a `DEFAULT` value (defeats expand-only).
- Kafka, Redis, WebSockets, or message brokers.
- Inter-service HTTP client changes (the internal endpoints do not need new fields).
- Simulator threads or standalone simulator container.
- Cloud deployment, K8s, or CI/CD.

---

## Suggested completion note

> Shipped Flyway V2 additive migration (`rides.cancelled_at TIMESTAMPTZ NULL`) across all three Spring Boot services, following expand-only schema-evolution rules. Added `Ride.markCancelled(...)` to the immutable domain model and updated all `RideRepository` SQL statements. Implemented `CancelRide` service in `matching-engine` (the only service that can atomically update both `rides` and `drivers` in a single transaction) with state-machine guards: riders can cancel `REQUESTED` or `MATCHED` rides; cancelling a `MATCHED` ride releases the driver back to `AVAILABLE` via the existing `ReleaseDriver` service. `rider-api` exposes `POST /rides/{id}/cancel` but delegates to `matching-engine` via HTTP, consistent with the V1 inter-service pattern. Verified clean-database boot: V1 schema + fixtures, V1.2 `estimated_duration_minutes`, then V2 `cancelled_at`, all apply in order with `success = true` in `flyway_schema_history`. Backward compatibility proven with mixed-data tests.

---

## Next ticket

- [PB-5.1](pb-5.1.md) — Spring Boot skeleton polish for `rider-api` and `driver-api`; HTTP inter-service call hardening (`/internal/offer`, retries, timeouts); root README + per-service README + `docs/architecture.md` stub

(End of file)
