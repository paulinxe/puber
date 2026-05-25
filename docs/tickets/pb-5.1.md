# PB-5.1 — Documentation & V1 Milestone: READMEs, Architecture Stub, and Repo Hygiene

| Field | Value |
|--------|--------|
| **ID** | PB-5.1 |
| **Phase** | Months 1–2 — Bootstrap + Domain + Matching |
| **Week** | 5 — Documentation & V1 Milestone |
| **Source** | [puber.md §Schedule — Week 5](../puber.md) |
| **Depends on** | [PB-4.1](pb-4.1.md) |

---

## Goal

Publish the repository as a readable, self-documenting V1 milestone. The Spring Boot skeleton and inter-service HTTP calls were already delivered in PB-1.1 (bootstrap) and PB-3.1 (matching + HTTP wiring). This ticket is pure documentation and repo hygiene: write the root README, per-service READMEs, and an architecture stub so a new reader can understand the project without reading source code.

---

## Context and constraints

- **No new service code.** This is a documentation and build-convenience ticket. A root `Makefile` is acceptable as pure build orchestration.
- **The V1 architecture is frozen** — three Spring Boot services (`rider-api`, `driver-api`, `matching-engine`), Postgres 17, Flyway V1/V2, Docker Compose, no Kafka, no Redis, no auth.
- **HTTP inter-service calls are V1-only** — they will be replaced by Kafka in Month 3. Do not invest in hardening (retries, circuit breakers) for code that will be deleted.
- **Per-service independence** — each service has its own `README.md` because each is independently buildable (PB-1.1 constraint).
- **Markdown only** — no diagrams-as-code tools required; ASCII art or simple lists are fine for the architecture stub.

---

## What to deliver

### 1. Root `README.md`

| Item | Requirement |
|------|-------------|
| Title | `Puber` — one-line description |
| What it is | 2–3 sentences: ride-hailing backend for learning backend engineering at scale |
| Stack | Java 25, Spring Boot 4.x, Postgres 17, Flyway, Gradle Wrapper (per service), Docker Compose |
| Quick start | `docker compose -f infra/docker-compose.yml up --build` then `curl` the three Actuator health endpoints |
| Service map | Table: service name → port → what it owns |
| Architecture link | Link to `docs/architecture.md` |
| Ticket index | Link to `docs/tickets/` directory |

### 2. Root `Makefile`

| Target | Command |
|--------|---------|
| `build` | `docker compose -f infra/docker-compose.yml build` |
| `run` | `docker compose -f infra/docker-compose.yml up` (or `up -d`) — boots Postgres + all three APIs |
| `test-rider` | `cd services/rider-api && ./gradlew test` |
| `test-driver` | `cd services/driver-api && ./gradlew test` |
| `test-engine` | `cd services/matching-engine && ./gradlew test` |
| `test` | Runs `test-rider`, `test-driver`, and `test-engine` in sequence (or parallel with `make -j`) |
| `down` | `docker compose -f infra/docker-compose.yml down -v` |

> The Makefile is a thin convenience wrapper. It does not replace Gradle or Docker Compose; it just saves typing.

### 3. `services/matching-engine/README.md`

| Item | Requirement |
|------|-------------|
| What it owns | Core domain (`Ride`, `Driver`, `Location`, `FareRule`), matching algorithm, state machine, scheduled retry/timeout |
| Key endpoints (internal) | `POST /internal/match`, `POST /internal/rides/{id}/accept`, `POST /internal/rides/{id}/complete`, `POST /internal/rides/{id}/cancel` |
| Key services | `MatchEngine`, `FindNearestDriver`, `AssignDriver`, `AcceptRide`, `CompleteRide`, `ReleaseDriver`, `CancelRide`, `RetryUnmatchedRides`, `ExpireStaleRequests` |
| Database | Postgres via `JdbcTemplate`; Flyway V1 + V1.2 + V2 migrations |
| Port | `8080` |
| Build & test | `cd services/matching-engine && ./gradlew test` |

### 4. `services/rider-api/README.md`

