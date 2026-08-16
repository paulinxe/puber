## Epic 5: Payments — `v0.2`

A rider's fare is held when they request and taken when they arrive; a ride that delivers no trip
never costs them anything; and money that is genuinely lost is known rather than buried.

> **Capacity warning carried from `roadmap.md`.** This phase was sized before the two-phase lifecycle
> and both failure paths landed — it grew from five capabilities to seven. That is why rider
> accounts, debtor standing, partial refunds and rider-initiated refund requests stay deferred rather
> than being folded in. Resist adding to it.

### Story 5.1: payment-service and the payment state machine

As an operator,
I want `payment-service` running with its lifecycle declared as an explicit transition table,
So that four services never read payment outcomes with four different vocabularies.

**Acceptance Criteria:**

**Given** the Compose stack
**When** it is brought up
**Then** `payment-service` starts with its own private Postgres database (AD-1, AD-3)
**And** it exposes health and Prometheus metrics like every other service (AD-54)
**And** it carries its own Gradle wrapper and the standard package layout (AD-7, AD-52)

**Given** the `payments` table
**When** it is created
**Then** it holds exactly one payment per ride, enforced by a unique `ride_id`
**And** the provider's intent identifier is a **column**, named for the domain concept rather than
the provider, because the provider is swappable (AD-3, AD-50)

**Given** the payment lifecycle
**When** it is declared
**Then** it is an enum plus an allowed-transitions map: `INITIATED → AUTHORIZED → CAPTURED →
REFUNDED`, plus terminals `VOIDED`, `FAILED` and `CAPTURE_FAILED`
**And** illegal transitions raise rather than no-op, which is what makes a replayed provider webhook
safe (FR-35, AD-11, AD-50)

**Given** the declared payment transition table and the `payments` columns
**When** they are inspected
**Then** no `CAPTURING` state exists — a payment under active capture pursuit reads `AUTHORIZED`
through every retry, and `CAPTURED` is written only once the money has moved
**And** retry bookkeeping lives in columns on the row, never in a state (AD-50)

**Given** `FAILED` and `CAPTURE_FAILED`
**When** the transition table and every query over payment outcomes are inspected
**Then** they are two distinct terminal states, and **no query counts one as the other** — capture
loss (AD-54) counts `CAPTURE_FAILED` alone
**And** this is a deliberate divergence from AD-18's fold-provenance-into-a-column rule, because they
differ in **kind**: `FAILED` is a ride that never happened and cost nothing, `CAPTURE_FAILED` is a
delivered trip whose money is gone, and only the second is revenue loss (AD-50)

**Given** `CAPTURE_FAILED`
**When** its outgoing transitions are inspected
**Then** it has none — it is terminal for this build, and any transition attempted out of it raises
**And** leaving it later is therefore a state-machine change — a newly declared transition or a second
payment for the ride — never a background job added quietly (AD-50)

**Given** any payment state entry, `INITIATED` included
**When** it commits
**Then** an event is written to `payment-service`'s own transactional outbox in the same transaction
**And** no payment state exists that a downstream projection could never hear about (AD-28, AD-59)

**Given** monetary values
**When** they are handled
**Then** they are integer minor units in transit and `DECIMAL` at rest, never floating point (Money convention)

### Story 5.2: Provider strategy and a stub that can produce every outcome

As an engineer,
I want the provider behind a strategy whose stub can produce every settlement outcome on demand,
So that the stress run and credential-free test runs exercise the real payment path rather than code that exists only in tests.

**Acceptance Criteria:**

**Given** the two strategy layers
**When** they are placed
**Then** `matching-service` holds the outbound `PaymentGateway` — call `payment-service`, or signal
authorised immediately
**And** `payment-service` separately holds the `PaymentProvider` — real provider, or stub (AD-43)

**Given** the provider strategy
**When** its outcome set is defined
**Then** it carries the **three-way capture answer** — settled, uncapturable, or neither —
**and the two-way void answer** — released, or the provider reporting the hold already gone (AD-43, AD-58)

**Given** the stub
**When** it is exercised
**Then** it can produce every one of those outcomes on demand
**And** without that, the retry-until-settled path, `CAPTURE_FAILED` and the durable void are never
exercised outside production (AD-43)

**Given** the stub and the real provider
**When** either is active
**Then** no caller inspects the concrete type or branches on which is in use
**And** the stub delivers its outcome through the **same channel** as the real one, merely faster — a
stub resolving synchronously where the provider resolves asynchronously would mean the stress run
never exercises the asynchronous path (AD-57)

