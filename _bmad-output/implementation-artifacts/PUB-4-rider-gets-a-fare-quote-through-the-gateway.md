---
baseline_commit: 051f0a212065dcb5428b96aa7940363f25d95300
umbrella_of:
  - PUB-4-1
  - PUB-4-2
  - PUB-4-3
---

# Story 1.4: Rider gets a fare quote through the gateway

Ticket: **PUB-4**
Status: backlog — umbrella; `done` only when PUB-4-3 is `done`

> **This file is an index, not a specification. Nothing here binds an implementation.**
>
> It exists to answer one question: *which slice satisfies which acceptance criterion, and in what
> order do they ship.* The three slice files are self-sufficient, because `bmad-dev-story` loads one
> story file and that file has to be the whole brief. Rules and decisions therefore live **in the
> slices**, never here — CLAUDE.md: *"two files holding the same rule drift, and the stale copy
> outlives the true one."*
>
> **Do not implement from this file.** Open the slice you are working on.

## Why this is three tickets

Story 1.4 as specified in the epic is one story with nine acceptance criteria, touching around
thirty-five files across two services, a new gateway, a new contract directory and the build. There is
no CI server here — `pre-push` is the only gate — so a smaller diff per review is worth real money, and
PUB-3's review produced seventeen patches on a much smaller change.

**The scope is unchanged.** All nine criteria are delivered; they are delivered in three commits
instead of one. That is why there is no sprint change proposal: nothing was added, removed or
reinterpreted, only sequenced.

## The three slices, and why in this order

Built **inner-first** — the transport before the service, the service before the front door.

| # | Ticket | Delivers | File |
| --- | --- | --- | --- |
| 1 | **PUB-4-1** | The contract has one source; the fare calculator is reachable over gRPC | `PUB-4-1-the-contract-and-the-quote-over-grpc.md` |
| 2 | **PUB-4-2** | **FR-1 itself** — a rider gets a fare over HTTP | `PUB-4-2-rider-service-delivers-the-quote.md` |
| 3 | **PUB-4-3** | One published port; `matching-service` unaddressable; every request traced from the edge | `PUB-4-3-the-gateway-is-the-front-door.md` |

**The order is not a preference.** Two hard constraints fix it:

- **A gateway's only deliverable is a route, and a route needs a destination.** HAProxy resolves its
  `server` addresses at startup, so a backend naming a container that does not exist fails config
  validation. The gateway cannot ship before `rider-service`.
- **AC3 requires deleting `matching-service`'s published port.** Doing that before `rider-service`
  exists leaves the system with nothing reachable and no replacement — a regression window rather than
  an increment.

Each slice leaves the system working, and the riskiest work (the Boot 4.1 gRPC dependency set, protobuf
codegen, the interaction with Spotless and ArchUnit) lands in slice 1, against a service that already
has a green suite to notice a regression.

## Where each acceptance criterion is satisfied

The epic's numbering is the authority; `epic-1-foundations-fare-quote.md#Story 1.4` is where the
criteria are written. Four of them split across slices, and the split is named in each slice file as
`AC1a` / `AC1b` / `AC1c` and so on.

| Epic criterion | PUB-4-1 | PUB-4-2 | PUB-4-3 |
| --- | :---: | :---: | :---: |
| **AC1** — gateway routes to `rider-service`; `rider-service` obtains the quote over gRPC; no ride created | gRPC surface, creates nothing | the rider's request makes the gRPC call | HAProxy routes it |
| **AC2** — no driver ⇒ fare and distance, ETA omitted, success not error | ETA left unset on the wire | key absent from the JSON | — |
| **AC3** — `matching-service` not routable | — | — | **whole** |
| **AC4** — request id minted, propagated over gRPC metadata, in every log line and error | metadata → MDC → log lines | filter, client interceptor, error bodies | **minted at the gateway** |
| **AC5** — malformed request ⇒ RFC 9457 with the request id, status 400 | `INVALID_ARGUMENT` with the field named | the mapping to 400 Problem Details | — |
| **AC6** — rider identity header trusted as-is | — | **whole** | — |
| **AC7** — `rider-service` exposes health and metrics as `matching-service` does | — | **whole** | — |
| **AC8** — the contract has one source, copied at build time | **whole** | uses the same mechanism unchanged | — |
| **AC9** — contracts evolve by addition only | **whole** | — | — |
| **AC10** — value types reject input they cannot price *(carried from PUB-3)* | **whole** | — | — |
| **AC11** — the new service carries its own structural rules *(carried from PUB-2's review)* | the contracts-package hole in `matching-service` | **the copies into `rider-service`** | — |

AC10 and AC11 are not in the epic's own numbering — they are the two carried debts the epic's Story 1.4
section names in prose (the PUB-3 carry-forward note, and `deferred-work.md`'s PUB-2-review item that
cites this story by number).

## Criteria that are only partly satisfiable, whatever the split

Stated here once so each slice does not have to re-argue it, and so a reviewer is not surprised:

- **AC2's ETA-present branch is unreachable.** There are no drivers in the system until Epic 2, so the
  ETA is *always* absent. Story 2.6 (PUB-10) lights up the other branch. Do not manufacture a fake
  driver to exercise it.
- **AC9 has no test and cannot have one.** It is a rule about a future edit; there is no second version
  of the contract to compare against. What it gets is explicit field numbers, `optional` presence
  instead of a sentinel, and the rule written where the next editor reads it.
- **AC6 is thin by design.** "Trusted as-is" (FR-48) means there is almost nothing to assert: an unseen
  rider id works, a blank one is refused, and there is no registration to test the absence of.
- **AC7's "exactly as `matching-service` does" cannot be literal.** `rider-service` owns no database, so
  it has no `db` health contributor. What is identical is the surface, not the body.

## Status

`PUB-4` moves `backlog → in-progress` when PUB-4-1 starts, and `→ done` when PUB-4-3 is `done`. It is
not itself a unit of work: **no commit is made against `PUB-4` directly**, and its status is a claim
about all three slices together.

PUB-4-3's Task 5.4 re-checks this table before the parent is closed.

## Change Log

| Date | Change | By |
| --- | --- | --- |
| 2026-08-25 | Created as a single story from epic-1 Story 1.4 | bmad-create-story |
| 2026-08-25 | Split into PUB-4-1 / PUB-4-2 / PUB-4-3 at the repo owner's request, inner-first; this file reduced to the allocation index | bmad-create-story |
