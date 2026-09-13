---
baseline_commit: 051f0a212065dcb5428b96aa7940363f25d95300
parent_ticket: PUB-4
slice: 2 of 3
depends_on: PUB-4-1
---

# Story 1.4 (slice 2 of 3): `rider-service` delivers the quote

Ticket: **PUB-4-2**
Parent: **PUB-4** — Rider gets a fare quote through the gateway
Status: done

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
2. **`POST /rider/v1/quotes`** — takes two coordinate pairs and a rider identity header, returns the fare and
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

> **FROZEN — implement exactly as written. Do not propose an alternative.**
>
> Every decision here was settled with the repo owner and is closed. **Appendix A** holds the reasoning,
> including the options that were considered and rejected; it exists so none of this is re-argued.
>
> Reopen a decision **only** if it is factually wrong — the API does not exist, the code does not
> compile, the version behaves differently than stated. Then stop, say which decision and what you
> actually observed, and change this section before writing code.
>
> A cleaner alternative, a more idiomatic shape, a rejected option looking attractive, or a personal
> preference is **not** grounds to reopen one. Appendix A already weighed those.

**Seven things this slice needs have no source anywhere in the planning artifacts.** No document states
the HTTP method or path of any endpoint, the header names, or where the DTOs live. They are pinned
here so the implementation is deterministic.

### D1 — `POST /rider/v1/quotes`, with a JSON body

```
POST /rider/v1/quotes
X-Rider-Id: rider-42
Content-Type: application/json

{"pickup":{"latitude":"38.72225000","longitude":"-9.13933000"},
 "dropoff":{"latitude":"38.75775000","longitude":"-9.11444000"}}
```

```json
{"fare": 1100, "distance": 4148}
```

**`POST` with a JSON body. Not `GET`, not `QUERY`.** → Appendix A1.

**Every public path is `/<service>/<version>/<resource>`, and the actor segment is suffix-free** —
`/rider`, not `/rider-service`. Epic 2's is `/driver/`, audit's query API is `/audit/`. → Appendix A2.
Task 7.2 writes the scheme into the spine.

**The directory, the image, the Compose service and the HAProxy backend all stay `rider-service`.**
Only the URL drops the suffix.

**Apply the prefix in one place, and not to `/actuator`.** Do **not** repeat it in every
`@PostMapping`, and do **not** use `server.servlet.context-path` — that would prefix the actuator
endpoints too, and health and metrics are neither versioned nor service-scoped (AD-54 fixes them at
`/actuator/**`). The hook, verified against `spring-webmvc-7.0.8`:

```java
// config/ApiVersionConfiguration.java
private static final String CONTROLLERS = "com.puber.rider.controller.";

@Override
public void configurePathMatch(PathMatchConfigurer configurer) {
    configurer.addPathPrefix("/rider/v1", inPackage(CONTROLLERS + "v1"));
    // a v2 is one new package and one new line here -- nothing above is touched
}
```

`PathMatchConfigurer.addPathPrefix(String, Predicate<Class<?>>)` exists and `getPathPrefixes()`
returns a `Map<String, Predicate<Class<?>>>`, so **calls accumulate** — one entry per version, each
predicate matching its own package. `QuotesController` still declares `@PostMapping("/quotes")`; the
prefix is configuration, not repetition. **Keep the predicates disjoint** — one package, one prefix —
because a class matching two entries resolves by map iteration order, which is not something to rely
on. → Appendix A2 for why the predicate is per-package rather than blanket.

**The DTOs carry the same version, in their own package: `dto/v1`** (D5). A version is **two packages
that open and close together**, `controller.v2` beside `dto.v2`. `ErrorDetailsHandler` is the
exception and stays in `shared`: AD-38's error vocabulary is one thing across every version.

**`nothingDependsOnController` still binds** — it is written `..controller..` and matches
sub-packages. **It does not cover the DTOs**, which is why D5 adds `onlyControllerDependsOnDto`.

**`matching-service` needs no equivalent.** Its version is in the proto package (`puber.quote.v1`), so
`quote/controller/QuoteGrpcService` stays where it is. Do not add a `.v1` Java package there for
symmetry.

**The service serves the full path itself; the gateway does not rewrite.** PUB-4-3's HAProxy passes
`/rider/v1/…` through unchanged, so this service's own integration tests hit the same path a real
client does.

**All three response fields are bare: `fare`, `distance`, `eta`.** → Appendix A3.

**`eta` is always minutes, and that is not a per-response choice.** **Nothing may return an ETA in any
other unit** — seconds would be an AD-33 change to what the field means and needs a new field beside
it. Story 2.6 (PUB-10) is what first populates it; until then it is always absent.

**The wire and the JSON disagree on purpose.** The proto fields are `fare_minor_units` and
`distance_metres` (PUB-4-1, shipped) and `rider-service` is a protocol translator. **Do not "fix" the
mismatch by renaming the proto fields** — AD-33 makes a field rename a breaking change.

### D2 — headers: `X-Request-Id` and `X-Rider-Id`

| Header | Set by | Required? | On the gRPC hop |
| --- | --- | --- | --- |
| `X-Request-Id` | PUB-4-3's gateway; **minted by `rider-service` if absent** | effectively yes | yes — metadata key `x-request-id` |
| `X-Rider-Id` | the client | **yes — missing or blank is a 400** | no |

**`rider-service` mints a request id when the header is absent.** AD-5 requires any surface reached
outside the gateway to mint its own, and there is no gateway at all until PUB-4-3 — so without this
the entire suite would run untraced and AC4b could not be asserted.

**`X-Rider-Id` is required, even though a quote does not use it.** → Appendix A4.

**"Trusted as-is" (FR-48) means non-blank is the only check.** No lookup, no format validation, no
registration, no persistence. It is logged beside the request id and goes no further — **do not
propagate it over gRPC metadata**, because nothing on the other side reads it.

**gRPC metadata keys must be lowercase.** `Metadata.Key.of("x-request-id", ASCII_STRING_MARSHALLER)`;
an uppercase key throws at construction.

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

**One class is the whole gRPC-client setup** — the channel address, the stub types, and the
interceptor chain that hangs off the channel:

```java
@Configuration
@ImportGrpcClients(target = "matching", types = QuoteServiceGrpc.QuoteServiceBlockingStub.class)
class GrpcClientConfiguration {

    // Without @GlobalClientInterceptor the bean is created and never attached to a channel.
    @Bean
    @GlobalClientInterceptor
    RequestIdClientInterceptor requestIdClientInterceptor() {
        return new RequestIdClientInterceptor();
    }
}
```

`@org.springframework.grpc.client.ImportGrpcClients` also accepts `factory`, `prefix`, `basePackages`
and `basePackageClasses`. **Use `types` and be explicit** — a package scan here would pick up whatever
the contract grows later.

**`@GlobalClientInterceptor` is mandatory on the `@Bean` method, and dropping it fails silently** —
the bean is created, no channel intercepts, the request id stops crossing the gRPC hop, and nothing is
logged and nothing turns red except Task 6.6. Verified in the bytecode; → Appendix A5.

**Ordering, if a second client interceptor ever arrives:** `configureInterceptors` sorts by `@Order`
and then **reverses** the list before `ManagedChannelBuilder.intercept(...)`. Do not reason about the
final call order from the `@Order` values alone — assert it.

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
  shared/      RequestId  RequestIdFilter  RequestIdClientInterceptor  ErrorDetailsHandler
  config/      GrpcClientConfiguration  ApiVersionConfiguration
  controller/
    v1/        QuotesController
  dto/
    v1/        QuoteRequest  QuoteResponse
  service/     RequestQuote
  model/       Quote
```

**Wire DTOs live in `dto/v1`, not in `model` and not inside `controller`.** → Appendix A6.

**`dto` is a seventh layer, and AD-7 lists six.** That is an addition to the spine, not a local
deviation, so Task 7.2 writes it into the same Consistency Conventions row as the path scheme —
`driver-service` (Epic 2) faces an actor the same way and must not invent a different answer.

**Moving them out of `controller` costs the guard `nothingDependsOnController` was giving them, so
this slice adds a replacement** and proves it by planting (Task 4.7):

```java
// no class outside controller may see a wire DTO -- a dto may still see another dto
@ArchTest
static final ArchRule onlyControllerDependsOnDto =
        noClasses()
                .that()
                .resideOutsideOfPackage("..dto..")
                .and()
                .resideOutsideOfPackage("..controller..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..dto..")
                .because("the wire shape reaches no further in than the controller that serves it")
                .allowEmptyShould(true);
```

The `..dto..` exclusion on the `that()` side is load-bearing: `QuoteRequest` holds a nested
`Coordinates` record, so DTOs depend on DTOs by design. Without it the rule fails on correct code, and
whoever hits that will weaken the rule rather than the code.

**No `@JsonInclude` anywhere.** AC2 needs the `eta` key *absent*, and the obvious annotation
puts a framework dependency on whichever class carries it. Set

```properties
spring.jackson.default-property-inclusion=non_absent
```

instead — one line, no annotation, and the model rule stays clean.

**`non_absent`, not `non_null`, and the difference is the difference between passing AC2 and failing
it.** `NON_NULL` does **not** drop an empty `Optional` — it emits the key with a `null` value. Proven
against `jackson-databind 3.1.4` on 2026-09-11 by serializing both shapes:

```
NON_NULL    OptionalLong.empty -> {"fare":1100,"distance":4148,"eta":null}   <- AC2 fails
NON_NULL    Long null          -> {"fare":1100,"distance":4148}
NON_ABSENT  OptionalLong.empty -> {"fare":1100,"distance":4148}
NON_ABSENT  Long null          -> {"fare":1100,"distance":4148}
```

`non_absent` is correct for **both** field shapes, so it removes the trap rather than depending on
nobody walking into it. Task 6.1 asserts the key is absent, not that it is null — which is what
catches this if the setting is ever changed back.

**No `strategy` package and no `Clock` in `rider-service`.** It varies nothing and reads no time.
Creating either would be scaffolding an empty layer to match a diagram, which project-context.md
forbids: *"Create layer packages only as they gain content."* See D6 for what that means for the
copied rules.

**No `package-info.java` and no JSpecify annotations.** PUB-60 (Story 1.5) adopts JSpecify across every
service and is sequenced **after** all of PUB-4, so it covers `matching-service` and this service in
one pass. **Create the packages bare; PUB-60 retrofits both services together.**

### D5a — the request id and Problem Details live in `shared`, and it is one flat directory

Both are **conventions, not domain behaviour**, which is project-context.md's membership test: *"does
the type encode a convention?"* Every service's edge implements both identically, so neither belongs
in this service's own layers.

```
com.puber.rider.shared
  RequestId.java                     the four names and the minting, in one place
  RequestIdFilter.java               inbound HTTP: read or mint, MDC, echo, clear in a finally
  RequestIdClientInterceptor.java    outbound gRPC: MDC -> metadata (plain class, wired in config)
  ErrorDetailsHandler.java           @RestControllerAdvice -> RFC 9457 ProblemDetail + request id
