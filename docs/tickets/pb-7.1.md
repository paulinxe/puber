# PB-7.1 — Query Tuning: Simulator-Scale Data, EXPLAIN ANALYZE, and Indexing

| Field | Value |
|--------|--------|
| **ID** | PB-7.1 |
| **Phase** | Months 1–2 — Bootstrap + Domain + Matching |
| **Week** | 7 — Query Tuning |
| **Source** | [puber.md §Schedule — Week 7](../puber.md) |
| **Depends on** | [PB-6.1](pb-6.1.md) |

---

## Goal

Generate enough realistic data that Postgres query plans show meaningful costs (not instant sequential scans on 10-row fixture tables). Then introduce `GET /rides/history` — the first genuinely query-heavy endpoint — and `EXPLAIN ANALYZE` the three hottest query shapes. Add one missing index, measure the improvement, and publish the before/after latency artefacts in `docs/sql/`.

This ticket is split into **3 sequential subtasks**. Work through them in order.

---

## Context and constraints

- **"Seed simulator data" defined** — This means producing a SQL script that inserts ~500 drivers, ~5,000 rides, and ~50,000 `driver_locations` using `generate_series`. The script is **not** a Flyway migration (we do not want 50k rows on every test boot). It lives in `docs/sql/` and is run manually against the Docker Compose Postgres for query-analysis sessions.
- **No host `psql` required** — The script can be piped into the Postgres container: `docker exec -i <postgres-container> psql -U puber -d puber < docs/sql/05-seed-simulator-data.sql`.
- **`EXPLAIN (ANALYZE, BUFFERS)`** — Every `EXPLAIN` command must use `ANALYZE` and `BUFFERS` so the output includes actual execution time and buffer hits, not just the planner's cost estimate.
- **One index only** — The chosen query is **Query 2: Ride history** (`GET /rides/history`). The chosen index is `CREATE INDEX idx_rides_rider_id_requested_at ON rides(rider_id, requested_at DESC);`. It is a composite index that covers both the `WHERE r.rider_id = ?` predicate and the `ORDER BY r.requested_at DESC` sort, which typically produces the most dramatic plan change (eliminates the separate Sort node). The other two queries (available drivers, active-ride guard) are analysed but left unindexed; their index targets are documented for future tickets.
- **History endpoint is read-only** — `GET /rides/history` returns past rides; it does not mutate state. Use the existing `RideRepository` pattern (`JdbcTemplate` + `RowMapper` + `LEFT JOIN drivers`).
- **Functional tests over unit tests** — The history endpoint is validated by `@SpringBootTest` + `TestRestTemplate` against real Postgres, using the existing 10-row fixture set. The seed script is validated by a Docker-based assertion (row count), not a JUnit test.
- **No query count changes in Week 7** — If the naive implementation of history would trigger N+1 queries, the Week 7 ticket uses a single JOIN (the correct pattern). The deliberate N+1 bug and JOIN fix is the subject of Week 8.

---

## Subtask overview

| # | ID | Title | What it delivers | Approx time |
|---|-----|-------|------------------|-------------|
| 1 | **PB-7.1.1** | Seed script + history endpoint | `docs/sql/05-seed-simulator-data.sql`, `RideRepository.findByRiderId`, `RideRepository.hasActiveRide` (EXPLAIN target), `GET /rides/history` | ~1h 30m |
| 2 | **PB-7.1.2** | EXPLAIN documentation | `docs/sql/06-explain-analysis.md` with `EXPLAIN (ANALYZE, BUFFERS)` output for 3 queries | ~1h |
| 3 | **PB-7.1.3** | Index + before/after | One `CREATE INDEX` statement, re-run EXPLAIN, `docs/sql/07-index-tuning.md` with latency diff | ~1h 30m |

---

## PB-7.1.1 — Simulator-Scale Data Seeding + `GET /rides/history`

### Goal

Create a realistic dataset for query-plan analysis, and introduce the first read-heavy endpoint so there is something meaningful to `EXPLAIN`.

### What to deliver

#### 1. `docs/sql/05-seed-simulator-data.sql` — Simulator-scale seed script

