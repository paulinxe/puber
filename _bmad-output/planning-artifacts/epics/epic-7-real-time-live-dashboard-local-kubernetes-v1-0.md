## Epic 7: Real-Time, Live Dashboard & Local Kubernetes — `v1.0`

Drivers stop polling for offers, an operator watches the system's domain state move in real time, and
the whole system runs as an orchestrated deployment under stress-scale load.

### Story 7.1: Drivers receive offers and ride changes over a live push channel

As a driver,
I want offers and the changes I must react to pushed to me rather than polled for,
So that I stop driving toward a pickup that no longer exists.

**Acceptance Criteria:**

**Given** WebSocket
**When** it is admitted to the system
**Then** it is a **third transport**, used only for server-initiated push to a connected client
**And** it is never used for request/response, which stays REST at the edge and gRPC internally
(AD-51, AD-37)

**Given** driver sockets
**When** they are held
**Then** `driver-service` holds them (AD-51)

**Given** an event that must reach a connected driver
**When** it is routed
**Then** **every replica consumes the relevant topic in its own consumer group** and pushes only to
the sockets it currently holds
**And** no replica registry, sticky routing, or shared session store is required (AD-51)

**Given** the push channel
**When** its payloads are defined
**Then** it carries ride offers, rider cancellations, withdrawn or expired offers, and auto-completions
(FR-45)

**Given** a driver connected to no replica
**When** an event concerning them occurs
**Then** they simply receive nothing
**And** they recover on their next session read (Story 3.12), because push is an accelerator over the
polling path and **never the only delivery route for anything correctness-bearing** (AD-51, FR-45)

**Given** the 2 s polling path from Story 3.12
**When** push lands
**Then** polling remains in place rather than being removed (AD-46, AD-51)

**Given** the push consumer
**When** it processes events
**Then** it deduplicates on `event_id` and applies jittered backoff with a dead-letter path like every
other consumer (AD-36, AD-55, NFR-4)

### Story 7.2: The live operational dashboard shows domain state moving

As an operator,
I want a live view of what the system is doing right now, in domain terms,
So that I can watch rides and drivers move rather than reading infrastructure metrics and inferring it.

**Acceptance Criteria:**

**Given** the dashboard
**When** it is served
**Then** it is a lightweight custom web UI (FR-50)

**Given** the dashboard
**When** it renders
**Then** it shows live counts of drivers by status, rides by status, and active riders (FR-50)

**Given** those counts
**When** they change
**Then** they are pushed in real time over the same mechanism as Story 7.1 (FR-50, AD-51)

**Given** the dashboard's sockets
**When** they are held
**Then** the dashboard consumer holds them, consuming in its own consumer group per replica, on the
same fan-out-and-filter routing (AD-51)

**Given** this dashboard's data source
**When** it is traced
**Then** it reads domain counts from the event stream and **queries no Prometheus metric**, keeping it
distinct from the Grafana dashboards of Story 4.9
**And** the two are never merged: this one shows **domain and business state**, those show
infrastructure metrics (FR-50, FR-46)

**Given** the dashboard disabled entirely
**When** the rest of the system runs
**Then** nothing breaks — it is Tier 3 (AD-48)

**Given** the dashboard
**When** it is reached
**Then** it is **not** a gateway route — the gateway carries actor-facing traffic only, and operator
and observability surfaces are reached directly inside the cluster, as Prometheus and Grafana are
**And** that is a *stronger* boundary than a route, not a looser one (AD-5)

### Story 7.3: The Simulator drives synthetic load against the running system

As an engineer,
I want a containerized Simulator generating synthetic riders, drivers and ride traffic at configurable scale,
So that I can watch how the deployed system actually behaves under load that no hand-written scenario reproduces.

**Acceptance Criteria:**

**Given** the Simulator
**When** it is packaged
**Then** it runs as its own container in the stack
**And** it is plain Java rather than Spring Boot (FR-49, Source tree)

**Given** a run
**When** it executes
**Then** it generates synthetic riders, drivers and ride traffic concurrently against the running
system (FR-49)

