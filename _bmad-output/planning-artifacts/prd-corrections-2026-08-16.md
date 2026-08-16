# Puber PRD — corrections required (2026-08-16)

Input for a `bmad-prd` **update** run. Produced by a read-only validation of the PRD against
`ARCHITECTURE-SPINE.md`, `SPEC.md` and the epic/story breakdown.

## Read this first

**The spine wins every disagreement.** `ARCHITECTURE-SPINE.md` is authoritative for every technical
decision; the PRD governs product shape. Every item below is a place the PRD contradicts a decision
the architecture run already made — so the correction is always *move the PRD to the spine*, never the
reverse. Do not "resolve" any of these by changing the spine.

**The spine was updated earlier today** and now carries `binds: FR-1–FR-51`, two new decisions —
**AD-60** (location-ping stream) and **AD-61** (rider-visible payment outcome) — a rewritten **AD-5**
(gateway route list), and no CI. Read the current spine, not a remembered one.

**Downstream documents already carry the corrected versions.** `SPEC.md` and
`_bmad-output/planning-artifacts/epics/` describe the system as the spine specifies it. The epics'
`requirements-inventory.md` records eleven requirements the spine's way with inline markers saying so.
Once this run lands, those markers and that note can be removed. **The PRD is currently the least
reliable document in the chain despite `status: final`.**

**Bump `updated:` in the frontmatter when done.**

---

## §2 — Goals & Success Criteria

- **"ClickHouse holds the full analytical copy"** → the columnar store is fed by an **independent
  parallel consumer over the same topics, never a Postgres-to-columnar copy job**, so neither store is
  derived from the other and either rebuilds from the log (AD-53). Say *"a parallel consumer feeds the
  columnar store from the same event stream; the row-oriented vs columnar comparison ships with a
  before/after benchmark."*
- **"Drivers receive ride offers over WebSocket"** → push is an **accelerator over the polling path,
  never the only delivery route for anything correctness-bearing** (AD-51). See FR-45 below.
- **"The Simulator generates reproducible, concurrent synthetic load … and tests assert matching
  correctness and state-machine integrity under that load"** → matching correctness is proven by
  **deterministic test scenarios, not synthetic traffic**. The Simulator is a phase-5 component. See
  FR-49 and `roadmap.md`.

## §3 — Features

### FR-51 — four defects in one requirement. This is the largest cluster; do it first.

The PRD was written after the architecture run and never reconciled against **AD-59** and **AD-46**.
Read both before editing. The corrected version is in `SPEC.md` and in epics Story 5.11.

1. **Case (a) refuses on rides that must never refuse.** PRD: *"the rider's previous ride is terminal
   but its payment has not settled, sitting in `INITIATED` or `AUTHORIZED`."* AD-59: only a
   **`COMPLETED`** ride refuses — *"a `CANCELLED`, `NO_DRIVER` or `PAYMENT_FAILED` ride never refuses,
   since its hold is being voided rather than captured and refusing on it would revoke AD-45's promise
   that the rider's own cancellation is their exit."* As written, **a rider who cancels during a
   provider outage is locked out.** `INITIATED` is also unreachable for a delivered trip.
2. **The bound is missing.** AD-59 refuses *"until it settles, **or until AD-46's session-expiry bound
   lapses measured from `capture_requested_at`, whichever comes first**"*, and states the lapse is not
   optional — capture pursuit is unbounded, so "until it settles" alone refuses **every rider who
   completed a trip, for the entire length of a provider outage**.
3. **The two arms are anchored differently**, which destroys the partition. PRD anchors (a) on *"the
   rider's previous **ride**"* and (b) on *"the rider's most recent **payment**"*. AD-59: **both read
   the rider's most recent ride, ordered by the `rides` identity bigint, and its single payment row**,
   with **arm 1 evaluated before arm 2**. Under the PRD's reading a rider whose latest ride is clean
   but whose earlier ride failed capture is still refused, and FR-46's per-reason counters stop summing
   to total refusals.
4. **It contradicts itself.** *"The 30-minute window is the entire clearing mechanism"* is true only of
   case (b); case (a) clears when the payment settles or the bound lapses. That claim is also the exact
   argument used to distinguish FR-51 from the deferred debtor standing, so it must be made accurately.
   Repeated in the §7 Glossary under **Capture cooldown** and **Money outstanding** — fix all three.

### Other FRs

- **FR-6** — *"the assigned driver's identity"* → **display name, never their identifier** (AD-39, AD-24).
- **FR-7** — the PRD says the rider sees an outcome *"only once the payment settles"*. **AD-61** is more
  specific: a capture still being pursued is reported as **settlement in progress**, never as
  uncaptured; only `CAPTURED`, `REFUNDED`, `CAPTURE_FAILED` and `FAILED` are outcomes to show. AD-61
  also fixes the read path — `rider-service` → `payment-service` over gRPC, answering 503 when
  `payment-service` is down rather than guessing.
- **FR-10** — *"nearest **available** driver"* → **matchable**: declared `AVAILABLE` **and** heartbeat
  fresh (AD-21).
- **FR-12** — *"a bounded **overall** window"* → a **seeking budget of accumulated time in
  `WAITING_MATCH` only, never time spent `OFFERED` or `MATCHED`** (AD-46). Measured as wall time since
  request it would be shorter than the 90 s `MATCHED` staleness window, making FR-13's salvage path
  unreachable in every case.
