---
baseline_commit: 051f0a212065dcb5428b96aa7940363f25d95300
parent_ticket: PUB-4
slice: 1 of 3
---

# Story 1.4 (slice 1 of 3): The contract, and the quote over gRPC

Ticket: **PUB-4-1**
Parent: **PUB-4** — Rider gets a fare quote through the gateway
Status: ready-for-dev

**Do PUB-4-1 → PUB-4-2 → PUB-4-3 in that order.** PUB-4 is marked `done` when PUB-4-3 lands, not
before. The parent file `PUB-4-rider-gets-a-fare-quote-through-the-gateway.md` holds the acceptance
criteria allocation and nothing binding — **this file is the whole specification for this slice.**

## Story

As an engineer,
I want the fare calculation reachable over a versioned gRPC contract whose definition has one source,
so that a second service can ask for a price without either service depending on the other's code.

### What this story actually does, in plain words

PUB-3 built a price calculator that only a test can call. This slice gives it **a way in from
outside the process**, and gives the system **its first shared contract**.

Three things get built:

1. **`contracts/`** — one directory at the repository root holding a single `.proto` file that
   describes the quote call. It is copied into each service when that service builds. Neither
   service ends up depending on the other's code; the only shared thing is a text file.
2. **A gRPC surface on `matching-service`** — a `quote` feature package that takes two coordinates,
   asks the existing calculator for a price, and answers over gRPC.
3. **Input validation that did not exist** — PUB-3's value types (`Coordinates`, `Distance`,
   `FareRule`) accept nonsense today because every value they have ever seen was a literal written
   by us. This is the first slice where a stranger's data reaches them, so this is where they start
   rejecting it. It is also what decides whether a bad request is a clean `INVALID_ARGUMENT` or an
   ugly `INTERNAL`.

**There is no `rider-service`, no HTTP endpoint and no gateway in this slice** — PUB-4-2 and PUB-4-3
add those. If you find yourself writing a `@RestController` or editing HAProxy config, you have left
this story.

The quote returns a fare and a distance and **no arrival estimate**, because there are no drivers in
the system yet. That is a success, not an error.

---

## Acceptance Criteria

These are PUB-4's AC8, AC9, AC10 and AC11 in full, plus the `matching-service` half of AC1, AC2, AC4
and AC5. The clauses that belong to the edge are named in "Scope boundaries" and are **not** this
slice's to satisfy.

**AC1a — the quote is served over gRPC, and creates nothing**

**Given** a gRPC client
**When** it calls the quote RPC with pickup and dropoff coordinates
**Then** `matching-service` answers with the fare and the distance
**And** no ride is created
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4 (second clause of its first criterion);
prd.md#FR-1; ARCHITECTURE-SPINE.md#AD-37 — REST at the edge, gRPC between services]

**AC2a — no driver means no ETA, not an error**

**Given** no driver is available
**When** a quote is requested
**Then** fare and distance are returned and the ETA field is left unset
**And** the response is a success, never an error
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4; prd.md#FR-1; SPEC.md#CAP-1]

**AC4a — a request id arriving over gRPC metadata reaches every log line**

**Given** a gRPC call carrying a request id in its metadata
**When** `matching-service` handles it
**Then** that id appears in every log line the call produces
**And** a call arriving without one has one minted at entry
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4; ARCHITECTURE-SPINE.md#AD-54 — Every
service is observable the same way from day one; #AD-5 — a surface reached outside the gateway mints
its own]

**AC5a — a malformed coordinate is `INVALID_ARGUMENT`, and it says which field**

**Given** a quote request whose coordinates cannot be read or are out of range
**When** it is rejected
**Then** the gRPC status is `INVALID_ARGUMENT`
**And** the description names the offending field
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4; ARCHITECTURE-SPINE.md#AD-38 — One error
vocabulary, mapped at the façade. AD-38's table maps `INVALID_ARGUMENT` → 400; producing the right
status here is what lets PUB-4-2 produce the right HTTP code]

**AC8 — the contract has one source, copied mechanically**

**Given** the `.proto` defining this first internal hop
**When** it is stored
**Then** it lives in one versioned directory in the repository
**And** it is copied into each service at build time, never hand-edited per service
**And** the services still build independently, with no runtime or compile dependency between them
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4; ARCHITECTURE-SPINE.md#AD-52 —
Cross-service contracts have one source and are copied mechanically]

**AC9 — the contract evolves by addition only**

**Given** a contract change
**When** it is made
**Then** adding a field is safe, while removing, renaming, retyping or **changing what a field means**
is breaking and requires a new message alongside the old
**And** protobuf field numbers are never reused
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4; ARCHITECTURE-SPINE.md#AD-33 — Contracts
evolve by addition only]

**In plain terms, because this one has no code to write.** AC9 is a *rule about future edits*, not a
feature. There is no second version of the contract yet, so nothing can be tested by comparing two.
What this slice owes AC9 is (a) field numbers written explicitly and never re-used, (b) the rule
written down where the next editor will read it — a header comment in the `.proto` and
`contracts/README.md` — and (c) `optional` presence used for the ETA rather than a sentinel value,
because a sentinel is exactly the "changing what a field means" case AD-33 forbids. Say plainly in
the completion notes that AC9 is groundwork enforced by review, not by a test.

**AC10 — the value types reject input they cannot price (carried from PUB-3)**

**Given** a coordinate built from data this service did not write
**When** the value cannot be parsed as a decimal number, or is outside its range
**Then** construction fails with a message naming the field
**And** the failure surfaces as AC5a's `INVALID_ARGUMENT`, never as an `INTERNAL`
**And** this is proven end to end through the gRPC endpoint, not by a unit test on the type
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4, "Carried from PUB-3";
implementation-artifacts/PUB-3-*.md#Deferred — first deferred item; AGENTS.md#Integration tests by
default]

**Narrower than PUB-3's deferred item, deliberately, and the difference is measured rather than
argued.** That item listed four weaknesses and predicted all four *"become real at PUB-4's HTTP
edge."* Only the two coordinate ones do — D5a shows why, with the generated code as evidence — so the
other two are routed to the stories that genuinely make them reachable rather than guarded here
against a caller that cannot exist. **The criterion's own wording is what permits this:** *"built from
data this service did not write."*

**AC11a — the structural rules still bite once generated code exists**

**Given** generated contract classes on the production classpath
**When** the architecture rules run
**Then** a domain type that depends on a generated contract class fails the build
**And** that failure is demonstrated rather than asserted
[Source: ARCHITECTURE-SPINE.md#AD-8 — One-way dependency inside a service. This clause is this
slice's own finding, not the epic's: see T6.1 for the hole `contracts/` opens]