**Given** the NFR-2 stress run
**When** payments are excluded from it
**Then** the exclusion is achieved by swapping the **provider**, keeping the whole payment state
machine in the flow
**And** never by skipping the payment path, which would stress-test code that does not exist in
production (NFR-8, AD-43)

**Given** no provider credentials configured in the environment
**When** the full suite runs
**Then** it runs against the stub and passes
**And** a fresh clone is therefore testable without obtaining sandbox credentials first (AD-43, NFR-10)

**Given** provider API keys
**When** they are configured
**Then** they live in environment configuration
**And** never in source, fixtures, or manifests (NFR-10, AD-49)

**Given** the real provider
**When** it is wired
**Then** it is the Stripe Java SDK 33.3.x against the sandbox, and no real card data exists anywhere (Stack, NFR-10)

### Story 5.3: Authorization gates dispatch, asynchronously

As a rider,
I want my fare held before any driver is dispatched, and my ride identifier back immediately anyway,
So that no driver drives toward me for a ride whose funds nobody holds, and I never wait on a payment provider to learn my ride exists.

**Acceptance Criteria:**

**Given** the ride request
**When** the payment-method token joins its contract
**Then** the token is **required**, and is a dedicated value type whose `toString()` is masked
**And** it is never persisted, never echoed in a response, and never written to a log
**And** it passes through to `payment-service` as the only component that talks to the provider (FR-2, AD-42, NFR-10)

**Given** the token
**When** a leak test runs
**Then** it appears in no log line, no response body, and no stack trace (NFR-10)

**Given** deliberately-declining test tokens
**When** they are used
**Then** the authorization-failure path is exercised routinely rather than theoretically
**And** they are supplied as test fixtures here; the Simulator exercises the same path under load in
Epic 7 (FR-9, FR-49)

**Given** `matching-service`'s `PaymentGateway`
**When** this story lands
**Then** it calls `payment-service` instead of signalling authorised immediately
**And** the immediate-authorise implementation is retired from runtime, remaining only for test runs
that exclude payments and for phases before `payment-service` existed
**And** it is never selectable as a degradation for a payment outage, which would dispatch rides
against funds nobody holds (AD-43)

**Given** a ride request
**When** it is admitted
**Then** the ride is persisted `REQUESTED` and its identifier returned immediately
**And** authorization proceeds asynchronously from there (AD-41, FR-9, FR-34)

**Given** the authorization outcome
**When** it resolves
**Then** a landed hold moves the ride to `WAITING_MATCH`
**And** a decline moves it to terminal `PAYMENT_FAILED`, and no driver is ever offered it (FR-9)

**Given** the handler applying that outcome
**When** it runs
**Then** it performs exactly one guarded transition
**And** that alone makes it idempotent, with no dedupe table required (AD-41)

**Given** a ride awaiting authorization
**When** it waits
**Then** it has **no timeout at all**
**And** it is surfaced as a metric and an alert, with the rider's own cancellation as the exit
**And** this is because a timeout would free the rider to request again while their first hold is
outstanding, doubling holds across every stuck rider against a rate-limited provider exactly when the
system can least cope (AD-45, FR-9)

**Given** a rider whose ride is stuck awaiting authorization
**When** they request another
**Then** the one-active-ride rule refuses them, which throttles load naturally during an outage (AD-45, FR-3)

**Given** `payment-service` unavailable
**When** rides are requested
**Then** requests still succeed and rides stall before dispatch
**And** `payment-service` is therefore Tier 2, never promoted onto the Tier 1 request path (AD-48)

### Story 5.4: Ride completion durably triggers capture

As an operator,
I want capture driven by a durable worker triggered by the completion event rather than an inline call,
So that a pod dying mid-pursuit resumes the capture instead of silently writing off a valid hold.

**Acceptance Criteria:**

**Given** a `ride.completed` event
**When** `payment-service` consumes it
**Then** it **stamps** `capture_requested_at` on the payment
**And** ride completion never calls capture inline, so the worker never joins across a service
boundary to learn a ride is `COMPLETED` (AD-58, AD-1)

**Given** that stamp
**When** it is written
**Then** it is a guarded update keyed on the unique `ride_id`, conditioned
`WHERE status = 'AUTHORIZED' AND capture_requested_at IS NULL`
**And** a redelivered `ride.completed` is a no-op that **never resets `attempts` or `next_attempt_at`**,
because redelivery restarting the backoff would reopen the request-rate hole the ceiling closes (AD-58)

