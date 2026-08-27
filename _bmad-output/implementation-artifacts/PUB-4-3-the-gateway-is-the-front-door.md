---
baseline_commit: 051f0a212065dcb5428b96aa7940363f25d95300
parent_ticket: PUB-4
slice: 3 of 3
depends_on: PUB-4-2
---

# Story 1.4 (slice 3 of 3): The gateway is the front door

Ticket: **PUB-4-3**
Parent: **PUB-4** — Rider gets a fare quote through the gateway
Status: ready-for-dev

**PUB-4-2 must be `done` before this starts.** It creates `rider-service` and the `POST /quotes`
endpoint this slice routes to; a gateway with nothing to route is a container that 404s everything.
The parent file `PUB-4-rider-gets-a-fare-quote-through-the-gateway.md` holds the acceptance criteria
allocation and nothing binding — **this file is the whole specification for this slice.**

**When this slice is `done`, mark `PUB-4` `done` too.** It is the last of the three.

## Story

As an operator,
I want one published port fronting the whole system,
so that no service is directly addressable and every request is traceable from the moment it arrives.

### What this story actually does, in plain words

After PUB-4-2 the system works but is shaped wrong. `rider-service` answers quotes — and is reachable
only from inside the Compose network, so nobody can actually call it. `matching-service`, which should
never be publicly reachable at all, is the one service publishing a port to the host.

This slice fixes both, with one small container and one deletion:

1. **HAProxy goes in front** — one published port, `8080`. It routes `/quotes` to `rider-service` and
   answers `404` to everything else. `matching-service` has no backend here and never will.
2. **`matching-service` stops publishing its port** — the deletion is the substance of the criterion.
   A gateway with no route to `matching-service` proves nothing while `curl localhost:8080` still
   reaches it directly.
3. **The request id is minted at the edge** — HAProxy generates one on every inbound request, so
   the id a log line carries was made before any application code ran. `rider-service` already knows
   how to read it; PUB-4-2 built the rest of the chain.
4. **The tests start using the front door** — the first tests in this repository that talk to the
   system the way a real client does: plain HTTP to `haproxy:8080`, no Spring context, nothing mocked.

**This is the smallest of PUB-4's three slices.** It writes one config file, edits Compose and the
Makefile, and adds one test class. If you find yourself writing Java in a `src/main` directory, you
have left this story.

---

## Acceptance Criteria

These are PUB-4's AC3 in full, plus the gateway clauses of AC1 and AC4. Everything else in PUB-4 was
delivered by slices 1 and 2.

**AC1c — the gateway routes the rider's request to `rider-service`**

**Given** the gateway
**When** a rider requests a quote with pickup and dropoff coordinates
**Then** HAProxy routes it to `rider-service`
**And** the response is the same quote `rider-service` returns directly
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4 (first clause of its first criterion);
prd.md#FR-1; ARCHITECTURE-SPINE.md#AD-37 — REST at the edge]

**AC3 — `matching-service` is not reachable from outside**

**Given** any client
**When** it attempts to reach `matching-service` through the gateway
**Then** `matching-service` is not routable
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4; ARCHITECTURE-SPINE.md#AD-5 — The gateway
exposes edge services only]

**In plain terms, because the letter of this is easy to satisfy while missing the point.** "Not
routable" has to mean **there is no route to it *and* no published port.** `matching-service`
currently publishes `8080:8080`, which satisfies the criterion as written (there is no gateway route)
while completely defeating it (a client reaches it anyway). The PRD is unambiguous: *"no service is
directly addressable, and `matching-service` is never publicly routable at all."* See D2.

**AC4c — the request id is minted at the gateway**

**Given** an inbound request
**When** it enters the gateway
**Then** a request id is minted there
**And** it reaches `rider-service` as a header and continues over gRPC metadata from there
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4; ARCHITECTURE-SPINE.md#AD-54 — *"The gateway
mints a request id on every inbound request"*]