**Standing criteria that also apply here** (epics/overview.md#Standing acceptance criteria): any
bounded window is exercised by advancing the `Clock`, never by sleeping; any container this project
builds runs non-root, numerically declared on runtime images and taking the host UID/GID where it
mounts the repository.

---

## Story-local decisions you must implement as written

**Eight things this slice needs have no source anywhere in the planning artifacts.** The spine, the
PRD, the addendum, SPEC, the glossary and every epic were searched: no document states the layout of
`contracts/`, the wire types, the RPC name, or the gRPC port. They are pinned here so the
implementation is deterministic. If one is wrong, raise it and change it here first.

### D1 — `contracts/` at the repository root, copied like `static-analyzers/`

```
contracts/
  README.md
  proto/
    puber/quote/v1/quote.proto
```

The copy mechanism is the one AD-52 already established for the analyzer config, and that
`static-analyzers/` proves works. **Reuse it exactly; do not invent a second mechanism.**

- `make` copies `contracts/proto/` into `$(TREE)/services/<svc>/build/contracts/proto/` before that
  service's wrapper runs.
- `build.gradle` points the protobuf plugin's source directory at `build/contracts/proto`.
- `.dockerignore` needs `!build/contracts/` beside the existing `!build/static-analyzers/`, and the
  `Dockerfile` needs `COPY --chown=10001:10001 build/contracts build/contracts` beside the existing
  static-analyzers copy — **before** the `src` copy, so a `.proto` change does not invalidate the
  dependency-resolution layer.

**Those three files change together or the build breaks in a way that looks unrelated.** Miss the
`.dockerignore` line and the copy is silently excluded from the image context; miss the `Dockerfile`
line and codegen finds no `.proto` inside the image while working fine under `make build`.

**Both services get their own generated copy of the stubs and neither depends on the other.** That is
AC8's third clause, satisfied by construction: the only shared artefact is a text file copied at
build time. PUB-4-2 adds the second service to the same mechanism with no change to it.

### D2 — the contract itself

```protobuf
syntax = "proto3";

// Served by matching-service, which owns fare_rules (AD-3). Ownership is recorded here and in
// contracts/README.md rather than in the package, because the package is the wire name -- see D2a.
//
// AD-33: additive only. Adding a field is safe. Removing, renaming, retyping, or changing what an
// existing field MEANS is breaking and needs a new message beside the old one. Field numbers are
// never reused -- if you delete one, `reserved` it.
package puber.quote.v1;

option java_package = "com.puber.contracts.quote.v1";
option java_multiple_files = true;

service QuoteService {
  rpc GetQuote(GetQuoteRequest) returns (GetQuoteResponse);
}

message Coordinates {
  // Decimal text, not double: DECIMAL(10,8) / DECIMAL(11,8) survives the wire only as a string.
  string latitude  = 1;
  string longitude = 2;
}

message GetQuoteRequest {
  Coordinates pickup  = 1;
  Coordinates dropoff = 2;
}

message GetQuoteResponse {
  int64 fare_minor_units = 1;
  int64 distance_metres  = 2;
  // Absent means "no driver available" (FR-1). `optional` gives real presence, so absence never has
  // to be encoded as 0 or -1 -- a sentinel is exactly the AD-33 breach of changing what a field
  // means.
  optional int64 eta_minutes = 3;
}
```

Three type choices, each forced by a convention rather than chosen:

- **Money is `int64` minor units.** Money convention: *"integer on the wire"*. `1100` is €11.00.
- **Coordinates are `string`.** There is no protobuf decimal type. `double` would import exactly the
  floating-point error the Money and Coordinates conventions exist to keep out, and would put a
  `double` in a production signature — which `floatingPointIsConfinedToDistance` fails the build on.
  Decimal text round-trips `DECIMAL(10,8)` exactly.
- **`eta_minutes` is `optional`, and is never set in this project phase.** The field exists so AC2a's
  "unset" is expressible now; Story 2.6 (PUB-10) fills it. Minutes, matching the glossary's ETA
  definition.

**`java_package` is `com.puber.contracts.quote.v1`, deliberately outside both service packages.**
Generated code belongs to neither service. It also keeps the generated classes out of
`ArchitectureRulesTest`'s scan, which is anchored on `com.puber.matching` — see T6.1 for the hole
that opens up as a result.

### D2a — contracts are organised by domain, not by owning service

Raised in review on 2026-08-25 and recorded so it is not re-litigated. The obvious alternative is
`contracts/proto/matching-service/quote/v1/quote.proto`, since `matching-service` owns this endpoint
(AD-3). **It is the wrong axis, and three of the four reasons are mechanical rather than aesthetic.**

1. **`matching-service` is not a legal proto package.** Proven with `protoc 34.2` on 2026-08-25:
   `package matching-service.quote.v1;` fails with `quote.proto:2:17: Expected ";"` — `-` is not an
   identifier character. By-owner would therefore have to be `matching_service`, which AD-12 already
   rejects in spirit: *"Java packages stay suffix-free (`com.puber.rider`)"*, because `-service` names
   a container, not a namespace.
2. **The package is the wire name, and AD-33 makes it unrenameable.** A gRPC method path is
   `/<package>.<Service>/<Method>`, so the package would put the current *transport topology* into an
   identifier every client depends on. AD-9 already gives `quote` its own feature package; if it ever
   moves service, the path either lies or needs a rename — and renaming a package is exactly the
   "changing what a field means" breaking change AD-33 forbids. Verified by compiling both forms and
   reading the descriptor: `matching_service.quote.v1.QuoteService` vs `puber.quote.v1.QuoteService`.
3. **This repository already names contracts by entity.** The spine's Event naming convention is
   `<entity>.<past-tense-action>` — `ride.matched`, `payment.captured`, with *"the prefix always
   matches `entity_type`"* — not `matching-service.ride.matched`. AD-52 puts event schemas in this same
   directory from Epic 4, so by-owner paths would put two organising principles in one tree.
4. **Path-matching-package is a convention, not a compiler rule.** Also proven: protoc accepts a file
   at `puber/…` declaring `package matching_service…`. So the directory could disagree with the
   package — which is worse than either choice, and is what every protobuf linter exists to catch.

**What by-owner gets right, and how to get it anyway.** Ownership *should* be obvious. It is recorded
in two places that can change when ownership changes, unlike a wire identifier: a header comment on
the `.proto` naming the serving service, and an ownership table in `contracts/README.md` (Task 1.2).

### D3 — gRPC on `9090`, HTTP stays on `8080`

`matching-service` gains `spring.grpc.server.port=9090` — a dedicated listener, separate from its
actuator HTTP port. A separate port, not gRPC-over-servlet, because AD-5 wants the internal transport
on a surface the gateway does not front. **Neither port is published to the host** — `matching-service`
currently publishes `8080:8080`, and PUB-4-3 removes it; do not add a second publication here.

**Verify, do not assume, which server you got.** `matching-service` has `webmvc` on the classpath, and
Boot ships both a Netty gRPC server and a gRPC-over-servlet path (`NettyGrpcServerConfiguration` /
`ServletGrpcServerConfiguration`, selected by `MissingNetworkGrpcServerCondition`). If the servlet
path wins, gRPC is multiplexed onto `8080` and `9090` is never bound — and PUB-4-2's client then fails
to connect with no obvious cause. **Check the startup log for the bound gRPC port before writing any
test**, and record what you saw in the Debug Log so PUB-4-2 does not have to rediscover it.

### D4 — `Distance` gains `roundedToMetres()`, the one rounding point for the wire

AC1a returns a distance, and `Distance` stores a `double`. Rounding has to happen exactly once, in one
place, and that place is `Distance` itself — the type that already owns this problem and is the sole
exemption in `floatingPointIsConfinedToDistance`.

```java
/** Metres to the nearest whole one, HALF_UP. Lossy, unlike {@link #inKilometres()}. */
public long roundedToMetres() {
    return inMetres().setScale(0, RoundingMode.HALF_UP).longValueExact();
}
```

Do **not** call `distance.metres()` and cast at the call site. project-context.md is explicit: *"Do
not add a metres-returning accessor to anything else, and do not write a bare `/ 1000` or `* 2` at a
call site."* `inMetres()` stays private; `roundedToMetres()` joins `inKilometres()` as the second — and
last — *converted* number `Distance` hands out. (The record component `metres()` is a third, and is
public only because a record cannot hide one; it returns a `double`, so misusing it needs a visible
cast.)

**The name is `roundedToMetres()`, not `inWholeMetres()`, and the reason is worth a line** because the
obvious name is the wrong one. `long` already says the result is whole, so "whole" is redundant — and
it is ambiguous besides, reading as *entire* at least as easily as *integer*. What the name has to
carry is the thing the signature cannot: **this conversion loses information and `inKilometres()` does
not.** Two sibling accessors that look alike but differ on lossiness is exactly the confusion
`Distance` exists to prevent, and it is the same reasoning behind `Clock.wallClockNow()`'s deliberate
verbosity — the name is shaped so the mistake is visible where it would be made. Do not "tidy" it back
into the `inX()` shape: `inKilometres()` is the only member of that family, and the rest of this
codebase names by phrase (`distanceTo`, `deadlineIn`, `minutesToCover`, `hasReached`).

### D5 — validation lives with the type that owns the value

Every semantic check on a coordinate happens **here**, in `matching-service`, not at the edge that
PUB-4-2 builds. AD-38 puts error *mapping* at the façade; it does not put the domain there.

| Failure | Detected by | Becomes |
| --- | --- | --- |
| latitude/longitude not a decimal number, out of range, or null | `Coordinates` | `INVALID_ARGUMENT` |
| a distance that is negative, `NaN` or infinite | `Distance` | `INVALID_ARGUMENT` |
| `fare_rules` missing its row | `FareRuleRepository` | `INTERNAL` — a broken deployment, not a bad request |

**PUB-4-2 will get no copy of `Coordinates`.** It passes the two decimal strings through untouched and
lets the owning service's type judge them. That is the smallest amount of duplicated domain the
spine's *"duplicated domain code across services is accepted"* has to cover, and it is why AC10's
hardening is what makes PUB-4-2's `400` possible.

### D5a — what the gRPC edge can actually reach, and what AC10 therefore is

PUB-3's review deferred four value-type weaknesses with the note *"Unreachable today — nothing
constructs these from untrusted input. Becomes real at PUB-4's HTTP edge."* **That note is one-third
true, and the difference decides what you build.** Verified on 2026-08-25 by generating the Java from
D2's contract with `protoc 34.2` and reading it:

```java
private volatile java.lang.Object latitude_ = "";                       // an unset string is ""
public Coordinates getPickup() {
  return pickup_ == null ? Coordinates.getDefaultInstance() : pickup_;  // an unset message is a default
}
```

**Nothing null can arrive from the gRPC layer.** A request with no `pickup` at all yields a
`Coordinates` whose latitude is `""` — not a null one.

| PUB-3's deferred weakness | Reachable from this endpoint? |
| --- | --- |
| `Coordinates` latitude/longitude unparseable (`""`, `"abc"`) | **yes** — `new BigDecimal("")` throws |
| `Coordinates` latitude/longitude out of range (`"999"`) | **yes** — the existing range check |
| `Coordinates(null, x)` | **no** — proto3 strings are never null |
| `new Distance(-5000)`, `Double.NaN`, infinite | **no** — `Distance` is built only by `Coordinates.distanceTo`, over coordinates already range-checked, and haversine's output is bounded to `[0, 20_015_087]` |
| `new FareRule(…, null)` | **no** — read from `NOT NULL` columns |

**So AC10 is satisfied by the two reachable rows, and its own wording agrees:** *"built from data this
service did not write."* A `Distance` from our own haversine and a `FareRule` from our own `NOT NULL`
seed are data this service **did** write.

**Do not add the other three guards.** project-context.md → YAGNI is explicit — *"name the failure it
prevents, then check that the failure is real. If you cannot reproduce it, do not add it. A rationale
is not evidence"* — and it names three PUB-1 guards that each had a convincing comment and each
guarded nothing. Adding an unreachable null check here would be the fourth.

**They become real later, and that is where they belong.** Story 4.7 (PUB-34) builds `Distance` from
Redis `GEOSEARCH` output and Epic 2 builds `Coordinates` from driver heartbeats — both genuinely
external. Task 8.3 routes them to the epic file rather than only to `deferred-work.md`, which nothing
reads.

### D6 — every existing integration test now starts a gRPC server, and they will fight over the port

**Read this before running the suite for the first time after D3.** There are four `@SpringBootTest`
classes in `matching-service` today. Spring **caches contexts and does not close them** between test
classes, so four live contexts each auto-configuring a gRPC server on a fixed `9090` is a bind
conflict — and the failure will look like an unrelated test breaking.

Two changes, and they are not alternatives:

- **The new quote test uses the in-process transport**, not a port:
  `@org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport`, with the channel
  from the auto-configured `TestGrpcChannelFactory`. `spring-boot-starter-grpc-server-test` already
  brings `grpc-inprocess`, so this needs no new dependency. It is also the *right* test: it exercises
  the service implementation and the interceptor without asserting anything about networking, which
  PUB-4-3's gateway tests cover properly.
- **Every other `@SpringBootTest` in `matching-service` pins `spring.grpc.server.port=0`** — a dynamic
  port — via its own `properties = …`. `@LocalGrpcServerPort` injects the resolved value if a test
  ever needs it. Per-class properties rather than an `src/integrationTest/resources/application.properties`,
  because a same-named file in the integration source set **shadows** `main`'s and would silently drop
  the datasource and actuator configuration.

**This is a prediction from reading the autoconfiguration, not a reproduced failure.** Confirm it the
first time you run `make test` after adding the starter. If all four classes are green with a fixed
`9090`, then contexts are being closed or the servlet path is in play — record which, and **drop
whichever half of the fix turned out to be unnecessary** rather than leaving it as decoration
(project-context.md → YAGNI: a rationale is not evidence).

---

## Tasks / Subtasks

### Task 1 — `contracts/` and the copy mechanism (AC8, AC9, D1, D2)

- [ ] **1.1** Create `contracts/proto/puber/quote/v1/quote.proto` exactly as D2 specifies, header
      comment included.
- [ ] **1.2** Create `contracts/README.md`: what the directory is, that AD-52 makes it the single
      source, that AD-33 makes edits additive-only, that field numbers are never reused, and that
      `make` copies it into build output. **Point at AD-33 and AD-52 rather than restating them** —
      two files holding one rule drift, and the stale copy outlives the true one.
      It also carries **an ownership table** — one row per contract, naming the service that serves it
      — and D2a's one-line reason that ownership lives here and in the `.proto` header rather than in
      the path. One row today:

      | Contract | Served by | Called by |
      | --- | --- | --- |
      | `puber/quote/v1/quote.proto` | `matching-service` (AD-3) | `rider-service` |

      This is the answer to "who do I call", which the by-domain path deliberately does not encode.
- [ ] **1.3** `Makefile`: extend the existing `analyzer-config` target — or add a sibling target that
      `build`, `static-analysis`, `format`, `test-unit` and `test-integration` all depend on, exactly
      as `analyzer-config` does — to copy `contracts/proto/` into
      `$(TREE)/services/<svc>/build/contracts/proto/`. Fail loudly when `contracts/proto` is missing,
      mirroring the existing `ANALYZER_SOURCES` guard. It must be written generically over
      `$(SERVICES)`, so PUB-4-2's new service is picked up with no edit here.
- [ ] **1.4** `.githooks/pre-commit`: add `contracts/` to the shared-build-input grep
      (`^(static-analyzers/|Makefile$|infra/|\.githooks/)`), so editing the contract analyses every
      service rather than none. Leave `pre-push` alone — it already runs everything, which is why the
      epic says there is no `contracts/` special case there.
- [ ] **1.5** `matching-service`: `.dockerignore` gains `!build/contracts/`; `Dockerfile` gains
      `COPY --chown=10001:10001 build/contracts build/contracts` **above** the `COPY src src` line.
- [ ] **1.6** Extend `build.gradle`'s existing missing-analyzer-config `GradleException` message to
      mention the contract copy too, since after 1.3 running `./gradlew` directly bypasses both.

### Task 2 — protobuf codegen in `matching-service` (AC8, D1, D2)

- [ ] **2.1** Add the protobuf Gradle plugin: `id 'com.google.protobuf' version '0.9.6'`.
      **`0.9.6` is the latest release** (verified against the Gradle Plugin Portal on 2026-08-25:
      `0.9.6` resolves, `0.9.7` does not). Per-service, not in `static-analyzers/`: that directory is
      scoped to *analysis*, and this is a build plugin.
- [ ] **2.2** Point it at the copied contract and read both generator versions out of Boot's BOM:
      ```groovy
      sourceSets.main.proto.srcDir layout.buildDirectory.dir('contracts/proto')

      protobuf {
          protoc { artifact = "com.google.protobuf:protoc:${dependencyManagement.managedVersions['com.google.protobuf:protobuf-java']}" }
          plugins { grpc { artifact = "io.grpc:protoc-gen-grpc-java:${dependencyManagement.managedVersions['io.grpc:grpc-api']}" } }
          generateProtoTasks { all().each { it.plugins { grpc {} } } }
      }
      ```
      **Read the versions, do not pin them** — the same reasoning `integrationTest`'s
      `useJUnitJupiter(dependencyManagement.managedVersions[...])` already uses in this file. Boot
      4.1.0 manages `protobuf-java 4.34.2` and `grpc-java 1.80.0`; a hand-pinned generator that
      drifts from the managed runtime is the failure this avoids. Confirm the resolved values in the
      build log.
- [ ] **2.3** Dependencies:
      ```groovy
      implementation 'org.springframework.boot:spring-boot-starter-grpc-server'
      implementation 'io.grpc:grpc-protobuf'
      implementation 'io.grpc:grpc-stub'
      implementation 'com.google.protobuf:protobuf-java'
      compileOnly    'javax.annotation:javax.annotation-api:1.3.2'
      testImplementation 'org.springframework.boot:spring-boot-starter-grpc-server-test'
      ```
      Three of these look redundant and are not — see Dev Notes, "The four gRPC dependency traps".
- [ ] **2.4** Verify the generated stubs compile **before writing any application code**: `make
      build`, then read `services/matching-service/build/generated/source/proto/main/` and confirm
      both the message classes and `QuoteServiceGrpc` are there. **Check whether
      `@javax.annotation.Generated` actually appears in `QuoteServiceGrpc.java`** — if it does not,
      drop 2.3's `compileOnly` line rather than leaving a dependency nothing needs.
- [ ] **2.5** Confirm Spotless does not try to format generated code: `make static-analysis
      SERVICE=matching-service` stays green. It should, because the target glob is
      `src/*/java/**/*.java` and generated sources live under `build/`. **Confirm it; do not assume
      it** — the protobuf plugin adds generated directories to `sourceSets.main.java.srcDirs`, and if
      Spotless ever resolved its target through the source set instead of the glob this goes red.

### Task 3 — the `quote` feature package (AC1a, AC2a, D2, D3)

- [ ] **3.1** `quote/model/Quote.java` — a record carrying `Money fare` and `Distance distance`. **No
      ETA field**: there is no driver to derive one from, and an always-null field is scaffolding
      (project-context.md → YAGNI). The absence lives in the wire contract, where AC2a needs it.
- [ ] **3.2** `quote/service/QuoteTrip.java` — a Spring bean taking `FareRuleRepository`, with one
      public method `execute(Coordinates pickup, Coordinates dropoff)` returning `Quote`. It composes
      what already exists: `pickup.distanceTo(dropoff)`, then
      `CalculateFare.calculate(fareRules.priceList(), distance)`. **Write no arithmetic here** —
      AGENTS.md's "No test-only seams" section uses this exact class as its worked example of the bad
      shape, and PUB-3 shipped that bad shape before the repo owner caught it in review.
- [ ] **3.3** `quote/controller/QuoteGrpcService.java` — annotated
      `@org.springframework.grpc.server.service.GrpcService`, extending the generated
      `QuoteServiceGrpc.QuoteServiceImplBase`. It parses the two decimal strings into `BigDecimal`,
      builds `Coordinates`, calls `QuoteTrip`, and maps `Quote` → `GetQuoteResponse` using
      `Money.minorUnits()` and `Distance.roundedToMetres()`. **Leaves `eta_minutes` unset** (AC2a).
- [ ] **3.4** Map a bad coordinate to `INVALID_ARGUMENT` (AC5a, D5). A `GrpcExceptionHandler` bean or
      `@GrpcExceptionHandler` on an advice class — both exist, in
      `org.springframework.grpc.server.exception` and `...server.advice`. `IllegalArgumentException`
      and `NumberFormatException` out of `Coordinates` or `Distance` construction →
      `INVALID_ARGUMENT` carrying the constructor's message. Everything else keeps the default.
- [ ] **3.5** `application.properties`: `spring.grpc.server.port=9090`, and D3's verification.
- [ ] **3.6** `shared/model/Distance.java`: add `roundedToMetres()` per D4.

### Task 4 — the value types start rejecting input (AC10, D5, D5a)

**Read D5a first.** Only one of the three types PUB-3 deferred is actually reachable from the gRPC
edge, and that changes both what you build and how you prove it.

- [ ] **4.1** `Coordinates`: reject a latitude or longitude that cannot be parsed as a decimal number,
      **naming the field**. Today `new BigDecimal("")` throws a bare `NumberFormatException` from
      inside `QuoteGrpcService`'s parse with nothing saying which of the four values was bad. The
      existing range check already names its field — match its message shape.
- [ ] **4.2** Confirm the range check still fires for a parseable but out-of-range value, and that its
      message reaches the caller. It exists already; this is a proof, not a change.
- [ ] **4.3** **Every rejection in 4.1 and 4.2 is proven by an integration test through the gRPC
      endpoint, not by a unit test** — see Task 7.3. One test then proves three things a unit test
      cannot: that the guard fires, that `INVALID_ARGUMENT` is the status, and that the description
      still names the field by the time it crosses the wire. That last part is what PUB-4-2's `400`
      body depends on, and it is exactly the seam a unit test steps over. AGENTS.md → *"Integration tests
      by default; unit tests only where there is no chain to prove."*
- [ ] **4.4** **Reproduce each failure before fixing it** — send the bad request, capture the ugly
      `INTERNAL` or the unnamed field, then add the guard and watch the same request turn into a
      named `INVALID_ARGUMENT`. project-context.md → YAGNI: *"a rationale is not evidence."*
- [ ] **4.5** **Do not add the null guards to `Coordinates`, `Distance` or `FareRule`, and do not add
      `Distance`'s negative/`NaN`/infinite guard** — D5a shows none of them is reachable, and
      project-context.md forbids a guard whose failure cannot be reproduced. Record them where the
      story that makes them reachable will read it (Task 8.3).
- [ ] **4.6** Do **not** add CHECK constraints to `base_fare`, `per_km_rate` or `per_minute_rate`.
      PUB-3's review deferred that as an architecture decision (AD-62 specifies exactly one CHECK);
      this slice does not reopen it.

### Task 5 — the request id, server side (AC4a)

- [ ] **5.1** A `ServerInterceptor` bean annotated
      `@org.springframework.grpc.server.GlobalServerInterceptor` that reads metadata key
      **`x-request-id`** into MDC under `requestId` for the duration of the call, and
      **clears it in a `finally`**. A pooled thread otherwise carries the previous call's id into the
      next call's logs, which is worse than no id at all.
- [ ] **5.2** Mint a `UUID` when the metadata is absent (AD-5: a surface reached outside the gateway
      mints its own). Nothing should arrive without one after PUB-4-3, but a direct gRPC client can,
      and the in-process tests do.
- [ ] **5.3** `logging.pattern.level=%5p [%X{requestId:-}]` in `application.properties`, so the id
      is on **every** line and not only the ones that remember to interpolate it. **Do not reach for
      `logging.pattern.correlation`** — in Boot that slot is driven by Micrometer tracing's trace and
      span ids, which this project does not have.
- [ ] **5.4** **gRPC metadata keys must be lowercase.** `Metadata.Key.of("x-request-id",
      ASCII_STRING_MARSHALLER)`; an uppercase key throws at construction, so you find out
      immediately — recorded here so you do not spend the minute wondering why.

### Task 6 — make the rules bite against generated code (AC11a)

- [ ] **6.1** **Close the hole `contracts/` opens in `modelDependsOnNothingFrameworkFlavoured`.** That
      rule allows `..model..` to depend on `java..` or **`com.puber..`** — and the generated stubs are
      `com.puber.contracts.quote.v1`, so a domain record could import a protobuf message and pass.
      Exclude the contracts package from the allowlist, and **prove it**: plant a `Quote` field typed
      as `GetQuoteResponse`, watch the rule go red, capture the failure, revert, confirm green.
- [ ] **6.2** Confirm the generated classes are outside `ArchitectureRulesTest`'s `com.puber.matching`
      scan, as D2 intends. If they are inside it, the `java_package` is wrong — **fix the `.proto`,
      not the rule.**
- [ ] **6.3** `featureDependenciesRunOneWay` already declares a `quote` layer and has been vacuous
      until now. It becomes live in this slice. Prove it: plant a `fare` type depending on `quote`,
      watch it fail, revert.
- [ ] **6.4** `HealthMetricsAndSchemaIntegrationTest`: this slice adds **no** migration, so
      `HIGHEST_VERSION_THIS_STORY_OWNS` stays `3` and `TABLES_THIS_SERVICE_OWNS` stays
      `List.of("fare_rules")`. Named here so you do not bump a constant out of habit.

### Task 7 — tests (AC1a, AC2a, AC4a, AC5a, AC10)

Unit tests in `src/test/java`, integration tests in `src/integrationTest/java`, `snake_case` methods,
`@DisplayName` carrying the `AC<n>:` reference (AGENTS.md → Test Naming and Placement).

- [ ] **7.0** Apply D6 **first** — `@AutoConfigureTestGrpcTransport` on the new test,
      `spring.grpc.server.port=0` on the four existing `@SpringBootTest` classes — and record in the
      Debug Log what the suite did before and after, so the fix is evidence rather than precaution.
- [ ] **7.1** Integration: the quote RPC returns the hand-computed fare and distance for two
      **distinct** coordinates, and leaves `eta_minutes` unset (AC1a, AC2a). **Two distinct
      coordinates, never `LISBON, LISBON`** — PUB-3's review proved a same-point test multiplies every
      rate by zero and cannot fail.
- [ ] **7.2** Integration: `fare_rules` is unchanged and no row exists anywhere after a quote (AC1a's
      "creates nothing"). The honest cheap form is a row count over every table this service owns
      before and after.
- [ ] **7.3** Integration, and this is where **all** of AC10 is proven (Task 4.3). Five requests
      through the gRPC endpoint, each answering `INVALID_ARGUMENT` with a description **naming the
      offending field**:
      - a latitude that is not a number (`"abc"`)
      - an empty latitude (`""`) — which is also what an entirely **missing `pickup`** produces, since
        proto3 has no null (D5a); assert both and let the test say they are the same case
      - a latitude out of range (`"91"`)
      - a longitude out of range (`"-181"`)
      - a well-formed request where only the **dropoff** is bad, so the message proves it names
        *which* of the four values failed rather than just "latitude"
      A unit test cannot cover this: the thing under test is the whole chain — guard fires, exception
      maps to `INVALID_ARGUMENT`, description survives to the wire — and PUB-4-2's `400` body depends
      on every link of it.
- [ ] **7.4** Integration: a call carrying a known request id in metadata produces log lines
      carrying that id (AC4a), and a call carrying none still produces an id. Capture the log output
      rather than asserting on the interceptor — an interceptor test proves the interceptor ran, not
      that the pattern picked the value up.
- [ ] **7.5** Unit: `Distance.roundedToMetres()` rounding at a `.5` boundary (D4). This is the one
      genuinely unit-shaped thing in the slice — a pure function over a value, with no chain to prove.
      **Task 4's rejections are deliberately not here**; they are integration tests, per 7.3.

### Task 8 — record what this slice settled

- [ ] **8.1** `project-context.md`: add only what is non-obvious from the code and not already in the
      spine — the `contracts/` copy mechanism and the three files that must change together; the
      Boot 4.1 gRPC starter coordinates and the four dependency traps; whichever half of D6 turned out
      to be real. **Do not restate AD-33, AD-37, AD-38, AD-52 or AD-54** — CLAUDE.md forbids
      duplicating a rule across files.
- [ ] **8.2** `contracts/README.md` (Task 1.2) carries the AD-33 discipline for the next editor.
- [ ] **8.3** Anything deferred out of this slice goes in `deferred-work.md` **and** in whichever of
      the epic file / `sprint-status.yaml` `action_items` / `project-context.md` will actually surface
      it. `deferred-work.md` alone is an audit trail nothing reads. **D5a's three unreachable guards
      are this slice's main entry**: route the `Distance` one to Story 4.7 (PUB-34), where Redis
      `GEOSEARCH` output first builds one, and the `Coordinates` null one to Epic 2, where driver
      heartbeats do. State that PUB-3's *"becomes real at PUB-4's HTTP edge"* was one-third right, so
      the next reader does not re-derive it.
- [ ] **8.4** If D3's port verification or D6's port conflict came out differently than predicted,
      **correct this story file** so PUB-4-2 reads the truth.

### Task 9 — the gate

- [ ] **9.1** `make build`, then `make test`, **in that order, from a clean tree**, and read the
      output. Not `make test-unit`: it runs neither Spotless nor the integration suite, which is how
      PUB-2's review left the build red while reporting the suite green.
- [ ] **9.2** Report what you saw, including the known environmental red —
      `HealthReportsDownPromptlyIntegrationTest`'s precondition — named rather than omitted, and never
      quietly counted as green.
- [ ] **9.3** Only then set this story and `sprint-status.yaml` to `review`. **Leave `PUB-4` itself at
      `in-progress`** — the parent is `done` only when PUB-4-3 lands.
- [ ] **9.4** Leave everything **unstaged**. The repo owner reviews the unstaged diff. `pre-commit`
      analyses the index, so an unstaged fix changes nothing about what the gate sees.

---

## Dev Notes

### What already exists, and what you are adding

`matching-service` after PUB-3 — eleven production classes, three migrations, one table:

```
src/main/java/com/puber/matching/
  MatchingServiceApplication.java
  config/ClockConfiguration.java
  shared/model/     Coordinates.java  Deadline.java  Distance.java  Money.java
  shared/strategy/  Clock.java  SystemClock.java
  fare/model/       AssumedSpeed.java  FareRule.java
  fare/repository/  FareRuleRepository.java
  fare/service/     CalculateFare.java
src/main/resources/db/migration/
  V1__baseline.sql  V2__create_fare_rules.sql  V3__seed_fare_rules.sql  README.md
```

There is **no `controller` package anywhere in the repository**, and no `quote`, `ride` or `dispatch`
package. You create `contracts/` and `quote` (all three layers). You create no migration, no table,
no second service, and no HTTP endpoint.

### The four gRPC dependency traps

Boot 4.1 brought gRPC in-house, and **every published Spring gRPC example predates that.** Four
things follow, each one a build failure if you get it wrong. All four were verified against
`spring-boot-dependencies-4.1.0.pom` and Maven Central on 2026-08-25.

1. **The starter is Boot's, not Spring gRPC's.**
   `org.springframework.boot:spring-boot-starter-grpc-server`, with `-grpc-server-test` beside it. The
   coordinate every tutorial shows — `org.springframework.grpc:spring-grpc-spring-boot-starter` — **is
   not in Boot's BOM at all**, so it resolves with no version and the build fails on an unversioned
   dependency. The underlying library `org.springframework.grpc:spring-grpc-core:1.1.0` *is* managed;
   you should not need to name it.

2. **`grpc-protobuf` is `runtime` scope inside the starter, and generated stubs need it at compile
   time.** `spring-boot-starter-grpc-server` pulls `grpc-services`, which declares `grpc-protobuf` as
   `runtime`. Generated `*Grpc.java` references `io.grpc.protobuf.ProtoUtils`, so without an explicit
   `implementation 'io.grpc:grpc-protobuf'` you get a compile error in code you did not write. Same
   for `protobuf-java`, and for `grpc-stub` — which the *client* starter happens to include at
   `compile` scope and the server starter does not.

3. **`protoc-gen-grpc-java` 1.80.0 still emits `@javax.annotation.Generated`, and Java 25 has no
   `javax.annotation`.** Confirmed by inspecting the generator binary: the string
   `@javax.annotation.Generated(` is present in it. Hence
   `compileOnly 'javax.annotation:javax.annotation-api:1.3.2'` — `compileOnly`, because the
   annotation is not needed at runtime and shipping a `javax.*` jar into a Boot 4 image invites
   confusion with `jakarta.*`. **Task 2.4 asks you to confirm it is actually emitted before keeping
   the line**: the generator holding the string is strong evidence, not proof that this template
   emits it.

4. **Versions come out of Boot's BOM, not out of your head.** Boot 4.1.0 manages `grpc-java 1.80.0`,
   `protobuf-java 4.34.2` and `spring-grpc 1.1.0`. The protobuf **Gradle plugin** is the one thing
   Boot does not manage — it ships only the Maven plugin's version — so `0.9.6` is pinned in
   `build.gradle`, and it is the latest release.

**One thing that looks like a violation of project-context.md and is not.**
`spring-boot-starter-grpc-server-test` depends transitively on the **monolithic
`spring-boot-starter-test`**, along with `spring-boot-grpc-client`, `grpc-inprocess` and `grpc-stub`.
project-context says to use the split capability starters rather than the monolith — and this *is* the
capability starter; what it drags in behind it is Boot's choice, not ours. **Do not add an `exclude`
block to "fix" it.** The rule is about what you declare.

**A new dependency has to be downloadable.** Everything in Task 2.3 resolves from `mavenCentral()`
(`repo.maven.apache.org`) and the plugin from the Gradle Plugin Portal, and the protobuf plugin also
fetches two **native executables** (`protoc` and `protoc-gen-grpc-java`) on first build. If a build
fails on resolution rather than compilation, that is a network-policy question, not a coordinate
question — read the error body before changing a version.

### The Spring gRPC API surface, as it actually exists in 1.1.0

Verified by listing the shipped classes rather than from documentation:

| What you want | The type |
| --- | --- |
| declare a gRPC service bean | `@org.springframework.grpc.server.service.GrpcService` |
| a global server interceptor | `@org.springframework.grpc.server.GlobalServerInterceptor` |
| map an exception to a status | `org.springframework.grpc.server.exception.GrpcExceptionHandler`, or `@GrpcExceptionHandler` on an advice class (`...server.advice`) |
| the server port | `spring.grpc.server.port` (`0` for a dynamic one) |
| an in-process transport in a test | `@org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport`, with `TestGrpcChannelFactory` |
| the resolved port in a test | `@org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort` |

The client-side half — `@ImportGrpcClients`, `spring.grpc.client.channel.<name>.target` — belongs to
PUB-4-2 and is documented there.

### Where the layer rules put each new class

AD-8's directions are already enforced, so a misplaced class fails the build rather than review:

- **A gRPC service implementation is a `controller`.** It is an inbound protocol adapter, and nothing
  may depend on it (`nothingDependsOnController`).
- **`model` may see only `java..` and `com.puber..`** — and Task 6.1 removes `com.puber.contracts..`
  from that. So no domain record holds a protobuf message.
- **`service` may not name a concrete strategy implementation.** This slice adds no strategy, so the
  rule stays vacuous — leave it in place; a rule added after the code it governs gets negotiated
  against existing violations rather than enforced.
- **AD-9's feature order is `shared ← fare ← ride ← dispatch ← quote`.** `quote` is the topmost layer,
  so it may depend on `fare` and `shared`, and nothing may depend on it.

### The `Clock` — and why this slice probably reads no time

A quote is a pure function of two coordinates and one row of rules (AD-62). It reads no clock, and
`fare_rules` has no timestamp columns by PUB-3's D8.

Task 5's request id is a `UUID`, not a timestamp. If you find yourself wanting `Instant.now()` the
build will stop you: `timeIsReadOnlyThroughTheClock` fails on any read outside `SystemClock`, and
`theRealClockIsOnlyEverInjected` on any class outside `config` that names `SystemClock`. Both are live
and non-vacuous here. `java.time.Clock` collides with ours and an IDE auto-imports the JDK one first —
read the imports of any file touching time.

### Boot 4.1 / Java 25 traps that apply here

From project-context.md → "Boot 4.1 / Java 25", narrowed to this slice:

- **Split test starters.** No monolithic `spring-boot-starter-test` in what you *declare*. Every
  capability starter you add needs its `-test` sibling — including the gRPC one.
- **Jackson 3** — `tools.jackson.databind.*`. `com.fasterxml.jackson` is not on the classpath. Do not
  add Jackson 2 to "fix" a missing package. Nothing in this slice should need Jackson at all.
- **Flyway must never be pinned**, and this slice adds no migration.
- **`@AutoConfigureMetrics`** is needed wherever a test asserts on `/actuator/prometheus`. This slice
  adds no such test, but do not remove it from the existing one.

### Gradle and Compose traps recorded by PUB-1 through PUB-3

- **`make build` runs `./gradlew build --no-deps`, so no datastore is running.** Anything touching a
  socket lives in `src/integrationTest/java` or it fails every build. D6's in-process transport keeps
  the new test off a socket, but it still needs Postgres for `fare_rules` — so it is an integration
  test.
- **Do not run `./gradlew` directly inside a service.** It bypasses the analyzer-config copy, and
  after Task 1.3 the contract copy too; `build.gradle` throws a `GradleException` telling you so
  (Task 1.6 extends its message).
- **A rename can crash `make format`.** Spotless's `build/spotless-clean` copies stop resolving;
  `make analyzer-config` clears them. You are adding a lot of files, so expect this.
- **Spotless formats all three source sets** under `src/*/java/**/*.java`. New `main`, `test` and
  `integrationTest` code must be AOSP-formatted or `make build` goes red.
- **`./gradlew check --warning-mode all` reports zero deprecations today.** Keep it that way; use
  assignment syntax in the Groovy DSL, never the space form.
- **`matching-postgres-data` is a named volume shared by every checkout on the machine.** This slice
  adds no migration, so the checksum trap does not apply — but if you find yourself adding one,
  `make clean` before iterating on it.

### Honest limits of this slice

**Two things here were reasoned from the autoconfiguration, not reproduced.** D3's Netty-versus-servlet
warning, and D6's context-caching port conflict. Every version, coordinate and API name in this file
was verified against upstream artefacts on 2026-08-25 — the *runtime behaviour* of those two was not,
because it needs the build to exist first. They are the first two things to check, and Task 8.4 asks
you to correct this file if either is wrong.

**AC2a is only half a criterion today.** "No driver is available" is not a branch this slice can
choose between — there are no drivers at all, so the ETA is *always* unset. The test proves the unset
case; the present case lights up in Story 2.6 (PUB-10). Do not manufacture a fake driver to exercise
the other branch.

**AC9 has no test and cannot have one.** It is a rule about a future edit. What it gets is explicit
field numbers, `optional` presence instead of a sentinel, and the rule written where the next editor
reads it. Say so in the completion notes.

**AC10 ships narrower than PUB-3's review predicted, and three guards are deliberately absent.** D5a
has the evidence: proto3 has no null, so two of the four deferred weaknesses stay unreachable after
this slice and one was never reachable at all. Leaving them out is project-context.md's YAGNI rule
applied, not a criterion dropped — but it is a judgement, and a reviewer reading only PUB-3's deferred
list will expect all four. Say plainly in the completion notes which three were left out, that Task
8.3 routed them onward, and that the reachable two are proven through the endpoint rather than on the
type.

**Nothing an actor can use ships in this slice.** Its only consumer for a phase is tests, which
`epics/overview.md` explicitly permits (*"a story that delivers a requirement is legitimate even when
tests are its only consumer for a phase — the Simulator is the standing example"*). It is still the
weakest of PUB-4's three slices on that measure, and worth saying rather than glossing.

### Scope boundaries — what is deliberately not here

| Not in this slice | Where it lands |
| --- | --- |
| `rider-service`, any HTTP endpoint, RFC 9457 Problem Details, `X-Rider-Id` | **PUB-4-2** |
| The request-id filter and gRPC **client** interceptor | **PUB-4-2** |
| Copying the ArchUnit rules into a second service | **PUB-4-2** |
| HAProxy, the gateway route, removing `matching-service`'s published port | **PUB-4-3** |
| Bringing the whole stack up for tests | **PUB-4-3** |
| ETA in a quote, driver proximity, the geo index | Story 2.6 (PUB-10) |
| `rides`, the ride state machine, `POST /rides` | Stories 3.1–3.2 (PUB-12, PUB-13) |
| The rest of AD-38's mapping table — 409, 404, `ABORTED` | The stories that create those states |
| Kafka, the outbox, any event | Epic 4 |
| CHECK constraints on the three rate columns | Deferred by PUB-3's review as an architecture decision |
| Any change to the fare formula, the seeded rates, or `fare_rules` | Nowhere — PUB-3 settled all of it |
| JSpecify, `@NullMarked`, any `package-info.java` | **PUB-60** (Story 1.5), after all of PUB-4 |

### Previous story intelligence

PUB-1, PUB-2 and PUB-3 are all `done`. The learnings that change what you do here:

1. **A test that cannot fail is the finding that recurs in every review so far.** PUB-2 planted typos
   into a banned-method list and the suite stayed green. PUB-3 replaced `CalculateFare`'s body with a
   zero distance and the suite stayed green, because both callers passed the same point twice. That is
   why Tasks 6.1, 6.3 and 7.1 are written as plant-run-capture-revert rather than as assertions.
2. **A rationale is not evidence.** Three guards written during PUB-1 each had a convincing comment
   and each guarded nothing. Task 4 asks you to reproduce each failure before fixing it, and Task 8.1
   asks you to delete whichever half of D6 turned out to be unnecessary.
3. **Read `AGENTS.md` before writing code.** PUB-2 and PUB-3 both shipped comments the Comments rule
   forbids, because neither run had the file open when the code was written. Its "No test-only seams"
   section names `CalculateFare` — the class Task 3.2 composes — as its worked example.
4. **The File List is routinely incomplete.** PUB-3's review found ten touched files missing from it.
   Keep the list as you go.
5. **`docs/` and `docs/tickets/pb-*.md` are a superseded planning attempt**, explicitly
   non-authoritative and stale on the payment flow, the ride state machine and the database topology.
   If a search surfaces `pb-1.4.md`, ignore it.

### Git intelligence

```
051f0a2 PUB-3
2640dbe PUB-2
41f2540 PUB-1
485e571 PUB-1 defined
f943d1e sprint planning
```

One commit per story, after the full gate passes. PUB-3's commit is the shape to extend: a feature
package across three layers, new ArchUnit rules with fixtures proving they can fail, and an
`AGENTS.md` amendment — and its review caught that `AGENTS.md` was left unstaged, shipping enforcement
without the rule authorising it.

### Pinned versions — do not move any of these

Java/Temurin **25** · Spring Boot **4.1.0** · Gradle wrapper **9.5.1** · PostgreSQL **18.6** ·
Flyway **12.4.0 (BOM-managed, never pinned)** · JUnit Jupiter **6.0.3** · ArchUnit
**`archunit-junit6:1.5.0`** · Spotless **8.10.0** · `io.spring.dependency-management` **1.1.7** ·
protobuf Gradle plugin **0.9.6** · `javax.annotation-api` **1.3.2 (`compileOnly`)**

BOM-managed and therefore **not** written into `build.gradle`: `grpc-java` **1.80.0**,
`protobuf-java` **4.34.2**, `spring-grpc` **1.1.0**.

Every version above was verified against upstream release data on 2026-08-25 —
`spring-boot-dependencies-4.1.0.pom` for the managed set, Maven Central for the artifacts, the Gradle
Plugin Portal for the protobuf plugin. **Nothing here is asserted from memory.**

**One new build plugin and one new `compileOnly` dependency is the whole budget.** If you think you
need another library, stop and say why — adding one is an architecture decision here, not a tooling
choice.

### Project Structure Notes

Target layout after this slice, and nothing beyond it:

```
contracts/                                        (new -- AC8)
  README.md
  proto/puber/quote/v1/quote.proto

services/matching-service/
  build.gradle                                    (edited -- protobuf plugin, gRPC deps, Task 1.6)
  Dockerfile  .dockerignore                       (edited -- Task 1.5)
  src/main/java/com/puber/matching/
    quote/model/       Quote.java                 (new)
    quote/service/     QuoteTrip.java             (new)
    quote/controller/  QuoteGrpcService.java      (new)
                       QuoteStatusMapper.java     (new -- Task 3.4)
    config/            RequestIdServerInterceptor.java   (new -- Task 5.1)
    shared/model/      Coordinates.java  Distance.java       (edited -- Tasks 4.1, 4.2, D4)
    fare/model/        FareRule.java              (edited -- Task 4.3)
  src/main/resources/application.properties       (edited -- grpc port, log pattern)
  src/test/java/com/puber/matching/
    rules/             ArchitectureRulesTest.java (edited -- Task 6.1) + one fixture
    shared/model/      CoordinatesTest  DistanceTest         (edited)
    fare/model/        FareRuleTest               (new)
  src/integrationTest/java/com/puber/matching/
    QuoteGrpcIntegrationTest.java                 (new -- Tasks 7.1-7.4)
    ClockWiringIntegrationTest.java               (edited -- D6's port pin)
    FareRulesIntegrationTest.java                 (edited -- D6)
    HealthMetricsAndSchemaIntegrationTest.java    (edited -- D6)
    HealthReportsDownPromptlyIntegrationTest.java (edited -- D6)

Makefile                                          (edited -- Task 1.3)
.githooks/pre-commit                              (edited -- Task 1.4)
project-context.md                                (edited -- Task 8.1)
```

No root build: `matching-service` keeps its own wrapper and build file, and the `Makefile`
orchestrates it (AD-52). Java packages are suffix-free (`com.puber.matching`); the directory keeps the
`-service` suffix (AD-12). The domain package is `model`, never `entity`.

### References

- `_bmad-output/planning-artifacts/epics/epic-1-foundations-fare-quote.md#Story 1.4: Rider gets a fare quote through the gateway` — the nine criteria this slice takes four-and-a-bit of, plus the PUB-3 carry-forward note
- `_bmad-output/implementation-artifacts/PUB-4-rider-gets-a-fare-quote-through-the-gateway.md` — the parent: which criterion each slice satisfies. **Index only; nothing there binds you**
- `_bmad-output/planning-artifacts/epics/overview.md#Standing acceptance criteria` — apply whether restated or not
- `ARCHITECTURE-SPINE.md#AD-5` (internal surfaces mint their own id), `#AD-7` (layers), `#AD-8` (one-way), `#AD-9` (feature order), `#AD-12` (naming), `#AD-33` (additive-only contracts), `#AD-37` (gRPC between services), `#AD-38` (one error vocabulary), `#AD-52` (one contract source, copied), `#AD-54` (observability and the request id), `#AD-56` (real stack, sequential), `#AD-62` (a fare is a pure function), `#Consistency Conventions` (Money, Coordinates, Errors, Logging, Configuration), `#Stack`
- `_bmad-output/specs/spec-puber/SPEC.md#CAP-1` (fare quote); `glossary.md#Ride and money` (Quote, Fare, ETA)
- `prds/prd-puber-2026-08-02/prd.md#FR-1` (the quote and its no-driver branch)
- `project-context.md` — binding project rules: the Boot 4.1 traps, the hook policy, the two ArchUnit rule classes, YAGNI, and why the database never tells the time
- `AGENTS.md` — coding style. **Read it before writing code; nothing loads it automatically.** Its "No test-only seams" section is about the class Task 3.2 composes
- `_bmad-output/implementation-artifacts/PUB-3-*.md#Deferred` — the value-type item this slice closes (AC10); `#Review Findings` for the tests-that-cannot-fail pattern
- `_bmad-output/implementation-artifacts/PUB-1-*.md`, `PUB-2-*.md` — the container, hook and rule patterns being extended

---

## Questions for the repo owner

None of these blocks implementation — every one is pinned above so the dev agent has a deterministic
answer. They are here because the answer was **chosen by this slice rather than found in a document**.

1. **The RPC is `GetQuote` on a `QuoteService`, and the response fields are `fare_minor_units` and
   `distance_metres` (D2).** Verbose, deliberately: `fare: 1100` invites someone to read €1,100.
   Renaming a field later is an AD-33 breaking change, so this is the moment.
2. **`contracts/proto/` rather than `contracts/` holding `.proto` files directly (D1).** The extra
   level exists because AD-52 also puts *event schemas* here, in Epic 4, and they are not protobuf. If
   you would rather flatten it now and move later, say so.
3. **Contracts are pathed by domain, not by owning service (D2a).** Raised and answered on
   2026-08-25: `matching-service` cannot be a proto package at all (protoc rejects the hyphen), the
   package is the gRPC wire name and AD-33 makes it unrenameable, and this repo already names
   contracts by entity. Ownership is instead recorded in the `.proto` header and
   `contracts/README.md`'s table. Listed here because it was a real question with a decidable
   answer — not because it is still open.
4. **Coordinates cross the wire as decimal strings (D2).** The alternative that preserves exactness is
   a scaled `int64` (micro-degrees), which is smaller and comparable but invents a second integer
   scaling convention beside money's minor units. I chose strings to keep one scaling convention in
   the system.

---

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Change | By |
| --- | --- | --- |
| 2026-08-25 | Story created from epic-1 Story 1.4 as PUB-4, then split into PUB-4-1/2/3 at the repo owner's request; this is slice 1 | bmad-create-story |
