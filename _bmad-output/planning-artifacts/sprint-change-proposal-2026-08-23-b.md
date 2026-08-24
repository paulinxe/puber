---
title: Sprint Change Proposal — The assumed speed is stated as 30 km/h, not 8.33 m/s
date: 2026-08-23
trigger_story: PUB-3 (Epic 1, Story 1.3)
scope_classification: Minor
status: approved-and-implemented
approved: 2026-08-23
ratified_by_owner: the 30 km/h speed serves BOTH driver-to-pickup ETA and trip duration
change_type: clarification — un-rounds an existing constant, does not choose a new speed
---

# Sprint Change Proposal — The assumed speed is stated as 30 km/h, not 8.33 m/s

## 1. Issue Summary

**Problem statement.** The fixed assumed speed is specified as `8.33 m/s`. That value is **30 km/h
rounded to three significant figures**, and every document that states it also glosses it as 30 km/h.
Specifying the rounded form rather than the round one forces a repeating-decimal conversion into the
implementation, in a system whose every other distance quantity is expressed in kilometres.

**This is a clarification, not a decision reversal.** No document ever decided on 8.33 m/s as such;
they decided on "about 30 km/h" and wrote the m/s conversion beside it. The proposal makes the stated
constant the intended one.

**Issue type:** technical limitation discovered during story review — a specified constant is the
lossy form of its own intent.

**How it was discovered.** During PUB-3 story review, working through decision D1 (units). The fare
formula multiplies a **per-kilometre** and a **per-minute** rate, so the implementation must convert
haversine metres into both kilometres and minutes. With the speed in m/s those are two independent
conversion chains from one source; with it in km/h they are one.

### Evidence

**The arithmetic.** `8.33` is the derived value:

```
30 km/h   ->  8.3333333... m/s   (repeating)
8.33 m/s  ->  29.988 km/h
```

**The documents already say which one is the intent.** In both places the number is glossed, the gloss
is 30 km/h and the `~` is attached to it rather than to the conversion:

| Source | Exact wording |
| --- | --- |
| `specs/spec-puber/glossary.md:18` | *"a fixed assumed speed (**~30 km/h** / 8.33 m/s)"* |
| `prds/prd-puber-2026-08-02/addendum.md:16` | *"`haversine(pickup, driver) / 8.33 m/s` (**~30 km/h**)"* |
| `prds/prd-puber-2026-08-02/prd.md:178` | *"a fixed assumed speed"* — **names no number at all** |

**Minutes per kilometre**, which is the figure the fare formula actually needs:

| Via | Minutes per km |
| --- | --- |
| `8.33 m/s` | `2.000800320128051220488195278` |
| `30 km/h` | `2`, exactly |

**Consequences of the rounded form**, each of which the round form removes:

| Consequence | Detail |
| --- | --- |
| Two conversion chains instead of one | `8.33 m/s` needs metres→km **and** metres→seconds→minutes. `30 km/h` needs metres→km, then km→minutes, and the second step is exact |
| A repeating decimal in the code | `2.0008003...` cannot be written down exactly, so it becomes either a truncated literal or a comment nobody can verify |
| The fare's term degeneracy is inexact | The effective per-kilometre rate is `170.0200080032012805122048820` rather than `170`. PUB-3 documents this degeneracy deliberately (the time term is proportional to the distance term because the speed is fixed); an exact figure is checkable, a repeating one is not |
| It is the only non-km distance quantity in the system | The matching radius is 5 km (AD-26 `GEOSEARCH ... BYRADIUS 5 km`), the fare rates are per-km, AD-25's bounds are described in km. This constant was the sole exception |
| It contradicts the project's own coding standard | `AGENTS.md`'s No Magic Numbers section lists its example constants as `MAX_MATCHING_RADIUS_KM`, **`AVERAGE_SPEED_KMH`**, `SCHEDULER_INTERVAL_MS`, `OFFER_TIMEOUT_SECONDS`. The standard already assumed a km/h speed constant |

### Measured impact on fares

Seeded rates from PUB-3 D6 (`base 250`, `per_km 120`, `per_minute 25`, `surge 1.00`), in minor units:

| Trip | via `8.33 m/s` | via `30 km/h` | Difference |
| --- | --- | --- | --- |
| 1 km | 420 | 420 | 0 |
| 5 km | 1100 | 1100 | 0 |
| 10 km | 1950 | 1950 | 0 |
| 25 km | 4501 | 4500 | −1 |
| 50 km | 8751 | 8750 | −1 |
| 100 km | 17252 | 17250 | −2 |