| Item | Requirement |
|------|-------------|
| **Drivers** | Insert ~500 rows into `drivers` using `generate_series`. Use deterministic UUIDs (e.g., `uuid_generate_v4()` or seeded `md5`). Set `status` randomly to `AVAILABLE`, `OFFLINE`, or `BUSY`. Set `current_lat` / `current_lng` randomly inside the Lisbon demo bounds. |
| **Rides** | Insert ~5,000 rows into `rides` using `generate_series`. Reference random driver IDs from the seeded set (or `NULL` for `REQUESTED` rides). Set `status` randomly from the allowed enum. Set `rider_id` to random UUIDs. Set `pickup_lat/lng` and `dropoff_lat/lng` inside the demo bounds. Set `requested_at` to `NOW() - random interval`. |
| **Driver locations** | Insert ~50,000 rows into `driver_locations` using `generate_series`. Each driver gets ~100 location records with `recorded_at` spaced 2 seconds apart. |
| **Idempotency** | Script starts with `TRUNCATE drivers, rides, driver_locations CASCADE;` followed by re-insertion of the original 10 Flyway fixture drivers + 1 fare rule, then the bulk inserts. This guarantees the script can be re-run against the same database without duplication. |
| **Verification** | End the script with `SELECT 'drivers:' || COUNT(*) FROM drivers; SELECT 'rides:' || COUNT(*) FROM rides; SELECT 'locations:' || COUNT(*) FROM driver_locations;` so the runner sees the counts. |

> **Why not a Flyway migration?** Flyway migrations run on every test boot. 50k rows would slow `gradle test` to a crawl. The seed script is for **manual exploration** against the Docker Compose Postgres.

#### 2. `RideRepository` extension — history query

| Item | Requirement |
|------|-------------|
| **Location** | `rider-api` only |
| **Method** | `List<Ride> findByRiderId(UUID riderId, int limit)` |
| **SQL** | `SELECT r.*, d.id AS d_id, d.name AS d_name, d.status AS d_status, d.current_lat AS d_current_lat, d.current_lng AS d_current_lng, d.created_at AS d_created_at, d.updated_at AS d_updated_at FROM rides r LEFT JOIN drivers d ON r.driver_id = d.id WHERE r.rider_id = ? ORDER BY r.requested_at DESC LIMIT ?` |
| **RowMapper** | Re-use the existing `Ride` `RowMapper` logic from `findById`. The `LEFT JOIN` columns are aliased (`d_id`, `d_name`, etc.) to avoid name collisions with `rides` columns. If `driver_id` is `NULL`, pass `null` to the `Driver.from(...)` factory. |
| **Why not `matching-engine`?** | `matching-engine` never queries rides by `rider_id`. It operates on ride IDs and statuses. Duplicating this query there would violate SRP and create dead code. |

#### 3. `RideRepository` extension — active-ride guard query (EXPLAIN target)

| Item | Requirement |
|------|-------------|
| **Location** | `rider-api` only |
| **Method** | `boolean hasActiveRide(UUID riderId)` — already shipped in PB-2.1 |
| **SQL** | `SELECT COUNT(*) FROM rides WHERE rider_id = ? AND status NOT IN ('CANCELLED', 'COMPLETED')` |
| **Rationale** | This is one of the three EXPLAIN targets. It is actively called on every `POST /rides` request. On 5,000 rows it will likely `Seq Scan` because there is no index on `rider_id` + `status`. Documenting it here connects the EXPLAIN exercise to production code that actually runs. |
| **No index added this week** | The Week 7 index budget is already spent on the history query. The active-ride guard can reuse the `rider_id` prefix of `idx_rides_rider_id_requested_at` partially, but a dedicated `(rider_id, status)` index is deferred. |

#### 4. `GetRideHistory` service (`rider-api`)

| Item | Requirement |
|------|-------------|
| **Location** | `com.puber.rider.services.GetRideHistory` |
| **Injection** | `RideRepository` |
| **Method** | `List<RideHistoryItem> execute(UUID riderId, int limit)` |
| **Logic** | `rideRepository.findByRiderId(riderId, limit)`; map each `Ride` to a `RideHistoryItem` DTO. |
| **DTO** | `RideHistoryItem` record: `UUID id`, `RideStatus status`, `Location pickup`, `Location dropoff`, `BigDecimal fare`, `Instant requestedAt`, `@Nullable UUID driverId`, `@Nullable String driverName`. |

#### 5. `RiderApiController` — `GET /rides/history`

| Item | Requirement |
|------|-------------|
| **Path** | `GET /rides/history?riderId={uuid}&limit={n}` where `limit` defaults to `20` and has a maximum of `100`. |
| **Response** | `200 OK` with JSON array of `RideHistoryItem`. Empty array if the rider has no rides. |
| **No pagination cursor** | Simple offset-less limit for V1. A cursor-based approach can be added in Month 3+. |

