---
title: Rubric Walk — Puber Architecture Spine
reviewer: independent (adversarial)
target: ARCHITECTURE-SPINE.md (status: final, updated 2026-08-13)
against: prd.md (FR-1–FR-50, NFR-1–NFR-10), addendum.md
date: 2026-08-13
---

# Rubric Walk — Architecture Spine (Puber)

## Verdict

**CHANGES REQUESTED.** The ride/dispatch/outbox core is genuinely excellent spine work — AD-13
through AD-20, AD-28 through AD-36 and AD-38 fix real divergence points with rules an epic can be
checked against. But the spine is **unevenly finished**: the ride path is at feature altitude while
payments, real-time push, audit/analytics and observability are at brochure altitude. Four
cross-service contracts are either missing or self-contradictory (Tier-2 degradation behaviour,
the payment state machine, the push channel, and where event/proto contracts physically live), and
two dimensions the altitude owns — observability, and the retention/analytics data pipeline — are
effectively silent while the Capability Map claims coverage via ADs that do not govern them. As
written, an epic-level breakdown of Phases 3–5 would have to invent invariants that Phases 1–2 were
given.

The Capability → Architecture Map is the weakest artifact in the document: every FR has a *row*,
but several rows cite ADs that govern something else, which makes the map read as complete when it
is not. Coverage claimed by row is not coverage.

Findings below are ordered critical → low. Each gives location, what is wrong, and a concrete fix.

---

## CRITICAL

### C1 — AD-43 and AD-48 give opposite answers for Tier-2 degradation

**Location:** AD-43 (Two payment strategies) vs AD-48 (turn-off boundary).

AD-43's rule: `matching-service` holds an outbound gateway strategy — "call `payment-service`, or
**signal authorised immediately** when payments are absent (early phases, **and Tier-2
degradation**)."

AD-48's rule: "Tier 2 — `payment-service` — **degrades to rides stalling before dispatch**."

These cannot both be true. One says a ride with `payment-service` off moves to `WAITING_MATCH` and
is dispatched; the other says it sits in `REQUESTED` and never dispatches. Two ADs, both explicitly
scoped to the same scenario, specifying opposite ride behaviour. This is not cosmetic: AD-19 and
FR-9 make "no driver dispatched until funds are held" a *structural* guarantee, and AD-43's version
of degradation silently dissolves it — the exact invariant AD-19 was written to make impossible to
break by accident.

It also collides with AD-45: if payments degrade by auto-authorising, the "ride awaiting
authorisation" metric and alert that AD-45 relies on as the *only* exit never fires.

**Fix:** pick one and delete the other clause. Recommended: AD-48 wins for the operational case
(rides stall in `REQUESTED`; the metric and alert of AD-45 are the signal), and AD-43's
signal-authorised-immediately strategy is scoped **only** to (a) phases before `payment-service`
exists and (b) CI/stress runs where the *provider* is stubbed. Then move the stubbing to the layer
NFR-8 actually specifies — AD-43's own second strategy, inside `payment-service` — and state
explicitly that a deployed-but-degraded Tier 2 is never auto-authorised. Add a line to AD-48
naming which of the two strategies is active per tier configuration.

### C2 — The payment state machine is never fixed, while the ride machine is

**Location:** AD-11 (binds "ride lifecycle, payment lifecycle"), AD-13, Capability Map row
"Payments (FR-34–FR-40)".

AD-13 exists because ride state names crossing service boundaries is a divergence point worth an AD
of its own. The payment machine is the *same shape of risk* and gets nothing: AD-11 says "each
machine is an enum plus an allowed-transitions map" and stops. The spine never names
`INITIATED → AUTHORIZED → CAPTURED → REFUNDED`, `FAILED`, `VOIDED` (PRD FR-35), never says which
transitions are legal, and never says who may drive them.