**Given** the first stamp
**When** it succeeds
**Then** it also sets `attempts = 0` and `next_attempt_at = :now`, bound from the `Clock` strategy
**And** leaving `next_attempt_at` NULL would make the row never claimable, so nothing would capture
at all while the gauge read a healthy zero (AD-58)

**Given** a stamp affecting zero rows
**When** it is handled
**Then** it means the hold has already ended — a legitimate no-op
**And** it is neither treated as AD-15's rejection nor dead-lettered (AD-58)

**Given** the capture worker
**When** its claim predicate is written
**Then** it is literally `status = 'AUTHORIZED' AND capture_requested_at IS NOT NULL AND
next_attempt_at <= :now`, where `:now` is bound from the `Clock` strategy
**And** dropping the `capture_requested_at` term would capture every hold the moment it is
authorised — before dispatch, on rides that never happened (AD-58)

**Given** the worker
**When** it runs
**Then** it is a durable claim-loop inside `payment-service` claiming with `FOR UPDATE SKIP LOCKED`,
attempting, and re-claiming (AD-58, AD-20)

**Given** retry state
**When** it is stored
**Then** it is columns on the `payments` row — `capture_requested_at`, `attempts`, `next_attempt_at` —
never an in-memory schedule or a delayed task in a running process
**And** a restart therefore resumes the pursuit rather than forgetting it (AD-58, FR-37)

**Given** the circuit breaker
**When** it is evaluated
**Then** it is checked **before claiming**, so an open breaker leaves rows due and untouched rather
than burning attempts on a dependency known to be down (AD-58, AD-34)

**Given** every `now()` in a claim or backoff predicate
**When** it is written
**Then** it is a bind parameter from the `Clock` strategy, never SQL `now()`
**And** otherwise the ceiling and the cooldown could only be tested by waiting, which the Testing
convention forbids and which means the tests that would catch a broken predicate never get written
(AD-58, NFR-9)

### Story 5.5: Capture outcomes are classified and unrecoverable loss is counted

As an operator,
I want every capture attempt classified by what the provider actually said, and genuine loss counted,
So that a slow provider is never mistaken for lost money, and lost money is never buried.

**Acceptance Criteria:**

**Given** a capture attempt
**When** the provider answers
**Then** it classifies exactly three ways: settled → `CAPTURED`; the hold uncapturable →
`CAPTURE_FAILED`; **anything else — unreachable, timeout, 5xx, rate-limited, ambiguous — is not an
outcome and reschedules** (AD-58, AD-50)

**Given** a provider answer that is **not** expired, revoked or cancelled
**When** it is classified
**Then** it **never** yields `CAPTURE_FAILED` — only those three verdicts do, and nothing else
qualifies
**And** the terminal state therefore always rests on a positive statement from the provider rather
than an inference from silence (FR-37, AD-50)

**Given** an ambiguous answer
**When** it is classified
**Then** it always reschedules
**And** only an explicit provider verdict is ever terminal (AD-58)

**Given** a provider answering *already captured*
**When** it is classified
**Then** it counts as **settled**, never as ambiguity
**And** read as ambiguity it would be pursued forever and alerted as a loss it is not (AD-58)

**Given** unbounded retries
**When** an attempt is made
**Then** it carries an idempotency key derived from the payment id
**And** a retry after an ambiguous answer therefore cannot capture twice (AD-58)

**Given** backoff
**When** it is applied
**Then** it is exponential with jitter up to a ceiling, then **flat at that ceiling indefinitely**
**And** the bound moves from the *number* of attempts to the *interval* between them, so an unbounded
pursuit still presents a bounded request rate (AD-58, FR-37)

**Given** a capture failing repeatedly without a provider verdict
**When** the payment row is inspected after any number of attempts
**Then** `dead_at` is never stamped and the row stays claimable — there is **no retry cap and
therefore no dead-letter path**, and AD-34's cap-then-dead-letter explicitly does not apply here
**And** the row is watched by the oldest-retrying gauge instead (AD-58, AD-55)

**Given** a payment reaching `CAPTURE_FAILED`
**When** the ride is read
**Then** it is still `COMPLETED`, because the trip did happen
**And** it is never routed to the ride machine's `PAYMENT_FAILED`, which would drop those rides out of
every "completed rides" query (AD-50)

