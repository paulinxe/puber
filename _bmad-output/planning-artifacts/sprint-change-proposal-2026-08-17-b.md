---
title: Sprint Change Proposal — Hook gate rebalance and a proportionate outage test
date: 2026-08-17
trigger_story: PUB-1 (Epic 1, Story 1.1)
scope_classification: Minor
status: approved-and-implemented
approved: 2026-08-17
supersedes_parts_of: epic-1 Story 1.1 AC blocks 4, 13, 14, 19
---

# Sprint Change Proposal — Hook gate rebalance and a proportionate outage test

Two independent changes to Story 1.1's acceptance criteria, both surfaced during detailing review,
both on a story that is `ready-for-dev` and unstarted.

## 1. Issue Summary

### Change A — the fast/full hook split was sized against an assumption this story invalidated

The epic splits the test gate deliberately: `pre-commit` runs the touched service's **unit tests**
(AC13) plus fast static checks (AC19), while `pre-push` runs the **full suite** (AC15). Its stated
reasoning is explicitly about tolerability:

> *"a multi-minute gate on every commit is one that gets bypassed with `--no-verify` inside a week. A
> bypassed hook protects nothing while reporting success, which is worse than no hook."*

That reasoning is sound. The **premise** underneath it is not, given decisions this story makes.
AC13 assumes a service's unit tests are cheap to run on every commit. But NFR-7 and AD-56 put every
test invocation inside a container, and this story's Task 6 makes that container **throwaway** — so
the Gradle daemon cannot survive between runs, and each commit pays container start + JVM start +
Gradle start + compile + test. For a one-line change that is plausibly tens of seconds.

The epic's own bypass argument therefore applies to the gate the epic itself placed on `pre-commit`.

The epic also already names where the gate genuinely matters:

> *"`pre-push` … the boundary immediately before the PR to `dev`, which is where the gate actually has
> to hold."*

**Issue type:** technical constraint discovered during implementation planning, invalidating a
premise rather than a decision.

### Change B — AC4's outage test is disproportionate to the defect it protects against

AC4 requires health to report DOWN while Postgres is unreachable and UP once it returns, *"proven by
an integration test rather than by inspection."* AD-56 forbids the test runner from holding a Docker
socket, so the test cannot stop the container. Every mechanism that genuinely severs a live
connection — a hand-written in-JVM TCP forwarder, or a Toxiproxy sidecar — is substantial machinery
for one assertion, and Toxiproxy would additionally introduce infrastructure three epics before
anything requires it.

**The realistic defect is latency, not absence.** HikariCP's default `connectionTimeout` is 30
seconds. Left at the default, health still eventually reports DOWN — so the behaviour "works" — but
takes long enough to break a Kubernetes readiness probe in Epic 7. **Manual inspection cannot catch
this**: an operator stops Postgres, curls health, waits, sees DOWN, and concludes it is correct. The
defect is *how long it took*.

**Issue type:** proportionality — the criterion's stated mechanism costs more than the risk it
retires, while missing the likelier failure.

## 2. Impact Analysis

### Epic impact

| Question | Finding |
| --- | --- |
| Epic 1 completable as planned? | **Yes.** No story added, removed, split or resequenced |
| Epic-level change? | Story 1.1 acceptance criteria only |
| Other epics affected? | **None.** Only Epic 1 mentions hooks at all |
| New epics needed? | No |
| Resequencing? | No |

**Forward-looking note carried into the record.** The full suite grows in Epics 4–6 (Kafka, Redis,
ClickHouse). Moving all tests to `pre-push` makes `pre-push` the only gate, and the epic's bypass
argument applies recursively: if `pre-push` becomes intolerable, **the answer is faster tests, not
moving the gate again.** With no CI server (settled decision), `pre-push` is the last line.

### Artifact conflicts

| Artifact | Impact |
| --- | --- |
| **PRD** | **No change.** It specifies no hook behaviour |
| **Architecture spine** | **No change required.** It says only *"the suite runs locally against the same Compose stack, behind git hooks, before a PR to `dev`"* — it never mandates a `pre-commit`/`pre-push` split. Change A arguably makes that sentence more literally true |
| **Epics — Story 1.1** | **Change proposed.** AC4 narrowed; AC13 rewritten; AC14 and AC19 removed |
| **Epics — overview.md** | **No change.** Standing criteria are unaffected |
| **`sprint-status.yaml`** | **No change.** PUB-1 stays `ready-for-dev` |
| **Story file `PUB-1-*.md`** | Renumber ACs, rewrite Tasks 9 and 11, replace the AC4 Dev Notes section |

### Why AC14 and AC19 are removed rather than moved

**AC14** — *"a commit touching the contracts directory runs every service's unit tests"* — becomes
**vacuous**. Once `pre-commit` runs no tests there is nothing for it to trigger, and `pre-push`
already runs every service's tests regardless of what changed. Its underlying fact remains true and
important — `.proto` and event schemas are copied into all services at build time, so a change there
is a change to all of them (AD-52) — and is preserved as a note rather than as a hook rule.

