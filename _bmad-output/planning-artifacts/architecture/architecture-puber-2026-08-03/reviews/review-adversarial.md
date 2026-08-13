---
title: Adversarial Review — Puber Architecture Spine
type: review
method: adversarial / divergence-pair attack
target: ARCHITECTURE-SPINE.md (status final, updated 2026-08-13)
created: 2026-08-13
---

# Adversarial Review — Architecture Spine

## Method

The spine's job is to make independently-built units converge. So the attack is not "is this AD
wise?" — it is: **construct two units one level down (two epics, two stories, two developers,
two agents) that each obey every AD to the letter, and yet build things that cannot coexist.**
Every such pair is a hole. Twenty-seven pairs are below, ordered by severity. Each names the two
units, the ADs they both satisfy, the concrete incompatibility, and the new or tightened AD that
closes it.

At the end there is an honest list of the things I attacked and **could not** break.

---

# Tier A — System-breaking

## A1. AD-43 and AD-48 give opposite answers to the same failure: payments down

**Unit 1 — Story "Payment gateway strategy + Resilience4j fallback" (matching-service, `ride`).**
AD-43 says `matching-service` holds an outbound gateway strategy — "call `payment-service`, **or
signal authorised immediately** when payments are absent (early phases, **and Tier-2
degradation**)". NFR-3 requires graceful degradation. The developer wires the Resilience4j
circuit breaker on the outbound gateway with a fallback that selects the
`AlwaysAuthorised` strategy when the breaker opens. Rides flow.

**Unit 2 — Story "Tier-2 degradation behaviour" (deployment/resilience epic).**
AD-48 says Tier 2 — `payment-service` — "degrades to **rides stalling before dispatch**". AD-45
says a ride awaiting authorisation "is **not** bounded by a timeout; it is surfaced as a metric
and an alert, and the rider's own cancellation is the exit." The developer implements exactly
that: payment-service unreachable ⇒ rides pile up in `REQUESTED`, alert fires, nothing dispatches.

**ADs both satisfy:** AD-41, AD-43, AD-45, AD-48, NFR-3.

**The incompatibility.** These are not two flavours of the same behaviour; they are opposites.
Unit 1's system dispatches every ride for free during a payment outage and then, at completion,
tries to capture against `payment_intents` rows that were never created — a capture path that
AD-44's "no automatic voiding sweep" rule guarantees nobody will clean up. Unit 2's system
refuses all rides. Both cite the spine. Worse, Unit 1's reading turns AD-19's structural
guarantee ("an unfunded ride is *invisible* to dispatch") into a lie: the ride is in
`WAITING_MATCH` with no funds held, and AD-19's whole claim is that this state cannot exist.

**Closing AD.** Tighten AD-43: *the "signal authorised immediately" gateway strategy is a
**deploy-time configuration choice**, selected only when `payment-service` does not exist in the
topology (early phases, CI, the NFR-2 stress run). It is **never** selected as a runtime
fallback. A circuit breaker on the outbound gateway fails the authorisation attempt; it never
substitutes a different strategy.* And add to AD-19 or AD-41: *a ride may only enter
`WAITING_MATCH` when the deployment's configured gateway strategy has returned an authorised
outcome; when the gateway is `AlwaysAuthorised`, `payment-service` is absent from the topology
and no capture is ever attempted — the capture path checks the same configuration flag, not the
runtime health of a service.*

---

## A2. AD-34 dead-lettering plus AD-35 age-shedding wedges the system permanently closed

**Unit 1 — Story "Outbox relay retry and dead-lettering" (AD-34).**
Success deletes the row; failure increments `tries` and pushes out `next_attempt_at`; at the cap,
`dead_at` is stamped and **the row stops being claimed**. AD-29 says rows are deleted *on
publish* — a dead row is never published, so it is never deleted. It stays in `event_outbox`
forever.

**Unit 2 — Story "Outbox backpressure / load shedding" (AD-35).**
"Past the bound, new ride requests are shed with a 503… The trigger is backlog **age**, not
depth — a large backlog draining healthily is fine; **a small one that has not moved is
broken**." The natural implementation is
`SELECT now() - min(occurred_at) FROM event_outbox` and shed above the threshold.

**ADs both satisfy:** AD-28, AD-29, AD-34, AD-35.

**The incompatibility.** One poison event — a payload that trips a serialisation bug, a topic
that does not exist yet, a single oversized message — exhausts its retries, is dead-lettered per
AD-34, and sits in the table. From that moment `min(occurred_at)` only ages. Backlog age crosses
the bound and **every ride request in the system is shed with 503, forever**, with a perfectly
healthy Kafka. AD-35's own words describe the dead row precisely: "a small one that has not
moved is broken". Each story is individually correct and defensible in review. Together they are
a permanent, self-inflicted global outage that no restart clears.

**Closing AD.** Tighten AD-35: *backlog age is computed over **claimable** rows only —
`WHERE dead_at IS NULL` — because a dead row is a resolved failure, not an undelivered one.
Dead rows are moved out of `event_outbox` (a `dead_events` table or a partition detach) so they
cannot be counted, queried, or scanned by the relay's hot path, and their presence raises its own
alert rather than shedding traffic.*

---

## A3. AD-29's identity sequence has commit gaps; a cursor relay silently loses events

**Unit 1 — Story "Outbox relay v1" (`matching-service`, Kafka epic).**
AD-29: "the primary key is a `GENERATED ALWAYS AS IDENTITY` bigint — **monotonic** because it
comes from one authority… `occurred_at` … is never used for ordering." AD-47 says pools are
sized small. A developer reads "monotonic sequence" as a stream cursor and implements the relay
as `SELECT … WHERE id > :last_published ORDER BY id LIMIT n`, tracking a high-water mark — an
obvious, cheap, index-friendly design, and exactly what "monotonic sequence" invites.

**Unit 2 — Story "Outbox relay" in `payment-service` (payments epic, weeks 17–20).**
Same ADs. This developer implements `SELECT … WHERE next_attempt_at <= now() ORDER BY id LIMIT n`
— a full scan of remaining rows, no cursor. Also legal.

**ADs both satisfy:** AD-28, AD-29, AD-34.

**The incompatibility, and the data loss.** Postgres identity values are handed out at *insert*
time, not commit time. Transaction T1 takes id 5, T2 takes id 6, T2 commits first. Unit 1's relay
polls, sees id 6, publishes it, advances its cursor to 6. T1 then commits and row 5 becomes
visible — **behind the cursor, never claimed, never published, never deleted.** The state change
committed and the event vanished: the exact dual-write failure AD-28 exists to prevent, now
reintroduced by AD-29's own wording. Unit 2's relay does not have the bug. So the same system has
one relay that loses events and one that does not, and the difference is invisible in review.

This compounds with A2: the lost row is also never deleted, so under A2's naive age query it
also eventually sheds all traffic.

**Closing AD.** New AD: *the relay never uses a high-water-mark cursor. Claim is
`SELECT … WHERE next_attempt_at <= now() AND dead_at IS NULL ORDER BY id LIMIT n FOR UPDATE SKIP
LOCKED`, over the **whole** remaining table — correctness comes from rows being deleted on
publish, so the un-published set is the working set. Identity ordering is a tie-break within a
claim batch, not a stream position; gaps in the sequence are normal and must never be
interpreted as progress.*

---

## A4. AD-34's per-row backoff and multi-replica relays destroy the per-entity ordering AD-36 promises