**Given** the transition to `CAPTURE_FAILED`
**When** it is recorded
**Then** `capture_failed_at` is stamped at the transition
**And** it is carried in the `payment.capture_failed` payload (AD-58, AD-32)

**Given** **capture loss**
**When** it is exported
**Then** it is `count(*)` and `sum(amount)` over `CAPTURE_FAILED`, read from the `payments` table
rather than an in-process counter that resets with the pod
**And** it is summed from the stored `DECIMAL` and exported in integer minor units
**And** it is zero in a healthy system and alerts on its own (AD-54, Metrics convention)

**Given** **the age of the oldest capture still retrying**
**When** it is exported
**Then** it is `max(:now − coalesce(capture_requested_at, void_requested_at))` in seconds — with
`:now` bound from the `Clock` strategy, so the gauge itself is assertable under a controlled clock — over
claimable rows — **coalesced, because a void stuck against a broken provider is a live AD-44 breach
that a capture-only gauge cannot see**
**And** it **includes rows an open breaker has left untouched**, since excluding them flattens the
gauge through exactly the outage it exists to lead (AD-54)

**Given** **payment success rate**
**When** it is exported
**Then** it is the proportion of payments reaching a settled outcome against those that did not,
derived from the `payments` table alongside the money gauges
**And** it is throughput rather than money, so it is read and alerted separately from capture loss
(FR-46, AD-54, Metrics convention)

> **Which gauge to watch, and in what order.** During a provider outage **oldest-retrying is the
> leading indicator** — it moves while the money is still recoverable. Capture loss only ever confirms
> that the other gauge was read too late: by the time a payment reaches `CAPTURE_FAILED` the money is
> already gone. Both alert independently, but an alert on capture loss is a post-mortem, not a
> warning (AD-54).

### Story 5.6: Holds are released on rides that delivered no trip

As a rider,
I want any hold released when my ride ends without a trip,
So that I am never left with money reserved against a ride that delivered nothing.

**Acceptance Criteria:**

**Given** a ride reaching `CANCELLED` or `NO_DRIVER`
**When** the event is consumed
**Then** it stamps `void_requested_at` on the payment, guarded
`WHERE status IN ('INITIATED','AUTHORIZED') AND void_requested_at IS NULL`
**And** it sets the same two scheduling columns, so a void is exactly as restart-proof as a capture (AD-44, AD-58)

**Given** a cancellation landing while authorization is still in flight
**When** the authorization later resolves to `AUTHORIZED`
**Then** it does **not** void inline — it leaves the stamped row for the worker
**And** the invariant therefore survives a restart rather than depending on an in-process call
completing (AD-44)

**Given** a *declined* authorization
**When** it resolves against a stamped void
**Then** it resolves to `FAILED` regardless of the stamp, because there is no hold to release
**And** `VOIDED` is therefore reachable only from `AUTHORIZED` (AD-44)

**Given** a `COMPLETED` ride
**When** payment is considered
**Then** it never stamps a void — a delivered trip is captured
**And** the two stamps are mutually exclusive because the ride states writing them are: `COMPLETED`
stamps capture, `CANCELLED` and `NO_DRIVER` stamp void (AD-44, AD-58)

**Given** the settlement worker
**When** it claims voids
**Then** it claims `status = 'AUTHORIZED' AND void_requested_at IS NOT NULL AND next_attempt_at <= :now`,
with `:now` bound from the `Clock` strategy
**And** it is the same worker on the same ceiling as capture (AD-58)

**Given** a void attempt
**When** the provider answers
**Then** it classifies **two** ways: released → `VOIDED`; and **the provider reporting the hold
already gone also settles as `VOIDED`**, because the outcome the void wanted has been achieved
**And** there is no `VOID_FAILED` to invent; anything else reschedules (AD-58)

**Given** an `AUTHORIZED` hold far older than any plausible trip whose ride has **not** reached a
terminal state
**When** the settlement worker runs
**Then** it does not act on it — there is **no automatic voiding sweep**, and no claim predicate
anywhere reads a hold's age
**And** this is because such a hold may belong to a genuinely long live trip, and voiding it would
leave a completed ride with nothing to capture (AD-44)

**Given** any hold that has reached a terminal state
**When** that state is read
**Then** it is exactly one of `CAPTURED`, `VOIDED` or `CAPTURE_FAILED` — three endings and no fourth
**And** that no hold sits in limbo is asserted by a test over the payment machine and monitored by an
alert in production (AD-44)

