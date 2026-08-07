# PB-6.1 — SQL Theory: ACID, Isolation Levels, and Concurrency Decision Record

> **⚠️ Superseded — historical reference only.** This ticket was written before the PRD existed and is **not authoritative**; do not reconcile against it. The current source of truth is `_bmad-output/planning-artifacts/prds/prd-puber-2026-08-02/prd.md` together with the architecture spine. Kept for the reasoning it records, not the scope it defines.

| Field | Value |
|--------|--------|
| **ID** | PB-6.1 |
| **Phase** | Months 1–2 — Bootstrap + Domain + Matching |
| **Week** | 6 — SQL Theory |
| **Source** | [puber.md §Schedule — Week 6](../puber.md) |
| **Depends on** | [PB-5.1](pb-5.1.md) |

---

## Goal

Map the concrete concurrency patterns already shipped in Puber V1 (`SELECT ... FOR UPDATE`, `@Transactional`, race-condition guards) to the underlying database theory: ACID properties, ANSI isolation levels, and Postgres implementation details. Produce a written decision record that justifies why the current stack (Spring Boot + Postgres `READ COMMITTED` + pessimistic row locking) is the correct V1 choice for `rides` + `drivers` concurrent updates.

This ticket is **research + documentation only** — no new service endpoints, no schema changes, no Java business logic. The output lives entirely in `docs/sql/` so it can be referenced during the query-tuning weeks (Week 7–8) and later when Kafka introduces distributed transactions.

---

## Context and constraints

- **Theory serves practice** — Every concept documented must tie back to a concrete Puber scenario already implemented in PB-3.1 (double-booking, stale accept, scheduler-vs-HTTP races).
- **Postgres-specific** — Isolation-level behaviour varies by engine. All claims must reference Postgres 17 semantics, not generic SQL.
- **No code changes to `src/main/java`** — this week is about understanding and documenting what already exists. The only "code" is a commented SQL script that exercises the current schema.
- **External resource required** — One high-quality article or video on isolation levels; summarised in exactly 5 bullets.
- **Spring Boot default** — Spring `@Transactional` defaults to the underlying datasource isolation level. For Postgres, that is `READ COMMITTED`.
- **Pessimistic locking is already in use** — `FOR UPDATE` appears in `RideRepository.findByIdForUpdate`, `DriverRepository.findByIdForUpdate`, `MatchEngine`, `AssignDriver`, `ReleaseDriver`, `AcceptRide`, `CompleteRide`, and `CancelRide`.

---

## How this ticket is organised: Learning vs. Code

| Component | Type | What it is |
|-----------|------|------------|
| **Part 1 — ACID primer** | Learning | Research + written explanation |
| **Part 2 — Isolation decision record** | Learning + Decision | Analysis + justified recommendation |
| **Part 3 — External resource summary** | Learning | 5-bullet distillation of one external source |
| **Part 4 — Race-scenario SQL script** | Code (documentation artefact) | Runnable `.sql` file that recreates each race scenario with comments |
| **Part 5 — Isolation verification test** | Code (test artefact) | One JUnit test that asserts the current Postgres isolation level |

---

## What to deliver

### 1. `docs/sql/01-acid-isolation.md` — ACID primer with Puber examples

| Item | Requirement |
|------|-------------|
| **Atomicity** | Explain with Puber example: `CancelRide` must both mark ride `CANCELLED` *and* release driver to `AVAILABLE`. If either fails, the transaction rolls back and neither change is visible. |
| **Consistency** | Explain with Puber example: `CHECK` constraints on `rides.status` and `drivers.status` ensure invalid state transitions are rejected at the database level, even if a bug bypasses the Java state machine. |
| **Isolation** | Explain with Puber example: two simultaneous `POST /rides` calls from the same rider. Without isolation, both `SELECT COUNT(*) ...` checks could see zero active rides and both insert. `@Transactional` + `READ COMMITTED` + `hasActiveRide` query timing prevents this. |
| **Durability** | Explain with Puber example: once `AssignDriver` commits, the `MATCHED` status and `driver_id` survive a Postgres crash because WAL (Write-Ahead Log) guarantees it. |
| **Isolation levels table** | A Markdown table comparing `READ UNCOMMITTED`, `READ COMMITTED`, `REPEATABLE READ`, `SERIALIZABLE` in Postgres 17. Columns: level, dirty read, non-repeatable read, phantom read, Postgres implementation detail. |
| **Puber mapping** | A second table mapping each Puber race scenario (from PB-3.1 concurrency section) to the isolation anomaly it would suffer if `FOR UPDATE` were removed. |

### 2. `docs/sql/02-isolation-decision.md` — Decision record

| Item | Requirement |
|------|-------------|
| **Question** | "What transaction isolation level should Puber V1 use for concurrent `rides` + `drivers` updates?" |
| **Decision** | `READ COMMITTED` is the correct choice for Puber V1. |
| **Consequences — Positive** | (1) Default in Spring Boot + Postgres — no config drift; (2) `FOR UPDATE` row locks prevent the lost-update anomaly that `READ COMMITTED` alone cannot handle; (3) Better concurrency than `SERIALIZABLE` (no predicate locking overhead) for the 10-driver fixture scale; (4) Well-understood, easy to `EXPLAIN` in Week 7. |
| **Consequences — Negative** | (1) `REPEATABLE READ` or `SERIALIZABLE` would catch more anomalies automatically, but in Postgres they retry or fail with serialization errors — adds complexity without benefit because `FOR UPDATE` already serialises the hot path; (2) If Puber scales to multiple JVMs in K8s, `FOR UPDATE` only locks within one database — distributed locking (advisory locks, Redis Redlock, or Kafka partition affinity) will be needed in Month 5+. |
| **When to revisit** | Revisit if: (a) Puber moves to read-replicas and replica lag matters; (b) distributed matching engines share one Postgres; (c) a new feature requires gap-locking (e.g., "prevent two rides requesting the same 1-minute window"). |