**AC19** — *"the fast static checks run alongside the unit tests"* — becomes **redundant with the
rewritten AC13**, which now states that static analysis is the whole of what `pre-commit` does.
Folding it in also addresses the readiness review's QUAL-5 critique that some AC blocks state
rationale rather than testable criteria.

**Net: Story 1.1 goes from 20 acceptance criteria to 18.** The readiness review flagged this story as
the largest in the document; this reduces it while removing nothing that was being enforced.

## 3. Recommended Approach

**Option 1 — Direct Adjustment. Selected.** Effort **Low**, risk **Low**.

Option 2 (rollback) is N/A — nothing is implemented. Option 3 (MVP review) is not required — no scope,
goal, or deferral changes.

**Rationale.** PUB-1 is unstarted, so both changes cost only the edit. Change A removes a gate that
the epic's own reasoning predicts would be bypassed, and relocates its coverage to the boundary the
epic itself identifies as decisive. Change B replaces bespoke test machinery with an assertion that
targets the defect most likely to occur and least likely to be caught by eye.

## 4. Detailed Change Proposals

### 4.1 AC4 — narrowed to a bounded-time DOWN assertion

**OLD**

```
**Given** a running service
**When** its Postgres is stopped and then restarted
**Then** health reports DOWN while the datastore is unreachable and UP once it returns
**And** this is proven by an integration test rather than by inspection
```

**NEW**

```
**Given** a running service
**When** its Postgres is unreachable
**Then** health reports DOWN **within a bounded time short enough to serve a Kubernetes readiness
probe**, rather than blocking on a default connection timeout
**And** this is proven by an integration test rather than by inspection
**And** the UP case needs no test of its own — every other integration test boots against a live
Postgres and fails if health is not UP
```

**Rationale:** keeps the criterion testable and keeps its "test, not inspection" clause — which is the
only such clause in the story and must not be eroded — while targeting the defect that actually
occurs. Removes the need for a TCP forwarder or a Toxiproxy sidecar.

### 4.2 AC13 — `pre-commit` runs static analysis only

**OLD**

```
**Given** a commit touching one service
**When** `pre-commit` runs
**Then** it runs that service's **unit tests only**, so the gate stays fast enough to be tolerated
**And** a failure **blocks the commit**
```

**NEW**

```
**Given** a commit touching one service
**When** `pre-commit` runs
**Then** it runs that service's **static analysis and nothing else** — no tests, so the gate costs
approximately nothing and is never worth bypassing
**And** a failure **blocks the commit**
**And** every test in the project runs at `pre-push` instead, where waiting is cheap and being wrong
is expensive
```

**Rationale:** preserves the epic's intent — a commit gate cheap enough that nobody reaches for
`--no-verify` — under this story's containerized, daemon-less test execution.

### 4.3 AC14 — removed, its fact preserved as a note

**REMOVE** the AC block beginning *"Given a commit touching the versioned contracts directory"*.

**ADD** this note to Story 1.1:

```
> **A contracts change is a change to every service, even though no hook now says so.** `.proto` and
> event-schema files in `contracts/` are copied into every service at build time (AD-52), so editing
> one edits all of them. `pre-commit` no longer expresses this — it runs no tests — and `pre-push`
> runs every service's suite regardless of what changed, so the rule is enforced by the full gate
> rather than by a special case.
```

### 4.4 AC19 — removed, folded into AC13

**REMOVE** the AC block beginning *"Given the `pre-commit` hook / When it runs / Then the fast static
checks run alongside the unit tests"*. Its content is now AC13's first clause.

### 4.5 Story file — `implementation-artifacts/PUB-1-*.md`

- Renumber AC blocks: old AC15→14, AC16→15, AC17→16, AC18→17, AC20→18, and update every
  cross-reference (the non-root criterion is referenced from Tasks 3, 6, 10, 12 and two Dev Notes
  sections).
- Rewrite **Task 11** for a static-analysis-only `pre-commit`.
- Rewrite **Task 9** where it references AC19.
- Replace Dev Notes → *"AC4 is the hardest AC in this story"* with the bounded-time approach; delete
  the TCP-forwarder guidance.
- Record the hook policy in `project-context.md` (Task 12) so later services inherit it.

## 5. Implementation Handoff

**Scope: Minor** — direct implementation, no backlog reorganisation, no replan.

| Artifact | Action | Owner |
| --- | --- | --- |
| `epics/epic-1-foundations-fare-quote.md` | AC4 narrowed; AC13 rewritten; AC14 + AC19 removed; note added | This workflow |
| `implementation-artifacts/PUB-1-*.md` | Renumber ACs; rewrite Tasks 9, 11; replace AC4 Dev Notes | This workflow |
| `ARCHITECTURE-SPINE.md` | **No change** | — |
| `sprint-status.yaml` | **No change** — PUB-1 stays `ready-for-dev` | — |
| Implementation | Dev agent at `dev-story` time | `dev-story` |

**Success criteria.**

1. `pre-commit` completes fast enough that bypassing it is never tempting, and runs no tests.
2. `pre-push` runs the entire suite and blocks the push on failure.
3. A test asserts health reports DOWN within a bounded time when the datastore is unreachable.
4. No TCP forwarder and no Toxiproxy service exist in the repository.