### Story 5.7: Provider webhooks are verified and applied once

As an operator,
I want provider callbacks trusted only when authentic and applied only once,
So that a forged payload changes nothing and a redelivery cannot advance a payment twice.

**Acceptance Criteria:**

**Given** an inbound webhook
**When** it arrives
**Then** the gateway routes it to `payment-service`'s webhook endpoint
**And** that endpoint is the **only** `payment-service` surface the gateway exposes — it exists because
the provider must reach it, and no other `payment-service` route is created; a rider reads a payment
outcome through `rider-service` (Story 5.10), never `payment-service` directly (AD-5, AD-61)

**Given** a webhook payload
**When** it is received
**Then** its signature is verified
**And** a forged payload is rejected, proven by a test rather than by inspection (FR-38)

**Given** the `webhook_events` table
**When** it is created
**Then** it uses the provider's event id as the dedupe key, alongside type and payload (AD-3)

**Given** the same provider event delivered twice
**When** the second arrives
**Then** it is deduplicated and changes nothing
**And** this is proven by a test (FR-38, NFR-4, AD-36)

**Given** a replayed webhook attempting an illegal transition
**When** it is applied
**Then** the state machine raises rather than no-ops, which is the second line of defence behind the
dedupe (AD-11, AD-50)

### Story 5.8: A captured payment can be refunded

As an operator,
I want a completed, captured payment refundable end to end,
So that money taken in error can be given back even though riders cannot request it themselves.

**Acceptance Criteria:**

**Given** a `CAPTURED` payment
**When** an internal operator-facing call issues a refund
**Then** the refund is issued against the provider
**And** the payment moves to `REFUNDED` (FR-39, AD-50)

**Given** the refund webhook
**When** it arrives
**Then** it is processed idempotently and the result reconciles (FR-39)

**Given** the gateway's route table and every rider-facing endpoint
**When** they are inspected
**Then** none reaches the refund trigger — there is **no rider-facing way to request a refund**, and
the trigger is **not a gateway route**
**And** it is reached directly inside the cluster as operator surfaces are, which is a stronger
boundary than exposing a route; it is exercised by tests and the Simulator (FR-39, Non-goals, AD-5)

**Given** the refund action
**When** it is recorded
**Then** the acting actor type is `Admin`, which distinguishes an operator-triggered action from a
`SYSTEM` one and is not an authenticated role (Glossary, FR-41 groundwork)

**Given** a ride auto-completed by the system
**When** an operator chooses to refund it
**Then** the refund succeeds through the same path, since `completed_by` is a column rather than a
distinct state (AD-18, FR-14)

**Given** `CAPTURED`
**When** its outgoing transitions are inspected
**Then** `REFUNDED` is the only one, reachable solely by a deliberate operator-triggered refund (AD-50)

### Story 5.9: Reconciliation surfaces what delivery missed

As an operator,
I want missed webhook deliveries and implausibly long-lived holds surfaced,
So that they are caught rather than accumulating silently.

**Acceptance Criteria:**

**Given** a reconciliation task
**When** it runs
**Then** it detects missed or failed webhook deliveries (FR-40)

**Given** a hold outstanding longer than a ride can plausibly live
**When** reconciliation runs
**Then** it is flagged (FR-40)

**Given** either finding
**When** it is surfaced
**Then** **neither is corrected automatically**
**And** this is because a hold that looks stranded may belong to a genuinely long trip, and voiding
it would invent an answer the system does not have (FR-40, AD-44)

**Given** a `CAPTURE_FAILED` payment
**When** reconciliation encounters it
**Then** it is not recovered — the state is terminal for this build
**And** recovering one later is a state-machine change rather than an extension of this task (AD-50)