### 3. `docs/sql/03-external-resource-summary.md` — 5-bullet summary

| Item | Requirement |
|------|-------------|
| **Source** | One external article, blog post, or video on SQL transaction isolation levels. Suggested: "A Critique of ANSI SQL Isolation Levels" (Berenson et al.), or a modern Postgres-specific deep-dive such as the `pgdba` or `use-the-index-luke` series. |
| **Format** | Exactly 5 bullets, each 1–2 sentences. |
| **Content** | Bullets must cover: (1) the biggest misconception the author corrects; (2) the difference between ANSI terminology and Postgres implementation; (3) why `READ COMMITTED` is not "unsafe" when combined with explicit locks; (4) one real-world bug caused by choosing the wrong level; (5) the author's single-sentence advice for OLTP systems like Puber. |

### 4. `docs/sql/04-concurrency-races-explained.sql` — Commented SQL script

| Item | Requirement |
|------|-------------|
| **Location** | `docs/sql/04-concurrency-races-explained.sql` |
| **Style** | Each race scenario is a commented block that could be run in two parallel `psql` sessions to observe the behaviour. |
| **Scenario 1 — Double-booking the same ride** | Session A: `BEGIN; SELECT * FROM rides WHERE id = ? FOR UPDATE; ...`. Session B: blocked on same `id`. Comments explain why `FOR UPDATE` serialises the match attempt and how `READ COMMITTED` prevents Session B from seeing an uncommitted match. |
| **Scenario 2 — Two rides compete for one driver** | Session A: matches ride R-1 to driver D-1. Session B: matches ride R-2 to driver D-1. Comments explain the re-lock in `AssignDriver` (`findByIdForUpdate`) and why the second session aborts silently after detecting `status = BUSY`. |
| **Scenario 3 — Expire vs. Accept race** | Session A: scheduler runs `ExpireStaleRequests`. Session B: driver calls `AcceptRide`. Comments explain the ride-row lock and how the slower transaction sees the state already moved. |
| **Scenario 4 — Scheduler retry vs. HTTP match** | Session A: `@Scheduled` task calls `MatchEngine`. Session B: rider-api HTTP call triggers `MatchEngine`. Comments explain the `findByIdForUpdate` ride lock and the `RideAlreadyMatchedException` that the second caller receives. |
| **Verification query** | Include `SHOW transaction_isolation;` and a query against `pg_locks` so a reader can observe the actual locks held during the script. |

### 5. Isolation-level verification test (one JUnit test per service)

| Item | Requirement |
|------|-------------|
| **Purpose** | Prove the theory matches the running system. Not a functional test of business logic — a meta-test of the environment. |
| `services/matching-engine/src/test/java/com/puber/matching/sql/IsolationLevelTest.java` | One `@SpringBootTest` that injects `DataSource`, obtains a `Connection`, and asserts `connection.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED`. |
| `services/rider-api/src/test/java/com/puber/rider/sql/IsolationLevelTest.java` | Same assertion. |
| `services/driver-api/src/test/java/com/puber/driver/sql/IsolationLevelTest.java` | Same assertion. |
| **Why three copies?** | Each service is independently buildable (PB-1.1 constraint). The test documents the isolation contract for that service's datasource. |

---

## Acceptance criteria (done = all true)

1. `docs/sql/01-acid-isolation.md` exists and contains the four ACID sections, each with a concrete Puber example.
2. `docs/sql/02-isolation-decision.md` exists and explicitly recommends `READ COMMITTED` for Puber V1, with at least two positive and two negative consequences.
3. `docs/sql/03-external-resource-summary.md` exists and contains exactly 5 bullets summarising one external source.
4. `docs/sql/04-concurrency-races-explained.sql` exists and covers all four race scenarios from PB-3.1 with runnable SQL blocks and explanatory comments.
5. All three `IsolationLevelTest` Java files exist and pass with `./gradlew test` in their respective services.
6. No changes to `src/main/java` in any service — this is a theory/documentation week.
7. No new Flyway migrations, endpoints, repositories, or domain classes.

---

## Explicitly out of scope for this ticket

- Query tuning, `EXPLAIN ANALYZE`, or index creation (Week 7).
- `GET /rides/history` endpoint or N+1 fixes (Week 8).
- Changing the isolation level of any existing `@Transactional` annotation.
- Adding advisory locks, `SERIALIZABLE`, or `REPEATABLE READ` experiments.
- Any change to `docker-compose.yml`, `Makefile`, or service `README`s (those were Week 5).
- Kafka, Redis, WebSockets, or message brokers.
- Simulator logic changes.

---

## Suggested completion note

> Shipped Week 6 SQL theory milestone: documented ACID properties with Puber-specific examples, published a decision record justifying `READ COMMITTED` + pessimistic row locking for V1 concurrent `rides`/`drivers` updates, summarised one external isolation-level resource in 5 bullets, and created a commented SQL script that recreates the four race scenarios from PB-3.1. Verified the Spring Boot + Postgres default isolation level with a passing JUnit test in all three services. No Java service code was changed — pure research and documentation. Ready for query tuning and `EXPLAIN` in Week 7.

---

## Next ticket

- [PB-7.1](pb-7.1.md) — Query tuning: seed simulator-scale data, `EXPLAIN ANALYZE` on 2–3 hot queries, add one index, and document before/after latency in `docs/sql/`