**0.04% on the time term; at most two cents on a 100 km trip.** Stated in proportion: this sits inside
a far larger approximation the project accepts on purpose. Haversine straight-line distance is
materially below real road distance, so Puber's fares already diverge from a real operator's by orders
of magnitude more than this. Changing the time term by 0.04% is noise inside noise.

## 2. Impact Analysis

### Epic impact

| Question | Finding |
| --- | --- |
| Can Epic 1 be completed as planned? | **Yes.** No story added, removed, split or resequenced |
| Epic-level change required? | Acceptance-criteria **wording** in Story 1.3 (AC4) and Story 2.6 (the ETA AC). No criterion added or removed |
| Future epics affected? | **No.** The constant appears in no epic after Epic 2 |
| New epics needed? | No |
| Resequencing needed? | No |

**Story 1.3 and Story 2.6 must move together.** They use the *same* constant for different purposes —
1.3 for trip duration in the fare, 2.6 for driver-to-pickup ETA. Changing one and not the other puts
two speeds in one system, which is strictly worse than either value alone. PUB-3 already carries the
instruction that Story 2.6 must **move** this constant into `shared` rather than copy it; this proposal
keeps that instruction and only changes the constant's units.

### Artifact conflicts

| Artifact | Impact |
| --- | --- |
| **`SPEC.md`** | **Change proposed** — `#Constraints`, one line. This is the preservation-validated contract, which is why this proposal exists rather than a direct edit |
| **`glossary.md`** | **Change proposed** — the ETA definition; drops the now-redundant dual statement |
| **`addendum.md`** | **Change proposed** — the ETA formula row |
| **PRD** | **No change.** Line 178 says *"a fixed assumed speed"* and names no number — nothing to correct |
| **Architecture spine** | **No change.** Verified: `8.33` and `haversine` appear **zero** times in the spine. Its only distance mechanism is AD-26's Redis `GEOSEARCH`, already in km |
| **Epics — Story 1.3** | **Change proposed** — AC4 |
| **Epics — Story 2.6** | **Change proposed** — the ETA AC |
| **`project-context.md`** | **No change.** It states no speed |
| **Story file `PUB-3-*.md`** | **Change proposed** — D1, Task 2.2, Task 6.1, Task 8.2, and three Dev Notes passages |
| **UX** | **N/A.** No UX document exists |
| **Review records** | **No change** — historical, deliberately left |
| **`sprint-status.yaml`** | **No change.** PUB-3 stays `ready-for-dev` |
| **Code** | **None.** No production code computes distance or time |

### Technical impact

The `AssumedSpeed` type in `fare/model` holds `AVERAGE_SPEED_KMH = 30` and derives minutes from it.
**The derived `2` must not become the constant** — writing `kilometres * 2` makes the speed
unchangeable without a reader also noticing that the `2` has to move. `AGENTS.md`'s No Magic Numbers
rule requires the named constant, and the division expresses the relationship:

```java
private static final int AVERAGE_SPEED_KMH = 30;
private static final int MINUTES_PER_HOUR = 60;

BigDecimal minutesToCover(Distance distance) {
    return BigDecimal.valueOf(MINUTES_PER_HOUR)
            .divide(BigDecimal.valueOf(AVERAGE_SPEED_KMH))   // exact: 2
            .multiply(distance.inKilometres());
}
```

`divide` without a `MathContext` is safe **only because 60/30 terminates**. If the speed ever changes
to a value that does not divide 60 exactly (7 km/h, say), this throws `ArithmeticException` rather than
silently rounding — which is the correct failure, and worth a comment saying so.

## 3. Recommended Approach

**Option 1 — Direct Adjustment. Selected.**

| Option | Assessment |
| --- | --- |
| **1. Direct Adjustment** | **Viable — selected.** Effort **Low**, Risk **Low** |
| 2. Potential Rollback | **N/A.** Nothing is implemented. No production code computes distance or time |
| 3. PRD MVP Review | **Not required.** No scope, goal or deferral change. The PRD names no speed |

**Rationale.** The change makes the specified constant equal to its documented intent, removes a
repeating decimal from the implementation, halves the unit-conversion surface in the one story most
exposed to a unit error, and aligns the last non-km distance quantity with the rest of the system. The
fare impact is 0.04%, inside an approximation the project already accepts deliberately.

**Why a proposal rather than a direct edit.** `SPEC.md` is the preservation-validated contract. A
constant changing there with no recorded rationale is indistinguishable from drift, and the most
likely outcome is that a later reader "restores" 8.33 believing it was specified deliberately. The
record is the deliverable as much as the edit is.

**Timeline impact:** none. **Risk:** low. The one implementation risk — hardcoding the derived `2` —
is addressed in §4.6.