**Unit 1 — Story "Per-row retry with exponential backoff" (AD-34).** "success deletes the row,
failure increments `tries` and pushes `next_attempt_at` out by exponential backoff, and **the
batch commits once so successes persist regardless of neighbours**."

**Unit 2 — Story "Consumers are idempotent, producers key by entity" (AD-36).** "every producer
sets the partition key to the entity id" — so all events for one ride land on one partition, in
publish order. AD-29 promises they are published in sequence order. Audit (FR-41), the dashboard
(FR-50) and analytics (FR-44) are all built on that assumption; AD-29 explicitly says the durable
record is the audit trail, so audit's ordering *is* the system's history.

**ADs both satisfy:** AD-29, AD-34, AD-36.

**The incompatibility.** Row 5 = `ride.matched`, row 6 = `ride.cancelled`, same ride, same
partition key. Row 5 hits one transient broker error and gets `next_attempt_at = now + 2s`. Row 6
succeeds in the same batch, "regardless of neighbours". Kafka now carries `ride.cancelled` before
`ride.matched` **on the same partition**, in the same key. AD-36's mitigation ("where a consumer's
write is a guarded transition, the guard supplies idempotency for free") does not apply to
audit-service — its write is an append, not a guarded transition — nor to the FR-50 dashboard
projection, which decrements the `MATCHED` count and then increments it, leaving the ride
displayed as matched forever, and leaves `audit_events` recording a ride that was cancelled and
then matched.

Multiply by replicas: AD-47 lists replica counts as derived capacity, and AD-28 puts a relay in
every producer, so N replicas of matching-service run N relays. Nothing in AD-34 mandates
`SKIP LOCKED` claiming (AD-20 mandates it, but AD-20 binds the *matching worker pool*, not the
relay). Two replicas polling `ORDER BY id LIMIT n` without locking publish rows 5 and 6
concurrently and racing into the partition.

**Closing AD.** New AD: *ordering is preserved **per entity**, not globally. The relay claims and
publishes rows grouped by `entity_id`; if a row for an entity fails, **no later row for that same
entity is published until it succeeds or is dead-lettered** — the failing entity parks, the stream
does not. Relay claiming is always `FOR UPDATE SKIP LOCKED` so concurrent replicas never hold
rows for the same entity simultaneously.* If parking is judged too costly, the alternative AD is:
*every event carries a per-entity `entity_sequence`, and every projection consumer rejects an
event whose sequence is not greater than the last one applied for that entity.* Pick one; the
spine currently picks neither while asserting the guarantee.

---

## A5. Redis has no persistence and TTL'd position keys — the staleness sweeps read a key that may not exist

**Unit 1 — Story "Per-driver position key" (AD-26, AD-27).**
AD-26: a per-driver position key covering every driver, **TTL'd**, "serving point lookups and
**all staleness comparisons**". AD-27: "no persistence (**every value rebuilds within one
heartbeat cycle**)". At 20k drivers (NFR-2) with 2 s heartbeats, a developer sizing Redis picks
a TTL of 60 s — comfortably past the 15 s idle staleness window, keeps the keyspace tight, and is
consistent with every AD.

**Unit 2 — Story "Session expiry sweep" (AD-22) / Story "MATCHED and IN_PROGRESS staleness
sweeps" (FR-13, FR-14, AD-46).**
AD-22: an idle driver "unreachable for **one hour**" is set `OFFLINE`. AD-46: `IN_PROGRESS`
staleness is **10 minutes**, session expiry **1 hour**. These sweeps compare "now" against the
driver's last heartbeat, which per AD-26 lives in the Redis position key.

**ADs both satisfy:** AD-22, AD-26, AD-27, AD-46.

**Two independent breakages.**

*(a) The TTL silently disables the mechanisms it feeds.* With Unit 1's 60 s TTL, a driver silent
for 61 s has **no position key at all**. The 10-minute `IN_PROGRESS` sweep and the 1-hour session
expiry can never observe "last heard 12 minutes ago" or "last heard 70 minutes ago", because the
evidence expired. AD-22's stated purpose — "Monday's shift leaking into Wednesday" — is exactly
the case that is now impossible to detect. AD-46 pins an ordering over the *time constants* but
never includes the Redis TTL in that ordering, so nothing tells Unit 1's developer that their
sizing decision is load-bearing for a mechanism in another epic.

*(b) A missing key is undefined, and the two readings differ catastrophically.* AD-27 says Redis
has no persistence. After a Redis restart (pod reschedule, `kind` node restart, memory pressure),
**every** position key is gone. AD-27's justification — "every value rebuilds within one
heartbeat cycle" — is true only for drivers who are still heartbeating; it is false for precisely
the silent drivers the sweeps exist to catch, and false for the whole fleet for the first two
seconds. Now:

- Dev A treats a missing key as **infinitely stale** (the honest reading of "unreachable"): at
  the first sweep tick after a Redis restart, every `MATCHED` ride is recovered and every
  `IN_PROGRESS` ride is **auto-completed and captured** (FR-14). A cache restart triggers a
  fleet-wide billing event.
- Dev B treats a missing key as **unknown, skip**: silent drivers are never recovered at all,
  rides stick in `IN_PROGRESS` forever, and AD-22 never fires.

Both are legal. One charges every rider in the system for a trip that did not end; the other
disables recovery.

