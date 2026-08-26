---
baseline_commit: 051f0a212065dcb5428b96aa7940363f25d95300
parent_ticket: PUB-4
slice: 2 of 3
depends_on: PUB-4-1
---

# Story 1.4 (slice 2 of 3): `rider-service` delivers the quote

Ticket: **PUB-4-2**
Parent: **PUB-4** — Rider gets a fare quote through the gateway
Status: ready-for-dev

**PUB-4-1 must be `done` before this starts.** It creates `contracts/`, the copy mechanism and the
gRPC surface this slice calls. The parent file
`PUB-4-rider-gets-a-fare-quote-through-the-gateway.md` holds the acceptance criteria allocation and
nothing binding — **this file is the whole specification for this slice.**

**Read PUB-4-1's Dev Agent Record before you start.** Two things in it were predictions rather than
reproduced facts (which gRPC server Boot selects, and whether cached test contexts fight over the
port), and its completion notes say how they actually turned out. The channel target in D3 below
depends on the first one.

## Story

As a rider,
I want to price a pickup/dropoff pair over HTTP,
so that I know the fare and distance before I request a ride.

### What this story actually does, in plain words

**This is the slice that delivers FR-1.** After PUB-4-1 a price can be fetched over gRPC, but only by
something that already speaks gRPC and sits inside the network. Nobody outside the system can ask for
a quote.

This slice builds `rider-service`: a second service that **owns no database at all**. Its entire job
is translation — HTTP request in, gRPC call out, HTTP response back. It is the first service in the
system that exists purely to face an actor.

Four things get built:

1. **The service itself** — its own Gradle wrapper, its own Dockerfile, its own place in the Compose
   stack, health and metrics exposed the same way `matching-service` exposes them.
2. **`POST /quotes`** — takes two coordinate pairs and a rider identity header, returns the fare and
   the distance. No arrival estimate, because there are still no drivers, and that is a success.
3. **Errors a caller can act on** — a bad request comes back as RFC 9457 Problem Details with a `400`
   and the request's request id, not as a stack trace and a `500`.
4. **The request id, client side** — read from the incoming header (or minted if there is none),
   put on every log line, carried into `matching-service` over gRPC metadata, and returned in every
   error body.

**There is no gateway in this slice.** `rider-service` is reachable only from inside the Compose
network, and `matching-service` still publishes its port. PUB-4-3 closes both. If you find yourself
editing HAProxy config or removing a `ports:` block, you have left this story.

One thing rides along that is not optional: **`rider-service` gets its own copy of the structural
rules.** PUB-2's code review recorded that the rules stopping code from reading the clock and
inverting the layers exist only in `matching-service`, that every new service needs its own copy, and
that *nothing reminds anyone to do it*. That item names this work by number. A service that exists for
a phase with no rules is a service where nothing turns red.

---

## Acceptance Criteria

These are PUB-4's AC2, AC5, AC6, AC7 and AC11 in full, plus the `rider-service` halves of AC1 and AC4.
The clauses that belong to the gateway are named in "Scope boundaries" and are **not** this slice's to
satisfy.

**AC1b — the rider's request reaches `matching-service` over gRPC, and creates nothing**

**Given** a rider requests a quote with pickup and dropoff coordinates
**When** `rider-service` handles it
**Then** it obtains the quote from `matching-service` over gRPC
**And** no ride is created
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4 (second and third clauses of its first
criterion); prd.md#FR-1; ARCHITECTURE-SPINE.md#AD-37 — REST at the edge, gRPC between services]

**AC2 — no driver means no ETA, not an error**

**Given** no driver is available
**When** a quote is requested
**Then** fare and distance are returned and ETA is omitted
**And** the response is a success, never an error
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4; prd.md#FR-1; SPEC.md#CAP-1]

**"Omitted" means the JSON key is absent, not present-and-null.** A `null` is a value a client has to
handle; an absent key is the shape FR-1 describes. See D5.

**AC4b — the request id is on every log line, in every error, and crosses the gRPC hop**

**Given** an inbound request
**When** it is handled
**Then** the request id is included in every log line and every error response
**And** it is propagated over gRPC metadata to `matching-service`
**And** a request arriving without one has one minted at entry
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4; ARCHITECTURE-SPINE.md#AD-54; #AD-5 — a
surface reached outside the gateway mints its own. Minting **at the gateway** is PUB-4-3's clause]

**AC5 — a malformed request is RFC 9457, and it is a 400**

**Given** a malformed quote request
**When** it is rejected
**Then** the response is RFC 9457 Problem Details carrying the request id
**And** the status is 400
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4; ARCHITECTURE-SPINE.md#AD-38 — One error
vocabulary, mapped at the façade]

**In plain terms:** RFC 9457 "Problem Details" is a small standard JSON error shape —
`{"type":…,"title":…,"status":…,"detail":…}` — served as `application/problem+json`. Spring has it
built in as `org.springframework.http.ProblemDetail`; **no library is needed.** AD-38 also fixes the
mapping: gRPC `INVALID_ARGUMENT` → HTTP 400.

**AC6 — identity is a header and is trusted as-is**

**Given** a rider identity header
**When** a request arrives
**Then** the identity is trusted as-is with no authentication or registration
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4; prd.md#FR-48]

**AC7 — `rider-service` is observable exactly like `matching-service`**

**Given** `rider-service`
**When** it starts
**Then** it exposes health and Prometheus metrics exactly as `matching-service` does
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.4; ARCHITECTURE-SPINE.md#AD-54]

