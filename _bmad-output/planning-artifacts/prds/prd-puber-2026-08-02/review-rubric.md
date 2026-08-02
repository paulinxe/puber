# PRD Quality Review — Puber PRD (prd-puber-2026-08-02)

## Overall verdict

This is a well-calibrated PRD for what it actually is: a solo capability spec for a learning project, not a product pitch forced into product-PRD clothing. Its strongest asset is scope honesty — the three real trade-offs (dropping cloud deployment, excluding Payments from the stress test, skipping auth) are all stated as deliberate decisions with rationale, not smoothed over or left implicit, and there are zero leftover contradictory references to the removed cloud-deployment path. What's at risk is downstream usability and a couple of done-ness gaps: the audit "retention policy" is invoked three times (Goals, FR-21, NFR-6) but never once quantified anywhere in the PRD or addendum, and there's no Glossary despite the addendum explicitly stating this PRD feeds `bmad-architecture` directly.

## Decision-readiness — strong

The PRD earns this rating on its three real de-scoping decisions. FR-26 states outright: "This is a deliberate scope decision, not an oversight — auth is out of scope for this project's learning goals." NFR-2/NFR-8 name the payments-stress-exclusion trade-off explicitly and even leave the *mechanism* as a genuinely open, unresolved item (§7 Open Questions) rather than papering over it with a fake-resolved rhetorical question. The Roadmap's Phase 5 footnote — "this phase no longer includes any real-cloud-vendor deployment — local K8s is the final target" — is a rare and valuable admission that a decision was *changed*, not merely made from a blank slate.

### Findings
- **medium** Cloud-deployment trade-off states the choice but not the cost (§4 NFR-7, §6 Roadmap Phase 5) — The PRD asserts local-only K8s as final target and even flags it changed from a prior plan ("no longer includes"), but never weighs this against the PRD's own stated secondary goal of CV bullets and "interview scrutiny" (§2 Goals, final paragraph). Real cloud experience (IAM, managed K8s quirks, networking) is arguably more interview-relevant than kind/minikube, and that forgone value is never acknowledged. *Fix:* add one sentence naming what's given up (e.g., "trades cloud-specific interview narrative for schedule predictability and zero cloud-cost/account risk").

## Substance over theater — strong

No persona theater, no innovation/differentiation section, no vision theater — correctly absent given the shape. The Vision statement (§1) is specific to this project (names Kafka, Resilience4j, Stripe sandbox, the Postgres→ClickHouse migration) and could not be swapped into another hobby PRD unchanged. NFRs carry real numbers rather than boilerplate: NFR-2's "~20k drivers and 200k riders," NFR-1's "no double-booked drivers... proven via concurrent test scenarios, not just code review," NFR-3's named mechanism (dead-letter queue) rather than a bare "gracefully."

### Findings
None — this dimension needs no correction.

## Strategic coherence — strong

The thesis ("practice production-grade backend engineering patterns... against a domain that's tangible and visually verifiable") is stated once and never contradicted. Feature/phase ordering in §6 Roadmap follows the thesis, not ease: matching correctness first, then Kafka/resilience/observability, then Stripe, then Audit/ClickHouse, then real-time+K8s — this is the natural teaching-value order, not the easiest-first order (Kafka before Payments is deliberately harder than doing Payments first). Success Criteria (§2) is an operationally binary "it works end to end" checklist, appropriately free of vanity/engagement metrics that would make no sense for a project with zero users.

### Findings
None — counter-metrics are not needed here; there is no metric-gaming risk in a single-developer project with binary pass/fail criteria.

## Done-ness clarity — adequate

Most FRs carry a testable consequence: FR-2's "5km radius," FR-6's "no driver is ever double-booked," FR-11's "sub-second" fast path, FR-24's named KPI list. But a few requirements assert a policy exists without ever defining it, and this is the dimension where downstream story-writers will get stuck.

### Findings
- **medium** "Retention policy" is asserted three times, defined zero times (§2 Goals, FR-21, NFR-6; addendum "Data Model") — Goals says "Postgres is partitioned with retention," FR-21 says audit events are "retained under a partitioning + retention policy," NFR-6 repeats "retained under a partitioning/retention policy," and the addendum's Data Model section only specifies the partitioning grain ("partitioned by month") — never a retention duration or drop condition. No engineer reading either document knows what "done" looks like for this requirement. *Fix:* state a concrete window (e.g., "Postgres retains N months before partition drop; ClickHouse retains indefinitely") or, if genuinely undecided, tag it as an open question rather than stating it three times as settled.
- **low** FR-9 heartbeat interval unstated (FR-9; addendum "Matching engine queries drivers.current_lat/current_lng directly" rationale row) — The addendum's rationale table backs into an assumed cadence ("~30 drivers × 1 location/2s = 15 writes/sec") to justify a design choice, but no requirement anywhere actually states the heartbeat interval as a spec. *Fix:* either add the interval to FR-9 directly or to the addendum as a stated parameter rather than an inferred one buried in a rationale cell.

