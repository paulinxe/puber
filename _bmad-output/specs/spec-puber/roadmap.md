# Roadmap — Puber

Companion to `SPEC.md`. Five phases across a 32-week plan; phase-level shape only. Each phase is a working system, not a layer — the milestone tag is cut on something that runs end to end.

**The weeks are an estimate, not a commitment.** They exist to fix the *order* of the phases and the relative weight between them; a phase that runs long is not a failure and nothing downstream should treat these as deadlines. What is binding is the sequence, the milestone tags, and the sequencing constraints below.

| Phase | Weeks | Lands | Milestone |
| --- | --- | --- | --- |
| 1. Bootstrap + domain + matching | 1–7 | Core services stand up; domain model, Postgres persistence and fixtures land; in-memory nearest-driver matching works end to end against the Simulator. No Kafka yet. | — |
| 2. Kafka + resilience + observability | 8–16 | Domain events move onto the event backbone; resilience patterns and full observability land; the driver-location fast path is introduced; surge becomes demand-derived now that the signal exists. | `v0.1` |
| 3. Stripe payments | 17–20 | `payment-service` integrates the sandbox across the full two-phase lifecycle — authorize at request, capture at completion, void on rides that deliver no trip — proving idempotent webhooks, the payment state machine, both failure paths, and refunds. | `v0.2` |
| 4. Audit + ClickHouse | 21–24 | `audit-service` captures state transitions across all domains; storage starts in partitioned Postgres and migrates to ClickHouse with the migration benchmarked; the same pipeline computes the driver-utilization, distance-traveled and ride-density analytics. | `v0.3` |
| 5. Real-time + local Kubernetes | 25–32 | Drivers get the live push channel for offers and ride-state changes; the live operational dashboard ships; the full system deploys to a local Kubernetes cluster; the stress-scale target is exercised. | `v1.0` |

## Week one, before any phase

Two capabilities are tagged `enabler` in `SPEC.md` and do not belong to the phase their subject matter suggests. They are built first, because each is cheap to establish and expensive to retrofit:

- **CAP-36 — health and metrics per service.** The spine and the PRD both say *from the first commit, not retrofitted*, while observability as a phase lands in weeks 8–16. Both are true: the dashboards and the fixed KPI definitions are phase 2, but a service that ships uninstrumented in phase 1 has already broken the rule.
- **CAP-40 — the clock abstraction.** Phase 1 already contains the offer timeout, the `NO_DRIVER` budget and the staleness windows. Every one of them must be exercisable by advancing a clock rather than by sleeping, from the first test that touches them.
**CAP-39 — the Simulator — was previously listed here and is not.** The reasoning that placed it in week one was that phase 1's matching correctness is proven against it. That correctness lands in phase 1's *matching* work, and it is proven by **deterministic test scenarios rather than synthetic traffic**: two threads, one ride, one latch, the clock advanced deliberately. A hand-written scenario is more deterministic than a seeded generator, because it contains no randomness to reproduce in the first place. Nothing before phase 5 therefore depends on the Simulator existing, which is what `enabler` means — so it is scheduled as an ordinary phase-5 capability and delivered once, as a component, where its actual job begins: surfacing **emergent** behaviour under mixed load that nobody wrote a scenario for, and making the deployed system watchable while it happens. Seeded reproducibility serves re-running a surprise, not asserting a known invariant.

Properties (`CAP-13`, `CAP-20`, `CAP-23`, `CAP-24`, `CAP-31`) are not scheduled at all. They attach as acceptance criteria to the stories that could violate them, in whatever phase those stories land.

## Sequencing constraints

- **Commands and reads never migrate to the backbone.** Phase 2 moves *events* only — anything an actor waits on a response for stays a synchronous call for the life of the project, because the edge services own no data.
- **Payments phase capacity is already tight.** Weeks 17–20 was sized before the two-phase lifecycle and both failure paths were added; the phase grew from five capabilities to seven. This is why rider accounts and debtor standing were deferred rather than folded in.
- **The stress run is a late-phase milestone, not a per-phase gate** — it sits alongside the local-K8s deploy so early matching-correctness work stays lean, and it is what produces the concrete capacity numbers the architecture deliberately leaves underived.
- **A component may be turned off exactly when it sits behind an event boundary** (AD-48): the gateway, the three ride-path services and their stores, Redis and Kafka are required; `payment-service` degrades to rides stalling before dispatch; audit, ClickHouse, Prometheus, Grafana and the dashboard can be disabled with nothing breaking and no data lost.

## Beyond `v1.0`

The secondary goal is not code: translate the finished system into concrete CV bullets and interview talking points — the Postgres → ClickHouse migration narrative, the concurrency-safety story, the idempotent-webhook story, and the scale-stress story.
