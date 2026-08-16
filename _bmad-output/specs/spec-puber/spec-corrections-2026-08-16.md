# SPEC kernel — corrections required (2026-08-16)

Input for a `bmad-spec` **update** run. Last of three reconciliation passes: the architecture spine and
the PRD were both corrected earlier today, and the SPEC now trails both.

## Read this first

### Capability IDs must not change

`SPEC.md`'s own Assumptions say *"Capability IDs are this SPEC's own and are stable from here."*
**Fifty-nine stories in `_bmad-output/planning-artifacts/epics/` reference them by ID** — CAP-13,
CAP-20, CAP-23, CAP-24 and CAP-31 are cited as standing acceptance criteria across many stories, and
CAP-36, CAP-39, CAP-40 are cited by name in the epic list and requirements inventory. Renumbering or
resequencing capabilities silently breaks that traceability. **Add capabilities if needed; never
renumber existing ones.**

### Two hand-edits need the preservation validation they bypassed

Made directly on 2026-08-16 without going through this skill:

- **`SPEC.md` CAP-39** — retagged `enabler` → `slice`, and its success criterion had *"Runs as an
  in-process test fixture early and a standalone containerized generator later"* reduced to *"Runs as a
  containerized generator"*.
- **`roadmap.md`** — the "Week one, before any phase" section went from three enablers to two, and
  gained a paragraph explaining CAP-39's removal.

Both reflect a real decision: nothing before phase 5 depends on the Simulator existing, because the
matching correctness that justified week-one placement is proven by **deterministic test scenarios,
not synthetic traffic** — which is what `enabler` means and what CAP-39 no longer is. Re-validate
rather than re-litigate; the expected outcome is that both stand.

### The sources have moved

- **PRD** — corrected today (`updated: 2026-08-16`). FR-51 rewritten, FR-6/10/12/26/27/45/46/49
  corrected, NFR-3/4/8/10 corrected, roadmap and Non-Goals fixed, §8 now lists four open decisions.
  Re-distil from the current text.
- **Spine** — now `binds: FR-1–FR-51`, with **two new decisions** and a rewritten AD-5. See below.

---

## 1. SPEC's own defects — present regardless of the source updates

### D-1 · MAJOR · NFR-10's persistence loophole propagated verbatim

`SPEC.md` Constraints: *"Payment-method tokens are transit-only: never logged, never echoed in
responses, never **persisted beyond what the provider integration requires**."*

AD-42 admits no exception: the token *"is never persisted, never echoed, and passes through to
`payment-service` as the only component that talks to the provider."* The PRD carried this loophole,
the SPEC inherited it, and the PRD has now been corrected. **Strike the qualifier: never persisted at
all.**

### D-2 · MEDIUM · The staleness ordering omits the capture-failed cooldown

`SPEC.md` Constraints: *"idle < `MATCHED` < `IN_PROGRESS` < session expiry."*

AD-46: *"idle < `MATCHED` < `IN_PROGRESS` < **capture-failed cooldown** < session expiry."* The
ordering is the invariant AD-46 says must survive any retuning, so an incomplete statement of it is a
defect rather than an abbreviation. *(The same omission was in `addendum.md` and is covered by the PRD
run.)*

---

## 2. New architecture decisions the SPEC has not absorbed

Neither AD is referenced anywhere in `SPEC.md`.

### D-3 · MAJOR · AD-60 — the location-ping stream

Affects **CAP-19** (location heartbeat and fast-path reads, FR-26/27/28) and the ping half of
**CAP-34** (aggregate analytics).

`driver-service` produces every heartbeat exactly once onto a single location topic keyed by driver id.
A ping is **telemetry, not a domain event**: no outbox row, no Postgres history anywhere, and never in
`audit_events`. Every reader is an independent consumer group over that same topic — `matching-service`
maintaining Redis, `audit-service` writing the columnar ping history it owns — never a re-publish onto
a second topic, which is where AD-23's produce-time stamp would be lost. **Because a ping carries no
`event_id`, idempotency uses a key derived from the payload — `(driver_id, occurred_at)`.** Storage is
a configured ceiling enforced by evicting oldest-first, with an ingest stop as an alarmed backstop
only. **The history begins with the topic and is never backfilled** — before the HTTP→Kafka swap there
is no ping history at all.

### D-4 · MEDIUM · AD-61 — the rider's payment outcome

Affects **CAP-5** (rider-visible payment outcome, FR-7).

Served by `rider-service` as a sub-resource of the ride, making **two** calls: ride detail from
`matching-service`, and the outcome from `payment-service` by `ride_id` over gRPC. **AD-59's admission
projection is explicitly not an alternative source** — being advisory and fail-open is what makes it
right for admission and wrong for money. When `payment-service` is down this read answers 503 — no
answer, never a guessed one. **A capture still being pursued is reported as settlement in progress**,
never as uncaptured; only `CAPTURED`, `REFUNDED`, `CAPTURE_FAILED` and `FAILED` are outcomes to show.

### D-5 · MINOR · AD-5 was rewritten; two questions it now answers

The gateway carries actor-facing traffic only. `payment-service` is routable for **the Stripe webhook
alone**. **Operator and observability surfaces are deliberately not routes** — FR-39's refund trigger,
FR-50's live dashboard, Prometheus and Grafana are reached directly inside the cluster. If the SPEC
states anything about how those are reached, align it.

### D-6 · MINOR · There is no CI

The suite runs locally behind git hooks with a PR to `dev`. The spine's CI subgraph is gone and AD-43's
CI clause was restated. If the SPEC mentions CI — CAP-25's or the Constraints' provider-swap
rationale — restate it as *"running the suite with no provider credentials configured"*. **Keep the
stub's full outcome set**: the three-way capture answer and two-way void answer are independently
required by the stress exclusion.

---

## 3. Do not change

- **The `slice` / `property` / `enabler` classification of every other capability.** The epics'
  decomposition depends on it directly — properties become distributed acceptance criteria and never
  stories.
- **CAP-13, CAP-20, CAP-23, CAP-24, CAP-31 remain `property`.** They are cited as standing acceptance
  criteria across the epics; promoting any to a `slice` would imply a story that has no definition of
  done.
- **The state machines** in `state-machines.md`. Neither new AD touches the ride or payment lifecycle.
- **`roadmap.md`'s five phases, their order, and their milestone tags.** Binding, and the epics are
  sequenced against them.

## 4. Verify while you are here

- `roadmap.md` phase 1 still says *"in-memory nearest-driver matching"*. The spine has no such seam —
  it is **Postgres-backed behind the `DriverLocationIndex` strategy**, with the Postgres→Redis swap in
  phase 2 (AD-10, AD-26). The same phrase was corrected in the PRD today.
- `glossary.md` — check the **Capture cooldown** and **Money outstanding** entries against the
  corrected FR-51: both arms read one anchor (the rider's most recent ride) and its single payment row,
  evaluated in fixed order, with arm 1 bounded by session expiry measured from `capture_requested_at`.
