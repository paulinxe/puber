---
title: Ticket-to-PRD Reconciliation — Weeks 1–7 (PB-1.1 → PB-7.1)
status: draft
created: 2026-08-02
---

# Reconciliation: Weeks 1–7 Tickets vs. PRD (prd.md + addendum.md)

## Method

Read `docs/tickets/pb-1.1.md` through `pb-7.1.md` in full (goals, acceptance criteria, explicit
out-of-scope notes) and cross-checked every product-facing behavior/capability they commit to
against the PRD's Features (FR-1–FR-28), NFRs, Non-Goals, and the addendum's architecture-decision
table. The filter applied: only flag a gap if it is a *capability or behavior contract a user/client
of the API would observe* — not SQL/index/migration-numbering/test-framework mechanics, which
correctly belong at the ticket layer.

Scope note: PB-1.1 through PB-7.1 cover only the V1 (Weeks 1–7) slice — bootstrap, domain model,
matching, cancellation, docs, and SQL/query-tuning theory. No payments, Kafka, audit, or real-time
tickets exist yet in this range, so this reconciliation cannot speak to FR-12 through FR-25/28.

## Findings — real product-level gaps

### 1. "One active ride per rider" is a hard business rule the tickets enforce but the PRD never states

- **Where in tickets:** PB-2.1.3 (`POST /rides`) — `RideRepository.hasActiveRide(riderId)` checks
  for any ride not in `CANCELLED`/`COMPLETED`; if one exists, the request is rejected with
  `RiderAlreadyHasActiveRideException` → `409 Conflict`. This rule is exercised again in PB-3.1.4
  and is treated as a first-class exception type (`ConflictException` subclass), not an
  afterthought.
- **PRD coverage:** None. FR-1 through FR-8 (Ride Lifecycle & Matching) describe the request →
  match → complete flow and cancellation rules (FR-5), but nowhere state that a rider is limited to
  one concurrent active ride, nor that violating this returns a conflict. NFR-4 talks about
  invalid *state-machine* transitions, which is a different concern (a single ride's status
  transitions) — not the *cross-ride* constraint of "only one active ride per rider at a time."
- **Why it's product-level, not mechanical:** This is a customer-facing rule with an observable
  contract (an HTTP status code tied to a business condition), directly analogous to the
  double-booking rule already captured for drivers in FR-6. Riders should arguably get the
  symmetric guarantee stated explicitly.
- **Suggested fix:** Add to Section A (Ride Lifecycle & Matching), e.g. as an addition to FR-1 or
  a new FR: "A rider may have at most one active ride at a time (not `CANCELLED`/`COMPLETED`);
  requesting a new ride while one is active is rejected."

### 2. Cancellation ownership check deliberately returns 404 (not 403) to avoid leaking ride existence — undocumented in the PRD

- **Where in tickets:** PB-4.1.1, `CancelRide` service: "If `ride.riderId()` does not equal
  `riderId`, throw `RideNotFoundException` (do not leak existence)." This is an explicit, called-out
  security/privacy design decision — a rider probing a `rideId` that isn't theirs gets the same
  404 as a nonexistent ride, not a 403 that would confirm the ride exists.
- **PRD coverage:** FR-5 states only "Rider may cancel only while `REQUESTED` or `MATCHED`" —
  nothing about ownership verification or the anti-enumeration behavior. FR-26 addresses identity
  trust generally ("no auth... identity passed per-request and trusted as-is") but doesn't cover
  the authorization contract for actions on *someone else's* resource, which is a distinct concern
  from authentication.
- **Why it's product-level, not mechanical:** This is exactly the class of behavior the task
  brief called out as a genuine gap example — a deliberate API contract choice (which status code,
  and why) that shapes what a client integrating against the system should expect, not an
  implementation detail like a specific SQL clause.
- **Suggested fix:** Extend FR-5 (or FR-26, since it's adjacent to the "trusted identity" framing)
  with: "Actions on a ride by a non-owning rider identifier behave identically to the ride not
  existing (404), to avoid leaking ride existence to unauthorized callers."

### 3. Driver accept/complete is guarded against acting on rides not currently offered to that driver

- **Where in tickets:** PB-3.1.3, `driver-api` `POST /rides/{id}/accept`: if the driver has no
  stored active request (`DriverRequestRepository.findByDriverId` is empty) → `409` via
  `NoActiveRequestException`; if the stored request's `rideId` doesn't match the path `{id}` → also
  `409`. This means a driver cannot accept/complete an arbitrary ride ID — only the one currently
  offered to them, within their offer window.
- **PRD coverage:** FR-3 covers only the *timeout* side of the offer lifecycle ("bounded window to
  accept... offer expires and re-offers"). It says nothing about what happens if a driver attempts
  to act on a ride that was never offered to them, or that no longer matches their live offer. This
  is a distinct, non-obvious product/API contract (rejecting off-script driver actions) that a
  driver-app implementer would need to know about.
- **Suggested fix:** Extend FR-3 with something like: "A driver may only accept/complete the ride
  currently offered to them; acting on any other ride ID, or acting with no live offer, is
  rejected."

## Non-findings — checked and NOT gaps

For completeness, behaviors considered and judged already covered or out of this reconciliation's
direction:

- **`GET /rides/history` pagination shape** (PB-7.1.1 ships a simple `limit`-only endpoint, no
  cursor) vs. PRD FR-8's "paginated ride history" wording — this is the *reverse* direction (PRD
  states a capability the ticket implements more simply), not a ticket-committing-something-the-PRD-lacks
  case, so it's out of scope for this reconciliation. Worth a PM glance separately if "paginated"
  was meant to imply cursor-based paging.
- **Driver-side cancellation is unsupported** (PB-4.1 explicitly notes this as out of scope) —
  consistent with FR-5's rider-only framing; not a gap.
- **Race-safe matching / no double-booked drivers** — fully covered by FR-6 and NFR-1.
- **Geo-bounded operating area (4km×4km Lisbon square) and out-of-bounds → 400** — already captured
  in the addendum's architecture-decisions table; not a PRD-level product capability so much as a
  simulation/demo constraint, and it's already documented at the appropriate layer.
- **Distinction between "trip duration ETA" (`estimatedDurationMinutes`, shown to rider at request
  time) and "driver's ETA to pickup" (`PickupEtaCalculator`, shown to the driver in the offer)** —
  both are real, and PRD FR-1 only mentions a single undifferentiated "ETA." This is a minor
  terminology ambiguity worth a PM clarification pass, but doesn't rise to a missing capability
  (some ETA concept is already present in the PRD) — flagged here for awareness, not counted as a
  formal gap.

## Summary

3 material product-level gaps found in the Weeks 1–7 ticket set relative to the PRD's Features/NFRs:
(1) no PRD statement of the one-active-ride-per-rider rule and its 409 behavior, (2) no PRD
statement of the anti-enumeration 404-on-mismatched-cancel-ownership behavior, (3) no PRD statement
of the driver accept/complete guard against acting on rides not currently offered to them. All three
are genuine behavior contracts the tickets commit to in acceptance criteria, at the same altitude as
the PRD's existing FRs (e.g. FR-5, FR-6, FR-3) — they read like they were meant to be FRs but got
left at the ticket layer.
