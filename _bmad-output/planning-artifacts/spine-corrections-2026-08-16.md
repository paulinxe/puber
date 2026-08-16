# Architecture spine — corrections required (2026-08-16)

Input for a `bmad-architecture` **update** run. Produced by a read-only validation of the PRD against
`ARCHITECTURE-SPINE.md`, `SPEC.md` and the epic/story breakdown, plus decisions taken during the
epics-and-stories run on 2026-08-16.

**The spine is authoritative and stays authoritative.** Nothing below asks it to follow the PRD; every
item is either a gap in the spine or a place the spine already decided something its own frontmatter,
map or diagram does not reflect. The PRD is being corrected separately, *against* the spine.

**Context the spine does not know about yet:**
- `_bmad-output/planning-artifacts/epics.md` was sharded into `_bmad-output/planning-artifacts/epics/`
  (11 files, 59 stories across 7 epics). That breakdown is derived from this spine.
- Its `requirements-inventory.md` currently records **eleven requirements as the spine specifies them
  rather than as the PRD words them**, marked inline. Those are PRD defects, not spine defects.

---

## 1. Traceability gaps — the spine does not claim work it does

### C-1 · MAJOR · FR-51 is outside the spine's declared scope and bound by no AD

- Frontmatter reads `binds: FR-1–FR-50` and `scope: … the system specified by PRD FR-1–FR-50 and
  NFR-1–NFR-10`. The string `FR-51` appears **zero times** in the whole document.
- AD-59 is unmistakably FR-51's implementation, but its `Binds:` line names only *"FR-3, ride request
  admission, `matching-service`, the payment topics"*.
- AD-46 defines the `CAPTURE_FAILED` cooldown, also FR-51's, and does not cite it either.
- The Capability → Architecture Map covers *"Quote, request, cancel, rider reads (FR-1–FR-8)"* and
  never names FR-51.

**Effect:** any automated traceability check reports FR-51 as unimplemented.

**Correction:** `binds: FR-1–FR-51`; same in `scope`; add FR-51 to AD-59's and AD-46's `Binds:`; add it
to the map row.

---

## 2. Genuine architecture gaps — no AD owns the decision

### C-2 · MAJOR · No AD covers the location-ping stream, its store, or its retention

Binds FR-27, and the ping half of FR-44.

- AD-53 governs `audit_events` only.
- AD-3 gives `audit-service` *"`audit_events` and the ClickHouse tables"* but names no ping table, no
  producer, no topic, no consumer group, and no ceiling.
- AD-26, AD-27 and AD-28 between them remove **every durable write** from the heartbeat path — Redis
  holds no persistence, and heartbeats never use the outbox.
- The map row *"Location, reachability, session expiry (FR-26–FR-30) | `driver-service` → Redis via
  Kafka | AD-21, AD-22, AD-23, AD-26, AD-27"* cites five ADs, **none of which persists anything**.

**Effect:** at roughly 1.3M pings/day this is the largest data stream in the system and it is
architecturally unowned. Story 6.5 in the epics has already been forced to author the design inside
acceptance criteria — columnar-only home, ceiling derived as ingest rate × window, oldest-first
eviction, ingest-stop as an alarmed backstop — citing only generic AD-47/AD-35/AD-48.

**Correction:** a new AD (or a second rule inside AD-53) fixing: heartbeat → Kafka topic → independent
consumer group → columnar table; its owner; its idempotency key; and its retention/eviction policy.
Bind FR-27 and the ping half of FR-44 to it. See also C-6.

### C-3 · MEDIUM · FR-7's rider-visible payment outcome has no read path

- The map row asserts *"Rider-visible payment outcome (FR-7) | `payment-service` → `rider-service` |
  AD-50, AD-58"*.
- The dependency graph has `RS -->|gRPC| MS` and `PS -->|gRPC| MS`. **There is no
  `payment-service → rider-service` edge.**
- The only copy of payment state outside `payment-service` is AD-59's projection, which the spine
  itself calls *"advisory"*, *"authoritative for nothing"*, and *"fails open, never closed"* — all
  properties that make it unsuitable for telling a rider whether their money moved.