> **Open decision — the reconciliation mechanism.** To be settled when this story is detailed. This
> story is deliberately thinner than its neighbours because **its source is**: AD-50, AD-58 and AD-59
> specify the payment machine, the settlement worker and the projection down to literal predicates,
> while reconciliation has no AD of its own — FR-40 and one clause of AD-44 are the entire input.
>
> Five questions to answer, and one constraint that binds whatever the answers are:
>
> 1. **Schedule.** What cadence, and driven by the `Clock` so it is testable without waiting (NFR-9)?
> 2. **Missed-webhook detection.** Poll the provider's event list forward from a durable watermark, or
>    compare local `payments` against provider intents? The first needs a stored watermark and
>    inherits AD-29's cursor hazard; the second costs work proportional to open payments.
> 3. **"Longer than a ride can plausibly live."** Derived from AD-46's ordered set — the `IN_PROGRESS`
>    staleness window is already the system's bound on a single trip — or a new constant? If new, it
>    **joins that ordered set and is ordered against it**, never chosen alone (AD-46).
> 4. **What "flagged" means concretely** — a gauge, an alert, a table, or an operator-facing query.
> 5. **Which holds this actually covers.** AD-54's oldest-retrying gauge already watches rows with
>    `capture_requested_at` or `void_requested_at` stamped. A hold with **neither** — an `AUTHORIZED`
>    payment whose ride never reached a terminal state — is watched by nothing, and is precisely the
>    stranded case FR-40 describes. Scope this to that gap rather than duplicating the gauge.
>
> **The binding constraint: reconciliation observes and never corrects** (FR-40, AD-44). It must not
> duplicate or race AD-58's settlement worker, and it must not void a hold that merely looks stale —
> that hold may belong to a genuinely long live trip, and voiding it would leave a completed ride with
> nothing to capture.
>
> **If the answer introduces mechanism, it likely belongs in the spine as an AD** rather than living
> only here — the same gap AD-47 has with storage ceilings.

### Story 5.10: Rider sees how the money ended

As a rider,
I want to see how the money ended on a finished ride,
So that the payment outcome is something I can read rather than infer.

**Acceptance Criteria:**

**Given** a `COMPLETED` ride whose payment settled
**When** the rider reads it
**Then** they see the final fare and whether it was **captured** or **refunded** (FR-7)

**Given** a `COMPLETED` ride whose payment reached `CAPTURE_FAILED`
**When** the rider reads it
**Then** they see that it was left uncaptured (FR-7, FR-37)

**Given** a `PAYMENT_FAILED` ride
**When** the rider reads it
**Then** they see that authorization was declined (FR-7)

**Given** a capture still being retried
**When** the rider reads the ride
**Then** it reports **settlement in progress** — never "uncaptured", and never nothing at all
**And** exactly four values are outcomes: `CAPTURED`, `REFUNDED`, `CAPTURE_FAILED`, `FAILED`; an
`AUTHORIZED` payment on a `COMPLETED` ride is AD-58's live pursuit, not one of them (FR-7, AD-61)

**Given** the outcome read
**When** it is served
**Then** it hangs off the ride as a sub-resource, served by `rider-service`, which makes **two** calls:
ride detail from `matching-service` — establishing the ride is this rider's and is terminal, so an
identity mismatch is 404 — and the outcome from `payment-service` by `ride_id` over gRPC (AD-61, AD-39, AD-38)

**Given** AD-59's admission projection
**When** a source for this read is chosen
**Then** it is **never** used — being advisory and fail-open is what makes it right for admission and
wrong for money, and reading it here would give one row's absence two meanings (AD-61, AD-59)

**Given** `payment-service` unavailable
**When** the rider reads the outcome
**Then** the read answers `UNAVAILABLE` → 503 — **no answer, never a guessed one**
**And** the ride-request path is untouched, because admission does not travel this edge (AD-61, AD-48)

**Given** the gRPC contract for this read
**When** it is defined
**Then** it is segregated by consumer — its own service definition, never a method bolted onto the
settlement contract `matching-service` calls (AD-61, AD-57)

**Given** a rider whose trip was auto-completed and then charged
**When** they look for a remedy
**Then** there is none in the system — no way to contest, no review workflow, no dispute status
**And** this is stated deliberately rather than omitted (Non-goals, FR-14)

### Story 5.11: Ride admission reads the payment-settlement projection

As an operator,
I want ride admission to check a rider's outstanding money against a local projection rather than a live call,
So that one rider cannot stack unlimited unpaid trips, without making ride requests impossible during a payment outage.

**Acceptance Criteria:**

**Given** the three refusal grounds
**When** they are evaluated
**Then** AD-14's one-active-ride index is evaluated **first**, always yielding `ALREADY_EXISTS` → 409
**And** the two payment refusals apply only when no active ride exists (AD-59)

**Given** both payment refusals
**When** they select the ride to read
**Then** both read the rider's **most recent ride**, ordered by the `rides` identity bigint
**And** earlier rides are never considered, or one stuck hold would refuse a rider forever (AD-59)

