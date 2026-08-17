---
title: Sprint Change Proposal — Containers never run as root
date: 2026-08-17
trigger_story: PUB-1 (Epic 1, Story 1.1)
scope_classification: Minor
status: approved-and-implemented
approved: 2026-08-17
---

# Sprint Change Proposal — Containers never run as root

## 1. Issue Summary

**Problem statement.** The epic's acceptance criteria require that every service builds and runs in
Docker with no host JDK (NFR-7), but say nothing about **which user those containers run as**. Eclipse
Temurin base images run as `root` by default, so the criteria as written are fully satisfiable by a
build in which every container — the service image, the build container, and the test runner — runs
with root privileges.

**Issue type:** new requirement emerged (security hardening), reinforced by a technical constraint
discovered while detailing PUB-1.

**How it was discovered.** During story detailing for PUB-1, while specifying the containerized test
runner (AD-56) and the multi-stage Dockerfile. The story was written with non-root guidance in its Dev
Notes, but as *house guidance with nothing gating on it* — a reviewer could not fail the story for
shipping root containers, because no acceptance criterion mentions it.

**Evidence.**

| Evidence | Consequence |
| --- | --- |
| Eclipse Temurin images declare no `USER`; the default is `root` | The service image ships running as root unless explicitly changed |
| NFR-7 puts **every** build and run inside a container | The exposure is on every build, not an occasional case |
| AD-56 requires the test runner to be a container with the repository available to it | It writes build output into a bind-mounted working tree as whatever user it runs as |
| Kubernetes `runAsNonRoot: true` cannot verify a **named** `USER` — only a numeric UID | A named non-root user still fails admission in Epic 7 (Stories 7.4, 7.5) |
| `ARCHITECTURE-SPINE.md` has no AD and no convention covering container runtime user | A genuine gap in the authority document, not a contradiction with it |

**Two distinct harms**, which is why one blanket rule is not sufficient:

1. **Security (primary).** The runtime service image runs with privileges it never needs. This
   propagates to the local Kubernetes deployment in Epic 7 and to all four services that follow, since
   each copies `matching-service`'s Dockerfile pattern.
2. **Practical.** Build and test containers bind-mount the repository. Running as root leaves
   root-owned files in the developer's working tree that cannot be removed without `sudo` — and
   because NFR-7 containerizes every build, this happens on the very first `make build`.

## 2. Impact Analysis

### Epic impact

| Question | Finding |
| --- | --- |
| Can Epic 1 be completed as planned? | **Yes.** No story added, removed, split, or resequenced |
| Epic-level change required? | Modify Story 1.1's acceptance criteria only |
| Future epics affected? | **Epic 7** benefits — a numeric non-root `USER` makes `securityContext.runAsNonRoot` straightforward in Stories 7.4 / 7.5 rather than a rework |
| New epics needed? | No |
| Resequencing needed? | No |

**Propagation is the important finding.** Four more services (`rider-service`, `driver-service`,
`payment-service`, `audit-service`) plus `simulator` each get a Dockerfile in later epics, and they
inherit `matching-service`'s pattern by imitation — Story 1.4 already says `rider-service` *"exposes
health and Prometheus metrics exactly as `matching-service` does"*. Adding this criterion **only** to
Story 1.1 would leave the rule enforced on one service and merely conventional on the other five.

### Artifact conflicts

| Artifact | Impact |
| --- | --- |
| **PRD** | **No change proposed.** NFR-7 covers containerization and NFR-10 covers secrets; container runtime user is *mechanism*, which the spine governs. Adding an NFR would be churn for no gain |
| **Architecture spine** | **Change proposed.** No AD or convention covers this. One row added to *Consistency Conventions* — a cross-cutting rule, not a decision needing a full AD's Prevents/Rule structure |
| **Epics — Story 1.1** | **Change proposed.** One acceptance criterion added (19 → 20 blocks) |
| **Epics — overview.md** | **Change proposed.** One entry added to *Standing acceptance criteria*, so later services inherit without restating |
| **UX** | **N/A.** No UX document exists — Puber is a backend system |
| **`deploy/` K8s manifests** | Downstream benefit in Epic 7: `securityContext.runAsNonRoot: true` becomes verifiable. No change now — `deploy/` is empty until Story 7.4 |
| **CI/CD** | **N/A.** No CI server exists by decision; the gate is the local git hooks |
| **`sprint-status.yaml`** | **No change.** No story added or removed; PUB-1 stays `ready-for-dev` |
| **Story file `PUB-1-*.md`** | Already carries the full implementation guidance in Dev Notes → *"Containers never run as root"*. Needs the new AC added so guidance and criteria agree |

### Sizing note — raised deliberately

The implementation-readiness review (finding **NEW-2**) recorded that Story 1.1 was already the
largest story in the document at 19 AC blocks — roughly 2.7× the median — and closed with *"Story 1.1
stays at 19 acceptance criteria. Reviewed and accepted by the user; not to be re-raised."*

This proposal takes it to **20**. That is **not** a re-litigation of the closed sizing decision — it is
a new requirement that did not exist when that decision was made. It is surfaced here so the increase
is a deliberate choice rather than an accidental erosion of a prior agreement. The criterion is
drafted as **a single block with And-clauses** rather than two separate blocks, specifically to keep
the increase to one.

## 3. Recommended Approach