**Standing criteria that also apply here** (epics/overview.md#Standing acceptance criteria): any
bounded window is exercised by advancing the `Clock`, never by sleeping. **The non-root rule binds
containers this project builds** — HAProxy is a stock image and is out of its scope, deliberately; see
D3.

---

## Story-local decisions you must implement as written

**Four things this slice needs have no source anywhere in the planning artifacts.** No document states
the HAProxy version, its configuration, the unrouted-path status code, or how the gateway is tested.
They are pinned here so the implementation is deterministic.

### D1 — HAProxy `3.2.22`, config mounted read-only from `infra/`

Pinned to a patch version, the same discipline as `postgres:18.6`. **`3.2.22` is the highest `3.2` tag
on Docker Hub as of 2026-08-25** — verified by querying the registry: `3.2.22` exists, `3.2.23` does
not. The spine's Stack table pins HAProxy at `3.2.x LTS`.

The config lives at `infra/haproxy.cfg`, beside `docker-compose.yml`, and is mounted `:ro`.

```
global
    log stdout format raw local0

defaults
    mode http
    log global
    option httplog
    timeout connect 5s
    timeout client  30s
    timeout server  30s

frontend edge
    bind :8080

    # AD-54: minted here, so no request anywhere is untraceable.
    unique-id-format %{+X}o\ %ci:%cp_%fi:%fp_%Ts_%rt:%pid
    unique-id-header X-Request-Id

    # AD-5: the route list is the actor-facing edge. rider-service only, today.
    use_backend rider if { path_beg /quotes }
    default_backend no_such_route

backend rider
    server rider-service rider-service:8080 check

# AD-5: matching-service has no backend here, and adding one is a spine amendment.
backend no_such_route
    http-request deny deny_status 404
```

Timeouts are **conventional, not measured** — the same honest position PUB-1 took on the Compose
healthcheck numbers, and for the same reason: AD-6's bound chain is sized from measurement under the
NFR-2 stress run (Story 7.6, PUB-59), and AD-47 says guessing them now would be fiction. Say so in a
comment and in the completion notes; do not present them as derived.

### D2 — the gateway is the only published port

`infra/docker-compose.yml` publishes `8080:8080` for `matching-service` today. **Delete it.** After
this slice exactly one service publishes a port: `haproxy`, on `8080`. `rider-service` publishes
nothing (PUB-4-2 already made sure of that).

**Nothing breaks, and it is worth knowing why:** every integration test reaches its own application
through `TestRestTemplate` on a random in-JVM port; `matching-service`'s Compose healthcheck probes
`127.0.0.1` **inside** the container; and `rider-service` reaches `matching-service` over the Compose
network by service name, not through the host.

**What does change is your local workflow.** After this you cannot `curl localhost:8080/actuator/health`
to see whether `matching-service` is alive — you go through `docker compose exec`, or read the Compose
healthcheck. That is the intended consequence of AD-5, not a side effect.

### D3 — HAProxy is a stock image, so the non-root rule does not bind it

project-context.md is explicit: the rule binds containers **this project builds**. Stock images —
`postgres:18.6` today, `haproxy:3.2.22` now — keep their own entrypoint's user handling, and forcing a
`user:` onto one can break its first-start initialisation.

The official HAProxy image already drops privileges, and the frontend binds `8080`, which needs no
privileged port anyway. **Do not add a `user:` line, and do not treat this as an exception being
carved out** — it is the rule as written.

`verify-no-root-owned-files` still has to pass: `haproxy.cfg` is mounted read-only and HAProxy writes
nothing into the workspace.

### D4 — an unrouted path answers `404`, not `503`

A `default_backend` with no server answers `503`, which means *"the service is there, try again."*
There is nothing to try again — the path does not exist. `no_such_route` denying with `404` says the
true thing.

This matters for the test, not just for correctness. **A 503 from an empty backend, a connection
refused because a container is down, and a deliberate 404 are three different facts**, and only the
last one proves AC3. Assert the `404` specifically, and assert **in the same run** that `/quotes`
answers `200` — otherwise a gateway that is simply broken passes AC3 while proving nothing.

### D5 — proving the gateway needs the stack up, so `make test-integration` brings it up

AC1c, AC3 and AC4c cannot be asserted from inside a JVM: HAProxy is a container. The test runner is
already a container on the Compose network (AD-56), so it can call `http://haproxy:8080/quotes` by
service name — but only if the gateway, both services and Postgres are running.

PUB-4-2 already grew `make test-integration` to bring up `matching-service` and gave it `images` as a
prerequisite. This slice adds `rider-service` and `haproxy` to the same `up -d --wait` line.

`make test` gets slower, and `pre-push` runs `make test`, so every push pays it. **That is the cost of
AC3 being tested rather than asserted in prose.** Do not "optimise" it by skipping the gateway tests
when the stack is down — a skipped test is a green suite that proves nothing, and project-context.md's
hook policy is explicit that if `pre-push` feels slow the answer is faster tests, never moving the
gate.

**Address services by Compose service name, never `localhost`.** The runner is a peer container.

---

## Tasks / Subtasks

### Task 1 — the gateway (AC1c, AC4c, D1, D4)

- [ ] **1.1** Create `infra/haproxy.cfg` exactly as D1 specifies, including the two `AD-5` comments and
      an honest note that the timeouts are conventional rather than measured (D1).
- [ ] **1.2** `infra/docker-compose.yml`: add `haproxy` — `image: haproxy:3.2.22`,
      `ports: ["8080:8080"]`, `volumes: ["./haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro"]`,
      `depends_on: rider-service: condition: service_healthy`.
- [ ] **1.3** **No healthcheck on `haproxy`.** The image is a HAProxy binary with no shell HTTP client
      to probe itself with, `depends_on` already gates it behind a healthy `rider-service`, and
      `docker compose up --wait` treats a running container with no healthcheck as ready. If the
      gateway tests turn out to race the listener, **add a bounded retry in the test rather than a
      fictional healthcheck** — a healthcheck that does not check anything is exactly the shape
      project-context.md → YAGNI forbids.
- [ ] **1.4** Confirm HAProxy actually starts and does not reject the config: `make run`, then
      `docker compose logs haproxy`. A `server` line naming a container that is not up yet is the usual
      first failure — `depends_on` in 1.2 is what prevents it.

### Task 2 — close the direct route (AC3, D2)

- [ ] **2.1** `infra/docker-compose.yml`: **delete** `matching-service`'s `ports:` block.
- [ ] **2.2** Confirm `rider-service` publishes nothing (PUB-4-2 should have left it that way) and that
      `haproxy` is the only service in the file with a `ports:` key. A grep for `ports:` returning one
      hit is the check.
- [ ] **2.3** `make run` still reports the stack up, and `make test` still passes. If anything depended
      on the published port, it was reaching `matching-service` in a way AD-5 forbids — fix the caller,
      not the Compose file.

### Task 3 — the stack comes up for tests (D5)

- [ ] **3.1** `Makefile`: `test-integration` brings up `matching-postgres matching-service
      rider-service haproxy` with `--wait`, and keeps the `images` prerequisite PUB-4-2 added so it
      still works on a fresh clone.
- [ ] **3.2** `Makefile`: `run` brings up the same set, so `make run` gives a working front door rather
      than a stack with no way in.
- [ ] **3.3** Update the `help` target's text if any of it stopped being true.
- [ ] **3.4** `make clean` still tears everything down: `$(COMPOSE_DOWN) --profile tools down -v
      --remove-orphans` already covers a new service, but confirm rather than assume — an orphaned
      HAProxy container holding port 8080 is a confusing failure the next time anything starts.

### Task 4 — the gateway tests (AC1c, AC3, AC4c, D4, D5)

Put them in `services/rider-service/src/integrationTest/java/com/puber/rider/GatewayIntegrationTest.java`.
**No Spring context**: this class talks to a running stack over plain HTTP, the way a real client does.
Use the JDK's `HttpClient` — no new dependency. `snake_case` methods, `@DisplayName` carrying the
`AC<n>:` reference (AGENTS.md → Test Naming and Placement).

- [ ] **4.1** `POST http://haproxy:8080/quotes` answers `200`, and the body carries the same
      `fareMinorUnits` and `distanceMetres` PUB-4-2's direct test asserts for the same two coordinates
      (AC1c). **Hand-computed expectation, two distinct coordinates** — PUB-3's review proved a
      same-point test multiplies every rate by zero and cannot fail.
- [ ] **4.2** The response carries an `X-Request-Id` the client did not send (AC4c).
- [ ] **4.3** A client-supplied `X-Request-Id` is **replaced**, not passed through (AC4c, D1). This
      is the clause that proves HAProxy minted it rather than `rider-service`: AD-54 says the gateway
      mints on **every** inbound request, and trusting a client-supplied id would let a caller collapse
      two traces into one.
- [ ] **4.4** Paths that would reach `matching-service` answer **404**, not 503 and not a connection
      error (AC3, D4). At minimum `/actuator/health` and `/actuator/prometheus` — the two surfaces
      that exist on both services and that AD-5 explicitly keeps off the route list.
- [ ] **4.5** 4.1 and 4.4 run in the **same** class, so a broken gateway cannot pass AC3 by failing
      everything (D4).
- [ ] **4.6** Make the failure messages name **which** service was unreachable. These are the least
      isolated tests in the repository — four containers have to be healthy — so a bare "connection
      refused to haproxy:8080" will cost the next person an hour.

### Task 5 — record what this slice settled, and close out PUB-4

- [ ] **5.1** `project-context.md`: add only what is non-obvious from the code and not already in the
      spine — that `haproxy` is the single published port and why `matching-service` publishes none
      (with the workflow consequence from D2), that the gateway config is the one place the route list
      lives and that adding a route is an AD-5 decision, and that gateway tests need the stack up. **Do
      not restate AD-5 or AD-54** — CLAUDE.md forbids duplicating a rule across files.
- [ ] **5.2** `README.md`: if it tells anyone to `curl localhost:8080` against `matching-service`,
      that instruction is now wrong. Check it.
- [ ] **5.3** Anything deferred out of this slice goes in `deferred-work.md` **and** in whichever of the
      epic file / `sprint-status.yaml` `action_items` / `project-context.md` will actually surface it.
      `deferred-work.md` alone is an audit trail nothing reads. HAProxy's unmeasured timeouts are the
      obvious candidate — AD-6's bound chain is Epic 4's work and AD-47 says the numbers are measured
      at Story 7.6 (PUB-59).
- [ ] **5.4** **Verify PUB-4's nine criteria are now all satisfied across the three slices**, using the
      parent file's allocation table. Name in the completion notes any criterion that is satisfied only
      partially and why — AC2's ETA-present branch (Story 2.6) and AC9's untestability are the two
      known ones.

### Task 6 — the gate

- [ ] **6.1** `make build`, then `make test`, **in that order, from a clean tree**, and read the output.
      Not `make test-unit`: it runs neither Spotless nor the integration suite, which is how PUB-2's
      review left the build red while reporting the suite green.
- [ ] **6.2** Report what you saw, including the known environmental red —
      `HealthReportsDownPromptlyIntegrationTest`'s precondition — named rather than omitted, and never
      quietly counted as green.
- [ ] **6.3** Set this story to `review`. **Set `PUB-4` to `review` as well** — this is the slice that
      completes it, and the parent's status is a claim about all three.
- [ ] **6.4** Leave everything **unstaged**. The repo owner reviews the unstaged diff.

---

## Dev Notes

### What exists when this slice starts

After PUB-4-1 and PUB-4-2:

- `contracts/proto/puber/quote/v1/quote.proto` is the single contract source, copied into each service
  at build time.
- `matching-service` serves `QuoteService/GetQuote` over gRPC, reads a request id out of metadata,
  and rejects bad coordinates with `INVALID_ARGUMENT`. **It still publishes `8080:8080`.**
- `rider-service` serves `POST /quotes`, mints a request id when none arrives, carries it over gRPC
  metadata, and answers RFC 9457 Problem Details on a bad request. **It publishes nothing, so nothing
  outside the Compose network can call it.**
- `make test-integration` already brings up `matching-service` and depends on `images`.

So the system is correct and unreachable. This slice makes it reachable, in exactly one place.

### Why the gateway comes last, recorded so it is not "improved" later

PUB-4 was split inner-first on purpose. A gateway's only deliverable is a **route**, and a route needs
something to point at:

- HAProxy resolves `server` addresses at startup. A `backend` naming a container that does not exist
  fails config validation, so the gateway could not have shipped before `rider-service`.
- AC3 requires removing `matching-service`'s published port. Doing that before `rider-service` existed
  would have left the system with nothing reachable at all and no replacement — a regression window,
  not an increment.

If a future story adds a second edge service, the same ordering applies: the service first, its route
second.

### What "not routable" has to mean, and the three ways this criterion gets faked

AC3 is the most easily faked criterion in PUB-4. Three ways it passes while being false:

1. **`matching-service` keeps its published port.** Task 2.1 removes it. Without that, `curl
   localhost:8080/actuator/health` reaches `matching-service` directly and the gateway is decoration.
2. **The gateway's default backend forwards somewhere.** D1's `no_such_route` denies with `404`
   instead of proxying.
3. **The test asserts "not 200" rather than `404`.** A 503 from an empty backend and a connection
   refused because a container is down both satisfy "not 200" while proving nothing about routing.
   Task 4.4 asserts the `404`, and Task 4.5 keeps a positive case in the same class so a broken stack
   cannot pass by failing everything.

### The request-id chain, now complete

```
client ──HTTP──▶ HAProxy ──HTTP──▶ rider-service ──gRPC──▶ matching-service
                 mints              MDC + response          MDC for the call
                 X-Request-Id   header + Problem        (metadata key
                 (this slice)       Details property         x-request-id)
                                    (PUB-4-2)                (PUB-4-1)
```

**`unique-id-header` overwrites any client-supplied value, and that is deliberate.** AD-54 says the
gateway *mints* the id on every inbound request. Trusting a client-supplied one would let a caller
collapse two requests into one trace, which is the exact failure the id exists to prevent. Task 4.3
asserts the replacement rather than merely the presence.

`rider-service` still mints its own when no header arrives (PUB-4-2's D2). That is not redundant: AD-5
requires any surface reached outside the gateway to mint its own, and PUB-4-2's in-JVM tests reach it
directly. Both halves stay.

### Compose and Makefile traps

- **The `tests` runner is a peer container on the Compose network.** It reaches `haproxy:8080` by
  service name. `localhost` inside it is itself.
- **`docker compose up --wait` treats a container with no healthcheck as ready once it is running.**
  That is why Task 1.3 relies on `depends_on` for ordering and accepts a small startup race, and why a
  retry in the test is the right fix if one appears.
- **`make clean` is `down -v --remove-orphans` and deliberately keeps `infra/.env`.** A new service in
  the file is picked up; an orphaned container from an older file version is what `--remove-orphans`
  is for. Confirm both (Task 3.4).
- **`.pre-commit-tree` is a materialised copy of the index.** `infra/` is already in `pre-commit`'s
  shared-build-input list, so editing `haproxy.cfg` or `docker-compose.yml` analyses every service.
  No hook change is needed in this slice.
- **`make build` does not need the stack.** Only `test-integration` and `run` do. Keep it that way —
  the build gate staying independent of running containers is why a failed build never leaves a tagged
  image behind.

### Honest limits of this slice

**HAProxy's timeouts are conventional, not measured.** `connect 5s`, `client 30s`, `server 30s` are
round numbers chosen to be comfortably generous. AD-6 makes the gateway the place the *tightest* bound
should sit, and AD-47 says those numbers come from measurement under the NFR-2 stress run. Nothing is
wrong today because nothing is under load; say this rather than letting the numbers look derived.

**There is no rate limit, no queue bound and no `maxconn`.** AD-6's chain is Epic 4's work. Adding one
now would be a guess dressed as a guard.

**The gateway tests are the least isolated tests in the repository.** Four containers must be healthy,
so a failure can mean HAProxy, `rider-service`, `matching-service`, Postgres, or the code. Task 4.6 is
about making the failure message say which — treat it as load-bearing, not polish.

**HAProxy logs to stdout and nothing collects it.** The request id appears in the container's own
log and in each service's, but there is no aggregation. That is Epic 4 and Epic 7 work; it does not
weaken AC4c, which is about minting and propagation.

### Scope boundaries — what is deliberately not here

| Not in this slice | Where it lands |
| --- | --- |
| `contracts/`, the copy mechanism, `matching-service`'s gRPC surface, the value-type hardening | **PUB-4-1** (done) |
| `rider-service`, `POST /quotes`, Problem Details, `X-Rider-Id`, the ArchUnit rule copies | **PUB-4-2** (done) |
| Routes for `driver-service`, the Stripe webhook, audit's query API | Epics 2, 5, 6 — AD-5's list grows when a new **actor** appears |
| TLS, auth, anything at the gateway resembling security | Nowhere — FR-48 puts auth out of scope on purpose |
| Rate limits, `maxconn`, AD-6's bound chain, the tightest-bound-at-the-edge rule | Epic 4, sized by measurement (AD-47) |
| Prometheus scraping HAProxy, dashboards | Story 4.9 (PUB-36) |
| A route to Prometheus, Grafana or the live dashboard | **Never** — AD-5 keeps operator and observability surfaces off the route list deliberately, which is a *stronger* boundary than a route |
| Kubernetes Ingress, `deploy/` | Epic 7 (PUB-57, PUB-58) |

### Previous story intelligence

1. **A test that cannot fail is the finding that recurs in every review so far.** PUB-2 planted typos
   into a banned-method list and the suite stayed green. PUB-3 replaced `CalculateFare`'s body with a
   zero distance and the suite stayed green. In this slice the equivalent risk is Task 4.4 — a "not
   routable" assertion that would also pass against a gateway that is simply down. Task 4.5 is the
   guard; do not drop it.
2. **A rationale is not evidence.** Three guards written during PUB-1 each had a convincing comment and
   each guarded nothing. Task 1.3 refuses a HAProxy healthcheck for exactly this reason.
3. **Read `AGENTS.md` before writing code.** This slice writes almost no Java, but the test class it
   does write is subject to the same rules: `snake_case` test methods, `@DisplayName` carrying the
   criterion, private helpers at the bottom and in `camelCase`, and a comment only where a reader would
   otherwise be surprised.
4. **The File List is routinely incomplete.** PUB-3's review found ten touched files missing from it.
5. **`docs/` and `docs/tickets/pb-*.md` are a superseded planning attempt**, explicitly
   non-authoritative. If a search surfaces `pb-1.4.md`, ignore it.

### Pinned versions — do not move any of these

HAProxy **3.2.22** · PostgreSQL **18.6** · Docker Compose spec **3.9** · everything else unchanged from
PUB-4-2.

**`3.2.22` was verified against the Docker Hub registry on 2026-08-25** — `3.2.22` exists, `3.2.23`
does not — not asserted from memory. The spine's Stack table pins the `3.2.x LTS` line; the patch
version is this slice's choice, following `postgres:18.6`'s precedent of pinning the patch rather than
floating on a minor tag.

**No new dependency of any kind.** The gateway test uses the JDK's `HttpClient`; the gateway itself is
a stock image and a text file.

### Project Structure Notes

Target layout after this slice, and nothing beyond it:

```
infra/
  haproxy.cfg                                    (new -- D1)
  docker-compose.yml                             (edited -- haproxy added,
                                                  matching-service ports REMOVED)

services/rider-service/src/integrationTest/java/com/puber/rider/
  GatewayIntegrationTest.java                    (new -- Task 4, no Spring context)

Makefile                                         (edited -- Task 3)
project-context.md                               (edited -- Task 5.1)
README.md                                        (checked -- Task 5.2)
```

Nothing under any `src/main` changes in this slice. If a production class needs editing, something in
PUB-4-1 or PUB-4-2 was left unfinished — say so rather than absorbing it here.

### References

- `_bmad-output/planning-artifacts/epics/epic-1-foundations-fare-quote.md#Story 1.4: Rider gets a fare quote through the gateway` — the nine criteria this slice takes the last two-and-a-bit of
- `_bmad-output/implementation-artifacts/PUB-4-rider-gets-a-fare-quote-through-the-gateway.md` — the parent: the allocation table Task 5.4 checks against. **Index only; nothing there binds you**
- `_bmad-output/implementation-artifacts/PUB-4-1-*.md`, `PUB-4-2-*.md` — what the first two slices delivered, and their Dev Agent Records
- `_bmad-output/planning-artifacts/epics/overview.md#Standing acceptance criteria` — apply whether restated or not
- `ARCHITECTURE-SPINE.md#AD-5` (the gateway exposes edge services only — the route list, and why operator surfaces are deliberately not routes), `#AD-6` (queue bounds, and why the tightest sits at the gateway), `#AD-37` (REST at the edge), `#AD-47` (capacity is derived, not guessed), `#AD-54` (the gateway mints the request id), `#AD-56` (real stack, sequential), `#Stack` (HAProxy 3.2.x LTS), `#Consistency Conventions` (Logging, Container runtime)
- `prds/prd-puber-2026-08-02/prd.md#2. Scope` — *"behind a single gateway — no service is directly addressable, and `matching-service` is never publicly routable at all"*
- `_bmad-output/specs/spec-puber/SPEC.md#Constraints` — the same, in the contract
- `project-context.md` — binding project rules: the non-root container rules and why stock images are out of their scope, the hook policy, YAGNI
- `AGENTS.md` — coding style. **Read it before writing the test class; nothing loads it automatically**
- `_bmad-output/implementation-artifacts/PUB-1-*.md` — the Compose, healthcheck and Makefile patterns this slice extends; `deferred-work.md` for the unmeasured-healthcheck-timing precedent Task 5.3 follows

---

## Questions for the repo owner

None of these blocks implementation — every one is pinned above so the dev agent has a deterministic
answer.

1. **`matching-service` stops publishing port 8080 (D2).** This is what makes AC3 mean something, but
   it changes your local workflow today: no more `curl localhost:8080/actuator/health` against
   `matching-service`. That is the intended consequence of AD-5, not a side effect — but it is the one
   change in PUB-4 you will feel every day.
2. **`make test` gets slower again.** `test-integration` now brings up four containers. `pre-push` runs
   `make test`, so every push pays it. The alternative is not testing AC3, which I do not think is an
   alternative — but if the gate becomes intolerable, project-context.md is explicit that the answer is
   faster tests, never moving the gate.
3. **The gateway routes on `path_beg /quotes` (D1).** That is a prefix match, so `/quotesomething`
   would route too. The alternative is an exact path match, which breaks the moment a story adds
   `/quotes/{id}`. I chose the prefix; say if you would rather be strict now and relax later.
4. **HAProxy's timeouts are conventional, not measured (D1).** Recorded honestly rather than presented
   as derived. Task 5.3 routes them to Epic 4 alongside PUB-1's healthcheck-timing item, which is the
   same shape of debt.

---

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Change | By |
| --- | --- | --- |
| 2026-08-25 | Story created from epic-1 Story 1.4 as PUB-4, then split into PUB-4-1/2/3 at the repo owner's request; this is slice 3, and completing it completes PUB-4 | bmad-create-story |