**Given** the two arms
**When** they are ordered
**Then** arm 1 is evaluated before arm 2, and both read that same anchor ride and its single payment row
**And** exactly one reason is emitted per refused request, so the label set is a true partition rather
than an arbitrary pick between two matching arms (AD-59)

**Given** arm 1 — a most-recent ride `COMPLETED` with its payment still `AUTHORIZED`
**When** a new request arrives
**Then** it is refused until the payment settles, **or until the session-expiry bound lapses measured
from `capture_requested_at`, whichever comes first**
**And** the bound is not optional: capture pursuit is unbounded, so "until it settles" alone would
refuse every rider who completed a trip for the entire length of a provider outage (FR-51, AD-59)

**Given** a most-recent ride that is `CANCELLED`, `NO_DRIVER` or `PAYMENT_FAILED`
**When** a new request arrives
**Then** it is **never** refused on arm 1, because that hold is being voided rather than captured
**And** refusing there would revoke AD-45's promise that the rider's own cancellation is their exit (AD-59)

**Given** arm 2 — the anchor ride's payment reached `CAPTURE_FAILED` inside the 30-minute cooldown
**When** a new request arrives
**Then** it is refused until the window lapses on its own
**And** the window is measured on wall clock from the recorded `capture_failed_at`, restarted by each
new failure and never stacked (FR-51, AD-59, AD-46)

**Given** the facts both arms read
**When** `matching-service` obtains them
**Then** it reads a **local read-model projection fed by the payment topic**
**And** never a synchronous gRPC call on the request path, which would turn a `payment-service`
outage from "rides stall before dispatch" into "no ride can be requested at all" (AD-59, AD-48)

**Given** the projection
**When** a row is absent
**Then** it means "no reason to refuse", **never** "refuse"
**And** cold start, rebuild and consumer lag therefore degrade to AD-14 alone rather than refusing
every rider at once (AD-59, AD-26)

**Given** the projection's shape
**When** it is defined
**Then** it is one row per `ride_id` — `(ride_id, payment_status, capture_requested_at,
capture_failed_at, last_event_id)` — joined to the local `rides` table on read
**And** no `rider_id` is added to payment payloads by a contract change nobody owns (AD-59)

**Given** the projection
**When** it must be rebuilt
**Then** replaying the payment topic from `earliest` reconstructs it (AD-59, AD-36)

**Given** `capture_failed_at`
**When** the projection stores it
**Then** it is stored **verbatim** from the event payload
**And** the projection never substitutes its own receipt time, or a replay-from-`earliest` rebuild
would re-date every historical failure to now and refuse that whole rider population at once (AD-58)

**Given** each of the three refusals
**When** it surfaces
**Then** it carries a reason token in the gRPC error detail, rendered by the façade as a distinct,
stable RFC 9457 `type` URI whose final segment is that same token
**And** `detail` prose is never the discriminator, since `FAILED_PRECONDITION` → 409 alone cannot tell
the two payment refusals apart (AD-59, AD-38)

**Given** the reason tokens surfaced in gRPC error details and the `reason` label values on the
refusal counter
**When** a test compares the two sets
**Then** they are the **same values**, drawn from the single closed enum of Story 3.3 rather than
declared twice
**And** a reason added, split or renamed therefore changes both together, so the API and the metric
can never disagree about why a rider was turned away (AD-59, AD-54)

**Given** the refusal counter
**When** the two payment reasons are added
**Then** they join the closed enum registered in Story 3.3
**And** every alert expression, recording rule and dashboard panel over this counter **groups by
`reason`**, with no aggregation across reasons permitted anywhere (AD-54)

**Given** the three reasons
**When** they are alerted on
**Then** the cooldown reason is **zero in health** and alerts on sustained nonzero; the unsettled-trip
reason has a **nonzero healthy baseline** and alerts on deviation, never on a nonzero absolute; and
the active-ride reason is counted for baseline and **never alerts** (AD-54)

**Given** the projection's consumer lag
**When** it is observed
**Then** it is a gauge on an AD-55-governed consumer
**And** both alerting reasons are read **together with** that gauge, because fail-open drives them
toward zero exactly when the projection is the broken thing (AD-59, AD-54)

**Given** `payment-service` unavailable
**When** riders request rides
**Then** requests still succeed, because the check reads a local projection
**And** the Tier 1 / Tier 2 boundary is preserved rather than quietly crossed (AD-48, AD-59)