| Item | Requirement |
|------|-------------|
| What it owns | Public rider-facing REST API |
| Key endpoints | `GET /rides/estimate`, `POST /rides`, `GET /rides/{id}`, `POST /rides/{id}/cancel` |
| Key services | `EstimateFare`, `RequestRide`, `GetRide` |
| Inter-service | Calls `matching-engine` via `MatchingEngineClient` (V1-only HTTP) |
| Database | Postgres via `JdbcTemplate`; Flyway V1 + V1.2 + V2 migrations |
| Port | `8081` |
| Build & test | `cd services/rider-api && ./gradlew test` |

### 5. `services/driver-api/README.md`

| Item | Requirement |
|------|-------------|
| What it owns | Public driver-facing REST API + internal request storage |
| Key endpoints | `POST /drivers/{id}/location`, `POST /drivers/{id}/availability`, `POST /rides/{id}/accept`, `POST /rides/{id}/complete`, `POST /internal/drivers/{id}/request` |
| Key services | `UpdateLocation`, `UpdateAvailability`, `RequestDriver` |
| Inter-service | Calls `matching-engine` via `MatchingEngineClient` (V1-only HTTP) |
| Database | Postgres via `JdbcTemplate`; Flyway V1 + V1.2 + V2 migrations |
| Port | `8082` |
| Build & test | `cd services/driver-api && ./gradlew test` |

### 6. `services/simulator/README.md`

| Item | Requirement |
|------|-------------|
| What it owns | Plain Java load-generator skeleton (not yet a standalone container) |
| Current state | Empty `main` method; real simulator is the `PuberSimulator` test fixture inside `matching-engine` (PB-3.1.5) |
| Build & test | `cd services/simulator && ./gradlew test` |

### 7. `docs/architecture.md` stub

| Item | Requirement |
|------|-------------|
| Service diagram | ASCII or bullet list showing `rider-api`, `driver-api`, `matching-engine`, `simulator`, `postgres` and the HTTP arrows between them |
| Data flow — request ride | Step-by-step from `POST /rides` to `MATCHED` |
| Data flow — accept | Step-by-step from `POST /rides/{id}/accept` to `IN_PROGRESS` |
| Data flow — complete | Step-by-step from `POST /rides/{id}/complete` to `COMPLETED` |
| Schema evolution note | Brief note on Flyway V1 → V1.2 → V2, expand-only migrations |
| Future evolution | One paragraph: Month 3 replaces HTTP with Kafka, adds Redis for locations, standalone simulator container |

---

## Acceptance criteria (done = all true)

1. A new reader can clone the repo, read the root `README.md`, and understand what the project is, what stack it uses, and how to boot it in Docker.
2. Root `README.md` links to `docs/architecture.md` and `docs/tickets/`.
3. Each service `README.md` lists its port, owned endpoints, key services, and build command.
4. `docs/architecture.md` contains the three core data flows (request → match, accept, complete) and a service diagram.
5. Root `Makefile` exists with `build`, `run`, `test`, `test-rider`, `test-driver`, and `test-engine` targets. `make test` runs all three test suites.
6. No Java source code changes anywhere — only Markdown, `Makefile`, and `README` files.
7. `docker compose -f infra/docker-compose.yml up --build` still works after the changes.

---

## Explicitly out of scope for this ticket

- No new endpoints, services, repositories, or domain classes.
- No dependency upgrades or `build.gradle.kts` changes.
- No Docker Compose changes (the `Makefile` may reference the existing file, but the Compose file itself does not change).
- No Resilience4j, no HTTP client hardening (retries, circuit breakers) — these HTTP calls are deleted in Month 3.
- No Kafka, Redis, WebSockets, or standalone simulator container.
- No CI/CD, no cloud deployment notes.
- No generated diagrams (PlantUML, Mermaid, etc.) — ASCII art or lists are sufficient.

---

## Suggested completion note

> Shipped V1 documentation milestone: root README with quick-start, per-service READMEs for `matching-engine`, `rider-api`, `driver-api`, and `simulator`, an `docs/architecture.md` stub documenting the three core flows (request → match, accept, complete), and a root `Makefile` with `build`, `run`, `test`, `test-rider`, `test-driver`, and `test-engine` targets. The Spring Boot skeleton and inter-service HTTP wiring were already delivered in PB-1.1 and PB-3.1; PB-5.1 is documentation, build convenience, and repo hygiene. Ready to proceed to SQL theory and query tuning in Week 6.

---

## Next ticket

- [PB-6.1](pb-6.1.md) — SQL theory: ACID, isolation levels, and concurrency notes in `docs/sql/`