**Option 1 — Direct Adjustment. Selected.**

| Option | Assessment |
| --- | --- |
| **1. Direct Adjustment** | **Viable — selected.** Effort **Low**, Risk **Low** |
| 2. Potential Rollback | **N/A.** Nothing is implemented; PUB-1 has not started. No code exists in the repository |
| 3. PRD MVP Review | **Not required.** No scope change, no goal change, no deferral |

**Rationale.** PUB-1 is `ready-for-dev` and unstarted, so this costs nothing but the edit — there is no
rework, no rollback, and no timeline impact. Landing it now is materially cheaper than later: the
Dockerfile pattern established in PUB-1 is copied by five more services, and retrofitting a non-root
user across six images plus their Kubernetes manifests is a far larger change than writing it once.

**Timeline impact:** none. **Risk:** low — the only implementation risk is the `GRADLE_USER_HOME`
writability trap, already documented in the story's Dev Notes.

## 4. Detailed Change Proposals

### 4.1 Epics — `epics/epic-1-foundations-fare-quote.md`, Story 1.1

**Append after the final existing AC block** (the `pre-commit` fast-static-checks block):

```
**Given** every container this project builds — the service image, the build container and the test runner
**When** any of them runs
**Then** none of them runs as `root`
**And** the service image declares its user **numerically** (`USER <uid>:<gid>`), because Kubernetes'
`runAsNonRoot` verifies a numeric UID and cannot resolve a name (AD-49)
**And** containers that mount the repository run as the **host user's UID and GID, supplied by
environment variables**, so nothing they write into the working tree is owned by `root` (NFR-7)
**And** this is proven by a check that no root-owned file exists after a full build and test run
```

**And append this note block** after the story's existing notes:

```
> **Stock images are out of scope, deliberately.** The rule binds containers **this project builds**.
> `matching-postgres` and every later datastore run stock images whose entrypoints already drop
> privileges themselves; forcing a `user:` onto them can break first-start initialisation. Use a named
> volume rather than a bind mount and there is no host-ownership problem to solve. The rule is "our
> containers do not run as root", not "every container gets a `user:` line".
```

**Rationale:** makes the security requirement gate-enforceable — a reviewer can now fail the story for
shipping root containers. The numeric-UID clause prevents an Epic 7 rework. The carve-out prevents an
over-literal reading that breaks Postgres.

### 4.2 Epics — `epics/overview.md`, Standing acceptance criteria

**Append to the existing bulleted list:**

```
- Any container **this project builds** runs as a **non-root** user — service images declaring it
  numerically, and any container mounting the repository taking the host UID/GID from environment
  (NFR-7). Stock datastore images keep their own entrypoint's user handling.
```

**Rationale:** this is the mechanism that makes the rule bind all five services without repeating the
criterion in five stories. It matches the section's stated purpose exactly — criteria that *"apply to
every story performing the action described, whether or not the story restates them."*

### 4.3 Architecture — `ARCHITECTURE-SPINE.md`, Consistency Conventions

**Append one row to the Consistency Conventions table**, after the `Testing` row:

```
| Container runtime | No container this project builds runs as `root`. Service images declare a numeric non-root `USER`, so a `runAsNonRoot` check can verify it without resolving a name; containers that mount the repository take the host UID/GID from environment configuration. Stock datastore images keep their own entrypoint's user handling. |
```

**Rationale:** the spine governs every technical decision, and it is currently silent here. A rule
living only in the epics would be invisible to anyone deriving from the spine — which is the stated
authority. A Conventions row is the right weight: this is a cross-cutting rule, not a decision needing
an AD's full *Binds / Prevents / Rule* structure.

### 4.4 Story file — `implementation-artifacts/PUB-1-*.md`

Add the new criterion as **AC20**, and update the Dev Notes section *"Containers never run as root"*
to drop its "house rule, not from the epic's ACs" framing — it is now an acceptance criterion.

**Rationale:** keeps the story file's criteria and its guidance consistent. The implementation
guidance itself needs no change; it already covers both cases, the numeric-UID reasoning, the
Postgres carve-out, and the `find . -user root` verification.

## 5. Implementation Handoff

**Scope classification: Minor** — direct implementation, no backlog reorganisation, no replan.

| Artifact | Action | Owner |
| --- | --- | --- |
| `epics/epic-1-foundations-fare-quote.md` | Add AC block + note to Story 1.1 | This workflow |
| `epics/overview.md` | Add standing acceptance criterion | This workflow |
| `ARCHITECTURE-SPINE.md` | Add Consistency Conventions row | This workflow |
| `implementation-artifacts/PUB-1-*.md` | Add AC20; adjust Dev Notes framing | This workflow |
| `sprint-status.yaml` | **No change** — no story added or removed; PUB-1 stays `ready-for-dev` | — |
| Implementation | Dev agent, when PUB-1 is developed | `dev-story` |

**Success criteria.**

1. `docker compose exec matching-service id -u` returns a non-zero UID.
2. `find . -user root -print -quit` outputs nothing after a full build and test run.
3. The service image's `USER` is numeric, so an Epic 7 `runAsNonRoot: true` pod admits without change.
4. `matching-postgres` is untouched and starts cleanly on a first run.

**Sequencing.** All four edits land before `dev-story` runs on PUB-1. No dependency on any other story.