```

**Flat, with no sub-layers.** Four classes do not need four layers, and a flat package is **one
directory to copy** into `driver-service`, `payment-service` and `audit-service`. Sub-layer it when a
fifth class arrives that genuinely belongs to a layer.

**Copy-pasted per service, never extracted into a library.** The spine: *"Duplicated domain code across
services is accepted; a shared library is not."*

**`RequestId` holds the four names that must agree** — the header, the gRPC metadata key, the MDC key
and the log pattern's `%X{…}` — plus `mint()`. **Nothing else in the service spells any of them.**
→ Appendix A7 for why it was right to extract this now and not in PUB-4-1.

**`shared` is not byte-identical across services, and that is not drift.** It is a menu keyed on which
transports a service has:

| Service | Transports | Its `shared` holds |
| --- | --- | --- |
| `matching-service` | gRPC server | `RequestId`, `RequestIdServerInterceptor`, `UnexpectedGrpcFailureStatusMapper` |
| `rider-service` | HTTP edge + gRPC client | `RequestId`, `RequestIdFilter`, `RequestIdClientInterceptor`, `ErrorDetailsHandler` |
| `driver-service` (Epic 2) | HTTP edge + gRPC client | the same four as `rider-service` |

**The HTTP-edge classes self-register; the gRPC client interceptor is wired in `config`.**
`RequestIdFilter` carries `@Component` and `ErrorDetailsHandler` carries `@RestControllerAdvice`.
`RequestIdClientInterceptor` is a **plain class with no annotation at all**, declared as a `@Bean` in
`GrpcClientConfiguration` (D3). → Appendix A5.

**`config` holds `GrpcClientConfiguration`** — the channel target, the stub types and the interceptor
chain — **and `ApiVersionConfiguration`**. Both are this service's own wiring rather than a convention.

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
| `sharedDependsOnNothingElseInThisService` | **new, written here** — see below |
| `onlyControllerDependsOnDto` | **new, written here** — see D5. `rider-service` only: `matching-service` has no `dto` package and gains one when it first serves JSON |

**`sharedDependsOnNothingElseInThisService`, written here rather than copied.**
`sharedDependsOnNoFeaturePackage` forbids `shared` depending on a *feature* (`fare`, `ride`, …), which
is a matching-service concern. What `rider-service` needs is stronger and simpler:
**`com.puber.rider.shared..` may depend on nothing else under `com.puber.rider..`** — not `controller`,
not `dto`, not `service`, not `model`, not `config`. The moment `ErrorDetailsHandler` references
`Quote`, the directory stops being liftable into `driver-service` and nobody finds out until they try.
**Prove it by planting** (Task 4.6).

**It also belongs in `matching-service`.** There the rule already exists in the weaker feature-only
form, so add the stronger clause **beside** it rather than replacing it: `shared` may depend on no
feature **and** on no layer of its own service.

**The trap: `DatabaseNeverReadsTimeTest.no_migration_asks_the_database_for_the_time` asserts
`filesScanned() > 0`.** `rider-service` owns no database (AD-3) and ships no `.sql` file, so copying
that method verbatim gives you a **red suite on a service that is behaving correctly**. Keep the
Java-source scan and the four planted-violation tests; drop the migration scan, with a one-line comment
saying why and naming what would bring it back.

**Copy the rule bodies, not a shared class.** They are test code, and no service depends on another's
code. They were written to copy cleanly — no `matching`-specific names in any of them.

### D7 — `matching-service` is stubbed in this service's tests, never called

**The rule lives in project-context.md → "Own datastores are real. Another service is stubbed."** Read
it there; it is not restated here. What follows is only what *this slice builds*.

`rider-service` owns no datastore, so **its integration tests need no container at all**. They boot
`rider-service` and an in-process gRPC stub of `QuoteService` in the same JVM:

```
src/integrationTest/java/com/puber/rider/
  support/StubQuoteService.java     extends QuoteServiceGrpc.QuoteServiceImplBase
                                    -- generated from contracts/proto, so a proto change breaks it
                                    -- records the Metadata it received; returns what the test sets