**Correction:** name the read path. Either extend the projection with an explicit staleness contract
scoped to FR-7, or add a `rider-service → payment-service` edge to the graph and reflect it in AD-37
and AD-5.

### C-4 · MEDIUM · AD-5's route list predates two things that need routes

AD-5: *"the gateway routes **only** to `rider-service`, `driver-service`, the Stripe webhook, and
audit's query API."*

- **FR-39's refund trigger** — *"an internal operator-facing call"*. No AD says whether that is a fifth
  gateway route, a direct hit on `payment-service`, or an out-of-band operation.
- **FR-50's live dashboard** — not in the list either. The consistent reading is that it is reached
  directly, as Prometheus and Grafana are, but AD-5 predates it. Raised as an open question in epics
  Story 7.2.

**Correction:** resolve both in one edit to AD-5 — either the list grows, or the rule states explicitly
that operator and observability surfaces are reached outside the gateway.

---

## 3. Decisions taken downstream that the spine should absorb

### C-5 · MEDIUM · CI has been dropped from the project

The team will run tests locally behind git hooks, with a PR to `dev` and merge. There is no CI server.

- The deployment diagram's `LOCAL --> CI --> K8S` flow and its `CI` subgraph no longer describe reality.
- AD-43's rationale cites CI: *"the same swap covers running before payments are built, and in CI
  without provider credentials."*

**Correction:** remove the `CI` subgraph from the diagram. In AD-43, keep the stub requirement — it is
independently justified by NFR-8's stress exclusion and by running before `payment-service` exists —
but restate the third case as *"running the suite with no provider credentials configured"* rather than
*"in CI"*. **Do not weaken the stub requirement itself; CI was a beneficiary of it, never its reason.**

### C-6 · MEDIUM · AD-47's deferred-capacity list does not cover storage

AD-47 and the Deferred section name pool sizes, replica and partition counts, the outbox bound, backoff
base, the outbox retry cap and the capture backoff ceiling — but **no disk bound for Postgres or
ClickHouse**. This is a dimension nobody wrote down, not one the spine deferred.

**Correction:** add storage ceilings to the deferred-capacity list, with the same derivation method the
other values use (rate × window, with headroom, confirmed under the NFR-2 stress run). Pairs with C-2.

---

## 4. Open decisions from the epics that may want to become ADs

Recorded in the epic files; listed here because the spine is where mechanism belongs.

| Decision | Where recorded | Spine relevance |
| --- | --- | --- |
| Outbox relay claim size — batched or single-row | Epic 4, Story 4.3 | AD-34 says *"the batch commits once"*; the invariant is per-row outcome isolation. If a **fixed** single-row design is chosen rather than a configured claim size, AD-34 should be amended rather than diverged from. Any answer should apply consistently to all three claim-loop workers — AD-20, AD-29/34, AD-58. |
| Reconciliation mechanism (FR-40) | Epic 5, Story 5.9 | **Has no AD at all.** AD-50/58/59 specify the payment machine to the level of literal SQL predicates; reconciliation has FR-40 and one clause of AD-44. If the answer introduces mechanism, it belongs here. |
| Storage ceilings for Postgres and ClickHouse | Epic 6 preamble, Stories 6.4/6.5 | See C-6 and C-2. |
| How the live dashboard is reached | Epic 7, Story 7.2 | See C-4. |
| Which environment the stress run targets | Epic 7, Story 7.6 | AD-47's values come from that run; a kind cluster competing for the machine's resources may distort them. |

---

## 5. Do not change

- **The stub provider's full outcome set** (AD-43, AD-58) — three-way capture answer and two-way void
  answer. C-5 removes CI, not this.
- **AD-59's fail-open behaviour and its advisory status.** C-3 must not be solved by making the
  projection authoritative.
- **AD-13, AD-50 state machines**, AD-46's ordering invariant, AD-14, AD-15, AD-19, AD-26's
  absence-is-not-staleness rule. No finding touches these; they are cited here only because the epics
  depend on them heavily and a broad rewrite would invalidate 59 stories.