#### 6. Functional test: `GetRideHistoryTest`

| Test | What it asserts |
|------|----------------|
| `GET /rides/history` for a rider with 3 rides | `200 OK`, array length `3`, ordered by `requestedAt` descending, first item has the most recent `requestedAt`. |
| `GET /rides/history?limit=2` | Array length `2`. |
| `GET /rides/history` for a rider with no rides | `200 OK`, empty array `[]`. |
| `GET /rides/history` with missing `riderId` | `400 Bad Request`. |

> **Note:** These tests use the existing 10-row fixture set. The 5,000-row seed script is for manual EXPLAIN sessions only.

### Acceptance criteria (done = all true)

1. `docs/sql/05-seed-simulator-data.sql` exists and can be run against the Docker Compose Postgres without error.
2. After running the script: `drivers` has ~510 rows (10 fixtures + 500 seeded), `rides` has ~5,000 rows, `driver_locations` has ~50,000 rows.
3. `cd services/rider-api && ./gradlew test` passes — `GetRideHistoryTest` asserts the endpoint against the real Postgres database with the 10-row fixture set.
4. `RideRepository.findByRiderId` uses a single query with `LEFT JOIN drivers` (no N+1).
5. `RideRepository.hasActiveRide` uses the existing SQL from PB-2.1 (`SELECT COUNT(*) FROM rides WHERE rider_id = ? AND status NOT IN ('CANCELLED', 'COMPLETED')`).
6. No Flyway migration files are added in this subtask.

---

## PB-7.1.2 — EXPLAIN ANALYZE Documentation

### Goal

Run `EXPLAIN (ANALYZE, BUFFERS)` on the three hottest query shapes against the seeded database, capture the output, and write a Markdown analysis that explains what the planner is doing.

### What to deliver

#### 1. `docs/sql/06-explain-analysis.md` — EXPLAIN report

| Item | Requirement |
|------|-------------|
| **Prerequisites** | Document that the reader must run `docker compose -f infra/docker-compose.yml up -d postgres` and then execute `docs/sql/05-seed-simulator-data.sql` before running these commands. |
| **Query 1 — Available drivers** | SQL: `EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM drivers WHERE status = 'AVAILABLE';`. Include the raw `EXPLAIN` output. Analyse: does it use Seq Scan or Index Scan? Why? (Low-cardinality `status` column, ~1/3 of rows match). |
| **Query 2 — Ride history** | SQL: `EXPLAIN (ANALYZE, BUFFERS) SELECT r.*, d.id AS d_id, d.name AS d_name, d.status AS d_status, d.current_lat AS d_current_lat, d.current_lng AS d_current_lng, d.created_at AS d_created_at, d.updated_at AS d_updated_at FROM rides r LEFT JOIN drivers d ON r.driver_id = d.id WHERE r.rider_id = '<a-seeded-rider-uuid>' ORDER BY r.requested_at DESC LIMIT 20;`. Include raw output. Analyse: Seq Scan + Sort vs. Index Scan. Note the cost of the `LEFT JOIN`. |
| **Query 3 — Active-ride guard** | SQL: `EXPLAIN (ANALYZE, BUFFERS) SELECT COUNT(*) FROM rides WHERE rider_id = '<a-seeded-rider-uuid>' AND status NOT IN ('CANCELLED', 'COMPLETED');`. Include raw output. Analyse: `Seq Scan` on `rides` with a filter on `status`. Note that without an index, Postgres must scan all ~5,000 rows even though only a handful match the rider. |
| **Shared analysis** | For each query, note: (a) scan type, (b) estimated vs. actual rows, (c) buffer hits (shared hit / read), (d) execution time, (e) the planner's sort method if any. |

> **How to pick a rider/driver UUID for EXPLAIN:** The seed script should include a final `SELECT` that returns one valid `rider_id` from `rides` and one valid `driver_id` from `drivers`, so the reader can copy-paste the EXPLAIN commands.

### Acceptance criteria (done = all true)

1. `docs/sql/06-explain-analysis.md` exists and contains the raw `EXPLAIN (ANALYZE, BUFFERS)` output for all three queries.
2. Each query's section includes a plain-English analysis of the scan type and why Postgres chose it.
3. No Java code changes in this subtask.

---

## PB-7.1.3 — Add One Index + Before/After Measurement

### Goal

Pick the query whose plan improves most dramatically, create the index, re-run `EXPLAIN`, and document the latency difference.

### What to deliver