```

- **It implements the generated base class**, so the request and response shapes cannot drift from the
  real peer without a compile error. Do not hand-roll a fake that returns plausible values.
- **It records the `Metadata` it received**, which is what Task 6.6 asserts on. A receiver that saw the
  header is stronger evidence than a sender that attached one.
- **It can return a `Status` as well as a response**, which is how Task 6.2 produces `INVALID_ARGUMENT`
  without needing `matching-service` to be wrong about a latitude.
- **Transport:** `@AutoConfigureTestGrpcTransport` plus the auto-configured `GrpcChannelFactory`, the
  shape project-context.md already records for a gRPC test. **Verify it wires the client side the same
  way** — PUB-4-1 used it for a server and this is the first client use. If it does not, say so in the
  completion notes and use an explicitly-built in-process channel instead.
- **`support/`, not `rules/fixtures/`.** The latter is for deliberate rule-violators scanned by
  ArchUnit (project-context.md); this is neither.

**What this does not prove, and where that is covered:** nothing here shows `rider-service` and
`matching-service` still agree on *behaviour*. That is one end-to-end test at **PUB-4-3**, which brings
the whole stack up for the gateway anyway. → Appendix A8.

---

## Tasks / Subtasks

### Task 1 — scaffold `rider-service` as an independent build (AC7, AD-52)

- [x] **1.1** Create `services/rider-service/` with its own `gradlew` + `gradle/wrapper` at **9.5.1**,
      `settings.gradle` (`rootProject.name = 'rider-service'`, the same
      `foojay-resolver-convention` plugin), `.gitignore`, `.gitattributes`. Copy each from
      `matching-service` and change only what differs. **No root build, no shared plugin, no
      `buildSrc`** (AD-52) — the `Makefile` discovers the service automatically from
      `services/*/gradlew`, which is why the wrapper is step one.
- [x] **1.2** `build.gradle`. Verbatim from `matching-service`: the Java 25 toolchain, the
      analyzer-config `apply from:` guard **and its contract-copy message** (PUB-4-1 Task 1.6), the
      `integrationTest` `JvmTestSuite` block including its `useJUnitJupiter(dependencyManagement…)`
      line, the `configurations { integrationTest… extendsFrom }` block, the
      `tasks.withType(Test)` block (`maxParallelForks = 1`, `outputs.upToDateWhen { false }`,
      parallel execution off), `check dependsOn compileIntegrationTestJava`, and
      `integrationTest shouldRunAfter test`. Those comments explain themselves where they are; do not
      re-word them.
      `bootJar { archiveFileName = 'rider-service.jar' }`.
- [x] **1.3** Dependencies:
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
- [x] **1.4** Protobuf codegen: the same plugin, the same BOM-read generator versions and the same
      `sourceSets.main.proto.srcDir layout.buildDirectory.dir('contracts/proto')` as PUB-4-1 Task 2.
      **`build/contracts/proto` is populated by the Makefile target PUB-4-1 wrote generically over
      `$(SERVICES)`** — it needs no edit for this service, which is the point of AC8's mechanism.
      Confirm that rather than assuming it.
- [x] **1.5** `Dockerfile`: two stages copied from `matching-service`. `USER 10001:10001` **numeric**
      on both stages, `GRADLE_USER_HOME` writable, `COPY build/static-analyzers` **and**
      `COPY build/contracts` above `COPY src src`, runtime stage a JRE holding
      `rider-service.jar` by exact name. Keep `matching-service`'s comments only where they are still
      true of this file — a stale comment is worse than none.
- [x] **1.6** `.dockerignore`: `build/*`, `.gradle/`, and the two `!build/…` exceptions.
- [x] **1.7** `make build` passes with the service present and empty of application code, before you
      write any. A scaffold that does not build is a scaffold you debug twice.

### Task 2 — the endpoint (AC1b, AC2, AC5, AC6, D1, D4, D5)

- [x] **2.1** `RiderServiceApplication.java` — `@SpringBootApplication`, nothing else.
- [x] **2.2** `model/Quote.java` — a record carrying `long fareMinorUnits`, `long distanceMetres`, and
      **`OptionalLong etaMinutes`**. Settled by the repo owner on 2026-09-11; `Long` was the
      alternative. "No ETA" is representable without a sentinel and without a null, matching the
      contract's `optional`. **The domain record only** — the DTO is a different decision, see 2.5.
      **The domain record keeps its unit suffixes while the DTO drops them** (D1): it holds raw `long`s
      with no `Money` or `Distance` type to carry the meaning, unlike `matching-service`'s
      `quote/model/Quote`, so here the name is the only thing saying what the number is. **Do not
      rename it to match `QuoteResponse`** — mapping between the two is the translator's job, and D5
      is why the DTOs live in `dto/v1` rather than in `model`.
- [x] **2.3** `service/RequestQuote.java` — holds the injected blocking stub, one public method taking
      the two coordinate pairs as strings, calls `GetQuote`, returns `model/Quote`. It reads
      `hasEtaMinutes()` on the response rather than comparing to zero.
- [x] **2.4** `config/GrpcClientConfiguration.java` — D3's `@ImportGrpcClients`, **plus the
      `@Bean @GlobalClientInterceptor` method for Task 3.2's interceptor**. This one file is the whole
      gRPC-client setup (D3, D5a). **The `@GlobalClientInterceptor` annotation goes on the `@Bean`
      method and is not optional** — without it the bean is built and never attached, and nothing
      turns red except Task 6.6.
- [x] **2.5** `dto/v1/QuoteRequest.java` / `QuoteResponse.java` — plain records, no annotations
      (D5). Nested `Coordinates` record for the pair-of-pairs shape. `QuoteResponse` is
      `fare` / `distance` / `eta` per D1 — all three bare, with `eta` fixed at minutes.
      **`eta` is a nullable `Long` here, not an `OptionalLong`** — do **not** mirror `model/Quote`'s
      shape onto the wire. An `Optional` is a return type, not a field a serializer should meet, and
      D5's `non_absent` is what makes either shape produce an absent key. Keeping the DTO on a plain
      nullable box means the wire type is the one every HTTP client already expects.
- [x] **2.6** `controller/v1/QuotesController.java` — `@PostMapping("/quotes")`, served at
      `/rider/v1/quotes` via Task 2.9's prefix (D1) — **do not spell the prefix here**. Named for the
      resource it serves, so the class and the path agree. Reads `X-Rider-Id` and rejects blank
      or missing (D2), calls `RequestQuote`, maps `Quote` → `QuoteResponse`. **It is the only class
      in the service that may import `dto.v1`** — Task 4.7's rule enforces that.
      The ETA is the one field that changes shape in that mapping:
      `quote.etaMinutes().isPresent() ? quote.etaMinutes().getAsLong() : null`. `OptionalLong` has no
      `map` to a boxed type, so this is the whole conversion — do not add a helper for it.
- [x] **2.7** `shared/ErrorDetailsHandler.java` (D5a — **`shared`, not `controller`**) — a
      `@RestControllerAdvice` producing `org.springframework.http.ProblemDetail`, the RFC 9457 media
      type `application/problem+json` (AC5). **The class is named for AD-38's vocabulary ("one error
      vocabulary, mapped at the façade") and not for the type it returns**, settled by the repo owner
      on 2026-09-11 — so the RFC and the Spring type are named here and in D5a rather than in the
      identifier. Do not rename it back to match `ProblemDetail`, and do not confuse it with Spring's
      own `ErrorAttributes` / `ErrorController`, which are a different mechanism this service does not
      use. AD-38's table, **restricted to what can occur
      today**: `INVALID_ARGUMENT` → 400, `UNAVAILABLE` → 503, anything else → 500; plus Spring's own
      deserialization and missing-header failures → 400. Every body carries the request id as a
      property. **Do not implement rows for statuses no code can produce yet** —
      `FAILED_PRECONDITION`, `ALREADY_EXISTS` and `NOT_FOUND` arrive with the stories that create
      rides, and a mapping with no producer is a guard guarding nothing.
- [x] **2.8** `application.properties` — mirror `matching-service`'s observability block **exactly**
      (AC7): `management.endpoints.web.exposure.include=health,prometheus`,
      `management.endpoint.health.show-details=always`,
      `management.endpoint.health.probes.enabled=true`,
      `management.endpoint.health.cache.time-to-live=0`. **Omit every datasource and Flyway line.**
      Add `spring.application.name=rider-service`, D3's channel target, D5's Jackson inclusion, and
      Task 3's log pattern.
- [x] **2.9** `config/ApiVersionConfiguration.java` — a `WebMvcConfigurer` applying D1's
      `addPathPrefix("/rider/v1", …)` keyed on the **`controller.v1` package**, not on controllers in
      general (D1). One class, one method, one entry per version. The controller-package root is **one
      named constant** — AGENTS.md's No Magic Numbers applies to a path segment a client depends on as
      much as to a number. **Note it is `/rider`, not `/rider-service`** (D1): the directory and the
      image keep the suffix, the URL does not.
      It lives in `config` and **not** in `shared`: the *convention* (the API is `/v1`, the actuator is
      not) is cross-service and belongs in the spine per Task 7.2, but the predicate names this
      service's own controller package, so the class is wiring like `GrpcClientConfiguration`.

### Task 3 — the `shared` package, in **both** services (AC4b, D5a)

3.0–3.4 build `rider-service`'s half. **3.5–3.9 move `matching-service`'s half**, so the convention
has one home from the day the second service exists rather than being reconciled later. That part
touches code PUB-4-1 already shipped; it is a move plus one extraction, and it changes no behaviour.

- [x] **3.0** `shared/RequestId.java` — the four names in one place (D5a): the header
      `X-Request-Id`, the gRPC metadata key `x-request-id` as a `Metadata.Key`, the MDC key
      `requestId`, and `mint()` returning a `UUID` string. **Nothing else in the service spells any of
      these four literals.** PUB-4-1 did **not** create this type — it kept the same four names inline
      in `matching-service`'s `config/RequestIdServerInterceptor`, correctly, because one class needed
      them. Read that file and lift its literals verbatim: the two services agreeing on the wire is the
      whole point.
- [x] **3.1** `shared/RequestIdFilter.java` — a `@Component` servlet `Filter` that reads
      `RequestId.HEADER`, mints when absent (D2), puts it in MDC under `RequestId.MDC_KEY`, echoes it
      on the response header, and **clears MDC in a `finally`**. A pooled request thread otherwise
      carries the previous request's id into the next one's logs, which is worse than no id at all.
- [x] **3.2** `shared/RequestIdClientInterceptor.java` — a `ClientInterceptor` that copies the MDC
      value into metadata under `RequestId.METADATA_KEY`. **A plain class: no `@Component`, no
      `@GlobalClientInterceptor` on the class.** Task 2.4's `@Bean` in `GrpcClientConfiguration` is
      what registers it, so all gRPC client wiring reads from one file (D3, D5a). It differs from
      `RequestIdFilter` and `ErrorDetailsHandler`, which do self-register — D5a says why.
- [x] **3.3** `logging.pattern.level=%5p [%X{requestId:-}]` in `application.properties`, so the id
      is on **every** line and not only the ones that remember to interpolate it. **Do not reach for
      `logging.pattern.correlation`** — in Boot that slot is driven by Micrometer tracing's trace and
      span ids, which this project does not have.
- [x] **3.4** `ErrorDetailsHandler` sets the id as a Problem Details property (AC5's second clause).

**`matching-service`, moving its half into `shared` (D5a):**

- [x] **3.5** Extract `shared/RequestId.java` in `matching-service` too, holding the same four names,
      and have the interceptor read them from it. This is the type that makes the two services
      *provably* agree — the alternative is two files each spelling `x-request-id` and nobody noticing
      when one changes.
- [x] **3.6** Move `config/RequestIdServerInterceptor.java` → `shared/RequestIdServerInterceptor.java`.
      It keeps `@Component @GlobalServerInterceptor`; the self-registration is already what D5a wants,
      so nothing about its wiring changes. Its `REQUEST_ID_HEADER` constant moves to `RequestId`.
- [x] **3.7** Move `config/UnexpectedGrpcFailureStatusMapper.java` →
      `shared/UnexpectedGrpcFailureStatusMapper.java`. It is AD-38's "one error vocabulary" for a gRPC
      edge — the same convention `ErrorDetailsHandler` is for an HTTP one — so it belongs beside it.
- [x] **3.8** Fix the one caller: `QuoteGrpcIntegrationTest` imports
      `com.puber.matching.config.RequestIdServerInterceptor` (line 19) and reads
      `RequestIdServerInterceptor.REQUEST_ID_HEADER` (line ~343). Repoint both at
      `shared.RequestId`. **That is the only reference in the repository** — verified by grep on
      2026-08-26; if you find another, this story's Dev Notes were wrong and say so.
- [x] **3.9** Confirm no existing ArchUnit rule breaks. Checked on 2026-08-26 and none should, but
      confirm rather than trust it:
      - `theRealClockIsOnlyEverInjected` restricts `config..` for `SystemClock` only — moving classes
        *out* of `config` cannot affect it.
      - `sharedDependsOnNoFeaturePackage` forbids `shared` → `fare`/`ride`/`dispatch`/`quote`. The
        interceptor depends on `io.grpc`, `org.slf4j` and `org.springframework.grpc` — no feature.
      - `modelDependsOnNothingFrameworkFlavoured` binds `..model..`. These land in `shared`'s **root**,
        not `shared/model`, which is exactly the root-versus-sub-layer split Task 7.1 writes down.
      - `featureDependenciesRunOneWay` declares `shared` as a layer with no `whereLayer` restriction
        and uses `consideringOnlyDependenciesInLayers()`, so `config` → `shared` was never considered
        and `shared` → nothing-in-a-layer stays true.

### Task 4 — the structural rules (AC11, D6)

- [x] **4.1** Create `src/test/java/com/puber/rider/rules/` per D6's table, plus the fixture classes
      each rule needs. **A violator whose rule names an absolute package cannot live under
      `rules/fixtures/`** — it sits in the production package it violates, which project-context.md
      records and PUB-3's review re-confirmed. None of `rider-service`'s rules name an absolute
      package yet, so all its fixtures can live in `rules/fixtures/`; check that before moving one.
- [x] **4.2** Create `src/integrationTest/java/com/puber/rider/rules/TestNamingRulesIntegrationTest.java`,
      including its **non-vacuity assertion** — a rule that scans nothing passes forever, which is
      exactly the failure that class was added to fix in `matching-service`.
- [x] **4.3** **Prove each copied rule can fail in `rider-service`.** Plant one violation per rule, run
      `make test`, capture the failure, revert, confirm green. This is AC11's second clause and the
      whole reason the deferred item exists: *"forget the copy and the new service can read the clock
      however it likes, and nothing anywhere turns red."* Record each planted violation and its failure
      line in the Debug Log.
- [x] **4.4** Apply PUB-4-1's contracts-package exclusion to this service's model rule, and prove it
      the same way: plant a `Quote` field typed as `GetQuoteResponse`, watch it go red, revert.
- [x] **4.5** Add `sharedDependsOnNothingElseInThisService` (D6) **to both services**: no class under
      `com.puber.<service>.shared..` may depend on anything else under `com.puber.<service>..`.
      **This one is written here, not copied** — it is what makes D5a's directory liftable into the
      next service. In `matching-service` add it **beside** `sharedDependsOnNoFeaturePackage`, not
      instead of it: the feature clause is AD-9's and still binds, and the new clause is stronger in a
      different direction.
- [x] **4.6** **Prove 4.5 fires, in both services.** In `rider-service`, plant an import of
      `com.puber.rider.model.Quote` into `ErrorDetailsHandler`. In `matching-service`, plant an
      import of `com.puber.matching.quote.model.Quote` into `RequestIdServerInterceptor` — which also
      re-proves the feature clause did not already cover it. Run `make test`, capture each failure,
      revert, confirm green. Then check neither is vacuous: point a rule at a package that does not
      exist and confirm it goes red rather than passing on an empty set.
- [x] **4.7** Add `onlyControllerDependsOnDto` (D5) to `rider-service`, **and prove it fires**:
      plant an import of `com.puber.rider.dto.v1.QuoteResponse` into `service/RequestQuote`, run
      `make test`, capture the failure, revert, confirm green. Then plant a second `dto` record that
      references `QuoteRequest.Coordinates` and confirm the suite **stays green** — a rule that also
      fails on DTO-to-DTO dependency is a rule someone will delete rather than fix. Not added to
      `matching-service`: it has no `dto` package, and a rule scanning nothing passes forever.
- [x] **4.8** Close `deferred-work.md`'s PUB-2-review item: state what was copied, what was
      deliberately not, and why. It named Story 1.4 by number; this is where it lands.

### Task 5 — Compose and the Makefile (AC7, D3, and the test wiring)

- [x] **5.1** `infra/docker-compose.yml`: add `rider-service` — built from `../services/rider-service`
      target `runtime`, `image: puber/rider-service:0.0.1-SNAPSHOT`,
      `depends_on: matching-service: condition: service_healthy`, environment carrying
      `MATCHING_SERVICE_GRPC_TARGET`, **no `ports:` block**, and the same `bash`/`/dev/tcp`
      healthcheck `matching-service` uses. Copy that healthcheck including the comment explaining why
      the runtime image has no `curl` and deliberately stays that way.
- [x] **5.2** **Do not touch `matching-service`'s `ports:` block.** Removing it is PUB-4-3's AC3 work;
      doing it here leaves the system with nothing reachable and no gateway to replace it.
- [x] **5.3** `tests` service: **no change, and that is the point** (D7). `rider-service`'s
      integration tests stand up an in-process stub, so they need neither `MATCHING_SERVICE_GRPC_TARGET`
      nor a `depends_on: matching-service`. **If you find yourself adding either to make a test pass,
      stop** — the test is reaching for the real peer and D7 says it must not.
- [x] **5.4** **There is still exactly one test runner, and it is built from `matching-service`'s
      Dockerfile.** It is a JDK-and-Gradle image; the Makefile points it at each service with
      `--workdir` and the mounted workspace supplies the sources. **Do not add a second `tests`
      service** — the only thing it would give you is a second image to keep in step.
- [x] **5.5** `Makefile`: `test-integration` needs **no new dependency for this service** — it already
      brings up `matching-postgres` for `matching-service`'s own suite, and `rider-service` adds
      nothing (D7). **Do not add `matching-service` to it.**
      `build`, `format`, `test-unit` and `static-analysis` need no change — they iterate
      `$(SERVICES)` and the wrapper from Task 1.1 is enough. `run` brings up `rider-service` too.
      Update the `help` text if any of it stops being true.
- [x] **5.6** **Address services by Compose service name, never `localhost`** (project-context.md →
      Real datastores only). The runner is a peer container.
- [x] **5.7** `verify-no-root-owned-files` passes after a full `make build && make test`. The protobuf
      plugin writes into `build/generated` and downloads two native executables into the Gradle cache,
      so this is a real check and not a formality.

### Task 6 — tests (AC1b, AC2, AC4b, AC5, AC6, AC7)

Unit tests in `src/test/java`, integration tests in `src/integrationTest/java`, `snake_case` methods,
`@DisplayName` carrying the `AC<n>:` reference (AGENTS.md → Test Naming and Placement).

**Every integration test below runs against D7's in-process stub, never against `matching-service`.**
The subject is this service: the HTTP surface, the translation in both directions, the error mapping
and the request-id chain.

- [x] **6.1** Integration: `POST /rider/v1/quotes` returns 200 with `fare` and `distance` carrying the
      values the stub returned, and **no `eta` key at all** (AC1b, AC2). **Assert the key's absence,
      not that its value is null** — the `non_null`/`non_absent` slip in D5 produces `"eta":null`,
      which a null-valued assertion would wave through.
      **This is a translation test, not a pricing test, and the values must make that obvious.**
      Have the stub return numbers no fare rule would ever produce — `fare_minor_units = 7`,
      `distance_metres = 3` — so a reader cannot mistake it for a pricing assertion and nobody tries to
      keep it in step with `fare_rules`. Pricing is proven inside `matching-service` by PUB-3 and is
      not re-proven here. **Also assert the request the stub received** carries the two coordinate
      strings unchanged (D4: this service passes them through and parses nothing).
- [x] **6.2** Integration: four failures — no body, a non-JSON body, a missing `dropoff`, and **the
      stub answering `INVALID_ARGUMENT`** — each answer **400**, `application/problem+json`, with the
      request id in the body (AC5). The fourth is the important one: it proves the gRPC status is
      mapped rather than leaking as a 500. **Whether `matching-service` really returns
      `INVALID_ARGUMENT` for an out-of-range latitude is PUB-4-1's business and PUB-4-1 tested it** —
      do not re-prove it here, and do not send a bad latitude expecting the stub to judge it. Add one
      more case while you are here: the stub answering `UNAVAILABLE` maps to **503** (Task 2.7's table).
- [x] **6.3** Integration: a request with no `X-Rider-Id` answers 400; a request carrying a rider id
      never seen before answers 200 (AC6 — "trusted as-is, no registration").
- [x] **6.4** Integration: health is UP and `/actuator/prometheus` serves Prometheus text format
      (AC7). **`@AutoConfigureMetrics` is required** or the endpoint 404s under test while working in
      the running service. **Assert the actuator paths are the unprefixed ones** — `/actuator/health`,
      not `/rider/v1/actuator/health` — which is what proves D1's prefix was scoped to
      controllers rather than applied with `context-path`. Both spellings answering `200` would mean
      the scoping failed, and **PUB-4-3's AC3 test depends on this**: it asserts the gateway 404s
      `/actuator/health`, which only means something while the actuator sits outside the routed
      prefix.
- [x] **6.5** Integration: a request carrying a known `X-Request-Id` gets it back on the response,
      and a request carrying none gets a minted one back (AC4b).
- [x] **6.6** **One test that proves the id crossed the gRPC hop** (AC4b's third clause): send a known
      `X-Request-Id`, then assert **D7's stub received it** in metadata under `x-request-id`. The stub
      is the receiver, so this proves the header arrived rather than merely that something attached it.
      **Asserting the id on the HTTP response only proves the filter ran** — that is Task 6.5, and it
      is not this. **No log scraping**: reading another process's stdout to assert a header races the
      flush and breaks on any log-format edit.
- [x] **6.7** Unit: `RequestQuote` maps an absent `eta_minutes` to `OptionalLong.empty()`, and a
      present one to `OptionalLong.of(...)`. The second case has no production producer yet and is the cheapest possible guard
      against Story 2.6 discovering the mapping was never written.
- [x] **6.8** Integration: **both** shorter spellings answer `404` — `POST /quotes` and
      `POST /v1/quotes`. Cheap, and the only thing that proves the prefix is real rather than
      decorative: a misconfigured predicate leaves the controller mapped at its bare path *as well*,
      and every other test still passes.

### Task 7 — record what this slice settled

- [x] **7.1** `project-context.md` → **extend the existing "What belongs in `shared`" section**, which
      is currently written only for `matching-service`'s feature order. It now has to say two more
      things: (a) in a **layered** service, `shared` means *the conventions every service's edge
      implements identically* rather than "the bottom of the feature order" — same membership test
      ("does the type encode a convention?"), different surrounding structure; and (b) `shared`'s
      **root holds cross-cutting plumbing while its sub-packages hold layer-shaped content**, which is
      why `RequestId` sits beside `shared/model/Money` rather than inside it.
      **Extend that one section; do not open a second `shared` section** — two homes for one rule is
      what CLAUDE.md forbids, and `project-context.md` already records what happened when "fixture"
      came to mean two things.
      Also record (c) that **`shared` is copied, but not every class in it self-registers**: the HTTP
      edge classes carry their own stereotype, while `RequestIdClientInterceptor` is a plain class
      wired as a `@Bean` in the receiving service's `GrpcClientConfiguration` (D5a). A service copying
      `shared` and getting no request id on its outbound gRPC calls has forgotten that bean, and the
      symptom is silent.
- [x] **7.2** `ARCHITECTURE-SPINE.md` → Consistency Conventions: add a **Public paths** row. No
      document mentions URL layout or versioning today, and Epic 2's `driver-service` needs the same
      rule, so the spine is its home: *every public path is `/<service>/<version>/<resource>` —
      `/rider/v1/quotes` — never rewritten by the gateway and never applied to `/actuator`. The
      actor segment is suffix-free per AD-12 (`/rider`, not `/rider-service`) and is what keeps AD-5's
      route list one rule per backend. **The version is a controller sub-package**
      (`…controller.v1`), and the path prefix is derived from it, one entry per version, so opening a
      v2 touches no v1 code; the request and response DTOs carry the same version in **their own
      package**, `dto.v1` beside `controller.v1`, because the DTO shape is the contract and because
      `controller` holds controllers. Internal protobuf is versioned in its package instead
      (`puber.<domain>.v1`), because there the package is the wire name.* **The same row records
      `dto` as a layer AD-7's list does not name** (D5), so Epic 2's `driver-service` does not invent
      a different answer, and it names `onlyControllerDependsOnDto` as what keeps the wire shape from
      reaching past the controller. **Do not restate any of it in `project-context.md`** — CLAUDE.md
      forbids the second copy.
- [x] **7.3** `project-context.md`: also add the request-id chain and the MDC-clearing requirement;
      that `rider-service` deliberately has no `Clock`, so one rule is absent by design; and that a new
      service's rule copies are hand-made, including the `DatabaseNeverReadsTimeTest` migration-scan
      trap. **Do not restate AD-5, AD-33, AD-37, AD-38 or AD-54** — CLAUDE.md forbids duplicating a
      rule across files.
- [x] **7.4** `deferred-work.md`: Task 4.8's closure.
- [x] **7.6** **The stub rule is already written** — project-context.md → "Own datastores are real.
      Another service is stubbed.", and AGENTS.md's narrowed bullet, both edited 2026-09-11 before this
      story was picked up. **Do not restate either** (CLAUDE.md forbids the second copy). What this
      task owes is the one thing they defer to: raise a `sprint-status.yaml` action item, targeted at
      **PUB-4-3**, for the single end-to-end test that closes the agreement gap. Without that item the
      gap is recorded only in a story file nobody reads again.
- [x] **7.5** Anything deferred out of this slice goes in `deferred-work.md` **and** in whichever of
      the epic file / `sprint-status.yaml` `action_items` / `project-context.md` will actually surface
      it. `deferred-work.md` alone is an audit trail nothing reads.

### Task 8 — the gate

- [x] **8.1** `make build`, then `make test`, **in that order, from a clean tree**, and read the
      output. Not `make test-unit`: it runs neither Spotless nor the integration suite, which is how
      PUB-2's review left the build red while reporting the suite green.
- [x] **8.2** Report what you saw, including the known environmental red —
      `HealthReportsDownPromptlyIntegrationTest`'s precondition — named rather than omitted, and never
      quietly counted as green.
- [x] **8.3** Only then set this story and `sprint-status.yaml` to `review`. **Leave `PUB-4` itself at
      `in-progress`** — the parent is `done` only when PUB-4-3 lands.
- [x] **8.4** Leave everything **unstaged**. The repo owner reviews the unstaged diff.
- [x] **8.5** **Prove Tasks 3.5–3.9 changed no behaviour.** `matching-service`'s suite was green
      before this slice touched it, so it must be green after with **no test expectation edited** — the
      only permitted change to `QuoteGrpcIntegrationTest` is Task 3.8's import and constant reference.
      If an assertion needed changing, the move was not a move. Say so in the completion notes rather
      than adjusting the test.

### Review Findings

Code review 2026-09-12 (`bmad-code-review`, three parallel layers: adversarial, edge-case, acceptance
audit). Every finding below was **run**, not reasoned about, unless its bullet says otherwise.

**Decisions resolved by the repo owner (2026-09-12)**

- [x] [Review][Dismissed] `ErrorDetailsHandler` holding gRPC-status mapping inside `shared/` is **not** a finding — owner's call: *"by being in shared, it doesn't necessarily mean it will be shared with other services. Mainly, it's shared with the other components of the same service."* `shared` is first the bottom of this service's own dependency order; cross-service liftability is a consequence of that, not its definition. The review had read project-context.md → "In a layered service `shared` means the same thing with different surroundings" as making copyability the primary test. It is not. No change.
- [x] [Review][Decision] `RequestQuoteTest` → **delete it**, and move its one case the endpoint does not yet cover into `QuoteIntegrationTest`. Recorded as a patch below.

**Patches — the fix is unambiguous**

- [x] [Review][Patch] Delete `RequestQuoteTest` and cover its one uncovered case at the endpoint [`services/rider-service/src/test/java/com/puber/rider/service/RequestQuoteTest.java`] — it unit-tests `RequestQuote`, which stands up an in-process gRPC server and channel to be testable at all. AGENTS.md → Integration tests by default: *"If the class needs a repository, a `Clock` or a transport to do its job, the test belongs in `src/integrationTest/java`"*; the class's defence ("no socket is opened, so this belongs in the unit suite") answers a criterion AGENTS.md does not use. Its javadoc claims it covers *"the one thing this service's translation does that the endpoint cannot show both sides of"*, which `QuoteIntegrationTest.serves_an_eta_when_one_is_available:242-254` falsifies — and the zero case is equally reachable with `answerWith(fare, distance, 0L)`. Add `serves_a_zero_eta_as_a_present_zero` to `QuoteIntegrationTest` (the only one of the three not already covered there), then delete the class.

- [x] [Review][Patch] A null or absent coordinate **value** returns HTTP 500 in plain JSON with no request id, not a 400 Problem Details [`services/rider-service/src/main/java/com/puber/rider/controller/v1/QuotesController.java:55-60`, `services/rider-service/src/main/java/com/puber/rider/service/RequestQuote.java:43-45`] — **violates AC5, AC4b and D4.** `requireCoordinates` checks the `Coordinates` *object* is non-null but never its two string fields, so a null reaches protobuf's `Coordinates.Builder.setLatitude`, which throws `NullPointerException` (`build/generated/sources/proto/main/java/com/puber/contracts/quote/v1/Coordinates.java:510`) **before the gRPC call is made** — which is why D4's row *"coordinate not a decimal number, out of range, **null** → `matching-service` → `INVALID_ARGUMENT` → 400"* can never fire. Measured against the running service: `{"pickup":{"latitude":null,"longitude":null},…}` → `HTTP/1.1 500`, `Content-Type: application/json`, `{"timestamp":…,"status":500,"error":"Internal Server Error","path":"/rider/v1/quotes"}` — no `detail`, no `title`, no `requestId`. `{"pickup":{}}` is identical. Found independently by all three review layers. **Also closes:** the unhandled-exception log line carries an empty request id (`ERROR [] … dispatcherServlet : … NullPointerException`), because `RequestIdFilter` clears the MDC in its `finally` before Tomcat's `StandardWrapperValve` logs — the one line an operator would grep is the one with no id. Handling the exception inside the dispatcher fixes both. No test covers either body shape.
- [x] [Review][Patch] 404, 405 and 415 responses are plain JSON with no request id and are not RFC 9457 [`services/rider-service/src/main/java/com/puber/rider/shared/ErrorDetailsHandler.java:24`] — the advice names three exception types and does not extend `ResponseEntityExceptionHandler`, so everything Spring resolves before the handler falls through to the default `/error` rendering. Measured: `Content-Type: text/plain` → `415 {"timestamp":…,"error":"Unsupported Media Type",…}`; `GET /rider/v1/quotes` → `405`; `POST /rider/v1/nope` → `404`. The `X-Request-Id` response **header** is correct in every case — only the body clause of AC4b (*"in every error"*) fails. `QuoteIntegrationTest.serves_the_quote_at_the_prefixed_path_only:229` already provokes two 404s and asserts only the status code.
- [x] [Review][Patch] `ArchUnitReadsJava25ClassFilesTest` was not copied into `rider-service` [`services/rider-service/src/test/java/com/puber/rider/rules/`] — it is the non-vacuity floor under every other rule, and **9 of `rider-service`'s 10 `ArchRule` fields carry `allowEmptyShould(true)`**. Its own javadoc in `matching-service` states the stake: *"If ArchUnit silently imported nothing, every rule in `ArchitectureRulesTest` would pass while asserting nothing at all."* A typo in `@AnalyzeClasses(packages = "com.puber.rider")`, or an ArchUnit/JDK bump, takes all nine green. D6's table enumerates *rules* and this is a meta-test, so its absence is defensible against the letter of the spec but not AC11's second clause; `deferred-work.md`'s new "every one was proven capable of failing there" table does not mention it either. **Honest limit:** the per-rule proving tests (`LayerRulesTest` and friends) import fixtures and `assertThrows(AssertionError.class, …)`, so a *total* ArchUnit parse failure would still turn them red. What is uncovered is the live `@AnalyzeClasses` scan importing an empty set. ~60 lines, class name and package swapped.
- [x] [Review][Patch] The `default ->` 500 branch of the upstream error mapping is correct and completely untested [`services/rider-service/src/main/java/com/puber/rider/shared/ErrorDetailsHandler.java:52-58`] — it carries the security-relevant promise *"Fixed text, never the upstream description: the detail belongs in this service's log, not on a wire an external caller reads."* `QuoteIntegrationTest` covers `INVALID_ARGUMENT` and `UNAVAILABLE` and stops. Verified correct today by stubbing `Status.INTERNAL.withDescription("fare_rules table is empty")` → `500 {"detail":"the request could not be answered",…,"requestId":"…"}`. Nothing pins it: someone "improving" the message to `status.getDescription()` would leak `matching-service`'s internal exception text to external callers and the suite stays green. `StubQuoteService.rejectWith(...)` already exists — three lines.
- [x] [Review][Patch] Five rule tests cite an acceptance criterion that means something else in this story [`services/rider-service/src/test/java/com/puber/rider/rules/DatabaseNeverReadsTimeTest.java:67,83,109,138` say `AC1:`; `services/rider-service/src/integrationTest/java/com/puber/rider/rules/TestNamingRulesIntegrationTest.java:27` says `AC7:`] — both numbers were copied verbatim from `matching-service`, where they meant PUB-2's criteria. Here AC1b is the gRPC hop and AC7 is observability, so grepping `AC7` in `rider-service` returns a test-naming check as if it were observability evidence, and grepping `AC11` misses five tests that actually prove it. AGENTS.md keeps `@DisplayName` precisely because *"it is what lets a reviewer grep for whether a criterion is actually tested"*. All five should read `AC11:`.
- [x] [Review][Patch] A comment states a fact the code contradicts, with a verification date attached [`services/rider-service/src/main/resources/application.properties:17-19`] — *"non_absent, NOT non_null: NON_NULL emits `"eta":null` for an empty OptionalLong… Checked against jackson-databind 3.1.4 on 2026-09-11."* No `OptionalLong` ever reaches Jackson: `QuoteResponse` is `record QuoteResponse(long fare, long distance, Long eta)`, a nullable box, which `NON_NULL` drops identically. This story's own Debug Log says exactly that — *"`non_absent` → `non_null` left the suite **green**"* — and names `always` as the real discriminator. AGENTS.md: *"Do not state facts you have not checked… Nothing tests a comment, so a wrong one outlives wrong code."* The `Checked against…` attribution buys credibility for the wrong half.
- [x] [Review][Patch] `floatingPointIsConfinedToDistance` keeps a name for an exemption it no longer has [`services/rider-service/src/test/java/com/puber/rider/rules/ArchitectureRulesTest.java:239`] — D6 correctly required the `Distance` exemption dropped and the body drops it, but the name still advertises a confinement to a type this service does not have. A reader grepping `rider-service` for `Distance` finds only a rule promising one. `noProductionTypeDeclaresFloatingPoint` says what it does. A rename costs an edit in `project-context.md` and `deferred-work.md`, which both refer to it by name.
- [x] [Review][Patch] `Makefile:151` hard-codes the service list where every other target is written over `$(SERVICES)` [`Makefile:151`] — `$(COMPOSE) up -d --wait matching-postgres matching-service rider-service`. `analyzer-config`, `contract-config`, `build`, `test-unit` and `test-integration` all iterate `$(SERVICES)`, and `contract-config`'s own comment states the convention: *"Written over `$(SERVICES)`, so a new service is picked up with no edit here."* `make run` now needs a hand-edit per service, PUB-4-3's gateway will need another, and nothing turns red when it is forgotten — `make run` just silently brings up less than the stack.
- [x] [Review][Patch] `handleMissingInput` echoes the framework's own exception message to external callers, two methods above a comment forbidding exactly that [`services/rider-service/src/main/java/com/puber/rider/shared/ErrorDetailsHandler.java:36-39`] — returns `missing.getMessage()` verbatim, so an absent header yields Spring's `Required request header 'X-Rider-Id' for method parameter type String is not present`, which names the Java parameter type. `handleUpstreamFailure`'s default arm deliberately refuses to echo upstream text; one of the two policies is wrong. The controller's own hand-written messages (`"pickup is required"`) *should* be echoed — the fix is to distinguish them from Spring's. Same file, line 30: `handleUnreadableRequest`'s `unreadable` parameter is declared and never used; `@ExceptionHandler` methods need no argument.
- [x] [Review][Patch] `StubQuoteService` retains the live `Metadata` object past the end of the call [`services/rider-service/src/integrationTest/java/com/puber/rider/support/StubQuoteService.java:92`] — stores the `headers` reference and reads it after the response is returned. gRPC does not guarantee a `Metadata` instance stays valid once the call completes. It works today; it is a flake waiting for a gRPC upgrade, in the one test that guards the `@GlobalClientInterceptor` silent-failure trap. Copying the value out inside `interceptCall` (`headers.get(RequestId.METADATA_KEY)`) costs nothing.
- [x] [Review][Patch] `RequestId`'s javadoc promises "the four names" but `matching-service` spells only three [`services/matching-service/src/main/java/com/puber/matching/shared/RequestId.java:17`] — `HEADER` has zero references across `matching-service/src`; that service has no HTTP edge reading a request header. Carrying it is defensible as the price of a verbatim-copyable directory, but the javadoc reads as a live contract and a reader cannot tell the dead constant from the load-bearing ones. Say which are live per service.

**Deferred — real, but not this slice's to close**

- [x] [Review][Defer] No deadline on the gRPC call to `matching-service` [`services/rider-service/src/main/java/com/puber/rider/config/GrpcClientConfiguration.java:19`, `src/main/resources/application.properties:14`, call site `services/rider-service/src/main/java/com/puber/rider/service/RequestQuote.java:27`] — deferred, routed to Epic 4 by this story's own Scope boundaries (*"Rate limits, HAProxy queue bounds, AD-6's bound chain → Epic 4 — and AD-47 says those numbers are measured, not guessed"*). Proven twice: `quotes.getCallOptions().getDeadline()` is `null`, and with the peer blocked for 9000 ms `rider-service` answered after **9213 ms** with a `200`. Tomcat's 200 request threads fill with waiters, `/actuator/health` stops answering, and Compose — and Epic 7's Kubernetes probe, the same `exec` — kills a service whose only fault is a slow peer. Corollary for whoever sets the number: `DEADLINE_EXCEEDED` cannot occur today, and when it can it falls into `default ->` and returns **500, not 504**.
- [x] [Review][Defer] A non-ASCII request id is silently mangled on the gRPC hop, so the two services log different ids [`services/rider-service/src/main/java/com/puber/rider/shared/RequestId.java:26-27`, `services/rider-service/src/main/java/com/puber/rider/shared/RequestIdClientInterceptor.java:29-32`] — deferred, belongs with the request-id contract at PUB-4-3 when the gateway becomes the minting point. `X-Request-Id: café-ééé` → `rider-service` echoes and logs `café-ééé`, the peer receives `caf?-??` (`Metadata.ASCII_STRING_MARSHALLER`). Nothing logs or fails; the only symptom is two log sets that cannot be joined, which is the one thing AD-54 exists to prevent.
- [x] [Review][Defer] The request id is unbounded on its way into gRPC metadata [`services/rider-service/src/main/java/com/puber/rider/shared/RequestIdFilter.java:23-27`, `services/rider-service/src/main/java/com/puber/rider/shared/RequestIdClientInterceptor.java:31`] — deferred, same home as the item above. A 7000-character `X-Request-Id` crossed to the peer intact; Tomcat's 8 KB header cap is the only bound and it is not gRPC's, whose default `maxInboundMetadataSize` is 8192 for the *whole* block. **Honest limit on this one:** exercised over the in-process transport, which bypasses HTTP/2 header limits — so it proves nothing in `rider-service` bounds the value, *not* that it fails over the real Netty channel.
- [x] [Review][Defer] Compose makes `rider-service` unable to start when `matching-service` is down [`infra/docker-compose.yml:68-70`] — deferred, routed to PUB-4-3. `depends_on: matching-service: condition: service_healthy`. `ErrorDetailsHandler` maps `UNAVAILABLE` → 503 precisely so the rider surface degrades rather than dies; in the stack as configured the container never comes up to serve that 503. It bites when PUB-4-3 wants to demonstrate the degraded path behind the gateway.


---

## Dev Notes

### What exists when this slice starts

After PUB-4-1: `contracts/proto/puber/quote/v1/quote.proto` is the single contract source, the
`Makefile` copies it into every service's build output, `matching-service` serves `QuoteService/GetQuote`
over gRPC and reads a request id out of metadata, and `Coordinates`, `Distance` and `FareRule`
reject bad input with named-field messages.

**Two classes sit in `matching-service`'s `config/` that this slice moves into `shared/`** —
`RequestIdServerInterceptor` (which also holds the header name, the `Metadata.Key` and the MDC key
inline) and `UnexpectedGrpcFailureStatusMapper`. Both are self-registering `@Component`s already.
Tasks 3.5–3.9 move them; D5a says why.

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

**Nothing in this slice proves the two services still agree on behaviour.** Its tests run against
D7's stub, so shapes cannot drift — both compile against the same `contracts/proto` — but nothing here
shows `matching-service` returns what `rider-service` expects. **That is deliberate and it is covered
once, at PUB-4-3**, which brings the whole stack up for the gateway: one end-to-end quote through
HAProxy → `rider-service` → `matching-service`. Until that lands, the gap is real and open. Do not
close it by reaching for the real peer here — project-context.md → "Own datastores are real. Another
service is stubbed."

**Nothing outside the Compose network can reach this endpoint yet.** `rider-service` publishes no
port, by design — PUB-4-3's gateway is the front door. So the only callers this slice has are the
integration tests, and a human wanting to try it must go through `docker compose exec`.

**This slice edits code PUB-4-1 already shipped, and that is deliberate rather than scope creep.**
Tasks 3.5–3.9 move two classes out of `matching-service`'s `config/` into `shared/` and extract the
four request-id names into a type both services read. The alternative was writing `rider-service`
against a `matching-service` that disagreed about where the convention lives, then reconciling — which
is more work and leaves a window where a third service could copy the wrong one. **It is a move plus
an extraction: no behaviour changes, and Task 8.5 is the check that says so.**

### Scope boundaries — what is deliberately not here

| Not in this slice | Where it lands |
| --- | --- |
| HAProxy, `infra/haproxy.cfg`, the `/rider/` route, the 404 default | **PUB-4-3** |
| Removing `matching-service`'s published `8080:8080` | **PUB-4-3** |
| Minting the request id at the gateway; the tests that prove it | **PUB-4-3** |
| Bringing HAProxy up for `make run` / `make test` | **PUB-4-3** |
| `contracts/`, the copy mechanism, `matching-service`'s gRPC surface, the value-type hardening | **PUB-4-1** (already done) |
| Any change to `matching-service` **other than** Tasks 3.5–3.9's move into `shared` | Nowhere — that move changes no behaviour, and anything beyond it has left this slice |
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
    shared/      RequestId.java  RequestIdFilter.java                  (D5a -- one flat
                 RequestIdClientInterceptor.java  ErrorDetailsHandler.java   directory, copied
                                                                          per service)
    config/      GrpcClientConfiguration.java  ApiVersionConfiguration.java
    controller/v1/
                 QuotesController.java
    dto/v1/      QuoteRequest.java  QuoteResponse.java
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
    support/StubQuoteService.java                      (D7 -- the in-process peer)
    rules/TestNamingRulesIntegrationTest.java

services/matching-service/src/                   (edited -- D5a, Tasks 3.5-3.9)
  main/java/com/puber/matching/
    shared/    RequestId.java                     (new -- extracted from the interceptor)
               RequestIdServerInterceptor.java    (moved from config/)
               UnexpectedGrpcFailureStatusMapper.java  (moved from config/)
  test/java/com/puber/matching/rules/
    ArchitectureRulesTest.java                    (edited -- the new shared clause)
  integrationTest/java/com/puber/matching/
    QuoteGrpcIntegrationTest.java                 (edited -- one import, one constant)

infra/docker-compose.yml                         (edited -- rider-service added, tests env)
Makefile                                         (edited -- Task 5.5)
project-context.md                               (edited -- Task 7.1)
_bmad-output/implementation-artifacts/deferred-work.md   (edited -- Task 4.8)
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

## Appendix A — why these decisions were made

**Not implementation guidance. Nothing here is a task.** This is the record of *why* each frozen
decision in "Story-local decisions" went the way it did, including the options that were considered and
rejected. It exists so none of them is re-argued — in review, in a later slice, or by the next agent
that finds a rejected option attractive.

Read a section only if you believe the decision it supports is **factually** wrong. A preference is not
a reason to reopen one.

### A1 — why `POST`, and not `GET` or `QUERY` (D1)

`POST`, not `GET`, and the reason is not REST purity. A quote's input is a *pair of pairs*; four flat
query parameters (`pickupLat`, `pickupLng`, …) has no shape and only one failure mode — a missing
parameter. A JSON body gives AC5 a real deserialization failure to map, and it is the shape
`POST /rides` needs in Story 3.2 anyway.

The `GET` argument is cacheability, and it does not apply: a quote moves with surge, so it is not
cacheable, and advertising it as cacheable would be actively wrong. AD-40's `ETag`/`304` handling is
for **ride detail**, not for quotes.

**The `QUERY` method was considered and rejected on tooling, not on semantics** (raised 2026-08-26).
QUERY is the proposed safe-and-idempotent method that carries a body, which is *exactly* this
endpoint's shape — and the obvious objection, that a quote returns a different price at different
times, **is not an objection**: HTTP idempotency is about the effect on the server, not the response.
`GET /stock-price` is safe and idempotent and changes every second. A quote's effect is zero, twice
over, which is what AC1's "no ride is created" says.

What rules it out is that **Spring cannot express it.** `@RequestMapping(method = …)` takes
`RequestMethod`, which in Spring Framework 7.0.8 — the version Boot 4.1 ships — is a **closed enum**
of `GET HEAD POST PUT PATCH DELETE OPTIONS TRACE`, verified by reading the class. Serving QUERY needs
a custom `RequestCondition` or `HandlerMapping`, and probably a servlet-layer workaround too, since
`HttpServlet.service()` answers `501` to methods it does not know. Against that, QUERY's one real
gain over POST is cacheability — which a surge-priced quote cannot use anyway.

Revisit only if QUERY is published *and* `RequestMethod` gains it. **Do not hand-roll it**:
project-context.md → YAGNI, name the failure it prevents. There isn't one.

### A2 — why the path is `/rider/v1/…`, and why the prefix is derived from a package (D1)

**Settled by the repo owner on 2026-08-26.** No planning document mentions URL versioning or path
layout anywhere — checked across the spine, the PRD, the addendum, SPEC, the glossary and every epic.

**The first segment names the actor, and that is what makes the gateway's route list one rule per
backend.** AD-5 routes to four — `rider-service`, `driver-service`, the Stripe webhook and audit's
query API — and frames its list as growing *"when a new **actor** appears"*. A bare `/v1/…` cannot
distinguish them, so the gateway would need a rule per **resource** instead, editing
`infra/haproxy.cfg` every time any story adds an endpoint. With the actor first, PUB-4-3 writes
`path_beg /rider/` once and Story 3.2's `/rider/v1/rides` needs no gateway change.

**`/rider`, not `/rider-service`.** AD-12 puts the `-service` suffix on containers, directories and
Kubernetes resources, and keeps it off identifiers: *"Java packages stay suffix-free
(`com.puber.rider`)."* A public URL is closer to an identifier a client depends on than to a
deployment name, and the segment's job here is to name **the actor whose API this is** — which is
also why AD-5's list is organised by actor rather than by process. **The Stripe webhook is the one
that will not fit this shape** (Stripe is the caller, not an actor with an API, and nothing versions
it for us) — leave that to Epic 5 rather than forcing it now.

If the directory keeping its suffix while the URL drops it reads as inconsistent, it is the same split
AD-12 already makes between `com.puber.rider` and `services/rider-service/`.

**It is not a YAGNI violation, and the PRD is why.** The usual objection — nothing asks for a `v2`,
so a prefix guards a migration with no requirement behind it — does not hold for this project, whose
stated deliverable is *"deep, demonstrable engineering experience and a portfolio narrative: a system
whose architecture, trade-offs, and migration stories hold up under senior-engineer interview
scrutiny"* (prd.md#Context). Under that framing the named failure is real: an unversioned public API
has no versioning story to show. There will almost certainly never be a `v2`; the point is that the
shape is right the first time, because retrofitting a prefix onto a live URL is the breaking change
`/v1` exists to avoid.

**It also makes the two contracts symmetric.** The internal hop is already `puber.quote.v1` (PUB-4-1,
shipped); now the external one is versioned too — by path rather than by package, which is the
ordinary split between a public REST surface and an internal protobuf one.

**Why the predicate is per-package rather than a blanket one.** A single
`addPathPrefix("/rider/v1", <every controller>)` looks equivalent today and is not: adding v2 would
force a predicate that *excludes* the v1 classes, or `/rider/v2` hand-written into a mapping — either
way an edit to code that already shipped, which is what a version prefix exists to avoid. With a
package per version, v1 is closed the moment v2 opens.

**Why the gateway does not rewrite.** The alternative — the gateway stripping `/rider` and the app
serving `/v1/…` — would leave this service's own integration tests hitting a path no client ever uses,
since those tests run in-JVM on a random port and never traverse HAProxy. Same path everywhere is
worth more than a shorter mapping.

### A3 — why the response fields are bare `fare`, `distance` and `eta` (D1)

**Settled by the repo owner on 2026-08-26**, against the alternative `fareMinorUnits` /
`distanceMetres`. The argument for the long form was that `fare: 1100` invites a reader to see €1,100;
the argument that won is that this system has exactly one money representation and one wire distance
unit — the Money convention is *"integer minor units everywhere"* and metres is the only distance that
crosses a boundary — so a client learns the units once, from the contract, rather than from every
field name. **Renaming the JSON later breaks every client**, so this was the moment it was cheap.

**`eta` is short too**, settled the same day, over the objection that a duration has several plausible
encodings — minutes, seconds, ISO-8601 `PT8M`, an absolute arrival time — where `fare` and `distance`
each have only one.

**The unit is not undocumented, it is just not in the JSON key**, and three places say so: the proto
field is `eta_minutes` (so the hop between services carries it explicitly), the glossary defines ETA
as derived from the fixed 30 km/h speed at *"exactly 2 minutes per kilometre"*, and AD-62 ties that
speed to the trip duration.

**Why the wire and the JSON disagree.** `rider-service` is a protocol translator: `QuoteResponse` is a
DTO mapped from `model/Quote`, not the proto message re-serialized. The proto keeps its units because
inside `matching-service` the arithmetic is right there, and AD-33 makes a field rename a breaking
change.

### A4 — why `X-Rider-Id` is required on an endpoint that does not use it (D2)

Name the failure it prevents, per project-context.md → YAGNI: without it, the quote endpoint works
anonymously, and Story 3.2 then has to make identity mandatory on an endpoint where it was optional —
which AD-33 classifies as *changing what a field means*, a breaking change. One identity rule for the
whole façade, from the first endpoint, avoids that.

The counter-argument is the plain YAGNI one: nothing reads it, so nothing needs it. The repo owner may
still make it optional-when-present; until then it is required.

### A5 — why the gRPC client interceptor is wired in `config` rather than self-registering (D3, D5a)

**Settled by the repo owner on 2026-09-09.** `GrpcClientConfiguration` is the whole gRPC-client setup:
the channel address, the stub types, and the interceptor chain that hangs off the channel.

**The split is on where the thing attaches, not on taste.** A servlet `Filter` and a
`@RestControllerAdvice` attach to the servlet container, which no class in this service configures —
so there is no file they could be wired *in*, and self-registration is the only shape available. A
`ClientInterceptor` attaches to a **channel**, and the channel is precisely what
`GrpcClientConfiguration` exists to configure. Splitting the channel's address and its interceptor
chain across two files means the answer to *"what does this service's gRPC client do"* is in two
places, and one of them is a package you would not think to open.

**This differs from the `SystemClock` / `ClockConfiguration` pattern in one respect**: that pattern
exists because `config` owns the wiring for a *Strategy seam*, so no class can name a concrete
implementation. None of these is a strategy and none has a second implementation, so no seam is being
protected — `GrpcClientConfiguration` names `RequestIdClientInterceptor` directly and that is fine.

**The evidence for the mandatory annotation.** Verified against `spring-grpc-core-1.1.0` on 2026-09-09
by reading the bytecode: `ClientInterceptorsConfigurer.findGlobalInterceptors()` calls
`ApplicationContextBeanLookupUtils.getBeansWithAnnotation(ctx, ClientInterceptor.class,
GlobalClientInterceptor.class)`, so a plain `@Bean ClientInterceptor` is **not** collected. The
annotation is declared `@Target({TYPE, METHOD})`, which is why putting it on a `@Bean` method is a
supported shape and not a trick.

**What this costs `shared`'s copy story, and why it is acceptable.** D5a's claim is that `shared` is
one directory to copy with one `package` line to change and nothing to re-wire. That is still true of
three of the four classes; `RequestIdClientInterceptor` now needs a `@Bean` method in the receiving
service too. **It is not a forgettable edit, because the receiving service is writing that file
anyway** — `driver-service` (Epic 2) has different stub types and must author its own
`GrpcClientConfiguration` regardless, so the interceptor bean goes in beside them rather than into a
file nobody was going to open. Task 7.1 records this so Epic 2 does not rediscover it.

### A6 — why the wire DTOs are in `dto/v1` and not in `controller` or `model` (D5)

**Settled by the repo owner on 2026-09-09.** `controller` holds controllers; a package that holds both
a controller and the records it serializes is two kinds of thing under one name, and the name only
describes one of them.

**Why not `model`.** `RequestQuote` returns a `Quote` from `model` and the controller maps that to
`QuoteResponse`. Putting the DTOs in `model` would either drag Jackson into the domain package — which
`modelDependsOnNothingFrameworkFlavoured` fails the build on — or make the controller a passthrough
that proves nothing.

**Why they carry the version.** `QuoteRequest` and `QuoteResponse` *are* the contract — a v2 exists
precisely because a shape changed — so they cannot sit in a shared `dto` root that both versions read.
`controller.v2` and `dto.v2` are separate packages because they are different kinds of thing, not
because they version separately.

**Why the new ArchUnit rule was necessary.** While the DTOs sat in `controller.v1`,
`nothingDependsOnController` mechanically stopped `service` from importing them; in `dto.v1` nothing
does. The failure that guard prevents is real and concrete: `RequestQuote` returns `QuoteResponse`
directly, the controller becomes the passthrough this decision exists to avoid, and the wire shape
becomes the domain — a rename in the JSON then reaches into the service layer.

### A7 — why `RequestId` is extracted now and was not in PUB-4-1 (D5a)

The header, the gRPC metadata key, the MDC key and the log pattern's `%X{…}` are four strings that
have to match; a mismatch is silent — the filter writes MDC under one name, the pattern reads another,
and every log line shows a blank id while the tests still pass. One type holding all four, plus
`mint()`, is the guard. That is a named, reproducible failure, so it survives YAGNI.

**It was right not to extract it in PUB-4-1, and it is right to extract it now** — the difference is
what justifies either. `matching-service` had **one** class needing the four names, so a constants
type would have been indirection with nothing to hold together. This service has **three**: the
filter, the client interceptor, and the log pattern's key.

### A8 — why `matching-service` is stubbed rather than called (D7)

**Settled by the repo owner on 2026-09-11**, reversing how this story first read. The rule itself now
lives in project-context.md; this records the argument.

**The deciding reason is portability, not speed.** `infra/docker-compose.yml` holding every service is
a convenience of this repository. The architecture is one repo per service — AD-52 already gives each
its own wrapper, build file and Dockerfile — so a suite that passes only because a *sibling* service
happens to be running cannot move to that service's own repository, and cannot run in a per-repo CI
job. With GitHub Actions free for public repositories, that stopped being hypothetical.

**The rule it replaced overreached.** AGENTS.md read *"no mocked collaborator that the Compose stack is
already running"*, which was written when `matching-service` was the only service and nothing called
anything. It was aimed at fake repositories and in-memory substitutes — where the point is that
race-safety *is* Postgres's behaviour — and it caught a sibling service by accident.

**Two arguments were made for keeping the real call and both were weaker than they looked.** That
Task 6.1's hand-computed fare would become meaningless: it does lose the pricing assertion, but
pricing is proven inside `matching-service` by PUB-3 and does not need proving twice — against the
stub, 6.1 proves the *translation*, which is the only thing `rider-service` does. And that Task 6.2
would stop proving `INVALID_ARGUMENT` maps to 400: it still proves the mapping, which is this
service's code; whether `matching-service` emits that status for a bad latitude is PUB-4-1's
criterion and PUB-4-1 tested it.

**The one real loss is agreement on behaviour**, and it is named in "Honest limits" and handed to
PUB-4-3 by Task 7.6 rather than left implicit.

---

## Questions for the repo owner

None of these blocks implementation — every one is pinned in the decisions section so the dev agent
has a deterministic answer. **These are for you, not for the dev agent**, which is told the decisions
are frozen.

1. **`X-Rider-Id` is required on the quote endpoint, and a quote does not use it (D2, Appendix A4).**
   The failure named is that making identity mandatory later is an AD-33 breaking change. The
   counter-argument is YAGNI: nothing reads it, so nothing needs it. Say the word and it becomes
   optional-when-present. **This is the only one still genuinely open.**
2. **Settled, listed so you can see what was chosen rather than found:** `POST` over `GET` and `QUERY`
   (A1), the `/rider/v1/…` path scheme and its package-derived prefix (A2), bare `fare` / `distance` /
   `eta` (A3), the gRPC interceptor wired in `config` (A5), wire DTOs in `dto/v1` (A6). Each has its
   full argument in Appendix A, including what was rejected and why.
3. **`make test` gets no slower for this service, which is a change from how this story first read.**
   `rider-service` owns no datastore and stubs its one peer (D7), so its suite adds no container at
   all. PUB-4-3 is where the cost comes back: it brings the whole stack up for one end-to-end test.

---

## Dev Agent Record

### Agent Model Used

claude-opus-5 (Claude Code, `bmad-dev-story`)

### Debug Log References

Every claim here was produced by running something. Dates are 2026-09-11 and 2026-09-12.

**Task 4.3/4.4/4.6/4.7 — every rule proven capable of failing in `rider-service`.** Planted in
production code, ran the suite, captured the failure, reverted, confirmed green. Four batches, so a
rule could not be credited for another's violation:

| Planted | Rule that fired |
| --- | --- |
| `Instant.now()` in `RequestQuote.execute` | `timeIsReadOnlyThroughTheClock` — *"no classes should access target where a system time source"* |
| a `java.util.Date` returned from `Quote` | `theLegacyDateApiIsNotUsedAtAll` (2 violations) |
| a `public double` field on `ErrorDetailsHandler` | `floatingPointIsConfinedToDistance` |
| `"select ... where created_at < now()"` in `RequestQuote` | `DatabaseNeverReadsTimeTest` — *"SQL reads the time: .../RequestQuote.java:46 matches \bnow\s*\("* |
| a `GetQuoteResponse` parameter on `Quote` | `modelDependsOnNothingFrameworkFlavoured` (2) — **this is Task 4.4's contracts exclusion** |
| a `QuoteResponse` returned from `RequestQuote` | `onlyControllerDependsOnDto` (2) |
| a `Quote` parameter on `ErrorDetailsHandler` | `sharedDependsOnNothingElseInThisService` (2) |
| `QuotesController.class` named in `RequestQuote` | `nothingDependsOnController` |
| `new BigDecimal(0.1)` in `RequestQuote` | `bigDecimalIsNeverBuiltFromADouble` |
| a camelCase `@Test` in `RequestQuoteTest` | `TestNamingRulesTest.testMethodsAreSnakeCase` |
| a camelCase `@Test` in `QuoteIntegrationTest` | `TestNamingRulesIntegrationTest` — **the separate class earns its place** |

**Task 4.6 in `matching-service`, and the proof the new clause is not a restatement.** Planting a
`quote.model.Quote` import into `RequestIdServerInterceptor` fired **three** rules at once —
`sharedDependsOnNoFeaturePackage`, `sharedDependsOnNothingElseInThisService` and
`featureDependenciesRunOneWay` — which shows the new clause covers the feature case but not that it
covers anything more. So `SharedTypeThatDependsOnConfiguration` was added: `shared` naming
`ClockConfiguration`. The new clause rejects it; `SharedStaysLiftableRuleTest` asserts the **feature
clause accepts it**, which is what proves the two rules are not the same rule written twice, and why
the new one sits beside the old rather than replacing it.

**Non-vacuity of the two rules that name an absolute package.** Typing `shared..` as `shard..` in
each service's `ArchitectureRulesTest` left the rule scanning an empty set. `rider-service` went red
on *"Expected java.lang.AssertionError to be thrown, but nothing was thrown"*; `matching-service`
went red on three tests including the feature-clause one. Both reverted.

**AC2's `eta` assertion can fail.** `non_absent` → `non_null` left the suite **green**, because
Task 2.5's DTO carries a nullable `Long` and `NON_NULL` drops a null box — the story's own table says
so. The real discriminator is `always`, which produced
`{"fare":7,"distance":3,"eta":null}` and the failure *"no driver means the eta key is absent, not
present and null"*. Reverted to `non_absent`.

**D3's channel target: `9090` is right.** PUB-4-1's Debug Log settled it from the running service —
`NettyGrpcServerFactory ... listening on ...:9090`, Tomcat separately on 8080. No re-measurement
needed and none done.

**D7's transport wires the client side.** `@AutoConfigureTestGrpcTransport` was a server-side use in
PUB-4-1 and this is the first client use, so it was checked rather than assumed: read from
`spring-boot-grpc-test-4.1.0.jar`, `TestGrpcTransportAutoConfiguration$TestGrpcClientTransportAutoConfiguration`
declares a `TestGrpcChannelFactory` that extends `DefaultGrpcChannelFactory<InProcessChannelBuilder>`,
answers `supports(...)` with `true` unconditionally and builds via `InProcessChannelBuilder.forName`,
ignoring the configured address. Confirmed by the passing suite. **No explicitly-built channel was
needed.**

**`grpc-stub` is on the client starter's compile classpath, as Task 1.3 claims.** Read back from the
resolved `compileClasspath`, not taken on trust: `io.grpc:grpc-stub:1.80.0` is there transitively
while `grpc-protobuf` needed its explicit line.

**`make build` and `make test`, in that order, 2026-09-12.** `make build`: **BUILD SUCCESSFUL** for
both services, all three images built, *"no root-owned files in the working tree (AC18)"*.
`make test`: 144 tests passed; the **one** failure is the known environmental red below.

### Completion Notes List

**The known environmental red, named rather than omitted.**
`HealthReportsDownPromptlyIntegrationTest > initializationError FAILED` — its `@BeforeAll`
precondition refuses to let the class pass vacuously: *"192.0.2.1:5432 accepted a TCP connection
after 1ms, rather than hanging until Hikari's connection-timeout expired"*, a transparent egress
proxy in this sandbox's network path answering for every address. **Pre-existing and untouched by
this slice** — `git log -1` on that file returns `051f0a2 PUB-3`, and `git status` shows no
modification. It is `matching-service`'s and is not counted as green.

**`make test` stops at the first failing service, so it never reached `rider-service`.** That is the
Makefile's `|| exit 1` per service and is not new, but it means the gate as written cannot show this
slice's integration suite while that red stands. Run explicitly:
`docker compose ... --workdir /workspace/services/rider-service tests ./gradlew integrationTest` —
**BUILD SUCCESSFUL, 19 tests, all passed.** Worth knowing for PUB-4-3: a second service makes the
early exit hide more each time.

**AC-by-AC, and what each is actually worth.**

| AC | Satisfied by | Honest limit |
| --- | --- | --- |
| AC1b — reaches `matching-service` over gRPC, creates nothing | `returns_the_fare_and_the_distance_with_no_eta`, `passes_the_coordinates_through_untouched` | "creates nothing" is trivially true — this service owns no datastore |
| AC2 — no driver means no ETA, not an error | the absent-key assertion, plus `RequestQuoteTest`'s three mapping cases | only half a criterion today: there are no drivers, so the ETA is *always* absent. The present branch is proven in the unit test and lights up at PUB-10 |
| AC4b — request id everywhere, and across the hop | `echoes_the_request_id_it_was_given`, `mints_a_request_id_when_none_arrives`, `carries_the_request_id_over_the_grpc_hop`, `carries_the_request_id_in_an_error_body` | "minted at the gateway" is PUB-4-3's; here it means minted by the service |
| AC5 — RFC 9457, 400 | five failure cases incl. `INVALID_ARGUMENT`→400 and `UNAVAILABLE`→503 | the error map has three rows because three have producers |
| AC6 — identity trusted as-is | `rejects_a_request_carrying_no_rider_identity`, `trusts_a_rider_identity_it_has_never_seen` | thin by design: there is no registration to test the absence of |
| AC7 — observable like `matching-service` | health UP, Prometheus text format, actuator outside the prefix | asserted on the *surface*, not a body comparison — no `db` contributor here |
| AC11 — the rules exist here and can fail here | the Debug Log's plant table | two rules deliberately absent; see deferred-work.md |

**Three things the story specified that reality contradicted, all recorded where the next author
looks.**

1. **`javax.annotation-api` is not needed.** Task 1.3 listed it `compileOnly`. This service's own
   generated stubs carry no `@javax.annotation.Generated` — `grep -r javax.annotation
   build/generated/sources/proto` returns nothing — and the build is green without it, matching what
   `project-context.md` already records. Removed. In `deferred-work.md`.
2. **`StubQuoteService` cannot wrap itself.** D7 asks the stub to record the `Metadata` it received;
   the generated `QuoteServiceImplBase` declares `bindService()` **final**, so the intended override
   does not compile. It records through `@GlobalServerInterceptor` on the stub itself instead —
   still a receiver-side assertion, which is what Task 6.6 was actually asking for.
3. **`MATCHING + ".."` is not a legal ArchUnit package identifier.** Writing the new `shared` rule
   that way gave `IllegalArgumentException: Package Identifier may not contain more than two '.' in
   a row`, and it failed as *"TestEngine with ID 'archunit' failed to discover tests"* — an entire
   engine down, not one rule red. `MATCHING + "."` is correct.

**What this slice does not prove.** Nothing here shows `rider-service` and `matching-service` agree
on **behaviour**. Shapes cannot drift — the stub extends the generated base class from the same
`contracts/proto` — but no test exercises the real pair. Deliberate (project-context.md → "Own
datastores are real. Another service is stubbed."), named in Honest limits, and routed to PUB-4-3 as
**`AI-4`** in `sprint-status.yaml` plus an entry in `deferred-work.md`. **The gap is real and open
until PUB-4-3 lands.**

**Task 8.5 — the `matching-service` move changed no behaviour.** `git diff` over its
`integrationTest` source set is **2 insertions, 4 deletions in one file**: the import and the
constant reference Task 3.8 names, and nothing else. No assertion was edited. Its suite is green
apart from the pre-existing red above.

**Scope held.** No HAProxy, no `ports:` removal from `matching-service`, no second `tests` runner, no
`depends_on: matching-service` on the runner, no dependency beyond Task 1.3's list (one fewer), and
no JSpecify. `make test-integration` gained no new datastore — `rider-service` adds none.

### File List

**New — `services/rider-service/` (the whole tree)**

- `gradlew`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- `settings.gradle`, `build.gradle`, `Dockerfile`, `.dockerignore`, `.gitignore`, `.gitattributes`
- `src/main/resources/application.properties`
- `src/main/java/com/puber/rider/RiderServiceApplication.java`
- `src/main/java/com/puber/rider/shared/RequestId.java`
- `src/main/java/com/puber/rider/shared/RequestIdFilter.java`
- `src/main/java/com/puber/rider/shared/RequestIdClientInterceptor.java`
- `src/main/java/com/puber/rider/shared/ErrorDetailsHandler.java`
- `src/main/java/com/puber/rider/config/GrpcClientConfiguration.java`
- `src/main/java/com/puber/rider/config/ApiVersionConfiguration.java`
- `src/main/java/com/puber/rider/controller/v1/QuotesController.java`
- `src/main/java/com/puber/rider/dto/v1/QuoteRequest.java`
- `src/main/java/com/puber/rider/dto/v1/QuoteResponse.java`
- `src/main/java/com/puber/rider/service/RequestQuote.java`
- `src/main/java/com/puber/rider/model/Quote.java`
- `src/test/java/com/puber/rider/rules/ArchitectureRulesTest.java`
- `src/test/java/com/puber/rider/rules/DatabaseNeverReadsTimeTest.java`
- `src/test/java/com/puber/rider/rules/TestNamingRulesTest.java`
- `src/test/java/com/puber/rider/rules/LayerRulesTest.java`
- `src/test/java/com/puber/rider/rules/TimeIsReadOnlyThroughTheClockRuleTest.java`
- `src/test/java/com/puber/rider/rules/MoneyIsNeverFloatingPointRuleTest.java`
- `src/test/java/com/puber/rider/rules/SharedStaysLiftableRuleTest.java`
- `src/test/java/com/puber/rider/rules/fixtures/` — `BuildsABigDecimalFromADouble`,
  `BuildsABigDecimalSafely`, `ConvertsTimeWithoutReadingIt`, `DeclaresFloatingPoint`,
  `DependsOnAController`, `DependsOnADto`, `ReadsTimeDirectly`, `UsesTheLegacyDateApi`,
  `controller/AControllerNothingMayDependOn`, `dto/AWireShapeOnlyAControllerMaySee`,
  `dto/AWireShapeNestedInsideAnother`, `entity/AClassInAPackageNamedEntity`,
  `model/ModelTypeCarryingAFrameworkAnnotation`, `model/ModelTypeThatDependsOnJackson`,
  `model/ModelTypeThatHoldsAWireMessage`, `service/ServiceThatDependsOnAConcreteStrategy`,
  `strategy/AConcreteStrategy`
- `src/test/java/com/puber/rider/shared/SharedTypeThatDependsOnThisService.java` — in the package it
  violates, because the rule names that package absolutely
- `src/test/java/com/puber/rider/service/RequestQuoteTest.java`
- `src/integrationTest/java/com/puber/rider/QuoteIntegrationTest.java`
- `src/integrationTest/java/com/puber/rider/HealthAndMetricsIntegrationTest.java`
- `src/integrationTest/java/com/puber/rider/support/StubQuoteService.java`
- `src/integrationTest/java/com/puber/rider/rules/TestNamingRulesIntegrationTest.java`

**New — `services/matching-service/`**

- `src/main/java/com/puber/matching/shared/RequestId.java` — extracted from the interceptor
- `src/test/java/com/puber/matching/rules/SharedStaysLiftableRuleTest.java`
- `src/test/java/com/puber/matching/shared/SharedTypeThatDependsOnConfiguration.java`

**Moved — `services/matching-service/src/main/java/com/puber/matching/`**

- `config/RequestIdServerInterceptor.java` → `shared/RequestIdServerInterceptor.java`
- `config/UnexpectedGrpcFailureStatusMapper.java` → `shared/UnexpectedGrpcFailureStatusMapper.java`

**Modified**

- `services/matching-service/src/test/java/com/puber/matching/rules/ArchitectureRulesTest.java` —
  `sharedDependsOnNothingElseInThisService` added beside the feature clause
- `services/matching-service/src/integrationTest/java/com/puber/matching/QuoteGrpcIntegrationTest.java` —
  Task 3.8's import and constant only
- `infra/docker-compose.yml` — `rider-service` added; no `ports:`; `tests` untouched
- `Makefile` — `run` brings `rider-service` up; nothing else needed
- `project-context.md` — Tasks 7.1 and 7.3
- `_bmad-output/planning-artifacts/architecture/architecture-puber-2026-08-03/ARCHITECTURE-SPINE.md` —
  Task 7.2's Public paths row
- `_bmad-output/implementation-artifacts/deferred-work.md` — Task 4.8's closure, plus this slice's
  own deferrals
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — status, and Task 7.6's `AI-4`
- this story file

## Change Log

| Date | Change | By |
| --- | --- | --- |
| 2026-08-25 | Story created from epic-1 Story 1.4 as PUB-4, then split into PUB-4-1/2/3 at the repo owner's request; this is slice 2 | bmad-create-story |
| 2026-09-09 | Wire DTOs moved from `controller/v1` to their own `dto/v1` package at the repo owner's request (D1, D5). Adds `onlyControllerDependsOnDto` and Task 4.7 to replace the guard `nothingDependsOnController` was giving them for free; Task 7.2's spine row now also records `dto` as a layer AD-7 does not list | bmad-create-story |
| 2026-09-09 | `RequestIdClientInterceptor` is now a plain class in `shared`, registered as a `@Bean @GlobalClientInterceptor` in `GrpcClientConfiguration`, at the repo owner's request — one file holds the whole gRPC client setup (D3, D5a, Tasks 2.4, 3.2, 7.1). `RequestIdFilter` and `ProblemDetailsHandler` still self-register; D5a records why the two cases differ | bmad-create-story |
| 2026-09-11 | Cross-service test strategy reversed at the repo owner's request: `rider-service`'s tests stub `matching-service` in-process against `contracts/proto` instead of calling it. The rule was narrowed at source — `AGENTS.md`'s "no mocked collaborator that the Compose stack is already running" and `project-context.md`'s "Real datastores only" — because each service is notionally its own repo and per-repo CI cannot boot a sibling. Adds D7 and Appendix A8; reverses Tasks 5.3 and 5.5; rewrites 6.1, 6.2 and 6.6; names the agreement gap in Honest limits and hands it to PUB-4-3 via Task 7.6 | bmad-create-story |
| 2026-09-11 | `shared/ProblemDetailsHandler` renamed to `shared/ErrorDetailsHandler` at the repo owner's request. It still returns `org.springframework.http.ProblemDetail` as `application/problem+json`; D5a and Task 2.7 now carry the RFC 9457 reference the old name carried | bmad-create-story |
| 2026-09-11 | `model/Quote`'s ETA pinned to `OptionalLong` at the repo owner's request (Task 2.2). Consequence found by testing `jackson-databind 3.1.4`: `non_null` emits `"eta":null` for an empty `OptionalLong` and would fail AC2, so D5's inclusion setting becomes `non_absent`, and Task 2.5 pins the DTO's `eta` to a nullable `Long` rather than mirroring the domain shape | bmad-create-story |
| 2026-09-09 | Decisions section trimmed from 473 to 320 lines and marked **FROZEN** at the repo owner's request: D1–D6 now carry only what to build, and every rejected-alternative and justification paragraph moved verbatim into the new **Appendix A**. Nothing deleted, nothing duplicated. "Questions for the repo owner" reduced to the one item still open | bmad-create-story |
| 2026-09-12 | Implemented by `bmad-dev-story`. `rider-service` built end to end; `matching-service`'s request-id and error-vocabulary classes moved into `shared`. Every structural rule proven capable of failing by planting. Three spec points corrected against what the code actually does: `javax.annotation-api` dropped as unnecessary, `StubQuoteService` records metadata through `@GlobalServerInterceptor` because the generated `bindService()` is final, and the new `shared` rule uses `MATCHING + "."` because a three-dot package identifier takes the whole ArchUnit engine down. Status -> review | bmad-dev-story |