## Scope honesty — strong

This is the PRD's best dimension and it directly matters for a project like this. §5 Non-Goals does real work: 11 explicit exclusions plus a separately labeled "Deferred" list distinguishing "will never do" from "might revisit." The three load-bearing scope trade-offs the task asked to check are all present as real decisions: cloud deployment removal (NFR-7, Non-Goals, Roadmap footnote — all three agree), Payments excluded from the NFR-2 stress target (NFR-8, cross-referenced both directions), and no-auth (FR-26). The single Open Question (§7) is genuinely open — it names four candidate mechanisms for the payments-stress exclusion and explicitly declines to pick one, correctly deferring to Architecture. Open-items density (1 open question) is appropriately low for this stakes level; nothing here reads like a forced-closed question.

### Findings
None — this dimension is the PRD's strongest and needs no correction. (Note: the PRD does not use the checklist's literal `[ASSUMPTION]` / `[NON-GOAL for MVP]` / `[NOTE FOR PM]` bracket-tag convention anywhere; it achieves the same function through plain prose — see Mechanical notes.)

## Downstream usability — thin

The addendum states explicitly that it "feeds `bmad-architecture` directly," which puts this PRD in the rubric's chain-top case where downstream usability matters more, not less. Domain vocabulary is used consistently enough to extract from (Simulator, matching-engine, PaymentIntent, audit-service all stay stable across sections), and cross-references resolve correctly (FR-28 ↔ FR-23/FR-24, NFR-2 ↔ NFR-8, §7 ↔ addendum.md). But there's no Glossary section, and downstream story/architecture work will have to reverse-engineer term definitions (e.g., what exactly "fast path" vs. "slow path" means for FR-11 is only spelled out in the addendum's rationale table, not defined in the PRD itself).

### Findings
- **low** No Glossary despite explicit downstream consumption (addendum, "feeds `bmad-architecture` directly") — domain terms are used consistently but never centrally defined, so architecture/story work has to infer definitions from scattered FR prose. *Fix:* a short glossary (Simulator, fast/slow path, stress-test scale, reconciliation) would cost little and pay off once Architecture/Epics start extracting from this doc.

## Shape fit — strong

Correctly treated as a capability spec, not a consumer-product PRD: no personas, no user journeys with named protagonists, no competitive positioning — all rightly absent per the rubric's own hobby/solo guidance. The substance bar is still met: FRs are specific (not just a capability list), NFRs carry real thresholds, and the scope trade-offs are surfaced as decisions rather than smoothed over. This is the correct shape for a single-operator, single-developer learning project and shows no sign of over-formalization (no manufactured UJs) or under-formalization (the FR/NFR substance is real, not a bare backlog).

### Findings
None.

## Mechanical notes

- **ID sequencing:** NFR-8 is defined between NFR-2 and NFR-3 (§4) rather than appended after NFR-7 or renumbered into sequence — clearly added later as NFR-2's companion, but it breaks strict numeric contiguity. Low-impact since both cross-reference each other correctly, but worth renumbering if the NFR list is touched again.
- **Bracket-tag convention absent:** The PRD does not use `[ASSUMPTION: …]`, `[NON-GOAL for MVP]`, or `[NOTE FOR PM]` tags anywhere, and there is no Assumptions Index. The document achieves equivalent function through plain prose (e.g., FR-26's "this is a deliberate scope decision, not an oversight" reads as a NOTE-FOR-PM; the "Deferred" list in §5 functions as `[NON-GOAL]` tags). Since the substance is present, this is a format deviation rather than a content gap — flagged for awareness, not as a defect.
- **Cloud-deployment consistency (explicitly checked per task request):** No leftover contradictory references found. §1 Vision, §4 NFR-7, §5 Non-Goals, §6 Roadmap Phase 5 footnote, and the addendum's Tech Stack line all agree that no real cloud vendor is used and that this was a deliberate change from an earlier plan. This is clean.
- **FR/NFR ID continuity:** FR-1 through FR-28 contiguous with no gaps or duplicates across all eight lettered groups (A–H). NFR-1 through NFR-8 all present, no duplicates (see sequencing note above for ordering, not continuity).
- **Cross-doc roundtrip (PRD ↔ addendum):** The one open item in §7 correctly points to addendum.md, and the addendum's "Open mechanism question" section mirrors it back — the two documents agree on what's unresolved rather than one silently assuming an answer the other doesn't have.