## 4. Detailed Change Proposals

### 4.1 `specs/spec-puber/SPEC.md`, Constraints

**OLD**

```
- Distance is haversine and ETA is `distance / 8.33 m/s`; no maps, routing, or navigation API is used anywhere.
```

**NEW**

```
- Distance is haversine, and one fixed assumed speed of 30 km/h — exactly 2 minutes per kilometre —
  derives **both** the driver-to-pickup ETA and the trip duration the fare prices; no maps, routing,
  or navigation API is used anywhere.
```

**Rationale:** states the intended constant in the units every other distance quantity uses, and names
the derived figure so it is not recomputed at each call site. It also **resolves a pre-existing
ambiguity rather than carrying it forward**: the old line said `distance` without saying which
distance, so Story 1.3 read it as the trip while the glossary described driver-to-pickup. Ratified by
the repo owner on 2026-08-23 — **one speed, both distances** — so the line can now say so outright.

### 4.2 `specs/spec-puber/glossary.md`, ETA

**OLD**

```
- **ETA** — estimated arrival of the *matched driver at pickup*, from haversine distance and a fixed assumed speed (~30 km/h / 8.33 m/s). Not the trip's own duration.
```

**NEW**

```
- **ETA** — estimated arrival of the *matched driver at pickup*, from haversine distance and the fixed assumed speed of 30 km/h (exactly 2 minutes per kilometre). ETA is the wait **before** pickup, not the trip's own duration — but the same speed derives that duration too, for the fare (FR-18).
```

**Rationale:** one value rather than a value plus its rounded conversion, and the `~` is dropped
because 30 km/h is exact. The clause about trip duration is **rewritten rather than preserved**. As
written it was correct about the *term* — an ETA is the wait before pickup, not how long your ride
takes — but it read as though the *speed* did not apply to trips, which is what made Story 1.3's AC4
look like it was contradicting the glossary. Both facts now sit in one sentence: the terms are
distinct, the constant is shared.

### 4.3 `prds/prd-puber-2026-08-02/addendum.md`, Constraints That Still Hold

**OLD**

```
| ETA formula: `haversine(pickup, driver) / 8.33 m/s` (~30 km/h) | Simplicity over routing-API realism |
```

**NEW**

```
| ETA formula: `haversine(pickup, driver) / 30 km/h`, i.e. 2 min/km | Simplicity over routing-API realism |
```

### 4.4 Epics — `epic-1-foundations-fare-quote.md`, Story 1.3 (AC4)

**OLD**

```
**Given** a pickup and dropoff coordinate
**When** distance and time are derived
**Then** distance is haversine
**And** time is `distance / 8.33 m/s`
**And** no maps or routing API is called anywhere
```

**NEW**

```
**Given** a pickup and dropoff coordinate
**When** distance and time are derived
**Then** distance is haversine, in kilometres
**And** time is `distance / 30 km/h` — the same fixed assumed speed the ETA uses, so exactly 2 minutes
per kilometre
**And** the speed is one named constant, never a per-call-site literal (AGENTS.md, No Magic Numbers)
**And** no maps or routing API is called anywhere
```

**Rationale:** *"in kilometres"* closes the unit gap that made this story's D1 decision necessary in the
first place — the formula's rates are per-km and per-minute, and nothing previously said what unit the
haversine returned. *"the same fixed assumed speed the ETA uses"* records that 1.3 and 2.6 share one
constant, which is the fact that keeps them from diverging.

### 4.5 Epics — `epic-2-driver-presence-location-tracking.md`, Story 2.6

**OLD**

```
**And** the ETA is haversine distance from the nearest matchable driver to pickup, divided by 8.33 m/s (FR-1)
```

**NEW**

```
**And** the ETA is haversine distance from the nearest matchable driver to pickup, divided by 30 km/h — the same named constant Story 1.3 introduced, **moved** into `shared` rather than copied (FR-1)
```

**Rationale:** must move with §4.4 or the system holds two speeds. The *"moved, not copied"* clause
carries forward the instruction already in PUB-3's Task 8.2 and puts it where Story 2.6's implementer
will actually read it — PUB-1's warning about longitude-before-latitude being *"the rule that gets
written correctly three times and wrongly the fourth"* applies identically to a duplicated speed.

### 4.6 Story file — `PUB-3-fares-are-computed-from-configurable-rules.md`