This is load-bearing across at least three independently built units:
- `payment-service` owns the machine;
- `matching-service` reacts to its outcomes (AD-41's single guarded transition);
- `audit-service` records its transitions as audit events (FR-41) and the FR-50 dashboard counts
  them;
- FR-7 renders them to the rider ("captured, failed, or refunded").

Absent an AD, four units invent four vocabularies for the same six states, and the AD-13 lesson
(one state must not mean two things) is not applied where money is involved. Note also that AD-44's
`void_requested` is a *flag*, not a state — consistent with AD-18's "provenance is a field"
principle, but nowhere is that stated for payments, so an implementer may reasonably add a
`VOID_PENDING` state and break the enum for everyone else.

**Fix:** add an AD mirroring AD-13 — "Payment state machine" — enumerating the six states, the
legal transitions, the terminal set, the fact that `void_requested` is a field on
`payment_intents` and never a state, and the rule that the payment state exposed to the rider
(FR-7) is exactly this enum, not a derived label.

### C3 — The real-time push channel (FR-45, FR-50) has no architectural home

**Location:** Capability Map rows "Real-time push, metrics, deployment (FR-45–FR-47)" → AD-48,
AD-49; and "Live operational dashboard (FR-50)" → AD-48. AD-37.

AD-48 is the turn-off tiering rule and AD-49 is GitOps. **Neither governs push.** FR-45 and FR-50 —
the headline deliverables of Phase 5 (`v1.0`) — are cited to ADs that say nothing about them.

Worse, AD-37 states the transport rule as a binary: "everything through the gateway is REST … every
internal synchronous hop is gRPC". WebSocket is neither, and AD-37 does not carve it out, so the
spine's transport invariant technically forbids FR-45. HAProxy WS upgrade handling is likewise
unmentioned despite AD-5 owning gateway routing.

The genuinely architectural questions here are all unanswered, and every one of them is a
cross-unit divergence point:
1. `matching-service` creates the offer; `driver-service` holds the socket. The push therefore
   crosses a service boundary — presumably the `KB -. events .-> DS` edge in the dependency
   diagram — but no AD says so, and AD-32 ("events carry what consumers need inline") has direct
   consequences for what `ride.offered` must contain if a socket is to render the offer without a
   callback.
2. With more than one `driver-service` replica, only one replica holds a given driver's socket.
   Routing an offer to the right replica (every replica consumes every partition under its own
   consumer group and drops non-local sockets, vs. sticky routing, vs. a shared socket registry) is
   a structural choice that cannot be made per-story.
3. FR-50's dashboard is said to use "the same mechanism as FR-45" but lives in `audit-service`
   (Tier 3) while FR-45 lives in `driver-service` (Tier 1). "Same mechanism" across a tier boundary
   needs stating, or the two get built twice, differently.
4. Message envelope on the socket: is it AD-31's event envelope, or a separate client-facing
   contract governed by AD-33?

**Fix:** add an AD — "Push is a third transport, fed only from the event backbone" — that (a)
amends AD-37 to admit WebSocket at the edge alongside REST, (b) states that no service pushes as a
side effect of its own write; a socket is fed only by consuming a Kafka event, so the push path can
never diverge from the recorded truth, (c) fixes the replica-fanout choice, and (d) states that the
socket payload is the AD-31 envelope plus body, governed by AD-33, so FR-45 and FR-50 share one
contract. Add a line to AD-5 for WS upgrade at the gateway.

### C4 — No canonical home for event and gRPC contracts, while a shared library is forbidden and the schema registry is deferred

**Location:** Source tree section ("Duplicated domain code across services is accepted; a shared
library is not"), Deferred → "Schema registry", AD-30, AD-33.

Three decisions interlock into a hole:
- AD-30 forbids native serialisation *because* "consumers are independently built with duplicated
  domain code, so a serialised Java object would force the shared library the project rejects";
- AD-33 governs how contracts evolve;
- Deferred drops the schema registry on the grounds that "additive-only evolution plus protobuf
  field numbering carries the contract discipline without another service to run".

But the spine never says **where the contract physically is**. `.proto` files and event payload
schemas have no stated location, no stated owner, and no stated copy-in mechanism. AD-33 says
"Protobuf field numbers are never reused" — a rule that is unenforceable and unverifiable if each
service keeps its own private copy of the `.proto` with no canonical original. The producer and
every consumer are, by explicit design, independently built units; deferring the registry without
naming an alternative source of truth defers a real invariant, which fails rubric item 3.

**Fix:** add a rule (extend AD-33 or add an AD): contracts live in a top-level `contracts/`
directory in the repo — `.proto` per gRPC service, and one schema/example document per
`event_type` — and are the single source of truth; services copy or generate from it at build time
and never hand-edit their copy. That is a directory, not a service, so it keeps the registry
deferral honest while making AD-33 checkable. Also record each event type's field list there so
AD-32 becomes falsifiable (see M6).

---

## HIGH

### H1 — AD-16 and AD-21 define "matchable" differently, and nobody owns the geo set

**Location:** AD-16, AD-21, AD-26, AD-3.

AD-16: "`BUSY` is set at offer time and cleared on release or completion, so **matchability is
simply `status = 'AVAILABLE'`**."
AD-21: "**Matchability is `AVAILABLE` and a fresh heartbeat.**"

AD-16's "simply" is exactly the sentence a dispatch implementer will quote. FR-29 and the PRD
glossary are unambiguous that freshness is required, so AD-16 is the wrong one — but a spine that
states an invariant twice with different content has no invariant.

Compounding it: AD-26 says the geo set contains "**matchable drivers only**", but matchability
depends on `BUSY`, which lives in `matching-service`'s dispatch table, while the geo set is written
per heartbeat by `driver-service`. So geo-set membership is a fact spanning two services, and the
spine never says who writes it, on what signal, or how `driver-service` learns that a driver went
`BUSY` (presumably the `KB -. events .-> DS` edge, but that is inference). AD-3 assigns *Postgres
and ClickHouse* ownership and never assigns Redis at all, so the hottest shared datastore in the
system has no owner. AD-1's "database per service" is scoped to Postgres by its own wording, so
Redis sharing is permitted by omission rather than by decision.

AD-4 keeps this from becoming a correctness bug (the cache is advisory; the conditional update
decides), which is why this is High and not Critical — but two independently built services cannot
agree on set membership from what is written.

**Fix:** (a) amend AD-16 to "the *dispatch-side* candidate filter is `status = 'AVAILABLE'`; full
matchability adds heartbeat freshness per AD-21" — one definition, one place; (b) add Redis to AD-3
with an explicit writer (`driver-service` writes both structures; `matching-service` reads only)
and state how `BUSY`/release reaches `driver-service` — either as a consumed event, or by moving
geo-set membership to `matching-service` and letting the position key alone be `driver-service`'s.
Either answer is fine; the absence of an answer is not.

### H2 — Retention, partitioning (NFR-6) and the ClickHouse mirroring mechanism (FR-43) have no architectural home

**Location:** Capability Map row "Audit and analytics (FR-41–FR-44)" → AD-31, AD-36, AD-48. NFR-6.

The words "retention" and "partition" (in the audit sense) do not appear anywhere in the spine.
NFR-6 specifies a bounded Postgres window with **drop-based partition retention** and indefinite
ClickHouse history — a real structural requirement (partition key, partition granularity, the drop
job, and the guarantee that ClickHouse has the row before Postgres drops the partition). It is
listed in the spine front-matter `binds` and then never addressed. **NFR-6 has no architectural
home** — the clearest instance of rubric item 4 failing.

Likewise FR-43: "mirrored to a columnar store" is a capability, not a mechanism. Kafka consumer
writing to ClickHouse, ClickHouse's Kafka table engine, or a batch copy out of Postgres are
materially different designs with different ordering, idempotency and Tier-3 restart semantics —
and AD-36's `event_id` dedupe rule means little against ClickHouse, which has no unique constraint
to dedupe against. The three cited ADs govern the envelope, Kafka consumption generally, and
tiering; none governs the analytics pipeline.

FR-44's analytics (distance per driver, ride density, utilisation) inherit the same vacuum — the
utilisation figure is derived from the driver state-transition audit trail, which requires
`driver-service` and `matching-service` transitions to be recorded with a shared vocabulary; AD-31
gets partway there via `entity_type`/`entity_id` but says nothing about the action vocabulary.

**Fix:** add an AD covering the audit store: Postgres `audit_events` partitioned by month on
`occurred_at`, retention by partition drop only (never `DELETE`), a stated ordering rule that a
partition is only droppable once mirrored, and the mirroring mechanism named explicitly with its
idempotency strategy (e.g. a ClickHouse `ReplacingMergeTree` keyed on `event_id`, since AD-36's
dedupe rule cannot be satisfied the Postgres way). Add the audit action vocabulary to the
Consistency Conventions table next to the existing Event naming row.

### H3 — Observability is a silent dimension; the correlation id dies at the outbox

**Location:** NFR-5, FR-46; Capability Map row FR-45–FR-47 → AD-48, AD-49; AD-31; Consistency
Conventions "Logging" row.

Rubric item 5 names the operational/environmental envelope explicitly. Deployment (AD-49),
environments (the deployment diagram) and infra strategy (AD-27, AD-48) are covered. **Observability
is not.** The spine offers Prometheus/Grafana in the Stack table, one Logging convention row, and
two ADs that mention "a metric and an alert" in passing (AD-44, AD-45) — and no AD at all. Five
independently built services with duplicated code and no shared library will produce five metric
naming schemes, five label sets and five health endpoint shapes. That is precisely the class of
divergence this spine exists to prevent, and it prevented the far less costly version of it for
package names (AD-7) and service names (AD-12).

FR-46 goes further and *fixes two definitions* — match latency as request→accept (reported
alongside time-to-first-offer) and "drivers online" as matchable-only — precisely because they are
easy to get subtly wrong. Those definitions are computed in at least two places (Grafana per FR-46,
the FR-50 dashboard) and the spine records neither.

Concrete inconsistency inside this gap: the Logging convention promises "one correlation id from
the gateway through every hop", but **AD-31's envelope column list has no correlation id**
(`event_id`, `event_type`, `entity_type`, `entity_id`, `actor_type`, `actor_id`, `schema_version`,
`occurred_at`). The trace therefore terminates at the outbox write and cannot be followed into any
Kafka consumer — which is exactly where NFR-5's "without log-diving" promise is hardest to keep.

**Fix:** (a) add `correlation_id` to AD-31's envelope columns; (b) add an observability AD:
metric naming convention (`puber_<service>_<subject>_<unit>`), the mandatory label set, the fixed
health/metrics endpoint paths, the rule that FR-46's two definitions are computed once and shared
by Grafana and the FR-50 dashboard rather than derived twice, and the rule that every AD asserting
"a metric and an alert" (AD-44, AD-45, AD-35's backlog age) names the metric there. Fix the
Capability Map row to cite it.

### H4 — Consumer-side resilience (FR-32, NFR-3) is unhomed; "jitter" and "dead-letter queue" never appear

**Location:** Capability Map row "Event backbone and resilience (FR-31–FR-33)" → AD-28, AD-34,
AD-35, AD-36. AD-34.

AD-34 is an excellent rule — but it governs the **outbox relay only** (the producer side). FR-32
requires "producers **and consumers** apply retry-with-jitter and circuit-breaking"; NFR-3 requires
failures to "degrade gracefully (**dead-letter queue**) rather than cascading". The spine states
neither for consumers. What a Kafka consumer does when a message fails repeatedly — retry in place
and block the partition, seek past it, publish to a retry topic, publish to a DLQ — is a per-service
choice today, and with `audit-service`, `matching-service` and `driver-service` all consuming, three
different answers is the default outcome. Under AD-36's partition-key-by-entity rule, a blocking
retry stalls every other entity on that partition, so this has real behavioural consequences.

Two smaller misses in the same family:
- **"Jitter" appears nowhere in the spine.** AD-34 says "exponential backoff", FR-32 and NFR-3 say
  "retry with jitter". Backoff without jitter is the specific thing that produces synchronised
  retry storms after an outage — the failure AD-34's own second "Prevents" clause is about.
- Resilience4j is in the Stack table and in AD-6's `Binds` (bulkheads) but no AD says which calls
  are wrapped in a breaker. AD-34 wraps the relay; the Stripe calls that FR-32 and the PRD Goals
  call out by name are not covered by any AD.

**Fix:** extend AD-34 or add an AD: every retry policy in the system is exponential backoff **with
jitter**; every Kafka consumer commits offsets only after a successful (idempotent) apply, retries
a bounded number of times, then publishes to a per-topic DLQ and advances rather than blocking the
partition; every outbound call to an external provider sits behind a named Resilience4j breaker.

### H5 — AD-2's rule is violated by the design in AD-41 and AD-44

**Location:** AD-2 vs AD-41, AD-44.

AD-2's rule: "if a single user-visible action writes two pieces of state, both live in one service's
database and are written in one local transaction." Its Prevents clause: "a saga or compensating
action anywhere in the core dispatch path."

A ride request is a single user-visible action that writes a `rides` row in `matching-service` and a
`payment_intents` row in `payment-service`, in two databases, asynchronously (AD-41). AD-44 then
specifies a **compensating action** — a terminal ride arriving before the authorisation resolves
records `void_requested` so the authorisation "voids on arrival instead of settling". That is a
compensation in the core path, by name.

The design is right; AD-2's wording is wrong, and wrong in the load-bearing direction. As written,
an epic author reading AD-2 could conclude that `payment_intents` belongs in `matching-service`'s
database ("if a design needs one, the boundary is wrong"), which would collapse a service boundary
the PRD's whole payments phase depends on. AD-2's closing clause is also rhetoric rather than a
rule — it is unfalsifiable and gives no test.

**Fix:** rewrite AD-2 to scope it correctly: "**within a synchronous request**, if a single action
writes two pieces of state, both live in one database and one local transaction. The one
deliberately asynchronous pair is ride ↔ payment, which is *not* coordinated by a distributed
transaction but reconciled by AD-41's single guarded transition and AD-44's `void_requested` flag;
no other cross-service co-write may be introduced." That preserves the intent, names the exception,
and stops the rule from arguing against the architecture that follows it.

### H6 — The durable location pipeline (FR-27, FR-44) is unspecified end to end

**Location:** AD-26 (Binds: FR-26, FR-28, FR-6 — **FR-27 absent**), AD-28, AD-3, Capability Map row
"Location, reachability, session expiry (FR-26–FR-30)".

FR-27 requires location updates to persist to a durable history, and FR-44 consumes that history for
distance-travelled and ride-density analytics. The spine's coverage of it is a chain of gaps:

1. AD-26 mandates "**zero Postgres writes**" per heartbeat and does not bind FR-27.
2. AD-28 states that "**heartbeats never use the outbox**" — correctly reasoned — but then never
   says how a heartbeat reaches Kafka at all. Direct publish is the only remaining path and is a
   dual write; that may be entirely acceptable for telemetry, but it is the spine's job to say so
   and to state what is lost when the publish fails.
3. AD-3's ownership list gives `audit-service` "`audit_events` and the ClickHouse tables" without
   ever mentioning location history, so the FR-27 store has no named owner. The addendum says pings
   go to the columnar store only; the spine does not carry that forward.
4. The Capability Map row for FR-26–FR-30 cites AD-21/22/23/26/27 — every one of which is about the
   *fast* path. FR-27 is inside a row whose ADs all govern something else.

Also note the wording drift this creates: AD-3 says `driver-service` "owns driver identity **and
location**", while AD-26 removes location from `driver-service`'s Postgres entirely.

**Fix:** add FR-27 to AD-26's `Binds` and add a rule covering the slow path: heartbeats are
published directly to Kafka (never the outbox, per AD-28), are best-effort telemetry whose loss is
acceptable and is measured, and are consumed into the columnar store by `audit-service`, which owns
the location-history table. Correct AD-3's ownership line to "driver identity; live location in
Redis, history in the columnar store."

---

## MEDIUM

### M1 — AD-35's bound is ambiguous: age or depth?

**Location:** AD-35.

The rule says three things that do not compose: the AD title says it "sheds on backlog **age**";
"past **the bound**, new ride requests are shed"; "the **trigger is backlog age, not depth**"; "**the
bound is derived as arrival rate × the outage duration to ride out**" — which is a *count*. An
implementer cannot tell what predicate to write. Since concrete values are deferred, the predicate
is all an epic has, and it is contradictory.

**Fix:** state the shed predicate unambiguously — "shed when the oldest unpublished `event_outbox`
row is older than T" — and re-label the rate × duration figure as capacity planning for the table,
not the trigger.

### M2 — FR-7's read path is forbidden by the spine's own dependency graph

**Location:** Dependency-direction diagram, AD-1, AD-3, Capability Map row FR-1–FR-8.

FR-7 requires the rider to see the payment outcome (captured / failed / refunded) on a finished
ride. Payment state is owned by `payment-service` (AD-3). AD-1 forbids cross-database reads. The
dependency diagram has **no edge from `rider-service` to `payment-service`** — only `RS → MS`,
`MS ↔ PS`. So the only legal implementation is a payment-status projection carried onto the ride in
`matching-service` (fed by the `KB -. events .-> MS` edge), and the spine never says that. FR-7 is
homed in the FR-1–FR-8 map row governed by AD-3/14/39/40, none of which mention payment state.

**Fix:** state it — `matching-service` carries a denormalised payment status on the ride, updated
by consuming payment events; `rider-service` never calls `payment-service`. Note the consequence
for AD-32 (`payment.captured` / `payment.failed` / `payment.refunded` must carry what the ride read
renders) and add it to the Capability Map row.

### M3 — Capability Map rows cite ADs that do not govern the capability

**Location:** Capability → Architecture Map.

Beyond C3 and H2/H3, several rows are miscited, which is what makes the map look complete:
- **FR-49 (Simulator)** → AD-39, AD-43. AD-39 is rider URL shape; AD-43 is payment strategies. The
  ADs that actually govern FR-49 are AD-25 ("fixture coordinates are generated relative to the
  configured bounds") and the Testing convention row (seeded, reproducible runs).
- **FR-45–FR-47** → AD-48, AD-49 (see C3 and H3). Only FR-47 is genuinely governed.
- **FR-31–FR-33** → producer-side ADs only (see H4).
- **FR-34–FR-40** → AD-41/42/43/44 leaves FR-35, FR-37, FR-38 and FR-39 unhomed (see C2).

**Fix:** re-derive the map from the ADs rather than from the FR groups, and split any row whose FRs
do not share a governing set. A row whose ADs do not mention the capability should be an explicit
gap marker, not a citation.

### M4 — Security posture is never stated as an invariant

**Location:** AD-5, AD-39, AD-42, Consistency Conventions (Configuration row).

The pieces exist (public routability, URL shape, token handling, secrets in env) but the *posture*
is never stated, and AD-39 goes out of its way to disclaim being one ("this is correct shape rather
than a security boundary — identity is trusted as-is under FR-48"). What is missing is the one
sentence that lets an epic stop asking: inside the cluster, all traffic is unauthenticated and
untrusted-by-design; gRPC is plaintext; the rider header identity is authoritative and unverified;
`matching-service` is reachable by any pod. FR-48 makes this a legitimate decision — but an
undecided posture and a deliberately-null posture look identical in a document, and rubric item 5
names security posture explicitly. FR-38's webhook **signature verification** is the one genuine
authentication in the system and appears in no AD.

**Fix:** add a short AD stating the trust model in those terms, naming the two real boundaries
(gateway routability per AD-5, Stripe webhook signature verification) and stating that everything
else is deliberately open, with `matching-service` restricted by ClusterIP-only + NetworkPolicy so
AD-5 has a named mechanism.

### M5 — The structural ADs have no enforcement mechanism

**Location:** AD-7, AD-8, AD-9, AD-12.

AD-8 ("`service` imports Strategy **interfaces**, never an implementation; nothing imports
`controller`") and AD-9's package ordering are exactly the rules that erode silently over 32 weeks
across five independently built codebases. They are perfectly enforceable — by ArchUnit — but the
spine never requires enforcement, and the Testing convention row mentions only Testcontainers and
the Simulator. A rule with no test is a preference.

**Fix:** add to AD-7/AD-8 (or the Testing convention): the layering and package-ordering rules are
asserted by an ArchUnit test present in every service, generated from the same service template
AD-49 already mandates. That also makes AD-9's ordering machine-checked rather than remembered.

### M6 — AD-32 is unfalsifiable as written

**Location:** AD-32.

"It is a contract, not a row dump: include what is genuinely consumed and nothing more" — there is
no way to fail this rule. "Genuinely consumed" is a judgement call made by the producer about
consumers it cannot see, in a design that explicitly forbids a shared library. The Prevents clause
(callback storms) is real, but the rule does not prevent it: a producer that omits a field a
consumer needs is *complying* with AD-32 and *causing* the callback.

**Fix:** make it checkable by tying it to C4's `contracts/` directory — each event type's field list
is recorded there with the consumer that requires each field; adding a field requires naming its
consumer, removing one follows AD-33. That converts "nothing more" from a sentiment into a diff
review.

### M7 — Heartbeat traffic and command traffic share one edge bound

**Location:** AD-6, AD-46, NFR-2.

AD-6 puts "the tightest bound … where there is visibility — HAProxy" so overflow sheds with a 503.
At NFR-2 scale, AD-46's 2 s heartbeat across 20k drivers is ~10k requests/sec of telemetry through
that same gateway, against a ride-request rate orders of magnitude lower. With one shared bound, a
heartbeat surge sheds ride requests, and shedding a heartbeat is nearly free while shedding a ride
request is not. AD-6's `Binds` lists Resilience4j bulkheads, implying the separation was thought
about, but the rule never states that traffic classes are bounded separately.

**Fix:** amend AD-6 — bounds are per traffic class, with commands, reads and heartbeats separated at
the gateway; heartbeats are the first thing shed and their shedding is not an error.

### M8 — AD-48's "Tier 3 loses no data" does not hold for the heartbeat topic at stress scale

**Location:** AD-48 vs AD-26, AD-46, NFR-2.

"Tier 3 … may be disabled with nothing breaking, and **loses no data** because consumer offsets
survive and the backlog drains on return." True for state-transition events (~10k/day per the
addendum). Not true for location pings: ~10k/sec at NFR-2 scale means Kafka retention, not consumer
offsets, decides what survives, and a Tier 3 outage measured in hours loses history that FR-27 calls
durable and FR-44 consumes.

**Fix:** qualify the claim — Tier 3 loses no *state-transition* data; location history is bounded by
Kafka retention and a Tier 3 outage longer than that retention loses pings, which is accepted
because pings are telemetry and FR-44's aggregates degrade rather than break. State the retention
figure as the bound.

### M9 — AD-40's "one Redis read" claim is probably wrong

**Location:** AD-40.

"position returns coordinates **and ETA** from one Redis read." ETA is haversine(pickup, driver
position) / fixed speed — it needs the ride's **pickup coordinates**, which live on the `rides` row
in `matching-service`'s Postgres (AD-3), not in Redis. Either the endpoint does a Postgres read too,
or pickup must also be cached — and the latter is an unstated cache with unstated invalidation
against AD-4.

**Fix:** either correct the wording to "one Redis read plus the ride's pickup, which is immutable
and cacheable per ride", or state the pickup cache explicitly and bring it under AD-4.

### M10 — CI is a box in a diagram, not a decision

**Location:** "Deployment and environments" section, Deferred → "Automated image promotion".

Deployment (AD-49) and the local/K8s target are well covered. CI is one node reading "Testcontainers:
real Postgres, Redis, Kafka; stub provider" and nothing else: no statement of what gates a merge,
how images are built and tagged (the Deferred entry says "manual tag bumps at milestone cadence",
which implies but does not define a tagging scheme AD-49's manifests must reference), or whether the
five independent Gradle builds run independently or together. Given AD-49's per-template generation
and the milestone tags (`v0.1`…`v1.0`) the PRD Goals commit to, the image tag ↔ manifest reference
contract is a real cross-cutting invariant.

**Fix:** add two or three lines to AD-49: the image tagging scheme, the fact that manifests
reference immutable tags (never `latest`), and what CI must pass before an image is tagged.

---

## LOW

### L1 — AD-7's `config` package is absent from the internal dependency diagram
The second Mermaid diagram shows `model`, `strategy`, `service`, `controller`, `repository` but not
`config`, which AD-7's rule requires. Since `config` is where AD-8's "wired at runtime only" arrow
actually lives, it should appear. **Fix:** add `config` to the diagram as the wiring point.

### L2 — AD-9's arrow notation is ambiguous and inverted relative to the diagrams
"ordered one-way `shared ← fare ← ride ← dispatch ← quote`" uses `←` while both Mermaid diagrams use
`-->` for the opposite relation, so a reader cannot tell whether `quote` depends on `dispatch` or the
reverse. **Fix:** write it as a sentence — "each package may import only packages to its left:
shared, fare, ride, dispatch, quote" — and drop the arrows.

### L3 — AD-12 says "all services" but the source tree contains `simulator/`
AD-12's rule is "All services carry the `-service` suffix" and the tree lists `simulator/` alongside
the five. It is defensible (the Simulator is a client, not a service) but unstated. **Fix:** one
clause in AD-12 — "the Simulator is a client, not a service, and is deliberately unsuffixed."

### L4 — Story-level detail carried at spine altitude
Rubric item 6. Several rules encode implementation where the invariant would do, which invites
re-litigation at story time and dates the document: AD-14's literal `CREATE UNIQUE INDEX` DDL
(invariant: one active ride enforced by a partial unique index, not by a check-then-insert); AD-29's
`GENERATED ALWAYS AS IDENTITY bigint` (invariant: ordering comes from a single monotonic authority,
never a timestamp); AD-33's Jackson `ObjectMapper` aside (a story note, not a rule); AD-46's
concrete second-values (the AD itself says "the **ordering** is the invariant" — the values belong in
one config artifact the AD points at); and the Stack table's patch-level pins (PostgreSQL 18.6,
Kafka 4.3.1), which the table's own footnote concedes are seed values. **Fix:** demote the mechanism
to a parenthetical and lead each rule with the invariant.

### L5 — No convention for bounded list reads (FR-8, FR-25)
Both FRs specify "most recent first, bounded by a result-size limit" and are implemented in two
different façades. Page size, cap, ordering tie-break and offset-vs-cursor will diverge. Small, but
it is exactly the class of thing the Consistency Conventions table exists for. **Fix:** one row —
"List reads: `?limit=` with a fixed default and hard cap, ordered by creation time descending, id
as tie-break."

### L6 — AD-3 and AD-26 disagree on where driver location lives
AD-3: `driver-service` "owns driver identity **and location**". AD-26: heartbeats do "**zero
Postgres writes**". Both are true under a charitable reading (owns the *live* location, in Redis)
but the wording drifts. Covered by H6's fix.

### L7 — AD-5 has no named enforcement mechanism
"`matching-service` is never publicly routable" is a property of HAProxy config plus Kubernetes
Service types. Naming the mechanism (ClusterIP only, no Ingress, NetworkPolicy) makes it reviewable
against a manifest rather than against intent. Covered by M4's fix.

### L8 — AD-26 does not bind FR-27
Covered by H6.

---

## What the spine gets right (so the fixes do not undo it)

Recorded deliberately, because several findings above are "extend this to the rest of the system"
rather than "this is wrong":

- **AD-19** is the best AD in the document — making an unfunded ride *invisible* to dispatch rather
  than guarded against is exactly the structural-over-remembered move a spine should make.
- **AD-13 / AD-16 / AD-18** together resolve the state-naming divergence properly, and AD-18's
  "provenance is a field, not a state" is stated as a generalisable principle rather than a one-off.
- **AD-28 through AD-36** are a coherent, genuinely enforceable event-backbone contract; AD-34's
  breaker-before-claim detail and AD-29's ordering rationale are the kind of specificity that
  actually prevents a bug.
- **AD-45** (bound outcomes the system can determine; never bound a wait for external truth) is a
  principle worth more than its FR bindings, and it is correctly carried from the addendum.
- **AD-38**'s error mapping table is unambiguous, checkable, and covers the anti-enumeration case
  the PRD called out.
- The **Deferred** section is mostly disciplined: each entry names why deferral is safe, and only
  the schema registry (C4) hides a real invariant.