**Given** simulated drivers
**When** the Simulator drives their heartbeats
**Then** each position advances by straight-line drift toward its destination on each heartbeat
**And** it uses the same haversine math as the ETA formula, never road-network routing (Non-goals)

**Given** an offer reaching a simulated driver
**When** it responds
**Then** it can accept, decline, or deliberately ignore the offer until timeout
**And** all three offer outcomes therefore occur under load (FR-11, FR-22, FR-23)

**Given** ride requests it generates
**When** they are sent
**Then** it supplies payment-method tokens, including deliberately-declining ones, so the
authorization-failure path is exercised under load too (FR-49, FR-9)

**Given** a seed
**When** a run is repeated
**Then** it produces the same sequence of ride requests and driver movements
**And** a failure observed during a run can therefore be re-run rather than merely observed once (NFR-9)

**Given** generation volume
**When** it is configured
**Then** it ramps from fixture scale toward the stress scale of NFR-2 without code changes (FR-49, NFR-2)

**Given** the configured geographic bounds
**When** coordinates are generated
**Then** they remain relative to those bounds rather than to any literal (AD-25)

> **What this is for, and what it is not.** The Simulator exists to surface **emergent** behaviour
> under mixed load — contention, saturation and interactions nobody wrote a scenario for — and to make
> the deployed system watchable while it happens. The deterministic proofs of specific races belong to
> the stories that own those transitions and were delivered there as ordinary test scenarios (see
> Epic 3). Seeded reproducibility here serves re-running a surprise, not asserting a known invariant.

### Story 7.4: The full stack runs on a local Kubernetes cluster

As an operator,
I want the whole system running as an orchestrated deployment,
So that it is a system rather than a pile of local processes.

**Acceptance Criteria:**

**Given** a local kind 1.35.x cluster
**When** the manifests are applied
**Then** the full stack deploys cleanly and runs (FR-47, NFR-7, Stack)

**Given** every service
**When** it is built and run
**Then** it does so in Docker from pinned base images with no host JDK dependency (NFR-7)

**Given** the manifests
**When** they are stored
**Then** they live in the repository under `deploy/` (AD-49)

**Given** the service manifests
**When** they are authored
**Then** services are **generated from one template** rather than copied per service
**And** infrastructure is declared individually (AD-49)

**Given** the deployment tiers
**When** they are exercised
**Then** Tier 1 runs alone; Tier 2 adds payments; Tier 3 adds audit, ClickHouse, Prometheus, Grafana
and the dashboard
**And** disabling Tier 3 breaks nothing and loses no data (AD-48)

**Given** the manifests under `deploy/`
**When** they are inspected
**Then** they reference no cloud-vendor resource, registry, load-balancer class or credential — the
target is a **local** cluster
**And** no cloud vendor is used at any point in the project (NFR-7)

> **Carried in from Story 1.1 (PUB-1), 2026-08-19 — the readiness group excludes the datastore.**
>
> PUB-1 set `management.endpoint.health.probes.enabled=true`, which creates
> `/actuator/health/liveness` and `/actuator/health/readiness`. Spring's own documentation points
> Kubernetes probes at those paths — but **the default readiness group contains only
> `readinessState`, not `db`.** Measured on the running service with Postgres stopped:
>
> | endpoint | result |
> | --- | --- |
> | `/actuator/health` | **HTTP 503, DOWN**, `db: DOWN` |
> | `/actuator/health/readiness` | **HTTP 200, UP** |
>
> So a `readinessProbe` on the documented path keeps the pod in the Service's endpoint list while it
> cannot reach its own database — the opposite of what Story 1.1's AC2 asks for. PUB-1's Compose
> healthcheck avoids this by hitting the aggregate `/actuator/health`; the manifests must not simply
> follow the Spring documentation here.
>
> The fix is one property, and it belongs to **readiness only**:
> `management.endpoint.health.group.readiness.include=readinessState,db`
>
> **Do not add `db` to the liveness group.** Liveness failure restarts the container, and restarting a
> pod because a database is unreachable fixes nothing while guaranteeing a crash loop. Note the
> trade-off even for readiness: every replica loses its database at once, so all of them go not-ready
> together and the Service empties. Decide deliberately between "serve nothing" and "serve errors".

