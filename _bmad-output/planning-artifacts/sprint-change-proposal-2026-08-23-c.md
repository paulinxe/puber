---
title: Sprint Change Proposal — FR-18 states the fare formula's rates
date: 2026-08-23
trigger_story: PUB-3 (Epic 1, Story 1.3)
scope_classification: Minor
status: approved-and-implemented
approved: 2026-08-23
change_type: defect fix — FR-18 as written is dimensionally incoherent; the correct form already exists downstream
---

# Sprint Change Proposal — FR-18 states the fare formula's rates

## 1. Issue Summary

**Problem statement.** FR-18 states the fare formula as **`(base + distance + time) × surge`**. That
expression adds a **price** to a **length** to a **duration**. It is not merely imprecise, it is
dimensionally impossible — there is no unit system in which it evaluates.

The correct, rate-bearing form already exists in two downstream documents and in the epic. **FR-18 is
the only place the formula is wrong, and it is the place every future story will cite first.**

**Issue type:** misunderstanding of original requirements — specifically, a requirement whose own text
does not express what every document derived from it expresses.

**How it was discovered.** During PUB-3 story review, while sourcing AC2's citation. The acceptance
criterion cites FR-18 for the full formula; FR-18 does not contain it.

### Evidence — the same formula in four places

| Source | Exact wording | Coherent? |
| --- | --- | --- |
| `prd.md:61` (FR-18) | *"Fare is computed at request time from a formula (base + distance + time) × surge multiplier."* | **No** — adds a price to a length to a duration |
| `prd.md:176` (PRD glossary, Fare) | *"the price computed at request time: (base + distance + time) × surge multiplier."* | **No** — same defect |
| `SPEC.md:39` (CAP-2) | *"the fare is computed once at request time as `(base + per-km × distance + per-minute × time) × surge`"* | Yes |
| `glossary.md:16` (Fare) | *"the price computed once at request time: `(base + per-km × distance + per-minute × time) × surge`"* | Yes |

`epics/epic-1-foundations-fare-quote.md:230` (Story 1.3 AC2) uses the coherent form **and cites
FR-18** — a citation that does not hold against the cited text.

### Why it matters beyond tidiness

| Consequence | Detail |
| --- | --- |
| A citation that does not hold | Story 1.3's AC2 says `(base + per_km × distance + per_minute × time) × surge` **(FR-18)**. A reviewer checking the source finds a different formula. PUB-3 carries a whole Dev Notes section warning the dev agent not to cite FR-18 for the rates — documentation existing solely to work around a defect |
| The units ambiguity originates here | Because FR-18 names no rates, it names no units. That is the root of PUB-3's decision D1 — *"the single most dangerous thing in the story"* — where feeding metres into a per-km term is silently 1000× wrong. Naming the rates in FR-18 also lets it name the units, killing the ambiguity at the source rather than at each consumer |
| The PRD is the first place anyone looks | `epics/overview.md` states the authority chain: *"the PRD governs product shape."* A downstream document being more correct than its source inverts that, and the next person to derive from the PRD inherits the defect |
| It went through three review passes | The `implementation-readiness-report-2026-08-16.md` audited all 414 acceptance criteria and praised FR specificity; `prd-corrections-2026-08-16.md` corrected fourteen FRs. **Neither touched FR-18.** The fare path has never had a reconciliation pass — which is also how the units gap survived |

## 2. Impact Analysis

### Epic impact

| Question | Finding |
| --- | --- |
| Can Epic 1 be completed as planned? | **Yes.** No story added, removed, split or resequenced |
| Epic-level change required? | **None to any acceptance criterion.** Story 1.3's AC2 already states the correct formula; this change makes its FR-18 citation valid |
| Future epics affected? | **No.** Story 4.8 (surge) and Story 3.2 (fare lock) reference the formula's *behaviour*, not its terms |
| New epics needed? | No |
| Resequencing needed? | No |

### Artifact conflicts

| Artifact | Impact |
| --- | --- |
| **PRD** | **Change proposed** — FR-18 (line 61) and the PRD glossary's *Fare* entry (line 176) |
| **`requirements-inventory.md`** | **Change proposed** — two restatements of FR-18 (lines 43, 194) |
| **`SPEC.md`, `glossary.md`** | **No change.** Both already carry the correct form — this proposal makes the PRD agree with them, not the reverse |
| **Epics** | **No change.** Story 1.3 AC2 is already correct |
| **Architecture spine** | **No change.** Verified: the spine states no fare formula |
| **`project-context.md`** | **No change.** States no formula |
| **Story file `PUB-3-*.md`** | **Change proposed** — the AC2 source note, the "Where the formula really comes from" table, and open question 7 |
| **UX** | **N/A.** No UX document exists |
| **Review records and dated reports** | **No change** — `implementation-readiness-report-2026-08-16.md` quotes FR-18's old text as a dated finding, and the two earlier proposals today quote it as evidence. All historical |
| **`sprint-status.yaml`** | **No change.** PUB-3 stays `ready-for-dev` |
| **Code** | **None** |

### Technical impact

None. No behaviour changes — the implementable formula was always the rate-bearing one, and PUB-3
already specifies it. This aligns the requirement with what was always going to be built.

## 3. Recommended Approach

**Option 1 — Direct Adjustment. Selected.** Effort **Low**, risk **Low**.

