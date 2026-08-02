---
title: Reconciliation — puber.md vs PRD + Addendum
created: 2026-08-02
---

# Reconciliation: `docs/puber.md` → PRD + Addendum

Source input: `docs/puber.md` (1018 lines, the original project brief).
Compared against: `prd.md` and `addendum.md` in this folder.

Scope note per instructions: two deliberate PRD overrides are **not** treated as
gaps — (1) cloud deployment dropped in favor of local-K8s-only, (2) stress-test
scale raised from ~30 drivers to ~20k drivers / 200k riders with Payments
excluded. I checked both for internal consistency across the PRD/addendum and
found none — every section that touches cloud deployment (Goals §2, NFR-7,
Non-Goals §5, Roadmap §6 phase 5, addendum Tech Stack) is aligned on
local-K8s-only, and every section touching stress scale (Goals §2, NFR-2,
NFR-8, Roadmap §6 phase 5, addendum "Sizing/Stress-Test Detail") is aligned on
20k/200k with Payments excluded. No contradiction found.

---

## Gaps found

### 1. The "why this matters to the author" origin story is flattened

`puber.md` §"Why this project?" explains a real narrative: a prior project
(`order-book`) taught Java/Spring/SQL depth but felt "too abstract," so Puber
reframes the *same* technical milestones around a tangible domain. It also
explicitly documents that this is a **companion to `plan.md`, a job-search
strategy / JD topic map** — the project exists to close specific
career-syllabus gaps, and cites a full "How This Maps to plan.md Gaps" table
(Java 25, Postgres/Flyway, transactions/isolation, Kafka, Resilience4j, Redis,
REST design, WebSockets, observability, Docker/K8s, system design, payments,
ClickHouse, audit design, fan-out).

The PRD's Vision (§1) and Goals (§2, closing paragraph) capture the *outcome*
("portfolio narrative," "CV bullets and interview talking points") but drop:
- The specific predecessor-project contrast (order-book → Puber, "too
  abstract" rationale) — the pedagogical reasoning for *why this domain*.
- The explicit tie to a companion job-search document and its gap-mapping —
  the PRD treats Puber as self-contained, whereas puber.md frames it as one
  deliverable inside a larger career plan.
- The named target companies in the payments rationale ("directly relevant to
  Revolut/Rain/Upvest interviews," puber.md §Decisions, Payment integration
  row) — dropped entirely, even from the addendum's rationale column for the
  same decision.

This is a tone/intent loss, not a feature loss — worth knowing about since a
PRD reader would not realize Puber is instrumental to a specific job search,
only that it's "a portfolio piece."

### 2. The "scope expansion mid-project" narrative is erased

`puber.md` §"Why this project?" states explicitly: *"Scope expansion
(mid-project): Stripe (sandbox) was added as an in-scope integration in Month
5... Audit-service... was added in Month 6... Both are real production
patterns worth learning; the 6-month arc extends to 8 months to accommodate
them."*

The PRD's Roadmap (§6) presents the 8-month/32-week, 5-phase plan as if it
were the original shape — Payments and Audit are just phases 3 and 4, with no
trace that these were later additions that *grew* the project from an
original 6-month scope. The rationale for *why* they were bolted on (closing
the "fintech-patterns gap" and the "ClickHouse / columnar gap... flagged in
the broader career plan") is preserved only partially, as generic feature
rationale in Vision/Goals, not as a scope-evolution decision. This is a minor
loss — defensible as PRDs describing current-state rather than history — but
it's a real "how we got here" nuance from the source that a reader of the PRD
alone would never learn.

### 3. The explicit "you control every input" design philosophy isn't stated as a principle

`puber.md` states a crisp design philosophy: *"**Key principle:** You control
every input. No Google Maps APIs, no SMS providers. 'Users' are Java threads
firing real HTTP requests with seeded random data. 'Location' is a `(lat,
lng)` pair you generate."*

The PRD captures the *consequences* of this principle piecemeal — Non-Goals
lists "real maps or routing APIs" and "real mobile apps" out of scope, and
FR-27 says the Simulator is "deterministic/seeded" — but never states the
unifying principle itself: that determinism and full input control are a
first-class design goal (not just an incidental scope cut), which is why
seeding, fixtures, and no-external-dependency choices recur throughout the
whole document. A reader of the PRD would see a list of unrelated exclusions
rather than one coherent philosophy driving them.

### 4. Risk-management / working-process guidance dropped entirely

`puber.md` §"Risk Notes" contains four operating rules with real rationale
that don't appear anywhere in the PRD or addendum:
- **Slip rule:** "If a week vanishes, repeat it or drop the optional slice. Do
  not stack debt."
- **Scope honesty:** "This is a learning project, not Uber. One bounded 4 km ×
  4 km area, 10 fixture drivers, one fare rule, seeded random data." (a
  tone-setting reminder against over-scoping)
- **Tests-in-Docker constraint:** integration tests need Postgres (and later
  Kafka) reachable from the test container — a concrete constraint on how
  "done" is verified.
- **V1-simplicity / location-path-evolution reminders:** explicit permission
  to *not* add Kafka/Redis/standalone-simulator before Month 3 — a
  deliberate-teaching-progression note, framed so the author doesn't feel
  pressure to over-build early.

These read as working-process/self-management guidance rather than product
requirements, so their absence from a capabilities-focused PRD is largely
correct discipline — flagging only because "Scope honesty" and the
tests-in-Docker constraint touch the quality bar (NFR-7 mentions Docker builds
but not that *tests* must run against containerized Postgres/Kafka) and could
be worth a line in NFR-7 or a QA-readiness section later.

### 5. Minor numeric inconsistency in the source itself (not a PRD-introduced gap)

`puber.md`'s own fixture SQL seeds exactly **10** drivers, but its Decisions
table rationalizes V1's direct-Postgres location-query approach using **"30
drivers × 1 location/2s = 15 writes/sec."** The PRD's NFR-2 inherited the "~30
drivers" figure ("functionally proven at fixture scale (~30 drivers)") rather
than the actual seeded count of 10. This isn't a PRD error — it faithfully
propagates a figure that already existed in puber.md's own rationale — but
it's worth flagging since "~30" doesn't match the concrete fixture data in the
same source document. Not a contradiction introduced by the PRD; a
pre-existing loose thread in puber.md.

---

## Explicitly checked and NOT flagged (working as intended)

- **Cloud deployment removal** and **stress-test scale change (20k/200k,
  Payments excluded)** — confirmed intentional overrides, applied
  consistently across every section of the PRD and addendum. No internal
  contradiction found.
- All Flyway/SQL implementation detail (exact `CREATE TABLE` statements,
  index-tuning weeks, matching-engine Java code, simulator code,
  service-ownership endpoint table, week-by-week ticket schedule, hosting
  provider shortlist) — correctly omitted from the PRD as technical-how;
  fully preserved in the addendum where it belongs (Architecture Decisions,
  Data Model, Tech Stack tables) or left for `docs/tickets/` /
  Architecture/Epics passes.
- Post-MVP ideas (riders table + FK, multi-vehicle types, scheduled rides,
  promo codes, driver earnings dashboard) — all captured in PRD §5
  "Deferred."
- Ticket conventions (`PB-X.Y` numbering, ~4h/week cadence, "Done" = merged +
  AC + tests green) — correctly left out as project-management/ticketing
  detail, not product shape.
