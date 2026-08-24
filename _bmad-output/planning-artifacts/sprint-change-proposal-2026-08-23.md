---
title: Sprint Change Proposal — Money at rest becomes integer minor units
date: 2026-08-23
trigger_story: PUB-3 (Epic 1, Story 1.3)
scope_classification: Minor
status: approved-and-implemented
approved: 2026-08-23
supersedes: review-adversarial.md finding C10 (never adopted)
resolves: review-adversarial.md finding B7 (never adopted)
---

# Sprint Change Proposal — Money at rest becomes integer minor units

## 1. Issue Summary

**Problem statement.** The Money consistency convention uses **two representations for one value** —
*"Integer minor units in transit; `DECIMAL` at rest. Never floating point."* The conversion between
them is unguarded, and the at-rest half does not actually deliver what the convention is for: it
forbids floating point without making floating point impossible.

**Issue type:** technical limitation discovered during story review — the convention's stated goal and
its chosen mechanism do not match.

**How it was discovered.** During PUB-3 story review, while explaining AC5 (*"it is integer minor units
in transit and `DECIMAL` at rest / And no floating-point type is used"*). PUB-3 is the first story that
stores money, so it is the first point the convention has to be implemented rather than read.

### Evidence

**`BigDecimal` does not prevent floating-point contamination.** Both of these compile, both look
identical in review, and one is wrong:

```java
new BigDecimal(0.1)        // 0.1000000000000000055511151231257827021181583404541015625
BigDecimal.valueOf(0.1)    // 0.1   (only because it routes via Double.toString)
```

A `long` of minor units has no constructor that accepts a `double`. The error stops being something a
reviewer must catch and becomes something nobody can write. This is the distinction
`CLAUDE.md` draws: *"Prefer a fix that makes the proof structural over one that adds another
hand-maintained list."*

| Evidence | Consequence |
| --- | --- |
| `DECIMAL` at rest requires `BigDecimal` in Java, whose `double` constructor silently imports IEEE-754 error | The convention's own prohibition is defeated by its chosen type, in a way that passes review |
| The spine pins **no precision and no scale** for `DECIMAL` (verified: zero hits for `12,2` in the spine) | Every table storing money picks its own scale, and two of them will differ |
| Stripe's API takes **integer minor units** (FR-34, Epic 5, Stripe sandbox) | Under the current convention every payment crosses a conversion at the provider boundary, for no gain |
| Money is already **exported** in minor units — AD-54: *"summed from the stored `DECIMAL` and exported in integer minor units per the Money convention"* | The system converts out of `DECIMAL` at the only place it reads money for humans, so `DECIMAL` serves no consumer |
| No production code stores money yet; `fare_rules` (PUB-3) would be the first | The change costs one edit now and six table migrations later |

**Two architecture-review findings are settled by this, neither of which was ever absorbed into the
spine.** Both are quoted from `reviews/review-adversarial.md`:

- **C10** — *"Stripe's API is integer minor units, so `payment-service` will naturally store
  `amount_minor bigint` while `matching-service` stores `rides.fare_amount DECIMAL`. No scale is
  pinned for the `DECIMAL`. FR-40 reconciliation then compares values of two types and two scales,
  across two databases it cannot join (AD-1)."* C10's own remedy was `DECIMAL(12,2)` plus
  `currency CHAR(3)` everywhere. **This proposal unifies in the opposite direction**, which is
  strictly better on C10's own terms: it removes the provider-boundary conversion rather than
  centralising it, and it does not add the `currency` column the spine deliberately rejected.
- **B7** — *"the money and coordinate conventions are genuinely ambiguous for an event payload, which
  is simultaneously in transit and at rest."* With one representation the ambiguity cannot arise: an
  event payload carries minor units whichever half of the old convention you thought applied. This
  matters from Epic 4 (`event_outbox`) and Epic 6 (`audit_events`, ClickHouse) onward.

### What this proposal does *not* claim

**Arithmetic drift is not the argument.** Ten thousand fares of `0.10` summed as `double` came to
`1000.0000000001588` — real, but `1.6e-10` and nobody loses money to it. The damage is that Epic 5
reconciles against Stripe's exact integers and FR-46 requires *"capture loss — count and summed
amount"*: a mismatch between an exact number and a drifted one is unexplainable and unfixable by
rounding, in the one report whose entire job is to be trustworthy.

## 2. Impact Analysis

### Epic impact

| Question | Finding |
| --- | --- |
| Can Epic 1 be completed as planned? | **Yes.** No story added, removed, split or resequenced |
| Epic-level change required? | Acceptance-criteria **wording** in Story 1.3, 5.1 and 5.5. No criterion is added or removed |
| Future epics affected? | **Epic 5 is simplified** — the Stripe boundary needs no conversion, and Story 5.5's *"summed from the stored `DECIMAL` and exported in integer minor units"* collapses to a plain `sum()`. **Epic 4 and Epic 6 gain a resolved ambiguity** (B7) rather than a change |
| New epics needed? | No |
| Resequencing needed? | No |
| Does this invalidate any planned epic? | No |

### Artifact conflicts

| Artifact | Impact |
| --- | --- |
| **PRD** | **No change.** Verified: the PRD names no money type, precision or representation anywhere. Representation is *mechanism*, which the spine governs |
| **SPEC / glossary / state-machines** | **No change.** Verified: no money-representation statement in any spec file |
| **Architecture spine** | **Change proposed.** The Consistency Conventions Money row (the authority for this rule) and one clause of AD-54 |
| **Epics — Story 1.3** | **Change proposed.** AC5 wording |
| **Epics — Stories 5.1, 5.5** | **Change proposed.** AC wording; 5.5 simplifies |
| **Epics — requirements-inventory.md** | **Change proposed.** The conventions summary line restates the rule |
| **`project-context.md`** | **Change proposed.** The `shared`-membership test cites the Money convention verbatim |
| **`db/migration/README.md`** | **Change proposed.** Its Conventions block states the rule for whoever writes the next migration |
| **Story file `PUB-3-*.md`** | **Change proposed.** Decision D2, AC5 guidance, Task 1.1, Task 3.1 |
| **UX** | **N/A.** No UX document exists — Puber is a backend system |
| **Review records** (`review-adversarial.md`, `review-currency.md`, `.memlog.md`) | **No change — deliberately.** These are dated records of what was found on 2026-08-13, not live rules. C10 is superseded by this proposal, not rewritten |
| **`sprint-status.yaml`** | **No change.** No epic or story added, removed or renumbered. PUB-3 stays `ready-for-dev` |
| **Code** | **None.** No production code stores money. `V1__baseline.sql` creates no tables |
| **CI/CD** | **N/A.** No CI server, by decision |

### Technical impact

**What changes.** Money columns become `BIGINT` holding minor units. Java holds money as `long`.

**What does not change, and this is the part most likely to be misread:** `BigDecimal` remains the
**computation** type, with exactly one rounding point. Integer-only arithmetic is impossible here
because the multipliers are fractional — `120` minor-units/km × `5.327` km = `639.240`, not an
integer. Distance and time are never whole numbers, so exact fractional arithmetic is unavoidable in
the middle regardless of storage type. The shape becomes `long → BigDecimal → long`, which is a better
split than `DECIMAL → BigDecimal → DECIMAL`, not the removal of a step.

**No precision is lost.** `DECIMAL(12,2)` already quantised every value to two decimal places, and
minor units quantise to exactly the same granularity. A rate of `1.20`/km stores as `120` either way.
If sub-cent rates are ever wanted, that is a separate decision under either convention. `BIGINT`
range (±9.22 × 10^18) is ~9.2 × 10^16 major units — no overflow concern.

**Two costs, stated plainly:**

1. **`BIGINT` does not self-document its scale.** `DECIMAL(12,2)` announces two decimal places in the
   schema; a `BIGINT` holding `250` does not say whether that is 250 or 2.50. Mitigated by making the
   **type the discriminator** and stating it once, prominently: `BIGINT` is money in minor units,
   `DECIMAL` is a coefficient and never a money amount. A `_minor` column suffix was considered and
   **rejected** — under a universal convention it is redundant on every column, and a naming
   convention can be forgotten on one column where a type rule cannot.
2. **Ad-hoc queries and dashboards read `1156`, not `11.56`.** Largely already true: AD-54 exports
   money in minor units today, and the wire format is minor units, so Puber never produces a
   major-unit value anywhere. Division by 100 happens at the presentation edge, outside this system.

## 3. Recommended Approach

**Option 1 — Direct Adjustment. Selected.**

| Option | Assessment |
| --- | --- |
| **1. Direct Adjustment** | **Viable — selected.** Effort **Low**, Risk **Low** |
| 2. Potential Rollback | **N/A.** Nothing is implemented. No production code stores money; PUB-3 is unstarted |
| 3. PRD MVP Review | **Not required.** No scope change, no goal change, no deferral. The PRD does not speak to money representation |

**Rationale.** The convention is being changed to do what it already says it does. Its stated
prohibition — *"Never floating point"* — is currently enforced by review alone, because the type it
mandates at rest is the type that can silently absorb a `double`. One representation makes the
prohibition structural.

**Timing is the decisive factor.** `fare_rules` is the first table in the system to store money, and
PUB-3 is `ready-for-dev` and unstarted. Landing this now costs the edits below. Landing it after
Epic 5 costs migrations on `fare_rules`, `rides`, `payments` and the `payment_standing` projection,
plus the `event_outbox`, `audit_events` and ClickHouse payloads that will have copied the old shape —
against a system where the money is the part you cannot get wrong.

**Timeline impact:** none. **Risk:** low; the only implementation risk is a reviewer reading `BIGINT`
without knowing the unit, which §4.1's explicit type rule exists to close.

## 4. Detailed Change Proposals

### 4.1 Architecture — `ARCHITECTURE-SPINE.md`, Consistency Conventions

**OLD** (line 460)

```
| Money | Integer minor units in transit; `DECIMAL` at rest. Never floating point. |
```

**NEW**

```
| Money | Integer minor units everywhere — `BIGINT` at rest, integer on the wire, `long` in Java. Never floating point, and never `DECIMAL` for a money amount. The column type is the discriminator: `BIGINT` is money in minor units; `DECIMAL` is a coefficient (a multiplier or a ratio) and never an amount. `BigDecimal` is the arithmetic type only — a calculation lifts minor units into it, rounds **once** at the end (`HALF_UP`), and returns minor units. Never construct one from a `double`: `new BigDecimal(0.1)` imports the error the rule exists to prevent. No currency dimension — see Deferred. |
```

**Rationale:** one representation, so there is no conversion to get wrong and no per-table scale to
diverge. Names the discriminator, because `BIGINT` alone does not say "minor units". Names the
`BigDecimal(double)` trap explicitly, because that is the single way floating point re-enters a system
that has banned it. Keeps `BigDecimal` where it is genuinely required rather than pretending integer
arithmetic suffices.

### 4.2 Architecture — `ARCHITECTURE-SPINE.md`, AD-54

**OLD** (within the AD-54 Rule)

```
capture loss is `count(*)` and `sum(amount)` over `CAPTURE_FAILED`, summed from the stored `DECIMAL`
and exported in integer minor units per the Money convention
```

**NEW**

```
capture loss is `count(*)` and `sum(amount)` over `CAPTURE_FAILED`, summed and exported in integer
minor units per the Money convention — one representation from the column to the gauge, with no
conversion to get wrong
```

**Rationale:** the old clause described a conversion that no longer exists. Left as-is it would read as
an instruction to convert, which is exactly the drift this proposal removes.

### 4.3 Epics — `epic-1-foundations-fare-quote.md`, Story 1.3 (AC5)

**OLD**

```
**Given** a monetary value
**When** it crosses a boundary or is persisted
**Then** it is integer minor units in transit and `DECIMAL` at rest
**And** no floating-point type is used (Money convention)
```

**NEW**

```
**Given** a monetary value
**When** it is persisted, computed with, or crosses a boundary
**Then** it is integer minor units in every one of those places — `BIGINT` in the column, `long` in Java
**And** no floating-point type is used, and no `BigDecimal` is constructed from a `double`
**And** `BigDecimal` appears only inside a calculation, which rounds once at the end (Money convention)
```

**Rationale:** the old criterion could be satisfied while holding money in a `BigDecimal` built from a
`double`. The new one closes that and states where `BigDecimal` is legitimately allowed, so a dev agent
does not read "never floating point" as "never `BigDecimal`" and hand-roll fixed-point integer maths.

### 4.4 Epics — `epic-5-payments-v0-2.md`, Story 5.1

**OLD** (line 65)

```
**Then** they are integer minor units in transit and `DECIMAL` at rest, never floating point (Money convention)
```

**NEW**

```
**Then** they are integer minor units in the column, in Java and on the wire, never floating point
**And** no conversion is needed at the Stripe boundary, which uses integer minor units itself (Money convention, FR-34)
```

**Rationale:** records the benefit at the story that collects it, so nobody reintroduces a conversion
helper believing one is required.

### 4.5 Epics — `epic-5-payments-v0-2.md`, Story 5.5

**OLD** (line 309)

```
**And** it is summed from the stored `DECIMAL` and exported in integer minor units
```

**NEW**

```
**And** it is summed and exported in integer minor units, with no conversion between the column and the gauge
```

**Rationale:** matches §4.2. This AC currently mandates a conversion that will not exist.

### 4.6 Epics — `requirements-inventory.md`, Conventions summary

**OLD** (within line 149)

```
**integer minor units in transit, `DECIMAL` at rest, never floating point**
```

**NEW**

```
**integer minor units everywhere — `BIGINT` at rest, never floating point, `DECIMAL` only for coefficients**
```

**Rationale:** this line is a restatement of the spine row. A stale copy outlives the true one.

### 4.7 `project-context.md`, "What belongs in `shared`"

**OLD**

```
Money (integer minor units in transit, `DECIMAL` at rest) and Coordinates
(`DECIMAL(10,8)`/`DECIMAL(11,8)`, WGS84, longitude first) are conventions.
```

**NEW**

```
Money (integer minor units everywhere — `BIGINT` at rest, `long` in Java, integer on the wire) and
Coordinates (`DECIMAL(10,8)`/`DECIMAL(11,8)`, WGS84, longitude first) are conventions.
```

**Plus a new bullet under the same section:**

```
- **`BigDecimal` is for arithmetic, never for storage or transport, and never built from a `double`.**
  `new BigDecimal(0.1)` stores 0.1000000000000000055511151231257827; `BigDecimal.valueOf(0.1)` stores
  0.1. Both compile and look identical in review. A calculation lifts `long` minor units into
  `BigDecimal`, rounds **once** at the end with `HALF_UP`, and returns minor units. Integer-only
  arithmetic is not an option: distance and time are fractional, so `120` minor-units/km × `5.327` km
  is `639.240`.
```

**Rationale:** `project-context.md` is loaded by every workflow, so this is the copy a future
`dev-story` run actually reads. The `BigDecimal(double)` trap needs to be where code gets written.

### 4.8 `services/matching-service/src/main/resources/db/migration/README.md`

**OLD** (line 25)

```
- Money: `DECIMAL` at rest, integer minor units in transit. Never floating point.
```

**NEW**

```
- Money: **`BIGINT`, in minor units** — 250 is 2.50. Never `DECIMAL` for an amount, never floating
  point. The type is the discriminator: `BIGINT` is money, `DECIMAL` is a coefficient (a multiplier or
  a ratio) and never an amount. There is no currency column.
```

**Rationale:** this file sits beside the migrations and is the one thing whoever writes `V4` will read.
It is also where the "`BIGINT` does not self-document its scale" cost is mitigated.

### 4.9 Story file — `PUB-3-fares-are-computed-from-configurable-rules.md`

| Location | Change |
| --- | --- |
| **AC5** | Replace with §4.3's text |
| **Decision D2** | Retitle *"Money at rest is `BIGINT` minor units"*. Replace the `DECIMAL(12,2)` reasoning with this proposal's; record that C10's `DECIMAL(12,2)` is now superseded rather than merely unadopted |
| **Decision D3** | Unchanged — `surge_multiplier` stays `DECIMAL(4,2)`. Add one line: it stays `DECIMAL` **because it is a coefficient, not money**, which is now the spine's stated discriminator |
| **Decision D4** | Unchanged in substance (one rounding point, `HALF_UP`). Update the code sketch to round to whole minor units and return `long` |
| **Decision D6** | Seed values become `250` / `120` / `25`, with the major-unit equivalent in a comment |
| **Task 1.1** | `Money` wraps `long minorUnits`. Remove `ofMajorUnits(BigDecimal)`; add the prohibition on any `double`-accepting factory |
| **Task 3.1** | `base_fare`, `per_km_rate`, `per_minute_rate` become `BIGINT NOT NULL`; `surge_multiplier` stays `DECIMAL(4,2)` |
| **Task 6.1** | Hand-computed expectation becomes `1100` minor units for the 5 km example |
| **Task 7.2** | The no-floating-point ArchUnit rule gains a second clause: **no production class constructs a `BigDecimal` from a `double`.** This is now mechanically checkable, which the old convention could not be |
| **Dev Notes → AC5 explanation** | Add the plain-language gloss of the convention (what minor units are, why one representation, where `BigDecimal` is still allowed) |

**Rationale:** Task 7.2's new clause is the point of the whole change — the prohibition becomes a rule
that can fire, and `CLAUDE.md` requires it to be proven by planting `new BigDecimal(0.1)`, watching the
suite go red, and reverting.

## 5. Implementation Handoff

**Scope classification: Minor** — direct implementation, no backlog reorganisation, no replan. No story
added, removed, split or resequenced; no epic resequenced; MVP unaffected.

| Artifact | Action | Owner |
| --- | --- | --- |
| `ARCHITECTURE-SPINE.md` | Conventions Money row (§4.1); AD-54 clause (§4.2) | This workflow |
| `epics/epic-1-foundations-fare-quote.md` | Story 1.3 AC5 (§4.3) | This workflow |
| `epics/epic-5-payments-v0-2.md` | Story 5.1 AC (§4.4); Story 5.5 AC (§4.5) | This workflow |
| `epics/requirements-inventory.md` | Conventions summary line (§4.6) | This workflow |
| `project-context.md` | `shared` membership line + new `BigDecimal` bullet (§4.7) | This workflow |
| `db/migration/README.md` | Conventions block (§4.8) | This workflow |
| `implementation-artifacts/PUB-3-*.md` | Nine edits per §4.9 | This workflow |
| Review records | **No change** — historical, deliberately left | — |
| `sprint-status.yaml` | **No change** — PUB-3 stays `ready-for-dev` | — |
| Implementation | Dev agent, when PUB-3 is developed | `dev-story` |

### Success criteria

1. `ARCHITECTURE-SPINE.md` states one money representation, and names the `BIGINT`-vs-`DECIMAL`
   discriminator explicitly.
2. No live artifact says *"`DECIMAL` at rest"* for money. (`grep -rn "DECIMAL. at rest"` returns only
   the dated review records.)
3. `fare_rules` ships with three `BIGINT` money columns and one `DECIMAL(4,2)` coefficient.
4. An ArchUnit rule fails the build on `new BigDecimal(<double>)` in production code, **proven by
   planting the violation, capturing the red, and reverting** — not by inspection.
5. `make build` and `make test` both pass, and the result is reported including the known
   `HealthReportsDownPromptlyIntegrationTest` environmental red.

### Sequencing

All seven artifact edits land before `dev-story` runs on PUB-3. No dependency on any other story. Epic
5's edits (§4.4, §4.5) are wording-only and touch no story that has started.

## 6. What was considered and rejected

| Considered | Rejected because |
| --- | --- |
| **A `_minor` column suffix** (`base_fare_minor`) | Redundant once minor units are universal — there is no second representation to disambiguate from. A naming convention can be forgotten on one column; the type rule in §4.1 cannot. Note that `review-adversarial.md` instinctively wrote `amount_minor` *precisely because* the two representations coexisted at the time |
| **C10's `DECIMAL(12,2)` + `currency CHAR(3)` everywhere** | Centralises the provider-boundary conversion rather than removing it, and adds a currency dimension the spine rejected on purpose — its own run log records dropping one because it *"invented a dimension no entity has"* |
| **Integer-only arithmetic, no `BigDecimal` at all** | Impossible. Distance and time are fractional, so `120` minor-units/km × `5.327` km = `639.240`. Hand-rolled fixed-point scaling would replace one clear rounding point with several implicit ones |
| **Keeping `DECIMAL` at rest and relying on the new ArchUnit rule** | The rule can ban `new BigDecimal(double)`, but `DECIMAL` at rest still requires a conversion at every boundary and still pins no scale — so C10's reconciliation problem and B7's event-payload ambiguity both survive |
| **Deferring the decision to Story 3.2** (first table storing a charged fare) | PUB-3 sets the precedent 3.2 copies. Deferring means either 3.2 follows a convention it disagrees with, or the system carries two |