**Closing AD.** New AD: *the authoritative `last_heartbeat_at` for staleness and session-expiry
decisions is a column on the owning row (`matching-service.drivers`), written at most once per
staleness-window quantum, not on every heartbeat. Redis serves position and matchability only.*
(If the hot-write cost is unacceptable, the alternative: *the Redis position-key TTL must exceed
the longest window that reads it — strictly greater than session expiry — and this ordering joins
AD-46's invariant chain: `heartbeat ≪ idle < MATCHED < IN_PROGRESS < session expiry < position
key TTL`.*) Plus, unconditionally: *a missing position key is `unknown`, never `stale`. No
sweep may take a terminal or state-advancing action on `unknown`; sweeps require a **positive
observation** of an old timestamp. On Redis cold-start the sweeps are suppressed for one full
session-expiry window.*

---

## A6. AD-46 fixes `NO_DRIVER` at 60 s and `MATCHED` staleness at 90 s, but never says when the window starts — so FR-13 recovery is dead on arrival

**Unit 1 — Story "NO_DRIVER window" (FR-12, AD-45, AD-46).** "`NO_DRIVER` is bounded, because
'we looked and found nobody' is an answer the system holds." AD-46: `NO_DRIVER` **60 s**. The
developer needs a clock origin. The obvious one — and the one FR-12's wording implies ("within a
bounded overall window") — is `requested_at`, or the timestamp of first entering
`WAITING_MATCH`. `overall` reads as "the whole search, not each attempt".

**Unit 2 — Story "Silent driver before pickup" (FR-13, AD-13, AD-46).** A driver holding a
`MATCHED` ride goes silent past the **90 s** staleness window; the ride "returns to
`WAITING_MATCH` and re-enters matching for the next-nearest driver… it is **salvaged rather than
killed**."

**ADs both satisfy:** AD-13, AD-45, AD-46. Both use the exact constants AD-46 prescribes.

**The incompatibility, and it fires 100% of the time.** `MATCHED` staleness (90 s) is *longer*
than the `NO_DRIVER` window (60 s) — that is what AD-46's own ordering chain
(`idle < MATCHED < IN_PROGRESS < session expiry`) demands, and it says nothing about `NO_DRIVER`'s
place in that chain. So any ride recovered by FR-13 re-enters `WAITING_MATCH` at T+90 s or later,
already past its 60 s overall window, and Unit 1's sweep terminates it as `NO_DRIVER` on the very
next tick. FR-13's "salvaged rather than killed" is never once achieved; the salvage path is
provably unreachable code. The same applies to any ride that burns >60 s across a few 10 s offer
timeouts and then gets recovered.

Dev B, reading it the other way — window resets on each entry to `WAITING_MATCH` — builds a
system where a ride passed between silent drivers can live indefinitely, and FR-12's promise of
"a definitive answer instead of waiting indefinitely" fails instead.

**Closing AD.** Tighten AD-46 and AD-45: *the `NO_DRIVER` window is measured from a
`matching_deadline` column stamped on the ride when it **first** enters `WAITING_MATCH` and never
recomputed — the rider's answer must be bounded from their own request, not from the system's last
retry. `NO_DRIVER` therefore joins the ordering invariant and must **exceed** the `MATCHED`
staleness window plus one offer timeout, so an FR-13 salvage has a real chance to re-offer.*
Concretely with today's numbers, either `NO_DRIVER` becomes ≥ 120 s or `MATCHED` staleness drops
below it; as written, 60 < 90 makes FR-13 a no-op.

---

# Tier B — Contract and ownership holes

## B1. Nobody owns the Redis geo set, and the two facts needed to populate it live in two services

**Unit 1 — Story "Location fast path" (`driver-service`, FR-26/FR-28).** AD-26 requires "two
writes per heartbeat, pipelined, and **zero Postgres writes**", sub-second reads. The developer
owning the heartbeat endpoint writes both structures directly from `driver-service` — it is the
only place with sub-second access to the ping, and AD-3 gives `driver-service` "driver identity
and **location**".

**Unit 2 — Story "Matchable geo index" (`matching-service`, `dispatch`).** AD-26 says the geo set
contains "**matchable drivers only**". Matchability is `status = 'AVAILABLE'` (AD-16) plus a fresh
heartbeat (AD-21) — and `status` lives in `matching-service`'s dispatch `drivers` table (AD-3),
which `driver-service` cannot read (AD-1). So this developer, correctly, concludes the geo set can
only be maintained by `matching-service`, consuming heartbeats from Kafka (the dependency graph
shows `KB -. events .-> MS`).

**ADs both satisfy:** AD-1, AD-3, AD-4, AD-16, AD-21, AD-26, AD-28.

**The incompatibility.** No AD names the writer, and each unit's reasoning is airtight given the
ADs it read. Three outcomes, all bad:

- **Both build it.** Two writers, two member encodings, two populations, no invalidation contract.
- **Only Unit 1 builds it.** `driver-service` does not know dispatch status, so it adds **every**
  heartbeating driver to the "matchable only" set — including `BUSY` ones. AD-26's stated purpose,
  "prevents over-fetching ineligible candidates when most drivers are busy", is defeated. AD-4
  keeps it *correct* (the conditional `UPDATE` loses and the worker re-searches) but at stress
  scale, with most of 20k drivers busy, `GEOSEARCH` returns mostly ghosts and the claim-loop burns
  its budget losing races. Worse: `matching-service` removes a driver from the set at offer time
  (AD-16 sets `BUSY` at offer), and `driver-service` **re-adds them 2 seconds later** on the next
  heartbeat. The removal has a 2-second half-life.
- **Only Unit 2 builds it.** Heartbeats now traverse Kafka before affecting matchability, adding
  consumer lag to the fast path that FR-28 requires to be sub-second, and AD-23's warning about
  consumer lag applies to matchability itself.

**Closing AD.** New AD: *the Redis geo set and position keys are written by exactly one service —
`matching-service`'s `dispatch` package — because geo-set membership is a function of dispatch
status, which only `matching-service` holds. `driver-service` produces heartbeats to Kafka and
nothing else; the position key is written by the same consumer, so both structures have one
writer and one clock. Geo-set membership is mutated at exactly three points: heartbeat consume
(add if `AVAILABLE`), offer/engagement (remove), release (add if `AVAILABLE`). Point-lookup
freshness for FR-6/FR-21 is therefore bounded by consumer lag, and the idle staleness window
under AD-46 must exceed it — this is the concrete meaning of AD-23's "plausible lag".*

## B2. AD-38's `FAILED_PRECONDITION` and `ABORTED` are indistinguishable at the moment of decision

**Unit 1 — Story "Driver accepts an offer" (FR-22).** AD-15: the guarded update carries expected
prior state and acting identity; "zero rows affected means rejected, never silently retried as
success." AD-38: "wrong state → `FAILED_PRECONDITION` → 409" and "**lost race → `ABORTED` →
retried internally and never surfaced**". Dev A maps a zero-row result to `ABORTED` and retries
internally, per AD-38's second clause.

**Unit 2 — Story "Rider cancels" (FR-16, FR-17).** FR-17: "both are guarded transitions on one
ride, whichever commits first wins, and **the loser is rejected** rather than both applying."
Dev B maps a zero-row result to `FAILED_PRECONDITION` → 409, per AD-38's first clause.

**ADs both satisfy:** AD-15, AD-17, AD-38.

**The incompatibility.** A guarded `UPDATE … WHERE status = 'OFFERED' AND driver_id = :me`
returning 0 rows carries **exactly one bit**. It cannot tell "the ride was already `CANCELLED`
five minutes ago" (wrong state, 409) from "a rider's cancel committed 3 ms ago" (lost race,
retry). AD-38 asks the implementer to distinguish two cases from a signal that does not
distinguish them. Dev A's accept endpoint retries an accept on a permanently cancelled ride until
its retry budget is gone, then surfaces some third thing; Dev B's cancel returns 409 correctly.
The same physical event produces a retry loop in one service and a clean 409 in another, and
FR-17's "the loser is rejected" is honoured in one path and violated in the other. Additionally
`ABORTED` is the gRPC code Postgres serialization failures should map to — conflating it with
guard misses means a real `40001` is handled identically to a legitimate business rejection.

**Closing AD.** Tighten AD-38: *a zero-row guarded update is **never** `ABORTED`. It is
classified by a follow-up read of the row inside the same transaction: row absent or identity
mismatch → `NOT_FOUND` → 404; row present in a different state → `FAILED_PRECONDITION` → 409.
`ABORTED` is reserved exclusively for Postgres serialization/deadlock errors (SQLSTATE 40001,
40P01), which are the only conditions retried internally and never surfaced.*

## B3. AD-44 handles terminal-before-authorisation and is silent on terminal-after — with no sweep to catch it

**Unit 1 — Story "Cancel while authorisation in flight" (AD-44).** "a terminal event arriving
before the authorisation resolves records the intent (`void_requested`) rather than no-opping;
the authorisation result then voids on arrival instead of settling."

**Unit 2 — Story "Apply authorisation outcome" (AD-41).** "The handler applying that outcome does
exactly one guarded transition, which makes it idempotent without a dedupe table." Ride is
`CANCELLED`; the guard expects `REQUESTED`; zero rows; handler returns cleanly. Idempotent, per
the AD.

**ADs both satisfy:** AD-41, AD-44, AD-36.

**The incompatibility.** The interleaving AD-44 does not cover: authorisation resolves **first**
(payment-service records `AUTHORIZED`, publishes `payment.authorized`), the ride is already
`CANCELLED`, matching's guarded handler drops the event on the floor exactly as AD-41 blesses,
and the `ride.cancelled` notification reaches `payment-service` afterwards — which is guaranteed
to happen whenever the cancel travels by outbox+relay (see B4), and possible under any transport
given AD-34's per-row backoff. Now:

- Dev A implements AD-44 literally — *record `void_requested`; the auth result voids on arrival* —
  and on finding the intent already `AUTHORIZED`, does nothing, because AD-44 describes only the
  pre-resolution case and AD-44 explicitly forbids a sweep. **Stranded hold**, which is the one
  thing AD-44 is titled to prevent.
- Dev B implements "if already `AUTHORIZED`, void now". Correct — but is not what any AD says.

And AD-44's deliberate removal of the safety net ("**No automatic voiding sweep**") means Dev A's
stranded hold is never found by anything except FR-40's flag-only reconciliation, which by design
does not correct it.

**Second, sharper gap:** AD-44 assumes a `payment_intents` row exists when the terminal event
arrives. Nothing guarantees it. A ride cancelled 50 ms after creation, while `payment-service` has
not yet created its `INITIATED` row, gives the cancel handler nothing to stamp `void_requested`
on — so the void intent is lost and the subsequent authorisation settles against a dead ride.

**Closing AD.** Tighten AD-44: *`payment-service` creates the `INITIATED` `payment_intents` row
**idempotently keyed by `ride_id`** as the first step of the authorisation command, before any
provider call. A terminal-ride notification is applied as an **upsert** on that key, so
`void_requested` can be recorded for a ride whose intent row does not yet exist. Voiding is
driven by a single guarded rule evaluated whenever either fact lands:* `void_requested = true AND
state = AUTHORIZED ⇒ VOID`. *This makes the two orderings converge on the same outcome and needs
no sweep. The invariant `NOT (ride terminal AND payment.state = AUTHORIZED)` is asserted in tests
and alerted on.*

## B4. No AD pins the transport for ride-terminal → payment, or for initiating authorisation

**Unit 1 — Story "Cancel releases the hold" (matching-service).** AD-37: "anything an actor waits
for a response to" is gRPC; AD-43 gives `matching-service` an outbound payment gateway strategy.
The developer calls `payment-service.VoidAuthorization(rideId)` synchronously in the cancel path.

**Unit 2 — Story "Payment reacts to terminal rides" (payment-service).** AD-28: domain code never
publishes; state changes write outbox rows. AD-31/AD-36: `ride.cancelled` is a domain event and
`payment-service` is a legitimate consumer. The developer consumes `ride.cancelled` and voids.

**ADs both satisfy:** AD-28, AD-31, AD-36, AD-37, AD-43.

**The incompatibility.** Both are fully legal readings, and they differ in every property that
matters:

- **If both are built** (entirely plausible across the payments epic and the Kafka epic), every
  cancellation triggers two voids. Idempotency is not stated for the void path.
- **Unit 1's synchronous void puts `payment-service` on the cancel request path**, which breaks
  AD-48's Tier-2 contract — Tier 2 is supposed to degrade *only* to "rides stalling before
  dispatch", not to cancellations failing. It also holds a `rides` row lock across a network call
  to a rate-limited provider-facing service (nothing in AD-2 or AD-15 forbids it), which is the
  worst possible thing to do under AD-6's queue-chain reasoning.
- **Unit 2's asynchronous void** is subject to AD-34's backoff and reorders relative to the
  authorisation result — triggering B3 above.

The identical gap exists on two other edges the spine never assigns a transport to: **capture on
completion** (`ride.completed` event vs. gRPC `Capture`) and — most dangerously — **initiating
the authorisation** at ride creation. For the latter, AD-41 says only that "authorisation proceeds
asynchronously". Dev A implements it as `ride.requested` → Kafka → payment-service; Dev B
implements it as an async in-process call through AD-43's gateway strategy. If both exist, **every
ride gets two holds** — precisely the doubling AD-45 is written to prevent. Dev A's version is
additionally forbidden, but only by an inference two ADs away: AD-42 says the payment token is
"never persisted", and an outbox row *is* persistence — so the token cannot ride the event path.
That is far too subtle to be the only thing standing between the project and double authorisation.

**Closing AD.** New AD: *the ride↔payment edge is **synchronous gRPC in one direction and events
in the other, and never both**: `matching-service` → `payment-service` commands (`Authorize`,
`Capture`, `Void`) are gRPC calls issued through the AD-43 gateway strategy, carrying the
transit-only token; `payment-service` → `matching-service` outcomes are domain events
(`payment.authorized`, `payment.failed`, `payment.captured`, `payment.voided`) applied by guarded
transition. `payment-service` **never consumes `ride.*` events** — it holds no ride state and must
not act on ride transitions independently. Every command carries `ride_id` as its idempotency key.*

## B5. The relay must choose a Kafka topic from envelope columns alone, and no AD says which

**Unit 1 — Story "Ride events onto Kafka" (matching-service).** AD-31: the relay must not parse
payloads; routing lives in envelope columns. The naming convention gives `ride.matched`,
`payment.captured` as **event names**. The developer publishes each event type to its own topic,
`ride.matched`, `ride.cancelled`, … — the names in the spine read like topic names.

**Unit 2 — Story "Payment events onto Kafka" (payment-service).** Same ADs; this developer maps
topic = `entity_type`, so one `payment` topic carries every payment event type.

**ADs both satisfy:** AD-28, AD-31, AD-33, AD-36.

**The incompatibility.**

- **AD-36's ordering guarantee only holds within a topic.** Unit 1's per-event-type topics mean
  `ride.offered` and `ride.matched` for the same ride are on *different* topics; keying by
  `ride_id` buys nothing. Per-entity ordering — which A4 already shows is load-bearing for audit,
  FR-44 and FR-50 — is destroyed by topology, not by failure.
- **FR-41 requires audit-service to capture *every* transition.** Against Unit 1's topology,
  audit needs a new subscription for every new event type ever added; a new event type from a
  future story is silently un-audited. Against Unit 2's, a wildcard/entity subscription suffices.
- Partition counts (AD-47 lists them as derived) are per topic; with per-event-type topics the
  derivation is per event type and the numbers are meaningless at that granularity.
- Nothing says whether topics are auto-created. If they are, defaults (1 partition) apply, and a
  later manifest raising the count rehashes keys and reorders every in-flight entity.

**Closing AD.** New AD: *one Kafka topic per `entity_type` — `ride`, `driver`, `payment` — and the
relay routes on the `entity_type` envelope column alone, with `entity_id` as the partition key.
Event type discrimination is the consumer's job, via the `event_type` column. Topic auto-creation
is disabled; topics, partition counts and retention are declared in `deploy/` per AD-49.*

## B6. AD-31 claims one vocabulary across two tables, but never enumerates or cases it

**Unit 1 — Story "Ride events" (matching-service).** Writes `entity_type = 'RIDE'`,
`actor_type = 'RIDER' | 'DRIVER' | 'SYSTEM'`. Uppercase matches every enum in the spine (ride
states, driver statuses, `completed_by = DRIVER | SYSTEM`).

**Unit 2 — Story "Payment events" (payment-service).** The Consistency Conventions say event names
are `<entity>.<past-tense-action>`, **lowercase**, and "**the prefix always matches
`entity_type`**". Read literally, `entity_type = 'payment'`, lowercase. This developer also has to
label a webhook-driven transition, whose actor is neither `RIDER`, `DRIVER`, `SYSTEM` nor `ADMIN`
— the PRD glossary's four values, which the spine never restates — and invents `PROVIDER`. For
`SYSTEM` events, `actor_id` is left `NULL`; Unit 1's developer wrote the literal `'system'`.

**ADs both satisfy:** AD-31, AD-33, and the naming convention (the convention actually *causes*
the divergence by asserting the prefix matches `entity_type`).

**The incompatibility.** AD-31's whole justification is "one vocabulary spans both tables". FR-42
("queryable by entity and by actor") returns nothing for `entity_type = 'RIDE'` against payment
rows and vice versa; FR-44's driver-utilisation aggregate silently excludes or double-counts
depending on which casing it filters. And AD-33 makes this permanent: "**changing what a field
means** is breaking" — you cannot normalise the vocabulary later without a new `event_type` for
every event in the system.

**Closing AD.** New AD: *the shared vocabulary is enumerated in the spine and is closed:*
`entity_type ∈ {RIDE, DRIVER, PAYMENT}`, `actor_type ∈ {RIDER, DRIVER, SYSTEM, ADMIN}`,
*uppercase, in both `event_outbox` and `audit_events`. `actor_id` is the acting UUID for `RIDER`
and `DRIVER`, and `NULL` for `SYSTEM` and `ADMIN` — never a sentinel string. Event names remain
lowercase; the mapping to `entity_type` is `upper(prefix)`. A provider-driven transition is
`actor_type = SYSTEM`; the provider is identified in the body, not the envelope.*

## B7. No AD pins the JSON encoding of the payload, so two producers emit the same concept in two shapes

**Unit 1 — Story "`ride.completed` event" (matching-service).** AD-30 serialises the built event
as JSON. Money convention: "**integer minor units in transit**; `DECIMAL` at rest". Kafka is
transit ⇒ `{"fareAmount": 1250}`. Jackson's default naming is camelCase, and the domain model is
an immutable Java record, so field names come out camelCase. The pickup goes in as
`{"pickup": [-0.1276, 51.5072]}` — longitude first, per the coordinate convention's "longitude
before latitude in geo calls".

**Unit 2 — Story "`payment.captured` event" (payment-service).** The same event JSON is stored in
`event_outbox` and later in `audit_events` and ClickHouse — that is *at rest* ⇒ `DECIMAL` ⇒
`{"amount": "12.50"}`. This developer configures `SNAKE_CASE` on the ObjectMapper to match the
snake_case envelope columns of AD-31, so it is `{"amount": "12.50", "captured_at": 1755100000}`
against Unit 1's `{"completedAt": "2026-08-13T10:00:00Z"}`.

**ADs both satisfy:** AD-30, AD-31, AD-32, AD-33, and every Consistency Convention (the money and
coordinate conventions are genuinely ambiguous for an event payload, which is simultaneously in
transit and at rest).

**The incompatibility.** `audit-service` and the ClickHouse analytics (FR-43, FR-44) consume both.
Revenue sums 1250 and 12.50. Ride-density analytics (FR-44) reading `[lng, lat]` from one producer
and `{"lat":…, "lon":…}` from another plots half the fleet in the wrong hemisphere. AD-33 then
welds the mistake in place: renaming a field is breaking. And **currency is absent from the entire
spine** — no column, no envelope field, no convention — so a fintech-pattern project has money
values with no unit attached anywhere.

**Closing AD.** New AD: *event payload encoding is fixed and identical across every producer:
`snake_case` keys (matching the envelope, one casing per document); money as
`{"amount_minor": <int64>, "currency": "<ISO-4217>"}` — never a decimal string, never a bare
number; timestamps as RFC 3339 UTC strings with `Z`; coordinates as an explicit object
`{"lat": <number>, "lng": <number>}` — the longitude-first ordering is a Redis/`GEOSEARCH` call
convention only and never appears in a payload or an API body. Every service builds its
ObjectMapper from one documented configuration, restated per service since there is no shared
library.* Add `currency` to the money row of the Consistency Conventions and to
`payment_intents` / `rides` at rest.

## B8. `schema_version` has no defined semantics, and one reading breaks every consumer

**Unit 1 — Story "Event envelope" (AD-31).** Adds `schema_version` as an envelope column,
initialised to 1. Later a story adds a field to `ride.matched` — additive, safe under AD-33 — and
this developer bumps `schema_version` to 2, which is the only reason the column exists.

**Unit 2 — Story "Audit consumer" / "Dashboard projection".** Reads AD-33: breaking changes get a
new `event_type`, so within an `event_type` the schema is stable. This developer treats
`schema_version` as a compatibility gate and writes `if (schema_version != KNOWN) { skip; }` —
defensive, and it is exactly what a version field is for.

**ADs both satisfy:** AD-31, AD-33.

**The incompatibility.** The day Unit 1 bumps to 2, Unit 2's consumer silently drops every
`ride.matched` — no error, no dead letter, an audit trail with a hole in it and a dashboard stuck
on a stale count. AD-33 tells consumers to "ignore unknown fields" but says nothing about ignoring
unknown versions.

**Closing AD.** Tighten AD-31/AD-33: *`schema_version` is scoped to `event_type`, starts at 1, and
increments only on an additive change. It is **advisory metadata for humans and replay tooling
only** — no consumer may branch on it, filter on it, or reject an event because of it. A consumer
that cannot process an event must fail loudly to a dead-letter path, never skip silently.*

## B9. WebSocket delivery (FR-45/FR-50) collides with consumer-group semantics and AD-36's `earliest`

**Unit 1 — Story "Driver offer push" (`driver-service`, FR-45).** The offer is created by
`matching-service`; the WebSocket is held by `driver-service` (Capability map). The graph shows
`KB -. events .-> DS`. The developer creates a consumer group `driver-service-push` and pushes
`ride.offered` to the driver's socket. Correct and idiomatic.

**Unit 2 — Story "Dashboard push" (`audit-service`, FR-50).** Same shape, consumer group
`dashboard`.

**ADs both satisfy:** AD-33, AD-36, AD-48.

**The incompatibility.** With N > 1 replicas of `driver-service` (AD-47 lists replica counts as
derived capacity, and it is the WebSocket-terminating service, so it *will* be scaled), the
consumer group **load-balances**: partition `P(driver_x)` is assigned to replica 2, but driver
x's socket is on replica 1. The push is **silently dropped**. The offer expires 10 s later
(AD-46), the ride re-enters `WAITING_MATCH`, the next driver's push is also dropped, and the ride
reaches `NO_DRIVER` with every driver having been "offered" a ride they never saw — while AD-17
appends each of them to `declined_by`, permanently poisoning the ride for the whole fleet. This is
a total dispatch failure that only appears at replica count 2 and looks like a supply problem.

The natural fix — one unique consumer group per replica so all replicas see all events — collides
head-on with AD-36's "**New consumer groups start at `earliest`**": every pod restart creates a
new group, replays the entire topic from the beginning, and re-pushes every historical offer to
every connected driver.

**Closing AD.** New AD: *events consumed to drive a **connection-bound** push (FR-45, FR-50) are
broadcast, not load-balanced: each replica subscribes with a per-instance group id, `auto.offset.
reset = latest`, and does not commit offsets — a socket-bound push has no meaning for a message
that predates the socket. AD-36's `earliest` default applies to **durable projection** consumers
(audit, analytics, dashboard state) only, and this distinction is explicit in each consumer's
config. A push failure never mutates ride or dispatch state: offers are always visible via the
FR-21 read path, and the WebSocket is an accelerator, never the delivery guarantee.*

## B10. AD-21's "never by loss of signal" versus FR-13's release leaves a driver permanently `BUSY`

**Unit 1 — Story "Silent-driver recovery before pickup" (FR-13, AD-16).** AD-16: "`BUSY` … cleared
on release or completion". The MATCHED-staleness sweep releases the ride *and the driver*, so the
developer sets `status = 'AVAILABLE'`, reading the sweep as part of "the ride lifecycle".

**Unit 2 — Same story, different developer.** AD-21 is unambiguous: "dispatch status is written
only by the driver's own action, the ride lifecycle, or session expiry — **never** by loss of
signal." A staleness sweep *is* loss of signal — that is its trigger and its name. This developer
releases the ride but leaves the driver `BUSY`, expecting session expiry to be the only automated
status writer.

**ADs both satisfy:** AD-16, AD-21, AD-22.

**The incompatibility.** Unit 2's driver is stuck `BUSY` with no ride, forever. They are not
matchable (`BUSY`), so they receive nothing; they cannot be rescued by AD-22, because AD-22
"applies only to drivers holding **no ride**" — and here the ambiguity compounds: is a `BUSY`
driver with `current_ride_id IS NULL` "idle"? Dev X's sweep filters `status = 'AVAILABLE'`
(never touches them); Dev Y's filters `current_ride_id IS NULL` (expires them to `OFFLINE`, which
at least unsticks them, but does so for a driver who is actively driving in Unit 1's world, since
in Unit 1 a `BUSY` driver's `current_ride_id` may legitimately be null between offer and accept).
So "idle" is a load-bearing undefined term with two readings that disagree about which drivers get
forcibly logged out.

**Closing AD.** Tighten AD-21 and AD-22: *"the ride lifecycle" **includes** system-initiated ride
transitions (offer expiry, FR-13 recovery, FR-14 auto-completion) — these write dispatch status
because they are ride transitions, not because signal was lost. AD-21's prohibition means only
this: **the absence of a heartbeat, on its own, never writes status**. AD-22's "idle" is defined
precisely as `status = 'AVAILABLE'`; a `BUSY` driver is never session-expired, and the invariant
`status = 'BUSY' ⇒ an OFFERED, MATCHED or IN_PROGRESS ride references this driver` is asserted in
tests and alerted on.*

## B11. Going offline with an outstanding offer: append to `declined_by` or not?

**Unit 1 — Story "Go offline while `OFFERED`" (FR-20, AD-16).** "with an offer still outstanding
(`OFFERED`) the offer is released and the driver goes offline". This developer does **not** append
to `declined_by` — the driver ended a shift, they did not refuse the ride.

**Unit 2 — Story "Offer expiry sweep" (AD-17).** "Offer timeouts append to it as well as explicit
declines." This developer generalises to every non-acceptance, including go-offline, because
AD-17's stated purpose is preventing re-offer ping-pong.

**ADs both satisfy:** AD-16, AD-17, AD-20.

**The incompatibility.** Unit 1's driver goes offline, comes back online 20 s later (well inside
the 60 s `NO_DRIVER` window), is still the nearest driver, and is re-offered the identical ride —
the exact ping-pong AD-17 exists to prevent, now via a path AD-17 never enumerated. Unit 2's
driver is permanently excluded from that ride even though they never saw it. The two units produce
different `declined_by` contents for the same physical sequence, which changes matching outcomes
and is visible in the audit trail.

**Closing AD.** Tighten AD-17: *`declined_by` records **every** way an offer ended without
acceptance — explicit decline, offer timeout, go-offline while `OFFERED`, and FR-13 release of a
`MATCHED` ride. The rule is: any driver who has held this ride and not delivered it is never
offered it again. There is exactly one release routine and it always appends; no caller may
release without appending.*

## B12. Driver-status projections are keyed by ride id, so they have no ordering guarantee

**Unit 1 — Story "Ride events" (matching-service).** AD-31: `entity_id` is the entity; AD-36:
producers key by entity id. A `ride.offered` / `ride.matched` / `ride.completed` event has
`entity_type = RIDE` and is keyed by `ride_id`. Every one of these also changes a **driver's**
dispatch status (AD-16).

**Unit 2 — Story "Drivers by status" (FR-50 dashboard) / "Driver utilisation" (FR-44).** Derives
driver status from those ride events, because that is where the transitions are recorded.

**ADs both satisfy:** AD-16, AD-31, AD-36.

**The incompatibility.** Two consecutive engagements of the same driver (`ride_A.completed` then
`ride_B.offered`) are keyed by two different ride ids, land on two different partitions, and are
consumed in arbitrary relative order. The projection applies `offered` (→ BUSY) then `completed`
(→ AVAILABLE) and reports a driver as available while they are on a trip. FR-46 explicitly fixes
"drivers online" as a definition that is "easy to get subtly wrong" — this is the mechanism by
which it goes wrong, and no AD prevents it. FR-44's utilisation percentages inherit the same
disorder.

**Closing AD.** New AD: *a dispatch-status transition is emitted as its own `driver.*` event with
`entity_type = DRIVER`, `entity_id = driver_id`, written to the same outbox row-set in the same
transaction as the ride transition that caused it, and carrying `caused_by_ride_id`. Driver-state
projections consume `driver.*` only and never derive status from `ride.*`. A state change that
concerns two entities emits two events, one per entity, so each entity's stream is complete and
ordered.*

## B13. `rides.driver_id` and `drivers.current_ride_id` are the same fact with two owners

**Unit 1 — Story "Release driver on offer expiry".** `UPDATE drivers SET status='AVAILABLE',
current_ride_id=NULL WHERE current_ride_id = :ride_id` — carries expected prior state and the
acting entity, satisfying AD-15.

**Unit 2 — Story "Release driver on cancel".** `UPDATE drivers SET status='AVAILABLE' WHERE id =
:driver_id AND status='BUSY'` — also carries expected prior state and identity, also satisfies
AD-15. Leaves `current_ride_id` dangling at the cancelled ride.

**ADs both satisfy:** AD-2 (both rows in one database, one transaction — AD-2 actively *permits*
the duplication), AD-3, AD-15.

**The incompatibility.** After a mix of both stories, `drivers.current_ride_id` and
`rides.driver_id` disagree. AD-16's go-offline guard "reads the **ride's** state" — but does not
say how the ride is found. Dev A finds it via `drivers.current_ride_id`; Dev B via
`rides WHERE driver_id = :d AND status IN (…)`. Once the pair has diverged, the same driver gets
"you may go offline" from one endpoint and "refused" from another. AD-4's "the owning row is
authoritative" resolves cache-vs-row, not row-vs-row inside one database.

**Closing AD.** Tighten AD-3/AD-15: *`rides.driver_id` is the sole authority for the ride↔driver
association. `drivers.current_ride_id` is a denormalisation maintained only for the
lock-ordering-friendly guard, is always written in the same transaction, and is **never read to
answer a question** — every ride-state question is answered from `rides`. Release is a single
routine, used by every release path, that writes both columns and appends to `declined_by`
(B11).*

## B14. AD-48's "loses no data" depends on Kafka retention, which no AD pins

**Unit 1 — Story "Kafka manifests" (`deploy/`, AD-49).** Declares the KRaft cluster. Nothing in
the spine specifies topic retention or `offsets.retention.minutes`, so the developer takes the
defaults — 7 days for both.

**Unit 2 — Story "Tier 3 is optional" (AD-48).** "Tier 3 … may be disabled with nothing breaking,
and **loses no data** because consumer offsets survive and the backlog drains on return."

**ADs both satisfy:** AD-36, AD-48, AD-49.

**The incompatibility.** Disable `audit-service` for the length of a holiday, and (a) segments
older than the retention are deleted, so the audit trail — which AD-29 designates as *the durable
record*, since outbox rows are deleted on publish — has a permanent hole, contradicting AD-48
verbatim; and (b) the consumer group's offsets expire, so on return the group is reset by
`auto.offset.reset`, which AD-36 sets to `earliest`, replaying everything still retained. Audit
dedupes on `event_id` (AD-36) and survives; the FR-50 dashboard projection, which counts, does
not.

**Closing AD.** Tighten AD-48/AD-49: *Kafka topic retention and `offsets.retention.minutes` are
declared explicitly in `deploy/` and must exceed the longest Tier-3 outage the system claims to
ride out — the same "derive it, don't default it" discipline as AD-35's outbox bound. AD-48's
"loses no data" is true only within that window, and the window is stated. Every projection
consumer is idempotent by construction (upsert on `(entity_id, event_id)` or a recomputable
projection), never by incrementing a counter, so a replay converges instead of doubling.*

---

# Tier C — Smaller but real

## C1. The relay is built three to five times, from scratch

The Source Tree says "Duplicated domain code across services is accepted; a shared library is
not", and AD-28 puts an outbox + relay "in every producer" (Capability map). So the most subtle
component in the system — the one carrying A2, A3 and A4 — is independently reimplemented in
`matching-service`, `driver-service` and `payment-service`. The three divergences in A3 are not
hypothetical; they are the expected outcome of the rule. **Closing AD:** *the relay is not domain
code. The no-shared-library rule covers the **domain model**; the outbox schema, claim query,
backoff, breaker placement, dead-lettering and topic routing are specified as an exact,
copy-verbatim reference implementation in the spine's companion, and any deviation is an
architecture change, not a service choice.* (A copied file with a fixed contract beats three
independent designs; a library is still avoidable.)

## C2. AD-40's ETag has no defined derivation and no version column exists

Dev A: `ETag = hash(response body)`. Dev B: `ETag = W/"<rides.updated_at>"`. Both satisfy AD-40.
Dev A's ETag churns whenever JSON field ordering changes and differs between the FR-5 (by id) and
FR-4 (active ride) endpoints for the same ride, so a client polling both thrashes its cache. Dev
B's is only as precise as the timestamp column, and no AD mandates that `rides` even *has* an
`updated_at` or `version`. **Closing AD:** *`rides` carries `version bigint`, incremented by every
guarded update in the same statement; `ETag` is `W/"<ride_id>-<version>"` on every endpoint that
returns a ride, derived from the row and never from the rendered body.*

## C3. The locked fare is not required to record its inputs

FR-18 locks the fare at request time; AD-3 puts `fare_rules` in `matching-service` as mutable
config that the AD-9 surge scheduler rewrites periodically. Dev A stores only
`rides.fare_amount`; Dev B stores fare, surge multiplier, base, per-km and per-minute rates. With
Dev A, FR-40 reconciliation and FR-44 analytics cannot reproduce any historical fare, and no query
can explain a charge — while AD-18's own principle ("how a state was reached is a column") points
the other way. **Closing AD:** *a ride records the full fare breakdown — the multiplier and every
rate used — at lock time. `fare_rules` is mutable configuration and is never read to explain a
past ride.*

## C4. The surge scheduler and the sweeps have no single-writer rule across replicas

AD-9 places the surge scheduler in `dispatch`; AD-20 makes matching a worker pool; AD-47 makes
replica counts derived. Nothing says the periodic jobs — surge recomputation, offer expiry, the
three staleness sweeps, `NO_DRIVER` expiry, FR-40 reconciliation — run once per cluster. Guarded
updates (AD-15) keep the *state* safe, but each replica's surge job writes `fare_rules` (not
covered by AD-15, which binds "ride and dispatch writes") and each emits a `SYSTEM` audit event
per tick, so FR-44 and FR-46 see N× the true event rate. **Closing AD:** *state-mutating scheduled
jobs are either (a) guarded by a claim on the row they act on, so concurrent replicas are safe by
AD-15, or (b) singleton-leased via a Postgres advisory lock. `fare_rules` writes and any job
emitting a per-tick audit event take the lease. Which category each job falls in is stated where
the job is specified.*

## C5. AD-20's claim scope is ambiguous and changes AD-47's derivation by an order of magnitude

"a small fixed pool **claims batches** with `FOR UPDATE SKIP LOCKED`, **processes**, and
re-claims". Dev A holds the transaction across the Redis `GEOSEARCH` and the offer update — one
transaction, lock held across a network hop. Dev B claims ids, commits, searches, then does a
separate guarded update. Both legal; AD-15's guard makes Dev B safe. But AD-47 sizes pools as
"arrival rate × **service time**", and service time differs by the Redis roundtrip plus the
`drivers` update, so the two designs derive very different pool and connection-pool sizes from the
same formula — and Dev A's design holds a Postgres connection for the whole match, which is
exactly the "queueing relocated into Postgres" AD-47 warns against. **Closing AD:** *the claim
transaction covers only the claim; candidate search and the offer transaction are separate. AD-15's
guard, not the claim lock, provides exclusivity.*

## C6. AD-24's snapshot is set at offer time but nothing says it is hidden until `MATCHED`

AD-24 copies the driver's display identity onto the ride **at offer time**; FR-6 says the rider
sees who is coming **while `MATCHED`**. Dev A (rider read endpoint) renders `driver_name` whenever
it is non-null. Dev B renders it only in `MATCHED`. Under Dev A, a rider polling at 2 s (AD-46)
sees "Alice is on her way", then Alice declines, then "Bob is on his way" — leaking the offer
sequence and showing drivers who never accepted. **Closing AD:** *the offer-time snapshot is
internal until acceptance; rider-facing reads expose driver identity only in `MATCHED`,
`IN_PROGRESS` and `COMPLETED`.*

## C7. A second owner of the driver's display name, with no invalidation

AD-3 gives `driver-service` driver identity; AD-24 copies the display name into
`matching-service.drivers` on the go-online call. A story that lets a fixture's display name
change in `driver-service` has no obligation to notify `matching-service` — no AD requires it —
so the same driver has two names. AD-24 argues the snapshot on the *ride* is "more correct — it
records who actually drove", which is right; the copy on the **dispatch driver row** has no such
justification and is simply a stale second owner. **Closing AD:** *the driver display name on the
dispatch `drivers` row is a transient carrier, refreshed on every go-online call and valid only
for the duration of that session; the durable snapshot is the one on the ride.*

## C8. AD-40's "one Redis read" forces an unsanctioned second cache

"position returns coordinates **and ETA** from one Redis read." ETA needs the pickup coordinate,
which lives on the ride in Postgres. So either the pickup is *also* cached in Redis — a cache of
ride data that AD-3, AD-4 and AD-26 never sanction and no AD gives an invalidation rule for — or
the endpoint reads Postgres too and violates AD-40 as written. Dev A caches pickup at offer time;
Dev B does two reads. Additionally, neither AD-40 nor FR-6 says what the ETA means once the ride
is `IN_PROGRESS` (ETA to pickup is meaningless then); one dev returns null, one returns ETA to
dropoff. **Closing AD:** *the position key carries the driver's position **and**, while engaged,
the coordinate they are heading to and its purpose (`TO_PICKUP` / `TO_DROPOFF`), written by the
single geo writer (B1) at offer/accept/start. ETA is computed from that pair. The position
endpoint returns `eta_seconds` with an explicit `eta_target` field.*

## C9. "Plausibly" in FR-40 / AD-44 is an unpinned threshold

AD-44 forbids an automatic voiding sweep but keeps FR-40's flag: "any hold left outstanding longer
than a ride can **plausibly** live". AD-46 does not include this constant in its tuned set. Dev A
picks 30 minutes and floods the alert channel with long trips; Dev B picks 24 hours and the alert
never fires. **Closing AD:** *add `max_plausible_ride_lifetime` to AD-46's tuned set, ordered
strictly greater than the `IN_PROGRESS` staleness window plus the capture retry budget, since any
ride exceeding both has already been auto-completed and captured — so anything still outstanding
past it is genuinely anomalous.*

## C10. `payment_intents` scale and the money round-trip

The Consistency Conventions say `DECIMAL` at rest, but Stripe's API is integer minor units, so
`payment-service` will naturally store `amount_minor bigint` while `matching-service` stores
`rides.fare_amount DECIMAL`. No scale is pinned for the `DECIMAL`. FR-40 reconciliation then
compares values of two types and two scales, across two databases it cannot join (AD-1).
**Closing AD:** *money at rest is `DECIMAL(12,2)` plus `currency CHAR(3)` in **every** service
including `payment-service`; conversion to minor units happens only at the provider boundary, in
one documented helper. Reconciliation compares `(amount, currency)` tuples.*

---

# What I could not break

Stated plainly, because padding this list would waste the exercise:

- **AD-14 (partial unique index for one active ride).** I tried the check-then-insert race, the
  cancel-then-reinsert race, and the recovery paths; the index covers every non-terminal state
  including `REQUESTED`, and a terminal transition frees it atomically. It holds.
- **AD-19 (matching reads only `WAITING_MATCH`).** The "invisible rather than guarded" framing is
  genuinely structural. The only way to break it is A1, which is an AD-43 problem, not an AD-19
  problem.
- **AD-15's fixed lock ordering (`rides` then `drivers`).** Every two-table transaction I could
  construct — accept, cancel, offer, release, complete — deadlocks only if the ordering is
  violated, and the rule is unambiguous. AD-15 also correctly kills the two-workers-one-ride race
  that C5 raises.
- **AD-17's `declined_by` array.** Concurrent appends are single-row updates under READ COMMITTED
  and serialise correctly. The gap is *which paths append* (B11), not the mechanism.
- **AD-1 / AD-7 / AD-8 / AD-12 (isolation, packaging, dependency direction, naming).** These are
  checkable, mechanical, and I could not construct two compliant-yet-incompatible readings.
- **AD-5 (gateway exposes edge services only).** Unambiguous and enforceable in one HAProxy file.
- **AD-9's package ordering.** `shared ← fare ← ride ← dispatch ← quote` is acyclic and the
  surge-scheduler placement argument is correct: the scheduler writes `fare_rules` from above,
  `fare` reads it from below, no cycle.
- **AD-42 (typed, transit-only token).** The masked value type plus "never persisted" is a real
  constraint that survived every laundering path I tried — including, incidentally, forbidding the
  broken half of B4, though only as a side effect.

---

# Summary of proposed AD changes

| # | Change | Kind |
| --- | --- | --- |
| A1 | AD-43: `AlwaysAuthorised` is deploy-time-only, never a runtime breaker fallback | tighten |
| A2 | AD-35: backlog age over claimable rows only; dead rows moved out of `event_outbox` | tighten |
| A3 | New: relay never uses a high-water-mark cursor; identity gaps are normal | new |
| A4 | New: per-entity ordering — a failing row parks its entity; `SKIP LOCKED` claiming | new |
| A5 | New: authoritative `last_heartbeat_at` on the owning row (or TTL > session expiry); missing key = `unknown`, never `stale`; sweeps suppressed on Redis cold start | new |
| A6 | AD-45/AD-46: `matching_deadline` stamped once; `NO_DRIVER` > `MATCHED` staleness + one offer timeout | tighten |
| B1 | New: `matching-service` is the sole writer of the Redis geo set and position keys | new |
| B2 | AD-38: zero rows is never `ABORTED`; classify by re-read; `ABORTED` = SQLSTATE 40001/40P01 only | tighten |
| B3 | AD-44: idempotent intent row keyed by `ride_id`; void as an upsert-driven rule over both facts | tighten |
| B4 | New: commands to payments are gRPC, outcomes are events; `payment-service` never consumes `ride.*` | new |
| B5 | New: one topic per `entity_type`; relay routes on `entity_type`; no auto-create | new |
| B6 | New: closed, uppercase `entity_type` / `actor_type` vocabulary; `actor_id` NULL for SYSTEM | new |
| B7 | New: fixed payload encoding — snake_case, minor units + currency, RFC 3339, `{lat,lng}` | new |
| B8 | AD-31/AD-33: `schema_version` is advisory; no consumer may branch or filter on it | tighten |
| B9 | New: connection-bound consumers broadcast (`latest`, per-instance group, no commits) | new |
| B10 | AD-21/AD-22: system ride transitions may write status; "idle" ≡ `AVAILABLE` | tighten |
| B11 | AD-17: one release routine; every non-acceptance appends to `declined_by` | tighten |
| B12 | New: dispatch-status changes emit `driver.*` events keyed by `driver_id` | new |
| B13 | AD-3/AD-15: `rides.driver_id` is authoritative; `current_ride_id` is write-only denormalisation | tighten |
| B14 | AD-48/AD-49: declare Kafka topic and offset retention; projections idempotent by construction | tighten |
| C1 | Relay is not domain code — one copy-verbatim reference implementation | new |
| C2 | `rides.version` column; ETag derived from the row | new |
| C3 | Ride records the full fare breakdown at lock time | new |
| C4 | Scheduled jobs are row-guarded or advisory-lock-leased | new |
| C5 | AD-20: claim transaction covers the claim only | tighten |
| C6 | AD-24: driver identity exposed to riders only from `MATCHED` onward | tighten |
| C7 | Dispatch-row driver name is a per-session carrier, not a second owner | tighten |
| C8 | Position key carries the heading target; `eta_target` is explicit | tighten |
| C9 | AD-46: add `max_plausible_ride_lifetime` to the tuned set | tighten |
| C10 | Money at rest is `DECIMAL(12,2)` + `currency` everywhere; minor units only at the provider boundary | tighten |

**Verdict.** The spine is unusually strong on single-service correctness — the Postgres-centric
concurrency story (AD-14, AD-15, AD-17, AD-19) is genuinely watertight, and I could not break it.
Every serious hole is at a **seam**: the outbox/relay/Kafka chain, the ride↔payment edge, the
Redis writer, and the scheduled sweeps. Six of the findings (A1–A6) are not clarifications — each
produces a system that is broken in production regardless of which builder is "right", and two of
them (A2, A6) fire deterministically rather than under a race.