**AC11 — the structural rules exist in the new service too (carried from PUB-2's review)**

**Given** a new service
**When** its test suite runs
**Then** it carries its own copy of the rules that stop code reading the clock and inverting the
layers
**And** each copied rule is proven capable of failing **in the new service**
[Source: implementation-artifacts/deferred-work.md#Deferred from: code review of PUB-2 — the item that
names Story 1.4 by number; ARCHITECTURE-SPINE.md#AD-7, #AD-8]

**Standing criteria that also apply here** (epics/overview.md#Standing acceptance criteria): any
bounded window is exercised by advancing the `Clock`, never by sleeping; **any container this project
builds runs non-root** — the runtime image declaring its user **numerically** (`USER 10001:10001`,
because Kubernetes' `runAsNonRoot` cannot resolve a name), and any container mounting the repository
taking the host UID/GID from environment.

---

## Story-local decisions you must implement as written

**Six things this slice needs have no source anywhere in the planning artifacts.** No document states
the HTTP method or path of any endpoint, the header names, or where the DTOs live. They are pinned
here so the implementation is deterministic. If one is wrong, raise it and change it here first.

### D1 — `POST /quotes`, with a JSON body

```
POST /quotes
X-Rider-Id: rider-42
Content-Type: application/json

{"pickup":{"latitude":"38.72225000","longitude":"-9.13933000"},
 "dropoff":{"latitude":"38.75775000","longitude":"-9.11444000"}}
```

```json
{"fareMinorUnits": 1100, "distanceMetres": 4148}
```

`POST`, not `GET`, and the reason is not REST purity. A quote's input is a *pair of pairs*; four flat
query parameters (`pickupLat`, `pickupLng`, …) has no shape and only one failure mode — a missing
parameter. A JSON body gives AC5 a real deserialization failure to map, and it is the shape
`POST /rides` needs in Story 3.2 anyway.

The `GET` argument is cacheability, and it does not apply: a quote moves with surge, so it is not
cacheable, and advertising it as cacheable would be actively wrong. AD-40's `ETag`/`304` handling is
for **ride detail**, not for quotes.

**No `/v1` prefix on the URL.** AD-33's additive rule governs protobuf and events; nothing asks for a
versioned URL, and adding one now is a guess about a migration with no requirement behind it. The
*proto* package carries `v1`, which PUB-4-1 settled.

**Field names are verbose on purpose.** `fare: 1100` invites someone to read €1,100; `fareMinorUnits`
does not. Renaming later is a breaking change for every client.

### D2 — headers: `X-Request-Id` and `X-Rider-Id`

| Header | Set by | Required? | On the gRPC hop |
| --- | --- | --- | --- |
| `X-Request-Id` | PUB-4-3's gateway; **minted by `rider-service` if absent** | effectively yes | yes — metadata key `x-request-id` |
| `X-Rider-Id` | the client | **yes — missing or blank is a 400** | no |

**`rider-service` mints a request id when the header is absent.** AD-5 requires any surface
reached outside the gateway to mint its own, and there is no gateway at all until PUB-4-3 — so without
this the entire suite would run untraced and AC4b could not be asserted.

**`X-Rider-Id` is required, even though a quote does not use it.** Name the failure it prevents, per
project-context.md → YAGNI: without it, `/quotes` works anonymously, and Story 3.2 then has to make
identity mandatory on an endpoint where it was optional — which AD-33 classifies as *changing what a
field means*, a breaking change. One identity rule for the whole façade, from the first endpoint,
avoids that.

**"Trusted as-is" (FR-48) means non-blank is the only check.** No lookup, no format validation, no
registration, no persistence. It is logged beside the request id and goes no further — **do not
propagate it over gRPC metadata**, because nothing on the other side reads it.

**gRPC metadata keys must be lowercase.** `Metadata.Key.of("x-request-id", ASCII_STRING_MARSHALLER)`;
an uppercase key throws at construction, so you find out immediately — recorded here so you do not
spend the minute wondering why.

### D3 — the channel, and where its address comes from

```properties
spring.grpc.client.channel.matching.target=${MATCHING_SERVICE_GRPC_TARGET}
```

Compose supplies `static://matching-service:9090`. The `static://` scheme is Spring gRPC's; a bare
`host:port` also resolves — use the explicit scheme so the intent is readable. Environment-driven, like
every other setting (Configuration convention); **no address literal in source.**

**Check PUB-4-1's completion notes for the port before you trust `9090`.** PUB-4-1 pinned
`spring.grpc.server.port=9090` but flagged that Boot can serve gRPC over the servlet container
instead, in which case the port is `8080` and `9090` was never bound. That was a prediction, not a
measurement; the answer is in PUB-4-1's Debug Log.

The stub is injected, not built by hand:

```java
@Configuration
@ImportGrpcClients(target = "matching", types = QuoteServiceGrpc.QuoteServiceBlockingStub.class)
class GrpcClientConfiguration {}
```

`@org.springframework.grpc.client.ImportGrpcClients` also accepts `factory`, `prefix`, `basePackages`
and `basePackageClasses`. **Use `types` and be explicit** — a package scan here would pick up whatever
the contract grows later.

### D4 — validation is structural here and semantic in `matching-service`

**`rider-service` gets no copy of `Coordinates`.** It never parses a coordinate — it passes the two
decimal strings through untouched and lets the owning service's type judge them. AD-38 puts error
*mapping* at the façade; it does not put the domain there.

| Failure | Detected by | Becomes |
| --- | --- | --- |
| body absent, not JSON, wrong types, missing `pickup` or `dropoff` | `rider-service` (Jackson, then a null check) | 400 Problem Details |
| `X-Rider-Id` missing or blank | `rider-service` | 400 Problem Details |
| coordinate not a decimal number, out of range, null | `matching-service` (`Coordinates`, PUB-4-1 AC10) | `INVALID_ARGUMENT` → 400 Problem Details |
| `fare_rules` missing its row | `matching-service` | `INTERNAL` → 500 |

This is the smallest amount of duplicated domain the spine's *"duplicated domain code across services
is accepted; a shared library is not"* has to cover. **Do not "improve" it by validating ranges here
too** — two copies of a range check is two answers to one question, and PUB-4-1 hardened the types
precisely so this side does not have to.

### D5 — layering, DTO placement, and the one annotation you must not use

AD-9 is explicit: *"`matching-service` alone splits by feature."* So `rider-service` is layered only.

```
com.puber.rider
  RiderServiceApplication
  config/      GrpcClientConfiguration  RequestIdFilter  RequestIdClientInterceptor
  controller/  QuoteController  QuoteRequest  QuoteResponse  ProblemDetailsHandler
  service/     RequestQuote
  model/       Quote
```

**Wire DTOs live in `controller`, not `model`.** `nothingDependsOnController` means `service` cannot
see them, which is the point: `RequestQuote` returns a `Quote` from `model`, and the controller maps
that to `QuoteResponse`. Putting the DTOs in `model` would either drag Jackson into the domain package
— which `modelDependsOnNothingFrameworkFlavoured` fails the build on — or make the controller a
passthrough that proves nothing.

**No `@JsonInclude` anywhere.** AC2 needs the `etaMinutes` key *absent*, and the obvious annotation
puts a framework dependency on whichever class carries it. Set

```properties
spring.jackson.default-property-inclusion=non_null
```

instead — one line, no annotation, and the model rule stays clean.

**No `strategy` package and no `Clock` in `rider-service`.** It varies nothing and reads no time.
Creating either would be scaffolding an empty layer to match a diagram, which project-context.md
forbids: *"Create layer packages only as they gain content."* See D6 for what that means for the
copied rules.

**No `package-info.java` and no JSpecify annotations.** PUB-60 (Story 1.5) adopts JSpecify across every
service and is sequenced **after** all of PUB-4, so it covers `matching-service` and this service in
one pass. Adding `@NullMarked` here would leave the repository half-annotated with no rule guarding
the coverage, which is the state PUB-60's ArchUnit rule exists to make impossible. **Create the
packages bare; PUB-60 retrofits both services together.**

### D6 — which rules get copied, which are adapted, and which are deliberately absent

`deferred-work.md` names four rules and this work by number. Copy three, strengthen one, defer one, and
add the layer rules:

| Rule | In `rider-service`? |
| --- | --- |
| `timeIsReadOnlyThroughTheClock` | **yes, and stronger** — drop the `SystemClock` exemption. There is no clock here, so *nothing* may read time |
| `theLegacyDateApiIsNotUsedAtAll` | yes, verbatim |
| `DatabaseNeverReadsTimeTest` | **yes, minus its migration test** — see the trap below |
| `theRealClockIsOnlyEverInjected` | **no** — it names `SystemClock.class`, which does not exist here. Add it with the story that gives `rider-service` a clock |
| `modelDependsOnNothingFrameworkFlavoured`, `serviceDependsOnStrategyInterfacesOnly`, `nothingDependsOnController`, `noPackageIsNamedEntity` | yes — AD-7 and AD-8 bind all services |
| `sharedDependsOnNoFeaturePackage`, `featureDependenciesRunOneWay` | **no** — AD-9 scopes the feature split to `matching-service` |
| `floatingPointIsConfinedToDistance`, `bigDecimalIsNeverBuiltFromADouble` | yes — the Money convention binds all services. Neither needs a `Distance` exemption here |
| `TestNamingRulesTest` + `TestNamingRulesIntegrationTest` | **yes, both.** AGENTS.md records why one class cannot cover both source sets: ArchUnit's `OnlyIncludeTests` recognises test output by path and knows `build/classes/java/test`, not `.../integrationTest` |
| PUB-4-1's contracts-package exclusion on the model rule | **yes** — this service generates the same stubs, so it has the same hole |

**The trap: `DatabaseNeverReadsTimeTest.no_migration_asks_the_database_for_the_time` asserts
`filesScanned() > 0`.** `rider-service` owns no database (AD-3) and ships no `.sql` file, so copying
that method verbatim gives you a **red suite on a service that is behaving correctly**. Keep the
Java-source scan and the four planted-violation tests; drop the migration scan, with a one-line comment
saying why and naming what would bring it back.

**Copy the rule bodies, not a shared class.** They are test code, and no service depends on another's
code. They were written to copy cleanly — no `matching`-specific names in any of them — which is the
intended design, not an accident.

---

## Tasks / Subtasks

### Task 1 — scaffold `rider-service` as an independent build (AC7, AD-52)

- [ ] **1.1** Create `services/rider-service/` with its own `gradlew` + `gradle/wrapper` at **9.5.1**,
      `settings.gradle` (`rootProject.name = 'rider-service'`, the same
      `foojay-resolver-convention` plugin), `.gitignore`, `.gitattributes`. Copy each from
      `matching-service` and change only what differs. **No root build, no shared plugin, no
      `buildSrc`** (AD-52) — the `Makefile` discovers the service automatically from
      `services/*/gradlew`, which is why the wrapper is step one.
- [ ] **1.2** `build.gradle`. Verbatim from `matching-service`: the Java 25 toolchain, the
      analyzer-config `apply from:` guard **and its contract-copy message** (PUB-4-1 Task 1.6), the
      `integrationTest` `JvmTestSuite` block including its `useJUnitJupiter(dependencyManagement…)`
      line, the `configurations { integrationTest… extendsFrom }` block, the
      `tasks.withType(Test)` block (`maxParallelForks = 1`, `outputs.upToDateWhen { false }`,
      parallel execution off), `check dependsOn compileIntegrationTestJava`, and
      `integrationTest shouldRunAfter test`. Those comments explain themselves where they are; do not
      re-word them.
      `bootJar { archiveFileName = 'rider-service.jar' }`.
- [ ] **1.3** Dependencies:
      ```groovy
      implementation 'org.springframework.boot:spring-boot-starter-actuator'
      implementation 'org.springframework.boot:spring-boot-starter-webmvc'
      implementation 'org.springframework.boot:spring-boot-starter-grpc-client'
      implementation 'io.grpc:grpc-protobuf'
      implementation 'com.google.protobuf:protobuf-java'
      compileOnly    'javax.annotation:javax.annotation-api:1.3.2'
      runtimeOnly    'io.micrometer:micrometer-registry-prometheus'

      testImplementation 'org.springframework.boot:spring-boot-starter-actuator-test'
      testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
      testImplementation 'org.springframework.boot:spring-boot-starter-grpc-client-test'
      testImplementation 'org.springframework.boot:spring-boot-starter-micrometer-metrics-test'
      testImplementation 'org.springframework.boot:spring-boot-resttestclient'
      testImplementation 'org.springframework.boot:spring-boot-restclient'
      testImplementation 'com.tngtech.archunit:archunit-junit6:1.5.0'
      testRuntimeOnly    'org.junit.platform:junit-platform-launcher'
      ```
      **`micrometer-registry-prometheus` is what creates `/actuator/prometheus`** — without it AC7's
      endpoint 404s in the running service, and `micrometer-metrics-test` is what stops it 404ing under
      `@SpringBootTest`. **`grpc-stub` is not listed** because the *client* starter includes it at
      compile scope, unlike the server starter; confirm that in the resolved classpath rather than
      taking it on trust. **No `jdbc`, no `flyway`, no `postgresql`** — `rider-service` owns nothing
      (AD-3), and adding the JDBC starter "just for the datasource" is named as a trap in
      project-context.md.
- [ ] **1.4** Protobuf codegen: the same plugin, the same BOM-read generator versions and the same
      `sourceSets.main.proto.srcDir layout.buildDirectory.dir('contracts/proto')` as PUB-4-1 Task 2.
      **`build/contracts/proto` is populated by the Makefile target PUB-4-1 wrote generically over
      `$(SERVICES)`** — it needs no edit for this service, which is the point of AC8's mechanism.
      Confirm that rather than assuming it.
- [ ] **1.5** `Dockerfile`: two stages copied from `matching-service`. `USER 10001:10001` **numeric**
      on both stages, `GRADLE_USER_HOME` writable, `COPY build/static-analyzers` **and**
      `COPY build/contracts` above `COPY src src`, runtime stage a JRE holding
      `rider-service.jar` by exact name. Keep `matching-service`'s comments only where they are still
      true of this file — a stale comment is worse than none.
- [ ] **1.6** `.dockerignore`: `build/*`, `.gradle/`, and the two `!build/…` exceptions.
- [ ] **1.7** `make build` passes with the service present and empty of application code, before you
      write any. A scaffold that does not build is a scaffold you debug twice.

### Task 2 — the endpoint (AC1b, AC2, AC5, AC6, D1, D4, D5)

- [ ] **2.1** `RiderServiceApplication.java` — `@SpringBootApplication`, nothing else.
- [ ] **2.2** `model/Quote.java` — a record carrying `long fareMinorUnits`, `long distanceMetres`, and
      an ETA the service can leave absent. **Use `Long` (nullable) or `OptionalLong`, and say which in
      the completion notes** — the point is that "no ETA" is representable without a sentinel, matching
      the contract's `optional`.
- [ ] **2.3** `service/RequestQuote.java` — holds the injected blocking stub, one public method taking
      the two coordinate pairs as strings, calls `GetQuote`, returns `model/Quote`. It reads
      `hasEtaMinutes()` on the response rather than comparing to zero.
- [ ] **2.4** `config/GrpcClientConfiguration.java` — D3's `@ImportGrpcClients`.
- [ ] **2.5** `controller/QuoteRequest.java` / `QuoteResponse.java` — plain records, no annotations
      (D5). Nested `Coordinates` record for the pair-of-pairs shape.
- [ ] **2.6** `controller/QuoteController.java` — `POST /quotes`. Reads `X-Rider-Id` and rejects blank
      or missing (D2), calls `RequestQuote`, maps `Quote` → `QuoteResponse`.
- [ ] **2.7** `controller/ProblemDetailsHandler.java` — a `@RestControllerAdvice` producing
      `org.springframework.http.ProblemDetail` (AC5). AD-38's table, **restricted to what can occur
      today**: `INVALID_ARGUMENT` → 400, `UNAVAILABLE` → 503, anything else → 500; plus Spring's own
      deserialization and missing-header failures → 400. Every body carries the request id as a
      property. **Do not implement rows for statuses no code can produce yet** —
      `FAILED_PRECONDITION`, `ALREADY_EXISTS` and `NOT_FOUND` arrive with the stories that create
      rides, and a mapping with no producer is a guard guarding nothing.
- [ ] **2.8** `application.properties` — mirror `matching-service`'s observability block **exactly**
      (AC7): `management.endpoints.web.exposure.include=health,prometheus`,
      `management.endpoint.health.show-details=always`,
      `management.endpoint.health.probes.enabled=true`,
      `management.endpoint.health.cache.time-to-live=0`. **Omit every datasource and Flyway line.**
      Add `spring.application.name=rider-service`, D3's channel target, D5's Jackson inclusion, and
      Task 3's log pattern.

### Task 3 — the request id, client side (AC4b)

- [ ] **3.1** `config/RequestIdFilter.java` — a servlet `Filter` that reads `X-Request-Id`,
      mints a `UUID` when absent (D2), puts it in MDC under `requestId`, echoes it on the response
      header, and **clears MDC in a `finally`**. A pooled request thread otherwise carries the previous
      request's id into the next one's logs, which is worse than no id at all.
- [ ] **3.2** `config/RequestIdClientInterceptor.java` — a `ClientInterceptor` bean annotated
      `@org.springframework.grpc.client.GlobalClientInterceptor` that copies the MDC value into
      metadata under the **lowercase** key `x-request-id`.
- [ ] **3.3** `logging.pattern.level=%5p [%X{requestId:-}]` in `application.properties`, so the id
      is on **every** line and not only the ones that remember to interpolate it. **Do not reach for
      `logging.pattern.correlation`** — in Boot that slot is driven by Micrometer tracing's trace and
      span ids, which this project does not have.
- [ ] **3.4** `ProblemDetailsHandler` sets the id as a Problem Details property (AC5's second clause).

### Task 4 — the structural rules (AC11, D6)

- [ ] **4.1** Create `src/test/java/com/puber/rider/rules/` per D6's table, plus the fixture classes
      each rule needs. **A violator whose rule names an absolute package cannot live under
      `rules/fixtures/`** — it sits in the production package it violates, which project-context.md
      records and PUB-3's review re-confirmed. None of `rider-service`'s rules name an absolute
      package yet, so all its fixtures can live in `rules/fixtures/`; check that before moving one.
- [ ] **4.2** Create `src/integrationTest/java/com/puber/rider/rules/TestNamingRulesIntegrationTest.java`,
      including its **non-vacuity assertion** — a rule that scans nothing passes forever, which is
      exactly the failure that class was added to fix in `matching-service`.
- [ ] **4.3** **Prove each copied rule can fail in `rider-service`.** Plant one violation per rule, run
      `make test`, capture the failure, revert, confirm green. This is AC11's second clause and the
      whole reason the deferred item exists: *"forget the copy and the new service can read the clock
      however it likes, and nothing anywhere turns red."* Record each planted violation and its failure
      line in the Debug Log.
- [ ] **4.4** Apply PUB-4-1's contracts-package exclusion to this service's model rule, and prove it
      the same way: plant a `Quote` field typed as `GetQuoteResponse`, watch it go red, revert.
- [ ] **4.5** Close `deferred-work.md`'s PUB-2-review item: state what was copied, what was
      deliberately not, and why. It named Story 1.4 by number; this is where it lands.

### Task 5 — Compose and the Makefile (AC7, D3, and the test wiring)

- [ ] **5.1** `infra/docker-compose.yml`: add `rider-service` — built from `../services/rider-service`
      target `runtime`, `image: puber/rider-service:0.0.1-SNAPSHOT`,
      `depends_on: matching-service: condition: service_healthy`, environment carrying
      `MATCHING_SERVICE_GRPC_TARGET`, **no `ports:` block**, and the same `bash`/`/dev/tcp`
      healthcheck `matching-service` uses. Copy that healthcheck including the comment explaining why
      the runtime image has no `curl` and deliberately stays that way.
- [ ] **5.2** **Do not touch `matching-service`'s `ports:` block.** Removing it is PUB-4-3's AC3 work;
      doing it here leaves the system with nothing reachable and no gateway to replace it.
- [ ] **5.3** `tests` service: add `MATCHING_SERVICE_GRPC_TARGET` to its environment and
      `matching-service: condition: service_healthy` to its `depends_on`. `rider-service`'s
      integration tests boot `rider-service` **inside the runner container**, so its gRPC client has to
      reach the real `matching-service`; without this they fail on a channel they cannot open.
- [ ] **5.4** **There is still exactly one test runner, and it is built from `matching-service`'s
      Dockerfile.** It is a JDK-and-Gradle image; the Makefile points it at each service with
      `--workdir` and the mounted workspace supplies the sources. **Do not add a second `tests`
      service** — the only thing it would give you is a second image to keep in step.
- [ ] **5.5** `Makefile`: `test-integration` must bring up `matching-service` (not just
      `matching-postgres`) and gains `images` as a prerequisite, so it still works on a fresh clone.
      `build`, `format`, `test-unit` and `static-analysis` need no change — they iterate
      `$(SERVICES)` and the wrapper from Task 1.1 is enough. `run` brings up `rider-service` too.
      Update the `help` text if any of it stops being true.
- [ ] **5.6** **Address services by Compose service name, never `localhost`** (project-context.md →
      Real datastores only). The runner is a peer container.
- [ ] **5.7** `verify-no-root-owned-files` passes after a full `make build && make test`. The protobuf
      plugin writes into `build/generated` and downloads two native executables into the Gradle cache,
      so this is a real check and not a formality.

### Task 6 — tests (AC1b, AC2, AC4b, AC5, AC6, AC7)

Unit tests in `src/test/java`, integration tests in `src/integrationTest/java`, `snake_case` methods,
`@DisplayName` carrying the `AC<n>:` reference (AGENTS.md → Test Naming and Placement).

- [ ] **6.1** Integration: `POST /quotes` returns 200 with `fareMinorUnits` and `distanceMetres`
      matching a **hand-computed** expectation for two **distinct** coordinates, and **no `etaMinutes`
      key at all** (AC1b, AC2). Assert the key's absence, not that its value is null. Two distinct
      coordinates, never the same point twice — PUB-3's review proved a same-point test multiplies
      every rate by zero and cannot fail.
- [ ] **6.2** Integration: four malformed requests — no body, a non-JSON body, a missing `dropoff`, and
      an out-of-range latitude — each answer **400**, `application/problem+json`, with the request
      id in the body (AC5). The last one is the important one: it proves PUB-4-1's `INVALID_ARGUMENT`
      is mapped rather than leaking as a 500.
- [ ] **6.3** Integration: a request with no `X-Rider-Id` answers 400; a request carrying a rider id
      never seen before answers 200 (AC6 — "trusted as-is, no registration").
- [ ] **6.4** Integration: health is UP and `/actuator/prometheus` serves Prometheus text format
      (AC7). **`@AutoConfigureMetrics` is required** or the endpoint 404s under test while working in
      the running service.
- [ ] **6.5** Integration: a request carrying a known `X-Request-Id` gets it back on the response,
      and a request carrying none gets a minted one back (AC4b).
- [ ] **6.6** **One test that proves the id crossed the gRPC hop** (AC4b's third clause). The cheap
      honest form: send a known id, then assert it appears in `matching-service`'s log output for that
      call. If that proves awkward, the alternative is a test-scoped server interceptor asserting on
      received metadata — **say in the completion notes which one you built and what it does not
      cover.** Asserting the id on the HTTP response only proves the filter ran.
- [ ] **6.7** Unit: `RequestQuote` maps an absent `eta_minutes` to an absent ETA, and a present one to
      a present one. The second case has no production producer yet and is the cheapest possible guard
      against Story 2.6 discovering the mapping was never written.

### Task 7 — record what this slice settled

- [ ] **7.1** `project-context.md`: add only what is non-obvious from the code and not already in the
      spine — the request-id chain and the MDC-clearing requirement; that `rider-service`
      deliberately has no `Clock`, so one rule is absent by design; that a new service's rule copies
      are hand-made and what the `DatabaseNeverReadsTimeTest` migration-scan trap is. **Do not restate
      AD-5, AD-33, AD-37, AD-38 or AD-54** — CLAUDE.md forbids duplicating a rule across files.
- [ ] **7.2** `deferred-work.md`: Task 4.5's closure.
- [ ] **7.3** Anything deferred out of this slice goes in `deferred-work.md` **and** in whichever of
      the epic file / `sprint-status.yaml` `action_items` / `project-context.md` will actually surface
      it. `deferred-work.md` alone is an audit trail nothing reads.

### Task 8 — the gate

- [ ] **8.1** `make build`, then `make test`, **in that order, from a clean tree**, and read the
      output. Not `make test-unit`: it runs neither Spotless nor the integration suite, which is how
      PUB-2's review left the build red while reporting the suite green.
- [ ] **8.2** Report what you saw, including the known environmental red —
      `HealthReportsDownPromptlyIntegrationTest`'s precondition — named rather than omitted, and never
      quietly counted as green.
- [ ] **8.3** Only then set this story and `sprint-status.yaml` to `review`. **Leave `PUB-4` itself at
      `in-progress`** — the parent is `done` only when PUB-4-3 lands.
- [ ] **8.4** Leave everything **unstaged**. The repo owner reviews the unstaged diff.

---

## Dev Notes

### What exists when this slice starts

After PUB-4-1: `contracts/proto/puber/quote/v1/quote.proto` is the single contract source, the
`Makefile` copies it into every service's build output, `matching-service` serves `QuoteService/GetQuote`
over gRPC and reads a request id out of metadata, and `Coordinates`, `Distance` and `FareRule`
reject bad input with named-field messages.

`matching-service` still publishes `8080:8080` to the host. There is no gateway. `rider-service` does
not exist.

### The gRPC client dependency traps

Boot 4.1 brought gRPC in-house, and **every published Spring gRPC example predates that.** Verified
against `spring-boot-dependencies-4.1.0.pom` and Maven Central on 2026-08-25:

1. **The starter is Boot's, not Spring gRPC's.**
   `org.springframework.boot:spring-boot-starter-grpc-client`, with `-grpc-client-test` beside it. The
   coordinate every tutorial shows — `org.springframework.grpc:spring-grpc-spring-boot-starter` — **is
   not in Boot's BOM at all**, so it resolves with no version and the build fails on an unversioned
   dependency.
2. **The client starter carries `grpc-stub` at compile scope; it does not carry `grpc-protobuf`.**
   Generated `*Grpc.java` references `io.grpc.protobuf.ProtoUtils`, so `implementation
   'io.grpc:grpc-protobuf'` and `protobuf-java` are both explicit. This asymmetry with the server
   starter is exactly the kind of thing that reads as a copy-paste error in review — it is not.
3. **`protoc-gen-grpc-java` 1.80.0 still emits `@javax.annotation.Generated`, and Java 25 has no
   `javax.annotation`.** Hence the `compileOnly` line. PUB-4-1 Task 2.4 already confirmed whether it
   is actually emitted — read its notes and match what it concluded, rather than deciding again.
4. **Versions come out of Boot's BOM.** `grpc-java 1.80.0`, `protobuf-java 4.34.2`,
   `spring-grpc 1.1.0`. Only the protobuf **Gradle plugin** (`0.9.6`) is pinned by hand, because Boot
   manages only the Maven plugin's version.

**One thing that looks like a violation of project-context.md and is not.**
`spring-boot-starter-grpc-client-test` depends transitively on the **monolithic
`spring-boot-starter-test`**. project-context says to use the split capability starters rather than the
monolith — and this *is* the capability starter; what it drags in behind it is Boot's choice. **Do not
add an `exclude` block to "fix" it.** The rule is about what you declare.

### The Spring gRPC client API, as it actually exists in 1.1.0

Verified by listing the shipped classes rather than from documentation:

| What you want | The type |
| --- | --- |
| get a stub injected | `@org.springframework.grpc.client.ImportGrpcClients(target = …, types = …)` on a `@Configuration` |
| a global client interceptor | `@org.springframework.grpc.client.GlobalClientInterceptor` |
| the channel address | `spring.grpc.client.channel.<name>.target` |
| stub flavours | `BlockingStubFactory`, `BlockingV2StubFactory`, `FutureStubFactory`, `ReactorStubFactory`, `SimpleStubFactory` |
| the channel factory, if you need it directly | `org.springframework.grpc.client.GrpcChannelFactory` |

### Boot 4.1 / Java 25 traps that apply here

From project-context.md → "Boot 4.1 / Java 25", narrowed to this slice. **These are the ones that will
actually bite while scaffolding a new service:**

- **`spring-boot-starter-webmvc`**, never `-web`. The `-web` coordinate does not exist in Boot 4.
- **Split test starters.** No monolithic `spring-boot-starter-test` in what you declare; every
  capability starter needs its `-test` sibling.
- **`TestRestTemplate` moved to `org.springframework.boot.resttestclient`** and is no longer registered
  by `webEnvironment = RANDOM_PORT` alone — it needs `@AutoConfigureTestRestTemplate` **plus both**
  `spring-boot-resttestclient` and `spring-boot-restclient`, because Boot 4 modularized the blocking
  REST clients. Task 1.3 lists both; `matching-service`'s `build.gradle` carries them with the comment
  explaining why.
- **Metrics exporters are off inside `@SpringBootTest`.** `@AutoConfigureMetrics` or Task 6.4's
  endpoint 404s under test while working in the running service.
- **Jackson 3** — `ObjectMapper` and `JsonNode` are `tools.jackson.databind.*`;
  `com.fasterxml.jackson.databind` is not on the classpath at all. Do not add Jackson 2 to "fix" the
  missing package. This slice serializes DTOs, so it is the first one that meets Jackson directly.
- **`TIMESTAMPTZ`, `Instant`, no `LocalDateTime` in any signature or DTO** — this slice writes DTOs, so
  it is the first place that rule has something to bind. Nothing here carries a time, and it should
  stay that way.

### Gradle and Compose traps recorded by PUB-1 through PUB-4-1

- **`make build` runs `./gradlew build --no-deps`, so nothing is running.** `build` includes `check`,
  which includes the unit suite. **Any test that opens a socket — including the gRPC channel — lives in
  `src/integrationTest/java`, or it fails every single build.** That is the whole reason the two source
  sets exist, and every test in Task 6 is an integration test for this reason.
- **Do not run `./gradlew` directly inside a service.** It bypasses the analyzer-config and contract
  copies; `build.gradle` throws a `GradleException` telling you so.
- **A rename can crash `make format`.** Spotless's `build/spotless-clean` copies stop resolving;
  `make analyzer-config` clears them. You are creating a whole service tree, so expect this.
- **Spotless formats all three source sets** under `src/*/java/**/*.java` — generated sources live
  under `build/` and are not matched. New `main`, `test` and `integrationTest` code must be
  AOSP-formatted or `make build` goes red.
- **`./gradlew check --warning-mode all` reports zero deprecations today.** Keep it that way; use
  assignment syntax in the Groovy DSL, never the space form.
- **`Makefile` service discovery is keyed on `services/*/gradlew`**, which is why Task 1.1 creates the
  wrapper first: a service directory with no wrapper is invisible, and a stray file cannot become a
  phantom service.

### Honest limits of this slice

**AC2 is only half a criterion today.** "No driver is available" is not a branch this slice can choose
between — there are no drivers at all, so the ETA is *always* absent. Task 6.1 proves the absent case,
Task 6.7 proves the mapping would handle a present one, and the real branch lights up in Story 2.6
(PUB-10). Do not manufacture a fake driver.

**AC4b's minting clause is only half-satisfied here, and deliberately.** `rider-service` mints when the
header is absent, which is AD-5's rule for a surface reached outside a gateway. Minting *at the
gateway* — AC4's actual wording — is PUB-4-3's. Until then, "minted" means "minted by the service".

**AC6 is thin by design.** "Trusted as-is" means there is almost nothing to assert: an unseen rider id
works, and a blank one is refused. There is no registration to test the absence of.

**AC7's "exactly as `matching-service` does" cannot be literal.** `rider-service` owns no database, so
its health body has no `db` contributor and its Flyway and datasource configuration is absent. What is
identical is the *surface*: the same two endpoints, exposed the same way, with the same
`show-details`, `probes.enabled` and `cache.time-to-live`. **Assert on the surface, not on a body
comparison.**

**Nothing outside the Compose network can reach this endpoint yet.** `rider-service` publishes no
port, by design — PUB-4-3's gateway is the front door. So the only callers this slice has are the
integration tests, and a human wanting to try it must go through `docker compose exec`.

### Scope boundaries — what is deliberately not here

| Not in this slice | Where it lands |
| --- | --- |
| HAProxy, `infra/haproxy.cfg`, the `/quotes` route, the 404 default | **PUB-4-3** |
| Removing `matching-service`'s published `8080:8080` | **PUB-4-3** |
| Minting the request id at the gateway; the tests that prove it | **PUB-4-3** |
| Bringing HAProxy up for `make run` / `make test` | **PUB-4-3** |
| `contracts/`, the copy mechanism, `matching-service`'s gRPC surface, the value-type hardening | **PUB-4-1** (already done) |
| ETA in a quote, driver proximity, the geo index | Story 2.6 (PUB-10) |
| `rides`, `POST /rides`, the ride state machine | Stories 3.1–3.2 (PUB-12, PUB-13) |
| AD-38's 409 / 404 / `ABORTED` rows | The stories that create those states |
| A `Clock` in `rider-service`, and `theRealClockIsOnlyEverInjected` there | The story that gives it one (D6) |
| Rate limits, HAProxy queue bounds, AD-6's bound chain | Epic 4 — and AD-47 says those numbers are measured, not guessed |
| `driver-service`, `payment-service`, `audit-service` | Epics 2, 5, 6 |
| JSpecify, `@NullMarked`, any `package-info.java` | **PUB-60** (Story 1.5), after all of PUB-4 — it covers both services in one pass (D5) |
| Kubernetes manifests, `deploy/` | Epic 7 |

### Previous story intelligence

PUB-1, PUB-2, PUB-3 and PUB-4-1 are the history that matters:

1. **A test that cannot fail is the finding that recurs in every review so far.** PUB-2 planted typos
   into a banned-method list and the suite stayed green. PUB-3 replaced `CalculateFare`'s body with a
   zero distance and the suite stayed green, because both callers passed the same point twice. That is
   why Tasks 4.3, 4.4 and 6.1 are written as plant-run-capture-revert rather than as assertions.
2. **A rationale is not evidence.** Three guards written during PUB-1 each had a convincing comment and
   each guarded nothing. Task 2.7 restricts the error map to statuses that have a producer for exactly
   this reason.
3. **Read `AGENTS.md` before writing code.** PUB-2 and PUB-3 both shipped comments the Comments rule
   forbids, because neither run had the file open when the code was written. This slice writes more new
   classes than any since PUB-1, so it has the most to get wrong: SOLID, one public method per service
   class, private methods at the bottom, and a comment only where a reader would otherwise be
   surprised.
4. **`AGENTS.md` was left unstaged in PUB-3**, shipping enforcement without the rule that authorised
   it. `pre-commit` analyses the index — check what you staged, not what you edited.
5. **The File List is routinely incomplete.** PUB-3's review found ten touched files missing from it.
   This slice creates an entire service tree; keep the list as you go.
6. **`docs/` and `docs/tickets/pb-*.md` are a superseded planning attempt**, explicitly
   non-authoritative and stale on the payment flow, the ride state machine and the database topology.
   If a search surfaces `pb-1.4.md`, ignore it.

### Pinned versions — do not move any of these

Java/Temurin **25** · Spring Boot **4.1.0** · Gradle wrapper **9.5.1** · Flyway **not present in this
service** · JUnit Jupiter **6.0.3** · ArchUnit **`archunit-junit6:1.5.0`** · Spotless **8.10.0** ·
`io.spring.dependency-management` **1.1.7** · protobuf Gradle plugin **0.9.6** ·
`javax.annotation-api` **1.3.2 (`compileOnly`)**

BOM-managed and therefore **not** written into `build.gradle`: `grpc-java` **1.80.0**,
`protobuf-java` **4.34.2**, `spring-grpc` **1.1.0**.

**No new dependency beyond Task 1.3's list.** If you think you need one, stop and say why — adding one
is an architecture decision here, not a tooling choice. In particular: **no validation library.** AC5's
four failure modes are a Jackson error, a null check and a blank check; `jakarta.validation` would be a
framework annotation on a DTO for three lines of logic.

### Project Structure Notes

Target layout after this slice, and nothing beyond it:

```
services/rider-service/                          (new, entire tree -- AD-52: its own wrapper)
  gradlew  gradle/wrapper/  settings.gradle  build.gradle
  Dockerfile  .dockerignore  .gitignore  .gitattributes
  src/main/java/com/puber/rider/
    RiderServiceApplication.java
    config/      GrpcClientConfiguration.java  RequestIdFilter.java
                 RequestIdClientInterceptor.java
    controller/  QuoteController.java  QuoteRequest.java  QuoteResponse.java
                 ProblemDetailsHandler.java
    service/     RequestQuote.java
    model/       Quote.java
  src/main/resources/application.properties
  src/test/java/com/puber/rider/
    rules/       ArchitectureRulesTest.java  DatabaseNeverReadsTimeTest.java
                 TestNamingRulesTest.java  + fixtures  (D6's table)
    service/     RequestQuoteTest.java                 (Task 6.7)
  src/integrationTest/java/com/puber/rider/
    QuoteIntegrationTest.java                          (Tasks 6.1-6.3, 6.5, 6.6)
    HealthAndMetricsIntegrationTest.java               (Task 6.4)
    rules/TestNamingRulesIntegrationTest.java

infra/docker-compose.yml                         (edited -- rider-service added, tests env)
Makefile                                         (edited -- Task 5.5)
project-context.md                               (edited -- Task 7.1)
_bmad-output/implementation-artifacts/deferred-work.md   (edited -- Task 4.5)
```

No root build: `rider-service` gets its own wrapper and build file, and the `Makefile` orchestrates
both services (AD-52). Java packages are suffix-free (`com.puber.rider`); the directory keeps the
`-service` suffix (AD-12). The domain package is `model`, never `entity` —
`noPackageIsNamedEntity` enforces it once Task 4 copies it.

### References

- `_bmad-output/planning-artifacts/epics/epic-1-foundations-fare-quote.md#Story 1.4: Rider gets a fare quote through the gateway` — the nine criteria this slice takes five-and-a-bit of
- `_bmad-output/implementation-artifacts/PUB-4-1-the-contract-and-the-quote-over-grpc.md` — **read its Dev Agent Record**: the gRPC port and the test-context findings live there
- `_bmad-output/implementation-artifacts/PUB-4-rider-gets-a-fare-quote-through-the-gateway.md` — the parent: which criterion each slice satisfies. **Index only; nothing there binds you**
- `_bmad-output/planning-artifacts/epics/overview.md#Standing acceptance criteria` — apply whether restated or not
- `ARCHITECTURE-SPINE.md#AD-3` (rider-service owns nothing), `#AD-5` (surfaces outside the gateway mint their own id), `#AD-7` (layers), `#AD-8` (one-way), `#AD-9` (only matching-service splits by feature), `#AD-12` (naming), `#AD-37` (REST at the edge, gRPC between services), `#AD-38` (one error vocabulary, mapped at the façade), `#AD-39` (internal ids stay internal), `#AD-49` (numeric non-root UID), `#AD-54` (observability, the request id), `#AD-56` (real stack, sequential), `#Consistency Conventions` (Money, Errors, Logging, Configuration, Container runtime), `#Stack`
- `_bmad-output/specs/spec-puber/SPEC.md#CAP-1` (fare quote); `glossary.md#Ride and money` (Quote, Fare, ETA)
- `prds/prd-puber-2026-08-02/prd.md#FR-1` (the quote and its no-driver branch), `#FR-48` (header identity, trusted as-is)
- `project-context.md` — binding project rules: the Boot 4.1 traps, the hook policy, the non-root container rules, the two ArchUnit rule classes, YAGNI
- `AGENTS.md` — coding style. **Read it before writing code; nothing loads it automatically**
- `_bmad-output/implementation-artifacts/deferred-work.md#Deferred from: code review of PUB-2` — the item AC11 closes
- `_bmad-output/implementation-artifacts/PUB-1-*.md` — the container, Dockerfile, non-root and hook patterns this service copies

---

## Questions for the repo owner

None of these blocks implementation — every one is pinned above so the dev agent has a deterministic
answer. They are here because the answer was **chosen by this slice rather than found in a document**.

1. **`POST /quotes` rather than `GET /quotes` (D1).** A quote creates nothing, which argues for `GET`.
   I chose `POST` because the input is a pair of coordinate pairs, because a JSON body is what gives
   AC5 a real 400 to map, and because `POST /rides` needs the same shape in Story 3.2. Switching means
   changing the DTOs, the malformed-request tests, and PUB-4-3's HAProxy `path_beg` rule — so this is
   the moment.
2. **`X-Rider-Id` is required on `/quotes`, and a quote does not use it (D2).** The failure I named is
   that making identity mandatory later is an AD-33 breaking change. The counter-argument is YAGNI:
   nothing reads it, so nothing needs it. Say the word and it becomes optional-when-present.
3. **Response field names are `fareMinorUnits` and `distanceMetres` (D1).** Verbose deliberately —
   `fare: 1100` invites someone to read €1,100. If you would rather have `fare` and `distance` with
   the units in documentation, that is a one-line change now and a breaking one later.
4. **`make test` gets slower.** `test-integration` gains `images` and brings up `matching-service` as
   well as Postgres, because `rider-service`'s tests need a real gRPC peer. `pre-push` runs
   `make test`, so every push pays it. PUB-4-3 adds two more containers on top.

---

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Change | By |
| --- | --- | --- |
| 2026-08-25 | Story created from epic-1 Story 1.4 as PUB-4, then split into PUB-4-1/2/3 at the repo owner's request; this is slice 2 | bmad-create-story |