### Story 7.5: Deployment is reconciled from git

As an operator,
I want the cluster reconciled to what the repository says,
So that drift is visible and a rollback is a revert rather than a procedure.

**Acceptance Criteria:**

**Given** Argo CD 3.5.x
**When** it runs
**Then** it reconciles the cluster to the manifests in the repository (AD-49, Stack)

**Given** cluster state drifting from the repository
**When** the controller next reconciles
**Then** the drift is corrected
**And** it was visible rather than silent (AD-49)

**Given** a bad deployment
**When** it must be undone
**Then** rollback is a revert of the manifest change (AD-49)

**Given** sync ordering
**When** the stack comes up
**Then** datastores are healthy before their dependants start (AD-49)

**Given** provider secrets
**When** they are handled
**Then** they are created out-of-band and **excluded from reconciliation**
**And** this is the one documented exception to declarative deployment, because NFR-10 forbids keys in
source (AD-49, NFR-10)

**Given** image promotion
**When** a new version ships
**Then** it is a manual tag bump at milestone cadence
**And** automated promotion stays deferred, since it solves a deploy frequency this project does not
have (Deferred)

### Story 7.6: The stress run produces the capacity numbers

As an engineer,
I want the system driven to stress scale and the bottlenecks it surfaces recorded,
So that every capacity value the architecture deliberately left underived is set by measurement rather than by guess.

**Acceptance Criteria:**

**Given** the Simulator of Story 7.3
**When** it ramps
**Then** it reaches roughly 20k drivers and 200k riders (NFR-2)

**Given** the operating area
**When** the stress scenario runs
**Then** it is widened to roughly 20 km across
**And** driver density therefore stays realistic and the 5 km matching radius still does real work,
rather than covering the whole grid and filtering nothing (AD-25, NFR-2)

**Given** payments during the stress run
**When** the provider is selected
**Then** the **stub** provider is used, keeping `payment-service` and its whole state machine in the
flow
**And** only the outbound provider call is replaced, never the payment path itself (NFR-8, AD-43)

**Given** the run
**When** it executes
**Then** it surfaces named bottlenecks — connection pool exhaustion, missing indexes, Kafka partition
throughput ceilings, cache hot-key contention — rather than falling over silently (NFR-2)

**Given** the measurements
**When** they are taken
**Then** they set the values AD-47 deliberately leaves deferred: pool sizes, replica and partition
counts, the outbox bound, backoff base, the outbox retry cap, and the capture backoff ceiling
**And** the storage ceilings left open in Epic 6 (AD-47, AD-35, AD-58)

**Given** the results
**When** the run finishes
**Then** they are recorded rather than merely observed, because the migration, concurrency, webhook
and scale narratives are the project's stated secondary deliverable (Goals)

**Given** Redis under load
**When** the geo key is the constraint
**Then** read replicas are the first scaling lever, which is safe because the geo set is advisory
**And** per-cell geo partitioning stays deferred until nothing before it has been exhausted (AD-27, Deferred)

**Given** the concurrency guarantees of Epic 3
**When** the system is under stress load
**Then** they still hold — no driver is ever double-booked (NFR-1, FR-17)

**Given** constrained machine resources
**When** the run is set up
**Then** Tier 3 may be disabled, which is what the tiering exists to permit (AD-48)

> **What the scale target is, and is not.** NFR-2's 20k drivers / 200k riders is a **milestone to
> reach**, not sustained production traffic to hold. The run exists to surface bottlenecks and set the
> capacity values AD-47 leaves underived; nothing in the system is required to stay at that load, and
> no acceptance criterion anywhere should be read as demanding it (NFR-2).

> **Decide when detailing: which environment the stress run targets.** The roadmap sequences it after
> the Kubernetes deploy, but a single-machine kind cluster running the full stack *and* 20k simulated
> drivers competes for the same resources the run is trying to measure. Running it against Compose,
> against kind, or against both with the difference recorded are all defensible — the spine does not
> say. Choose deliberately, because the numbers this run produces become the system's configured
> capacity everywhere else.