#### 1. Chosen index (locked)

| Item | Requirement |
|------|-------------|
| **Chosen query** | **Query 2 — Ride history** (`GET /rides/history`). |
| **Index DDL** | `CREATE INDEX idx_rides_rider_id_requested_at ON rides(rider_id, requested_at DESC);` |
| **Rationale** | This composite index covers both the `WHERE r.rider_id = ?` predicate (first column) and the `ORDER BY r.requested_at DESC` sort (second column, descending). On ~5,000 rows it typically transforms the plan from `Seq Scan on rides` + `Sort` (high cost, separate sort step) to `Index Scan Backward using idx_rides_rider_id_requested_at` (low cost, no sort step). It is the clearest before/after learning outcome because the `Sort` node disappears entirely from the plan. |

> **Why not `drivers(status)`?** Low-cardinality columns (3 values) rarely benefit from B-tree indexes; Postgres will still `Seq Scan`. This is documented in `06-explain-analysis.md` as a learning point, not an index target.
>
> **Why not the active-ride guard this week?** The `hasActiveRide` query (`SELECT COUNT(*) ... WHERE rider_id = ? AND status NOT IN (...)`) can partially use the `rider_id` prefix of `idx_rides_rider_id_requested_at`, but a dedicated `(rider_id, status)` composite index would enable an `Index Only Scan`. It is deferred because Week 7's index budget is one index only, and the history query shows the more dramatic plan change (Sort node elimination).

#### 1b. Future index targets (documented, not created)

Document the following in `07-index-tuning.md` under a "Future targets" heading:

| Table | Columns | Query it would serve | Why deferred |
|-------|---------|----------------------|------------|
| `rides` | `(rider_id, status)` | `hasActiveRide` — active-ride guard on `POST /rides` | Week 7 already has one index. The existing `idx_rides_rider_id_requested_at` partially covers the `rider_id` prefix, so the guard is not unindexed, just not fully optimised for an `Index Only Scan`. |
| `rides` | `(driver_id, status)` | Active-ride lookup for a driver | Not yet a hot path; no endpoint queries rides by `driver_id` in V1. |

### Acceptance criteria (done = all true)

1. One Flyway V2.1 migration file exists in all three services and applies cleanly on `docker compose up`.
2. `docs/sql/07-index-tuning.md` exists and contains before/after `EXPLAIN` output with a comparison table showing execution time and scan type.
3. The comparison table shows a measurable improvement (lower execution time, different scan type, or fewer buffers read) for the chosen query.
4. `docker compose -f infra/docker-compose.yml down -v && docker compose -f infra/docker-compose.yml up -d postgres rider-api` boots without Flyway errors; `\d rides` shows `idx_rides_rider_id_requested_at`.
5. `cd services/rider-api && ./gradlew test` still passes after the migration.

---

## Explicitly out of scope for the whole ticket

- Changing query shapes beyond the three listed (no new endpoints besides history).
- N+1 detection or fixing — that is Week 8.
- `EXPLAIN` on the matching engine's `findAvailable` Java stream (the SQL is simple; the EXPLAIN target is the SQL query itself).
- Making the seed script a Flyway migration.
- Adding more than one index.
- Pagination cursors or keyset pagination for history.
- Kafka, Redis, WebSockets, or message brokers.
- Performance benchmarking tools (JMH, Gatling) — `EXPLAIN (ANALYZE)` is sufficient for this week.
- Cloud deployment, K8s, CI/CD.

---

## Suggested completion note

> Shipped Week 7 query-tuning milestone: a `docs/sql/05-seed-simulator-data.sql` script that generates ~500 drivers, ~5,000 rides, and ~50,000 driver locations for realistic `EXPLAIN` output; introduced `GET /rides/history` with `RideRepository.findByRiderId` using a single `LEFT JOIN`; documented `EXPLAIN (ANALYZE, BUFFERS)` findings for available drivers, ride history, and active-ride guard in `docs/sql/06-explain-analysis.md`; added the composite index `idx_rides_rider_id_requested_at ON rides(rider_id, requested_at DESC)` via Flyway V2.1 and proved a measurable latency improvement (elimination of the Sort node) with before/after comparison in `docs/sql/07-index-tuning.md`. Ready for N+1 fix and matching logic unit tests in Week 8.

---

## Next ticket

- [PB-9.1](pb-9.1.md) — Kafka in Docker Compose: KRaft mode, `ride.requested` / `ride.matched` JSON schemas, and `matching-engine` consumer