Option 2 (rollback) is N/A — nothing is implemented. Option 3 (MVP review) is not required — no scope,
goal or deferral change.

**Rationale.** This is a defect fix, not a decision. The intended formula is unambiguous: two
independent documents state it, the epic implements it, and the incumbent operators price exactly this
way (Uber's rider help names *"a base amount, a per minute amount, a per mile amount"*). The only
question was whether to correct the source or keep working around it downstream, and working around a
wrong requirement in every consumer is how a defect becomes permanent.

## 4. Detailed Change Proposals

### 4.1 PRD — `prd.md`, FR-18

**OLD**

```
- **FR-18:** Fare is computed at request time from a formula (base + distance + time) × surge multiplier.
```

**NEW**

```
- **FR-18:** Fare is computed at request time as `(base + per-km rate × distance + per-minute rate × time) × surge multiplier`, with distance in kilometres and time in minutes, over the configurable fare rules of FR-19.
```

**Rationale:** makes the requirement dimensionally coherent and its Story 1.3 citation valid. **Naming
the units is deliberate** — their absence is the root of PUB-3's D1 hazard, where a per-km rate fed
metres is silently 1000× wrong. Pointing at FR-19 records where the four values live, so the two
requirements read as one mechanism rather than two overlapping ones.

### 4.2 PRD — `prd.md`, glossary entry *Fare*

**OLD**

```
- **Fare** — the price computed at request time: (base + distance + time) × surge multiplier.
```

**NEW**

```
- **Fare** — the price computed at request time: `(base + per-km rate × distance + per-minute rate × time) × surge multiplier`. Never recomputed at trip end.
```

**Rationale:** the PRD glossary carried the identical defect, so fixing only FR-18 would leave a wrong
copy two hundred lines below the corrected one. *"Never recomputed at trip end"* is added to match
`specs/spec-puber/glossary.md`, which already says it — the fare-lock rule (FR-2, FR-14) is the single
most load-bearing fact about a fare and the PRD glossary omitted it.

### 4.3 Epics — `requirements-inventory.md`, FR-18 restatement (line 43)

**OLD**

```
FR-18: Fare is computed at request time from `(base + distance + time) × surge`.
```

**NEW**

```
FR-18: Fare is computed at request time from `(base + per-km × distance + per-minute × time) × surge`, distance in kilometres and time in minutes.
```

### 4.4 Epics — `requirements-inventory.md`, FR coverage map (line 194)

**OLD**

```
| FR-18 | 1 | Fare formula `(base + distance + time) × surge` |
```

**NEW**

```
| FR-18 | 1 | Fare formula `(base + per-km × distance + per-minute × time) × surge` |
```

**Rationale for both:** these are restatements of FR-18. A stale copy outlives the true one, and this
file is loaded by `create-story` for every future story.

### 4.5 Story file — `PUB-3-fares-are-computed-from-configurable-rules.md`

| Location | Change |
| --- | --- |
| **AC2 source note** | Drop the warning that FR-18 lacks the rates. Cite FR-18 directly for the whole formula, since it now carries it |
| **Dev Notes → "Where the formula really comes from"** | Rewrite. The three-way table showing FR-18 disagreeing with SPEC and the glossary becomes a one-line note that all three now agree, with a pointer to this proposal so the history is recoverable |
| **Open question 7** | Resolved — remove from the open list and record the resolution |

## 5. Implementation Handoff

**Scope: Minor** — direct implementation, no backlog reorganisation, no replan.

| Artifact | Action | Owner |
| --- | --- | --- |
| `prds/prd-puber-2026-08-02/prd.md` | FR-18 (§4.1) and glossary *Fare* (§4.2) | This workflow |
| `epics/requirements-inventory.md` | Two restatements (§4.3, §4.4) | This workflow |
| `implementation-artifacts/PUB-3-*.md` | Three edits (§4.5) | This workflow |
| `SPEC.md`, `glossary.md`, epics, spine, `project-context.md` | **No change** — already correct or silent | — |
| `sprint-status.yaml` | **No change** | — |

### Success criteria

1. `grep -rn "base + distance + time"` returns **only** dated records — the readiness report and the
   sprint change proposals that quote it as evidence.
2. FR-18, the PRD glossary, `SPEC.md` CAP-2, `specs/glossary.md` and Story 1.3 AC2 all state the same
   formula.
3. Story 1.3's AC2 citation of FR-18 holds against FR-18's actual text.
4. FR-18 names the units, so no consumer has to decide them again.

## 6. Note on what this does not fix

**The formula's two terms remain mathematically redundant, and that is correct and documented.**
Because time is derived from distance at a fixed 30 km/h, `time = 2 × distance` always, so the per-km
and per-minute rates collapse into one effective per-km rate (`120 + 2 × 25 = 170` on PUB-3's seeded
values). The four-column table has three degrees of freedom.

This is not a defect in FR-18 and must not be "simplified" away: the two-rate form is what the
incumbents use, it is what `SPEC.md` CAP-2 and both glossaries specify, and the terms are independent
in any system with real trip durations. They collapse here only because Puber deliberately has no
routing API (a stated non-goal). PUB-3's Dev Notes explain this at length, including the practical
consequence that tuning `per_minute_rate` alone cannot change the price's time-sensitivity.