- **FR-26** — *"tracks current position **and availability status**"* → position only; declared
  availability is a separate fact owned by dispatch and **never written by a heartbeat** (AD-3, AD-21).
- **FR-27** — *"persist to a durable history (position audit trail)"* → now governed by **AD-60**:
  columnar store only, **no Postgres history anywhere**, and never in the audit trail, which covers
  state transitions alone. Note AD-60 also states the history **begins with the Kafka topic** and is
  never backfilled.
- **FR-45** — *"rather than polling"* → *"rather than **only** polling"*; push accelerates FR-21's
  polling path, which remains permanently in place as the correctness-bearing route (AD-51, AD-46).
- **FR-46** — the refusal-reason label *"unsettled payment"* → **"most recent completed ride not yet
  paid"** (AD-54, AD-59 arm 1). AD-59 requires the API's reason tokens and the metric's labels to be
  the same values — *"a reason added, split or renamed changes both together."* **NFR-5 carries the
  same label and needs the same edit.**
- **FR-49** — drop *"runs as an in-process test fixture early on and"*. The Simulator is delivered once,
  as a containerized component, in phase 5. `roadmap.md` and `SPEC.md` already record this.

## §4 — Non-Functional Requirements

- **NFR-3** — the dead-letter promise needs a carve-out: **the settlement pursuit of FR-37 has no retry
  cap and therefore no dead-letter path**; a settlement that cannot proceed stays a claimable row,
  watched by the oldest-retrying-capture gauge (AD-55, AD-58). Already corrected in `SPEC.md`.
- **NFR-4** — *"must be idempotent, **deduplicating on a stable event identifier**"* → *"either by
  deduplicating on a stable event identifier, or by a guarded state transition that supplies
  idempotency structurally"* (AD-36, AD-41). Read literally the PRD demands a dedupe table on the
  authorisation-outcome handler that AD-41 deliberately does without. **AD-60 adds a second case**:
  location pings carry no `event_id`, so their key is derived from the payload — `(driver_id,
  occurred_at)`.
- **NFR-8** — drop the CI clause. **There is no CI server**; the suite runs locally behind git hooks,
  with a PR to `dev`. Keep the provider-swap requirement itself, which is independently justified by
  the stress exclusion and by running before `payment-service` exists.
- **NFR-10** — *"persisted **beyond what the provider integration requires**"* → **never persisted at
  all** (AD-42). The loophole has propagated verbatim to `SPEC.md:222` and should be closed there too.

## §5 — Non-Goals / Out of Scope

- The list of a driver's exits — *"declining an offer (FR-23) or completing the ride (FR-24)"* — is
  incomplete. Add **going offline while an offer is still outstanding** (FR-20) and **being released by
  recovery** (FR-13, FR-14).
- **"Multiple vehicle types"** appears twice, once under Non-Goals and once under Deferred, with two
  different statuses. `SPEC.md` already keeps it in Deferred only.

## §6 — Roadmap

- *"Detailed week-by-week ticket breakdowns live in `docs/tickets/`"* → **those files are historical and
  non-authoritative** (addendum §"Ticket-Level Source Material", `SPEC.md` Constraints), and they still
  exist on disk so the pointer resolves and misleads. Point at
  `_bmad-output/planning-artifacts/epics/` instead.
- Phase 1's *"**in-memory** nearest-driver matching"* → **Postgres-backed, behind the
  `DriverLocationIndex` seam**; the Postgres→Redis swap is phase 2 (AD-10, AD-26). *(The same phrase is
  stale in `roadmap.md` and should be fixed there.)*
- **Phase 2 carries no milestone tag** while phases 3–5 each do, and §2 assigns it `v0.1`. Append
  *"Milestone `v0.1`."*

## §8 — Open Questions

Currently reads *"None outstanding."* Four open decisions have since surfaced and are recorded in the
epics; §8 already promises *"Additional items get added here as they surface during Epics/Stories
work."* List them, or drop the promise:

| Decision | Recorded in |
| --- | --- |
| Outbox relay claim size — batched or single-row | epics Story 4.3 |
| Reconciliation mechanism (FR-40) — has no AD at all | epics Story 5.9 |
| Storage ceilings for both stores — values only; AD-60 fixes the mechanism | epics Stories 6.4, 6.5 |
| Which environment the NFR-2 stress run targets | epics Story 7.6 |

*(A fifth — how the live dashboard is reached — was resolved by the AD-5 rewrite: operator and
observability surfaces are deliberately not gateway routes.)*

---

## Addendum (`addendum.md`) — two stale entries

- The **`payments` column list** predates AD-44 and AD-58. It describes *"a void requested before the
  authorization resolved"*, but AD-44 stamps `void_requested_at` on **any** no-trip terminal ride
  regardless of timing, and AD-58 adds `capture_requested_at`, `attempts`, `next_attempt_at` and
  `capture_failed_at`.
- The **staleness ordering** reads *"idle < matched < in-progress < session expiry"*, omitting the
  capture-failed cooldown. AD-46: *"idle < `MATCHED` < `IN_PROGRESS` < **capture-failed cooldown** <
  session expiry."* *(`SPEC.md` has the same omission — flagged for the `bmad-spec` run.)*