| Location | Change |
| --- | --- |
| **AC4** | Replace with §4.4's text |
| **Plain-words intro** | *"assumes the driver averages 8.33 m/s (about 30 km/h)"* → *"assumes the driver averages 30 km/h, so 2 minutes per kilometre"* |
| **Decision D1** | The time conversion becomes `km → minutes` at 2 min/km. Drop the metres→seconds→minutes chain. Keep the units decision itself — rates stay per-km and per-minute — and record that the change **halves** the conversion surface D1 exists to guard |
| **Task 2.2** | `AssumedSpeed` holds `AVERAGE_SPEED_KMH = 30` and `MINUTES_PER_HOUR = 60`, deriving minutes by division. Add: **do not hardcode the derived `2`**, and note that `divide` is exact only because 60/30 terminates — a non-dividing speed throws rather than silently rounding, which is the correct failure |
| **Task 6.1** | Hand-computed example becomes clean: 5 km → 10 minutes exactly → `250 + 120×5 + 25×10 = 1100` minor units |
| **Task 8.2** | Constant renamed to km/h; the *"Story 2.6 must move it, not copy it"* instruction is unchanged |
| **Dev Notes → "The 8.33 m/s constant is documented as something else"** | Retitle to the 30 km/h form. **Keep the substance** — the glossary defines this speed as driver-to-pickup ETA and says *"Not the trip's own duration"*, and AC4's reuse of it for trip time is still authorised only by the epic. §6 keeps that open |
| **Dev Notes → term-degeneracy passage** | `time_minutes = 2 × distance_km` exactly; effective per-km rate becomes exactly `170` |
| **Dev Notes → "How the real ones do it"** | The fixed-speed row updates to 30 km/h. The comparison to real routing engines is unchanged |
| **Questions for the repo owner** | Item 2 (reusing the ETA speed for trip duration) stays open — this proposal changes the constant's units, not what it is applied to |

## 5. Implementation Handoff

**Scope classification: Minor** — direct implementation, no backlog reorganisation, no replan.

| Artifact | Action | Owner |
| --- | --- | --- |
| `specs/spec-puber/SPEC.md` | Constraints line (§4.1) | This workflow |
| `specs/spec-puber/glossary.md` | ETA definition (§4.2) | This workflow |
| `prds/.../addendum.md` | ETA formula row (§4.3) | This workflow |
| `epics/epic-1-foundations-fare-quote.md` | Story 1.3 AC4 (§4.4) | This workflow |
| `epics/epic-2-driver-presence-location-tracking.md` | Story 2.6 ETA AC (§4.5) | This workflow |
| `implementation-artifacts/PUB-3-*.md` | Nine edits per §4.6 | This workflow |
| `prd.md`, `ARCHITECTURE-SPINE.md`, `project-context.md` | **No change** — none names a speed | — |
| `sprint-status.yaml` | **No change** — PUB-3 stays `ready-for-dev` | — |
| Implementation | Dev agent, when PUB-3 is developed | `dev-story` |

### Success criteria

1. No live artifact states `8.33`. (`grep -rn "8\.33"` returns only the dated review records and the
   two sprint change proposals that quote the old text.)
2. Story 1.3 and Story 2.6 name the **same** speed, in the same units.
3. `AssumedSpeed` exposes `AVERAGE_SPEED_KMH = 30`; the derived 2 min/km appears nowhere as a literal.
4. A unit test asserts 2 minutes per kilometre, so a future speed change that breaks the relationship
   fails a test rather than silently repricing every trip.
5. `make build` and `make test` both pass, reported including the known
   `HealthReportsDownPromptlyIntegrationTest` environmental red.

### Sequencing

All five artifact edits land together, before `dev-story` runs on PUB-3. §4.4 and §4.5 **must not be
split** — shipping one without the other puts two speeds in the system.

## 6. Resolved during approval

Both items raised as open were settled by the repo owner on 2026-08-23, and the proposal above was
revised accordingly before implementation:

1. **Does the driver-to-pickup ETA speed also price trip duration? — Yes.** *"The 30 km/h speed is for
   driver-to-pickup but also trip's duration."* One constant, both distances. This had been authorised
   only by Story 1.3's AC4 while `glossary.md` appeared to deny it; the glossary is now corrected
   (§4.2) so the two agree. It also settles **PUB-3's open question 2**, which asked exactly this.
2. **`SPEC.md#Constraints` said `distance` without saying which distance** — the same bare word served
   the trip and the ETA. Resolved as a consequence of item 1: §4.1 now names both uses explicitly, so
   the sentence no longer needs a reader to guess.

Nothing from this proposal remains open. The one question still outstanding on PUB-3 is unrelated to
the speed: FR-18's own text reads *"(base + distance + time) × surge"* and omits the per-km and
per-minute rates, which live only in `SPEC.md` CAP-2 and the glossary.
