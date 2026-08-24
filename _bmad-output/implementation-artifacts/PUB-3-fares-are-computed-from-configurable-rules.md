---
baseline_commit: 2640dbe6eac8a58957d4d664ae34d1037dc699ce
---

# Story 1.3: Fares are computed from configurable rules

Ticket: **PUB-3**
Status: done

## Story

As a rider,
I want the fare for a trip computed from a published formula over configurable rules,
so that the price I am quoted is explainable and consistent rather than arbitrary.

### What this story actually does, in plain words

Right now `matching-service` owns no tables and computes nothing. This story gives it its first
real content: **one small configuration table holding the price list, and one function that turns a
pickup and a dropoff into a price.**

That is all. There is **no HTTP endpoint, no gRPC, no `rider-service`, no gateway route** — Story 1.4
(PUB-4) adds those. If you find yourself writing a controller, you have left this story.

The price list has four numbers: a flat starting charge, a charge per kilometre, a charge per minute,
and a multiplier that will later move with demand but is `1.00` for now. The function measures the
straight-line distance between the two points, assumes the driver averages 30 km/h — so exactly two
minutes per kilometre — and applies the formula.

---

## Acceptance Criteria

**AC1 — the table exists and this service owns it**

**Given** a first start after this story
**When** Flyway migrations run
**Then** a `fare_rules` table is created holding base, per-km, per-minute and the surge multiplier
**And** it is owned by `matching-service`
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.3; ARCHITECTURE-SPINE.md#AD-3 — Service ownership]

**AC2 — the formula**

**Given** a `fare_rules` row carrying base, per-km, per-minute and surge
**When** a fare is computed for a pickup/dropoff pair
**Then** it equals `(base + per_km × distance + per_minute × time) × surge`
[Source: prd.md#FR-18, specs/spec-puber/SPEC.md#Capabilities → CAP-2,
specs/spec-puber/glossary.md#Ride and money, and epics/epic-1-foundations-fare-quote.md#Story 1.3 —
all four now state the same formula. FR-18 was corrected on 2026-08-23; see
planning-artifacts/sprint-change-proposal-2026-08-23-c.md.]

**AC3 — the seeded surge value**

**Given** a first start
**When** `fare_rules` is seeded
**Then** the surge multiplier is `1.00`
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.3; prd.md#FR-19; SPEC.md#CAP-14]

**AC4 — how distance and time are derived**

**Given** a pickup and dropoff coordinate
**When** distance and time are derived
**Then** distance is haversine, in kilometres
**And** time is `distance / 30 km/h` — the same fixed assumed speed the ETA uses, so exactly 2 minutes
per kilometre
**And** the speed is one named constant, never a per-call-site literal (AGENTS.md, No Magic Numbers)
**And** no maps or routing API is called anywhere
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.3; SPEC.md#Constraints;
prds/.../addendum.md#Constraints That Still Hold]

**AC5 — money representation**

**Given** a monetary value
**When** it is persisted, computed with, or crosses a boundary
**Then** it is integer minor units in every one of those places — `BIGINT` in the column, `long` in Java
**And** no floating-point type is used, and no `BigDecimal` is constructed from a `double`
**And** `BigDecimal` appears only inside a calculation, which rounds once at the end
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.3 and ARCHITECTURE-SPINE.md#Consistency
Conventions → Money, both as amended by
planning-artifacts/sprint-change-proposal-2026-08-23.md (approved 2026-08-23)]

**In plain terms, because this one is easy to misread.** "Minor units" means the smallest unit of the
currency — cents. €11.00 is `1100`. The rule is that money is *always* that whole number: in the
column, in Java, and on the wire. You never store `11.00` anywhere.

Why not `BigDecimal`, when it is the exact-decimal type? Because it does not stop floating point
getting in. `new BigDecimal(0.1)` stores `0.1000000000000000055511151231257827`;
`BigDecimal.valueOf(0.1)` stores `0.1`. Both compile, and in review they look the same. A `long` has
no constructor that accepts a `double`, so the mistake becomes unwritable rather than merely
forbidden.

`BigDecimal` is still needed — but only *inside* the calculation, because distance and time are
fractional (`120` minor-units/km × `5.327` km = `639.240`). Lift the `long`s into `BigDecimal`, round
once at the end, return a `long`. That is D4.

And `surge_multiplier` stays `DECIMAL` because it is a **coefficient, not money** — which is now the
spine's stated discriminator: `BIGINT` is money, `DECIMAL` is a coefficient and never an amount.

**AC6 — coordinate representation**

**Given** coordinates
**When** they are persisted or passed to a geo call
**Then** latitude is `DECIMAL(10,8)`, longitude is `DECIMAL(11,8)`, WGS84
**And** longitude precedes latitude in geo calls
[Source: ARCHITECTURE-SPINE.md#Consistency Conventions → Coordinates]

**AC7 — test isolation**

**Given** a test class
**When** it starts
**Then** `fare_rules` is truncated and reseeded before it runs
**And** running the full suite twice produces identical results
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.3; ARCHITECTURE-SPINE.md#AD-56]

---

## Story-local decisions you must implement as written

**Seven values this story needs have no source anywhere in the planning artifacts.** I searched the
spine, the PRD, the addendum, SPEC, the glossary, both corrections files and every epic. They are
genuinely absent, not merely hard to find. Each is pinned below so the implementation is
deterministic; each is flagged in "Questions for the repo owner" at the end of this file so the choice
is ratified rather than discovered later in a review.

**Do not deviate from these.** If one looks wrong, stop and raise it — do not silently pick another.

### D1 — Units: rates are per-kilometre and per-minute; convert once, explicitly

**This is still the most dangerous thing in the story, but it got materially safer on 2026-08-23.**
The formula multiplies a **per-kilometre** and a **per-minute** rate, while haversine returns
**metres** — so a conversion is unavoidable and a missed one is silent:

- feed metres into the per-km term and it is **1000× too large**
- feed the wrong time unit into the per-minute term and it is **60× too large**

Both pass every acceptance criterion as literally written.

**What changed.** The assumed speed was `8.33 m/s`, which needed *two* independent chains from one
source — metres→km, and metres→seconds→minutes at `2.0008003...` min/km. It is now **30 km/h**, so
there is one chain and its second step is exact:
`planning-artifacts/sprint-change-proposal-2026-08-23-b.md`.

**Decision:** rates are per kilometre and per minute, matching the column names. Go to kilometres once,
then to minutes from kilometres — never from metres, and never with a bare literal at a call site:

```java
Distance distance     = pickup.distanceTo(dropoff);      // metres, inside the type
BigDecimal kilometres = distance.inKilometres();
BigDecimal minutes    = ASSUMED_SPEED.minutesToCover(distance);   // exactly 2 x kilometres
```

Two rejected alternatives, recorded so neither is re-proposed:

- **Renaming the columns to `per_metre` / `per_second`** would make the conversion structurally
  impossible to get wrong, but `glossary.md#Entities` and `addendum.md#Data Model` both name them
  "per-km, per-minute", and the incumbents (Uber, Bolt) price on exactly those units. Editing two
  authority documents to save one conversion is the wrong trade.
- **Hardcoding the derived `2` min/km.** See Task 2.2 — the constant is the *speed*, and the 2 is
  derived from it. Write `kilometres.multiply(TWO)` and the speed can never be changed without a
  reader also spotting that the 2 has to move.

### D2 — Money at rest is `BIGINT`, in minor units

**This is now a spine rule, not a story-local choice.** The Money convention was amended on
2026-08-23: *integer minor units everywhere — `BIGINT` at rest, `long` in Java, integer on the wire.*
See `planning-artifacts/sprint-change-proposal-2026-08-23.md`.

Applies to `base_fare`, `per_km_rate` and `per_minute_rate`: all `BIGINT NOT NULL`, all holding minor
units. `120` is €1.20 per kilometre.

No precision is lost against the `DECIMAL(12,2)` this story previously specified — that already
quantised to two decimal places, and minor units quantise identically. `BIGINT` range is ±9.22 × 10^18,
so ~9.2 × 10^16 major units; overflow is not a concern.

`reviews/review-adversarial.md#C10` proposed `DECIMAL(12,2)` plus a `currency CHAR(3)`. It was never
absorbed into the spine and is now **superseded**: unifying on integers removes the Stripe-boundary
conversion instead of centralising it, and adds no currency column (the spine rejected a currency
dimension deliberately). **Do not implement C10 if you find it.**

### D3 — Surge is `DECIMAL(4,2)`

**It stays `DECIMAL` while the money columns become `BIGINT`, and that is the point of the
discriminator:** `BIGINT` is money in minor units, `DECIMAL` is a coefficient and never an amount.
A surge of `1.00` is a multiplier, not a price, so expressing it as `100` "hundredths" would put a
second and different integer-scaling convention in the same row.

Range `0.01`–`99.99`, seeded `1.00`. Two decimal places is exactly what FR-19's literal `1.00`
implies. `NOT NULL`, with `CHECK (surge_multiplier > 0)` — a zero or negative multiplier would make
every fare free or negative, and Story 4.8 will be writing this column from a ratio computation.

### D4 — Rounding happens once, at the end, HALF_UP, to 2 decimal places

Compute the entire expression in `BigDecimal` at full precision, then round **once**:

```java
// Lift the minor-unit longs into BigDecimal for the fractional multiplies. This is the one
// place BigDecimal is legitimate, and BigDecimal.valueOf never sees a double.
BigDecimal gross = BigDecimal.valueOf(rule.baseFare())
        .add(BigDecimal.valueOf(rule.perKmRate()).multiply(kilometres))
        .add(BigDecimal.valueOf(rule.perMinuteRate()).multiply(minutes))
        .multiply(rule.surgeMultiplier());
// gross is in minor units already, because base/perKm/perMinute came out of BIGINT columns.
Money fare = Money.ofMinorUnits(gross.setScale(0, RoundingMode.HALF_UP).longValueExact());
```

Rounding each term separately compounds the error and makes the result depend on evaluation order.
`HALF_UP` is the ordinary commercial convention and is what a rider expects to see. One rounding
point is the only shape that is testable, and AC7's "the suite run twice produces identical results"
depends on the arithmetic being exact rather than merely repeatable.

**No `MathContext` on the intermediate multiplications.** `BigDecimal.multiply` is exact; introducing
a precision limit mid-expression is a second, hidden rounding point.

**Rounding is to whole minor units — `setScale(0)`, not `setScale(2)`.** The inputs are already minor
units, so scale 0 *is* cent precision. `longValueExact()` rather than `longValue()`: it throws instead
of silently truncating, and after `setScale(0)` there is nothing legitimate to truncate.

### D5 — Earth radius is 6 371 000 m (mean radius)

Absent everywhere. Use the IUGG mean Earth radius as a named constant.

**Carry this forward:** Redis `GEOSEARCH` computes its own haversine with **its own** earth-radius
constant, which is not 6 371 000. When Story 4.7 (PUB-34) compares a Redis 5 km radius against this
function, confirm the two constants rather than assuming they agree — a small difference shows up
exactly at the radius boundary, which is the one place a test will look. I have **not** verified
Redis's constant; do not write a number for it into a comment on my word.

### D6 — Seed values for the three rates

AC3 pins only the surge. The rates are configuration with no stated values:

| Column | Type | Seeded value | Meaning |
| --- | --- | --- | --- |
| `base_fare` | `BIGINT` | `250` | €2.50 flat |
| `per_km_rate` | `BIGINT` | `120` | €1.20 per kilometre |
| `per_minute_rate` | `BIGINT` | `25` | €0.25 per minute |
| `surge_multiplier` | `DECIMAL(4,2)` | `1.00` | a coefficient, not money (D3) |

Put the major-unit equivalent in a comment in the seed migration. A bare `250` in a `BIGINT` does not
say whether it is 250 or 2.50, and the migration is where a reader meets it first.

Chosen to be plausible and to make hand-computed test expectations readable. They are configuration —
changing them later is a data change, not a migration.

### D7 — The table holds exactly one row, enforced

`fare_rules` is global configuration, not a domain entity. The spine's identifier rule ("UUID for
domain entities; monotonic bigint identity only for internal ordering") does not cover this case, and
`ARCHITECTURE-SPINE.md#Deferred` forbids the obvious alternative shape: *"surge is computed globally;
geographic granularity needs a cell scheme with no stated requirement behind it"* — so **no cell key,
no area key, no effective-from column.**

```sql
id SMALLINT PRIMARY KEY CHECK (id = 1)
```

The check is not ceremony: Story 4.8's surge scheduler will write this row, and an `INSERT` where it
meant `UPDATE` is the failure it prevents. With the constraint, that mistake fails loudly on the first
run instead of producing two price lists and a fare that depends on which row a query happened to
return.

### D8 — No timestamp columns on `fare_rules`, deliberately

`created_at` / `updated_at` are the reflex here. **Do not add them.**

A column default would be `DEFAULT now()`, which `DatabaseNeverReadsTimeTest` fails by design — PUB-2
wrote that rule with this exact table in mind: *"This bans `DEFAULT now()` in a migration, and that is
intended… Story 1.3's `fare_rules` and every table after it supply timestamps as bind parameters from
the `Clock`."* And a nullable column with no default is a column nothing writes, which YAGNI forbids.

When Story 4.8 starts writing surge and wants to record when, it adds the column by expand-only
migration and writes it as a bind parameter from the `Clock`. That is the correct time for it.

---

## Tasks / Subtasks

### Task 1 — `shared/model` value types (AC5, AC6, AC4)

PUB-1 left an explicit instruction that these belong in `shared`, not in `fare`:

> *"Money (integer minor units in transit, `DECIMAL` at rest, never floating point) and Coordinates
> (`DECIMAL(10,8)`/`DECIMAL(11,8)`, WGS84, longitude before latitude) are conventions, so they live in
> `shared`. […] Every feature touches coordinates — `fare` computes haversine distance from them,
> `quote` returns them, `ride` persists them, `dispatch` searches by them. Four private `Coordinate`
> types in one JVM means conversion at every internal boundary and a convention with four
> implementations, which is a convention no longer enforceable. **Longitude-before-latitude is exactly
> the rule that gets written correctly three times and wrongly the fourth.**"*

**Quoted as written in PUB-1, but its Money clause is superseded.** The 2026-08-23 amendment replaced
*"integer minor units in transit, `DECIMAL` at rest"* with *integer minor units everywhere — `BIGINT`
at rest*. The instruction the quote exists for — **Money and Coordinates live in `shared` because they
encode conventions** — is unchanged, and so is every word about Coordinates.
> [Source: PUB-1 story file, Dev Notes → "What belongs in `shared`"]

- [x] 1.1 `shared/model/Money.java` — immutable record over **`long minorUnits`**, and that is the
      only representation it has. One factory, `ofMinorUnits(long)`; one accessor, `minorUnits()`.
      **No `ofMajorUnits`, no `toMajorUnits`, no `BigDecimal` in the signature, and no factory that
      accepts a `double`.** Major units exist only at the presentation edge, outside this system —
      AD-54 exports money in minor units and the wire format is minor units, so nothing here ever
      needs the divided-by-100 form.
- [x] 1.2 `shared/model/Coordinates.java` — immutable record over two `BigDecimal` fields,
      `latitude` and `longitude`. Validate in the constructor: latitude within ±90, longitude within
      ±180. **Field and constructor order is latitude then longitude** — AC6's ordering rule is
      scoped to *geo calls*, not to constructors, and a `Coordinates(lat, lon)` reading in
      lat/lon order matches how a human writes a coordinate pair.
- [x] 1.3 `Coordinates.distanceTo(Coordinates)` returning `Distance` — the haversine, using the D5
      constant. This is the **only** place trigonometry appears.
- [x] 1.4 `shared/model/Distance.java` — immutable record over `double metres`, with `inMetres()` and
      `inKilometres()` returning `BigDecimal`. This type exists to make D1's unit error impossible to
      write; that is the failure it prevents, and it is a real one.
- [x] 1.5 Confirm none of these four classes imports anything framework-flavoured —
      `modelDependsOnNothingFrameworkFlavoured` governs them.

### Task 2 — the `fare` feature package (AC2, AC4)

`fare` may import **only** `shared` (AD-9: `shared ← fare ← ride ← dispatch ← quote`).

- [x] 2.1 `fare/model/FareRule.java` — immutable record whose field types mirror the columns exactly:
      `long baseFare`, `long perKmRate`, `long perMinuteRate` (minor units, from `BIGINT`) and
      `BigDecimal surgeMultiplier` (a coefficient, from `DECIMAL(4,2)`). The mixed types are
      deliberate and are the D2/D3 discriminator showing through: `long` is money, `BigDecimal` here
      is a multiplier. **No `double`, and no `BigDecimal` built from one.**
- [x] 2.2 `fare/model/AssumedSpeed.java` — holds `AVERAGE_SPEED_KMH = 30` and `MINUTES_PER_HOUR = 60`,
      exposing `minutesToCover(Distance)`. **One named constant, not a literal at a call site**
      (AGENTS.md → No Magic Numbers, whose own example list is `MAX_MATCHING_RADIUS_KM`,
      **`AVERAGE_SPEED_KMH`**, …). **Derive the 2 min/km, never hardcode it:**
      `BigDecimal.valueOf(MINUTES_PER_HOUR).divide(BigDecimal.valueOf(AVERAGE_SPEED_KMH)).multiply(km)`.
      Note in a comment that the bare `divide` is safe **only because 60/30 terminates** — a speed that
      does not divide 60 exactly throws `ArithmeticException` rather than silently rounding, and that
      is the correct failure, so do not "fix" it with a `MathContext`.
- [x] 2.3 `fare/repository/FareRuleRepository.java` — a **class, not an interface**. AD-10:
      *"Repositories take no interface."* Explicit SQL through `JdbcTemplate`; no ORM, no JPA, and do
      not add the JPA starter for the datasource. One method, reading the single row.
- [x] 2.4 `fare/service/CalculateFare.java` — the calculation. **Verb-named with a single public
      method** (AGENTS.md → Service Naming: `CalculateFare`, never `FareService`). Signature:
      `Money calculate(Coordinates pickup, Coordinates dropoff)`. Depends on `FareRuleRepository`.
- [x] 2.5 Implement D4's rounding exactly as written — one rounding point, at the end.
- [x] 2.6 **Create no empty layer packages.** `fare/controller` does not exist in this story; PUB-4
      creates it. *"An empty layer package is a violation, not preparation."*
- [x] 2.7 **This story persists nothing but the seeded rules.** The computed fare is returned to the
      caller; it is not written anywhere. Locking a fare onto a ride is Story 3.2 (PUB-13).
- [x] 2.8 **A missing `fare_rules` row must fail loudly**, not default to zero and not return an
      empty `Optional` that a caller can ignore into a free ride. Throw. The row is guaranteed by
      the migration and by the reseed; if it is gone, something is broken and silence would hide it.
- [x] 2.9 **Do not create a `FareStrategy` interface.** Fare computation is absent from AD-10's
      `Binds` list (`PaymentProvider`, `PaymentGateway`, `Clock`, `DriverLocationIndex`,
      `EventTransport` — verified, it is exactly those five), and AGENTS.md forbids an interface with
      one implementation. Creating one violates AD-10 and then drags AD-57's substitutability burden
      in for no benefit.

### Task 3 — migrations (AC1, AC3, D7, D8)

- [x] 3.1 `src/main/resources/db/migration/V2__create_fare_rules.sql` — **DDL only.** The singleton
      key from D7, the three `BIGINT` money columns from D2, the `DECIMAL(4,2)` surge from D3,
      all `NOT NULL`, and the surge `CHECK`. Comment each money column with its major-unit meaning. No timestamp columns (D8). No `DEFAULT now()` in any
      form — read `project-context.md` → "Never let the database tell the time" for the full list of
      Postgres spellings, including the bracket-free ones like `DEFAULT 'now'::timestamptz`.
- [x] 3.2 `src/main/resources/db/migration/V3__seed_fare_rules.sql` — **the `INSERT` only**, with D6's
      values. Two files rather than one because the expand-only convention says *"no backfill in the
      same migration"*, and because AC3's test needs the production seed as an independently
      executable statement (Task 5.5).
      **This file is production data.** Test data does not live here — see Task 5.
- [x] 3.3 **Never edit `V1__baseline.sql`.** Flyway checksums it byte for byte; a change there fails
      every integration test at context startup with "Migration checksum mismatch for version 1".
- [x] 3.4 ~~Update `db/migration/README.md`~~ — **done as written, then that file was deleted on
      2026-08-24 and this task with it.** Once D3/D4/D5/D7 were promoted into the spine, the README
      was duplication: its conventions block was verbatim spine, four of its decision rows already
      deferred to AD-62, and the seed values and no-timestamps reasoning sit in `V3` and `V2`
      themselves. D1-D8 now live in `ARCHITECTURE-SPINE.md` -> AD-62 and the spine's Money
      convention, with the unit-conversion chain and the rate degeneracy in `project-context.md`.
      The premise that "migrations cannot carry revisable prose" still holds -- it is why the
      rationale went to the spine rather than into `V2`.

### Task 4 — the test that PUB-1 built for this story to break

`src/integrationTest/java/com/puber/matching/HealthMetricsAndSchemaIntegrationTest.java` was written
in anticipation of PUB-3 and **will go red** until you edit it. It carries:

```java
/** Story 1.3 adds V2 and bumps this, editing one constant instead of three tests. */
private static final int HIGHEST_VERSION_THIS_STORY_OWNS = 1;
```

- [x] 4.1 Bump `HIGHEST_VERSION_THIS_STORY_OWNS` to **3** (this story adds V2 *and* V3).
- [x] 4.2 `baseline_creates_no_tables()` asserts `information_schema.tables` is empty apart from
      `flyway_schema_history`. Rewrite it to assert the schema now holds exactly `fare_rules`.
      Keep the shape that names what it expects, so the next story edits one list.
- [x] 4.3 Update the `attribute(...)` failure message — it currently reads *"`fare_rules` arrives in
      Story 1.3 and rides in Story 3.1"*. It exists to distinguish a stale shared Postgres volume
      from a real defect, which matters more now, not less (see Dev Notes → "The shared volume will
      bite you").
- [x] 4.4 `flyway_recorded_the_baseline()` is already scoped to `version = '1'` and needs no change.
- [x] 4.5 Leave `a_second_start_applies_no_migrations()` alone. Confirm it still passes — it interacts with
      Task 5 and is the assertion that would catch the trap described there.
- [x] 4.6 Note that `attribute(...)`'s javadoc predicts a single file named `V2__fare_rules.sql`.
      Task 3 splits it into `V2__create_fare_rules.sql` and `V3__seed_fare_rules.sql` for the reason
      given there. Update the javadoc so the prediction and the reality agree.
- [x] 4.7 **Do not add a `Clock` bean visible to the application context.**
      `ClockWiringIntegrationTest.the_running_context_is_wired_with_the_real_clock()` asserts the context
      exposes **exactly one** `Clock`, and that it is `SystemClock`. A `@TestConfiguration` picked up
      by component scan, or a `@Primary` test clock, breaks it. This story should need neither —
      see Dev Notes → "The `Clock` you must use if you touch time at all".

### Task 5 — test data: two tiers, and the trap underneath them (AC7)

**Read this before writing any of it.** The epic pairs "seeded on a first start" with "truncated
before each test class" and does not notice that those two things fight:

> A versioned Flyway migration runs **once**. The schema history then records it as applied. So the
> first test that runs `TRUNCATE fare_rules` deletes the seeded row **permanently** — Flyway will not
> put it back on the next context start, because as far as it is concerned V3 is done. Every later
> test then computes fares against an empty table.

The consequence is the specific failure `CLAUDE.md` warns about: "the full suite twice produces
identical results" would be **satisfied by being consistently broken**. A rule that cannot fire is
indistinguishable from a rule that passes.

A repeatable migration (`R__`) does **not** rescue this either: Flyway re-runs those only when the
file's checksum changes, and after a `TRUNCATE` the checksum is unchanged. **Tests must own their own
seeding.** That is what the tiers below are for.

#### The two tiers, and which to use

| Tier | What it is for | Where it lives |
| --- | --- | --- |
| **Inline SQL, in the test** | Data *this test* needs — a specific row, a specific state | The test method, right beside the assertion |
| **A `.sql` fixture** | The generic baseline every test assumes — `fare_rules` is the first one | `src/integrationTest/resources/fixtures/` |

**Migrations are production. Fixtures are tests.** `db/migration/` holds production schema and
production data and nothing else. Test data never goes there, and a fixture never reaches into a
migration file — with exactly one exception, task 5.5, where the production seed *is* the subject
under test.

**The two are free to hold different values, and that is not drift.** The fixture answers *"is the
formula correct over known rules"* — any known rules will do. AC3 answers *"does a first start seed
surge 1.00"*, which is about the migration and is tested separately in 5.5. Conflating those two
questions is what makes people believe the values must match.

- [x] 5.1 `src/integrationTest/resources/fixtures/fare_rules.sql` — `TRUNCATE fare_rules;` then the
      baseline `INSERT`. Use D6's values so hand-computed expectations stay readable, but say in a
      comment that matching production is a convenience, not a constraint.
      `src/integrationTest/resources/` already exists and is empty.
- [x] 5.2 A small loader in **`src/integrationTest/java`**, not `src/test/java`. PUB-2 recorded that
      `src/test/java` is *not* on the integration suite's compile classpath — verified: `build.gradle`
      extends `integrationTestImplementation` from `testImplementation` (dependencies), which does not
      add `sourceSets.test.output` (compiled classes). Putting it in the integration source set needs
      **no `build.gradle` change**. PUB-2's note is explicit that if a story ever does need the
      cross-set link, the fix is `sourceSets.test.output` — *"not to adopt the `java-test-fixtures`
      plugin for one class, and not to duplicate the double."*
- [x] 5.3 Invoke it from an explicit **`@BeforeEach`** — per test, not per class. AD-56's *"every test
      class truncates and reseeds before running"* is the floor; a `TRUNCATE` plus one `INSERT` is
      cheap enough that per-test is free, and it stops a test that mutates `fare_rules` from poisoning
      its siblings.
      **Do not introduce a base class, and do not apply this to every integration test.**
      `HealthReportsDownPromptlyIntegrationTest` boots against a deliberately unreachable datasource
      with `spring.flyway.enabled=false` — truncating there fails for reasons unrelated to the test.
      Opt in per class; the repo has deliberately avoided integration-test base classes and the three
      existing classes carry divergent annotation stacks.
- [x] 5.4 **Prove the reseed works rather than reasoning that it does.** Per `CLAUDE.md`: add a test
      that truncates `fare_rules`, then asserts the fixture restored the row. Then plant the failure —
      comment out the `@BeforeEach` — run the integration suite, capture the red, and revert. A reseed
      nothing verifies is exactly the hole this task exists to avoid.
- [x] 5.5 **AC3's own test, which is about the migration and not the fixture.** Truncate, execute the
      `V3__seed_fare_rules.sql` resource, assert `surge_multiplier = 1.00`. This is the one place a
      test legitimately reads a migration file: the production seed is the thing under test.
      **State the limit honestly in the test's comment** — this proves the seed *statement* yields
      `1.00`, and `flyway_recorded_the_baseline()` plus `a_second_start_applies_no_migrations()` prove Flyway
      ran the migrations; but nothing observes the pristine post-migration row unless the suite runs
      against a fresh volume. Do not claim more than that.
- [x] 5.6 Prove AC7's second clause the only way it can be proven: **run the whole suite twice** and
      show both runs identical. `make test && make test`. Record the output in the Dev Agent Record.

### Task 6 — tests for the calculation (AC2, AC4, AC5, AC6)

Unit tests are `*Test` in `src/test/java`; integration tests are `*IntegrationTest` in
`src/integrationTest/java`; **never `*IT`**. The directory decides the suite.

**Test method names are `snake_case`** (`AGENTS.md` → Test Naming and Placement, added 2026-08-23; the
existing suite was converted in the same pass). Keep `@DisplayName` for the `AC<n>:` reference and the
exact expectation:

```java
@Test
@DisplayName("AC2: a 5 km trip at the seeded rates prices at 1100 minor units")
void prices_a_five_kilometre_trip_from_the_seeded_rules() { … }
```

Private helpers, lifecycle methods, `@ArchTest` `ArchRule` fields and the `rules/fixtures/` classes all
stay `camelCase` — the reasons are in `AGENTS.md`, and Task 7.1's new fixtures follow the fixture rule,
not this one.

- [x] 6.1 Unit-test the formula against **hand-computed** expectations. Work one out on paper and put
      the arithmetic in the test as a comment so a reader can check it. With D6's seeded values and a
      5 km trip: distance `5 km`, time `5 × 2 = 10 min` exactly, fare
      `(250 + 120×5 + 25×10) × 1.00 = 1100` minor units (€11.00) — no rounding needed at all, which is
      one of the things 30 km/h bought. **A test whose expectation was produced by running the code
      proves nothing.**
- [x] 6.2 Unit-test the units explicitly — this is D1's guard. Assert that a trip of a known distance
      produces the kilometre and minute figures you expect, so a metres-for-kilometres slip fails
      here rather than showing up as a plausible-looking price.
- [x] 6.3 Unit-test rounding at a boundary that distinguishes HALF_UP from HALF_EVEN. If your seeded
      values do not produce such a case, construct a `FareRule` in the test that does — otherwise D4
      is asserted by nothing.
- [x] 6.4 Unit-test the surge multiplier's effect independently of the other three terms.
- [x] 6.5 Unit-test `Coordinates` rejecting out-of-range latitude and longitude.
- [x] 6.6 Unit-test `distanceTo` against a known coordinate pair with a published distance, allowing
      a stated tolerance. Say in the test why the tolerance is what it is.
- [x] 6.7 Integration-test the repository against the **real** Postgres: read the **fixture's** row
      (Task 5.1) and assert the four values and their types. AD-10 and AD-56 forbid H2, an in-memory substitute, a
      fake repository, or Testcontainers. Address Postgres by its Compose service name
      `matching-postgres`, never `localhost` — tests run as a peer container.
- [x] 6.8 For every test in this story, answer *"what change makes this go red?"* — PUB-2's review
      recorded that *"a test that cannot fail is the review finding that recurs."*

### Task 7 — make the structural rules bite (AC5, and a debt this story is named for)

`deferred-work.md` names this story specifically:

> *"**1.3 is now the target**: `fare/` arrives with `model`, `service` and `strategy` content together,
> which is the first point all five remaining rules have something to bite on."*

Verified against the code: `serviceDependsOnStrategyInterfacesOnly`, `nothingDependsOnController`,
`noPackageIsNamedEntity`, `sharedDependsOnNoFeaturePackage` and `featureDependenciesRunOneWay` have
never evaluated a single class. Your `fare` package is the first thing they govern.

- [x] 7.1 Add fixtures under `src/test/java/com/puber/matching/rules/fixtures/` with **inverted
      assertions**, so each newly-live rule is shown to be *capable* of failing. Follow the shape of
      `TimeIsReadOnlyThroughTheClockRuleTest`, which is the existing template for proving a rule can
      go red. Note `@AnalyzeClasses(importOptions = ImportOption.DoNotIncludeTests.class)` — that
      exclusion is load-bearing, and it is why the fixtures need their own driving test rather than
      simply existing.
- [x] 7.2 **Make "never floating point" structural — this is the point of the whole convention
      amendment.** AC5 states it; verified, **no rule enforces it anywhere today**. Two clauses:
      (a) no production class declares or returns a `float`/`double`/`Float`/`Double`, exempting
      `Distance` alone — one exemption derived from the type itself, in the style of the existing
      `SystemClock` exemption, **not** a growing list of allowed classes; and
      (b) **no production class constructs a `BigDecimal` from a `double`** — `new BigDecimal(double)`
      and `BigDecimal(double, MathContext)`. Clause (b) is the one the old `DECIMAL`-at-rest
      convention could not express, and closing it is why the convention changed.
- [x] 7.3 Plant a violation of **each** clause of 7.2 — a `double` field on `FareRule` for (a), and a
      literal `new BigDecimal(0.1)` for (b) — run the suite, capture the red for both, revert, confirm
      green. A rule that fires on one clause and not the other is half a rule, and clause (b) is the
      one worth proving: `BigDecimal.valueOf(0.1)` must still pass, or the rule is unusable.
- [x] 7.4 **Optional, and cut it if the story is running long — say so rather than half-doing it.**
      `modelDependsOnNothingFrameworkFlavoured` enumerates nine banned packages rather than expressing
      "nothing framework-flavoured", so `org.slf4j`, `lombok`, `org.hibernate` and any future
      framework pass it unchallenged. **The concrete live hole: the list bans
      `com.fasterxml.jackson..` — Jackson 2, which is not on the classpath at all — and does not ban
      `tools.jackson..`, which is the Jackson 3 that *is*.** So the one framework the rule names for
      the domain model is the one it cannot meet, and the real one is unguarded. `CLAUDE.md` prefers
      the structural form over a longer list. This is a change to an existing rule's shape, so it is
      separable from the rest of the story.
- [x] 7.5 Keep every rule you add in the existing `ArchitectureRulesTest`. **Do not create a third
      rules class.** There are already two, and they cannot be merged: `ArchitectureRulesTest` is
      `DoNotIncludeTests` (production code) and `TestNamingRulesTest` is `OnlyIncludeTests` (test
      code). Everything this story adds governs production code, so it belongs in the first.

### Task 8 — record what this story settled (D1–D8)

- [x] 8.1 Add to `project-context.md`: the unit convention from D1, the rounding rule from D4, and the
      earth-radius constant from D5.
      **The two-tier test-data rule is already recorded** — `project-context.md` → "Test data:
      migrations are production, fixtures are tests", added 2026-08-23. Follow it; do not restate it
      in this story.
      `project-context.md` is loaded by every workflow; `deferred-work.md` is an audit trail that
      *"`create-story`, `dev-story`, `sprint-planning`, `sprint-status` and `retrospective` never
      read"*. A rule left only in this story file is lost when the next story starts.
- [x] 8.2 Add the instruction Story 2.6 needs: the 30 km/h constant lives in `fare/model/AssumedSpeed`
      for now because `fare` is its only caller. **Story 2.6 (PUB-10) must move it to `shared`, not
      copy it** — Story 2.6's ETA AC now says so too. Two speed literals in one JVM is the same failure
      mode PUB-1 described for longitude-before-latitude, and the repo owner ratified on 2026-08-23
      that this is deliberately **one** speed serving both the ETA and the trip.
- [x] 8.3 ~~Update `db/migration/README.md`~~ (also Task 3.4) — superseded by that file's deletion; see 3.4.

### Task 9 — the gate

- [x] 9.1 `make format`, then **`git add`** — `pre-commit` analyses the **index**, so fixing a file
      without staging it changes nothing. Spotless is `googleJavaFormat().aosp()`: 4 spaces, never
      tabs, and it fails the build.
- [x] 9.2 Run **`make build`, then `make test`**, in that order, and read the output.
      **Not `make test-unit`** — it runs neither Spotless nor the integration suite, which is exactly
      how PUB-2's review left the build red while reporting the suite green.
- [x] 9.3 `find . -user root -print -quit` must print nothing after a full build and test run.
- [x] 9.4 Report what you saw, **including a failure you did not cause.**
      `HealthReportsDownPromptlyIntegrationTest` is a known environmental red in sandboxes whose
      egress accepts TEST-NET-1 (`192.0.2.0/24`). Name it; do not omit it and do not treat it as
      green.

### Review Findings

Added by `bmad-code-review` on 2026-08-23. Three parallel reviewers (adversarial, edge-case,
acceptance) plus an independent verification pass. **Every finding marked "proven" below was
confirmed by planting the violation, running the suite, capturing the result and reverting** —
`CLAUDE.md` → "Prove it, don't reason about it". The gate was re-run from a clean tree at the end:
`make build` green, `make test` 15 integration tests / 1 failed (PUB-1's known environmental
precondition) and 65 unit tests green, identical to the pre-review baseline.

#### Decisions needed — both resolved by the repo owner on 2026-08-23

- [x] [Review][Decision] **Test-method naming is unenforced across the whole integration suite** —
      `TestNamingRulesTest` is `@AnalyzeClasses(packages = "com.puber.matching", importOptions =
      OnlyIncludeTests.class)`, which scans the `test` task's classpath. The `integrationTest` source
      set is not on it, so none of those classes are ever seen. **Proven:** a `@Test` method named
      `thisNameIsCamelCaseAndMustBeRejected()` added to `FareRulesIntegrationTest` produced
      `TestNamingRulesTest > testMethodsAreSnakeCase PASSED` and `BUILD SUCCESSFUL`. The rule does
      bite in `src/test/java`. Consequence: the 14 `snake_case` renames this story made across
      `FareRulesIntegrationTest`, `HealthMetricsAndSchemaIntegrationTest`, `ClockWiringIntegrationTest`
      and `HealthReportsDownPromptlyIntegrationTest` are convention, not enforcement, and the new
      `AGENTS.md` text claims otherwise. Options: (a) add a second driving test in
      `src/integrationTest/java` that imports the integration classes and calls the same rule;
      (b) give the `test` task visibility of `sourceSets.integrationTest.output`, which crosses the
      source-set boundary PUB-2 deliberately left closed; (c) accept the limit and correct
      `AGENTS.md`. Related: the rule also selects `areAnnotatedWith(Test.class)`, so
      `@ParameterizedTest`, `@RepeatedTest` and `@TestFactory` are invisible to it in **both** suites.
      **Resolved: option (a).** Add a driving test in `src/integrationTest/java` that imports the
      integration classes and calls the same rule. It follows the existing driving-test template,
      needs no `build.gradle` change, and leaves PUB-2's source-set boundary closed. Now a patch.
- [x] [Review][Decision] **`floatingPointIsConfinedToDistance` cannot see local variables, so AC5's
      "no floating-point type is used" is only enforced for declarations** —
      `neverDeclareOrReturnFloatingPoint()` iterates `type.getFields()`,
      `codeUnit.getRawReturnType()` and `codeUnit.getRawParameterTypes()`, and nothing else.
      **Proven:** `long plantLocalDoubleArithmetic() { double rate = 0.1; double surge = 1.15; return
      (long) (rate * surge * 100); }` in `CalculateFare` gave
      `floatingPointIsConfinedToDistance PASSED` and `BUILD SUCCESSFUL`. This is also why
      `Coordinates.distanceTo` passes with ten `double` locals. Decide whether to accept this as a
      documented limit (and reword the rule's `because` clause, which claims "floating point has no
      business in this service at all") or to pursue a mechanism that reads method bodies.
      **Resolved: accept the limit and fix the wording.** The rule stays declarations-only and is
      extended to arrays and boxed collections under its own patch below; the `because` clause and
      `project-context.md` are reworded so neither claims floating point is banned outright. Now a
      patch.

#### Patches

- [x] [Review][Patch] Nothing tests that `calculate` turns its two coordinates into a distance —
      proven: replacing the body with `priceOf(fareRules.priceList(), new Distance(0))` leaves the
      whole suite green. Both callers in the repository pass `LISBON, LISBON`, so the only
      end-to-end price assertion multiplies `per_km_rate`, `per_minute_rate` and `surge_multiplier`
      by zero. Add one integration test with two distinct coordinates and a hand-computed
      expectation. [services/matching-service/src/main/java/com/puber/matching/fare/service/CalculateFare.java:33]
- [x] [Review][Patch] The Paris–London tolerance is 2 000 m against a 44 m actual error, so it admits
      any earth radius from roughly 6 334 726 to 6 408 903 m — proven: substituting the WGS84
      equatorial radius `6_378_137` for AD-62's IUGG mean `6_371_000` leaves the suite green. The
      comment defending the tolerance ("a wrong earth radius or a swapped axis misses by far more
      than that") is therefore false in its radius half. Tighten to about ±200 m and correct the
      comment. [services/matching-service/src/test/java/com/puber/matching/shared/model/CoordinatesTest.java:13]
- [x] [Review][Patch] `AGENTS.md` is modified but **not staged** (` M AGENTS.md`, +30 lines). It adds
      the "Test methods are `snake_case`" section that Task 6 cites and that `TestNamingRulesTest`
      mechanises, so as staged the commit ships the enforcement and 26 renames without the rule that
      authorises them. `pre-commit` analyses the index. [AGENTS.md]
- [x] [Review][Patch] `floatingPointIsConfinedToDistance` misses `double[]`/`float[]` and boxed
      collections — proven: `private static final double[] PLANT_ARRAY_OF_DOUBLES` and
      `private static final java.util.List<Double> PLANT_LIST_OF_DOUBLES` in `CalculateFare` both
      passed. `FLOATING_POINT_TYPE_NAMES` is an exact match on the type name, and a `List<Double>`
      field's raw type is `java.util.List`. `DeclaresFloatingPoint` declares only scalars and boxes,
      so the reflect-over-the-fixture trick cannot detect the gap — extend the fixture at the same
      time. [services/matching-service/src/test/java/com/puber/matching/rules/ArchitectureRulesTest.java:61]
- [x] [Review][Patch] Neither CHECK constraint AD-62 calls load-bearing is tested. Delete
      `check (id = 1)` or weaken `check (surge_multiplier > 0)` to `>= 0` and the suite stays green;
      `FareRuleRepository`'s comment "the table's own CHECK constraint says so" is verified by
      nothing. Two `assertThrows(DataAccessException.class, ...)` lines close it. [services/matching-service/src/main/resources/db/migration/V2__create_fare_rules.sql:17]
- [x] [Review][Patch] `HIGHEST_VERSION_THIS_STORY_OWNS` was bumped 1→3 but is read only at lines 198
      and 212, both inside the `attribute(...)` failure-message helper, so the bump changed no
      assertion and its javadoc ("editing one constant instead of three tests") is inaccurate. A V2
      or V3 row recorded with `success = false` is invisible to the suite. [services/matching-service/src/integrationTest/java/com/puber/matching/HealthMetricsAndSchemaIntegrationTest.java:36]
- [x] [Review][Patch] `Distance` hands out three numbers, not one: the record component `metres()`
      and `inMetres()` alongside `inKilometres()`. `inMetres()` has exactly one caller in the
      repository — its own sibling `inKilometres()` — so making it private turns the type's stated
      purpose into something structural. `project-context.md` claims `Distance` "is the only type
      that will hand you a number a rate can multiply", which is true only of `inKilometres()`. [services/matching-service/src/main/java/com/puber/matching/shared/model/Distance.java:20]
- [x] [Review][Patch] The rationale for the floating-point exemption names the wrong class. Both
      `ArchitectureRulesTest` and `project-context.md` say `Distance` is exempt "because the
      haversine is trigonometric" — the haversine is in `Coordinates.distanceTo`, which is not exempt
      and passes only because its locals are unchecked. [services/matching-service/src/test/java/com/puber/matching/rules/ArchitectureRulesTest.java:261]
- [x] [Review][Patch] Three wrong statements in `AssumedSpeed`'s javadoc: "a speed that does not
      divide 60 exactly throws" (40 km/h does not divide 60 exactly and `60/40 = 1.5` terminates
      fine — the real condition is a non-terminating quotient, e.g. 45); "throws
      `ArithmeticException`" (a static initialiser failure surfaces as `ExceptionInInitializerError`);
      and "at startup" (`AssumedSpeed` is not a bean and is first touched inside
      `CalculateFare.priceOf`, so class init happens on the first fare priced). Nothing tests any of
      it. [services/matching-service/src/main/java/com/puber/matching/fare/model/AssumedSpeed.java:23]
- [x] [Review][Patch] `ModelTypeThatDependsOnJackson` proves only the method-parameter form of a
      framework dependency. The form AD-8 leakage actually arrives in is an annotation
      (`@Entity`, `@JsonProperty`); add one annotated field so the reshaped allowlist is proven
      against the case that matters. [services/matching-service/src/test/java/com/puber/matching/rules/fixtures/model/ModelTypeThatDependsOnJackson.java:1]
- [x] [Review][Patch] The File List omits ten files this commit touches: the eight test files carrying
      `snake_case` renames (`ClockWiringIntegrationTest`, `HealthReportsDownPromptlyIntegrationTest`,
      `ArchUnitReadsJava25ClassFilesTest`, `DatabaseNeverReadsTimeTest`,
      `TimeIsReadOnlyThroughTheClockRuleTest`, `DeadlineTest`, `ControllableClockTest`,
      `SystemClockTest`), `TestNamingRulesTest` (new, mentioned in prose only) and `AGENTS.md`. [_bmad-output/implementation-artifacts/PUB-3-fares-are-computed-from-configurable-rules.md]
- [x] [Review][Patch] The antipodal clamp `Math.min(1, Math.sqrt(halfChordSquared))` is a guard with
      no reproduced failure, which `project-context.md` → YAGNI forbids. One assertion both
      reproduces the case and pins the largest distance the formula can ever be handed:
      `at("90","0").distanceTo(at("-90","0"))` is 20 015 087 m. [services/matching-service/src/main/java/com/puber/matching/shared/model/Coordinates.java:49]
- [x] [Review][Patch] `CoordinatesTest.accepts_the_limits` contains no assertion — it passes on the
      absence of an exception, which is defensible but should say so with `assertDoesNotThrow`. [services/matching-service/src/test/java/com/puber/matching/shared/model/CoordinatesTest.java:74]
- [x] [Review][Patch] Two comments overstate. `Money`'s javadoc says the floating-point mistake
      "becomes unwritable" — `Money.ofMinorUnits((long) 2.5)` compiles; "requires a visible cast" is
      the true and still-useful claim. `FareRulesIntegrationTest`'s `@BeforeEach` javadoc says a
      truncate plus one insert is "cheap enough that it is free", which is an unmeasured performance
      claim. [services/matching-service/src/main/java/com/puber/matching/shared/model/Money.java:7]
- [x] [Review][Patch] Completion note 1 says AC6's first clause is "carried by the `Coordinates` type"
      — `Coordinates` validates range only, and `BigDecimal` is unbounded in scale, so nothing in the
      type expresses `DECIMAL(10,8)`/`DECIMAL(11,8)` or WGS84. The note should read "no code carries
      it yet". [_bmad-output/implementation-artifacts/PUB-3-fares-are-computed-from-configurable-rules.md]

#### Deferred

- [x] [Review][Defer] `Distance`, `Coordinates` and `FareRule` accept values their invariants exclude
      — `new Distance(-5000)` yields a negative fare, `new Distance(Double.NaN)` throws a bare
      `NumberFormatException` out of `BigDecimal.valueOf` rather than at construction,
      `new Coordinates(null, x)` NPEs inside `requireWithin` with no field name, and
      `new FareRule(…, null)` NPEs inside `multiply`. Unreachable today — nothing constructs these
      from untrusted input. Becomes real at PUB-4's HTTP edge. — deferred, pre-existing
- [x] [Review][Defer] `fails_loudly_when_the_price_list_is_missing` truncates `fare_rules` and there
      is no `@AfterEach`, so the class's cleanliness depends on JUnit's method order. **Checked and
      did not reproduce**: that method runs fourth of six and `restores_the_price_list_after_a_truncate`
      runs last and reseeds, so a full suite leaves one row (verified by querying Postgres). Latent
      fragility, not a current defect. — deferred, pre-existing
- [x] [Review][Defer] `base_fare`, `per_km_rate` and `per_minute_rate` carry no CHECK, so a negative
      or zero rate is storable and would produce a negative or free fare. AD-62 specified exactly one
      CHECK, so adding more is an architecture decision rather than a review fix. — deferred, pre-existing
- [x] [Review][Defer] `com.puber.matching.ride.model.ARideTypeThatOnlyExistsToBeScanned` occupies the
      package the real `ride` feature takes in Epic 3, and the deliberate violators
      `SharedTypeThatDependsOnAFeature` and `FareTypeThatDependsOnRide` live in production package
      names rather than `rules/fixtures/`, which `project-context.md` names as their home. Each has a
      sound stated reason (the rules name absolute packages), but the convention does not record the
      exception. — deferred, pre-existing

#### Dismissed as noise

Five, recorded so they are not re-raised:

1. *"The suite permanently empties the production seed row in the shared dev database."* Checked by
   running the suite and querying Postgres: the count is 1. See the deferred item above.
2. *"`FareRulesIntegrationTest`'s honest-limit comment names two methods this diff renamed."* It names
   `flyway_recorded_the_baseline()` and `a_second_start_applies_no_migrations()` — the new names.
3. *"The V2 header, the migration README and `project-context.md` say the same things."* The
   arrangement was ratified on 2026-08-23: AD-62 is the authority, the README holds the migration
   rationale, and `project-context.md` was trimmed to what AD-62 does not cover.
4. *"Four tests cannot meaningfully fail."* `MoneyTest.stores_minor_units_unchanged` catches a factory
   that scaled its argument and `DistanceTest.reports_metres_unchanged` catches a swapped accessor;
   both are thin but real. Only `accepts_the_limits` warranted a change, and it is a patch above.
5. Two unreachable arithmetic paths: a `long` overflow via an absurd `per_km_rate` (the
   `longValueExact()` throw is the designed loud failure) and a duplicate-key violation if V3's seed
   statement were re-executed against a populated table (its only caller truncates first).

#### What the reviewers confirmed as correct

Stated because a review that reports only problems misrepresents the change. All eight pinned
decisions D1–D8 are implemented literally. The arithmetic was re-derived by hand by two reviewers
independently: 5 km → 1100, 5 327 m → 10.654 min, the 1 005 m case genuinely distinguishes `HALF_UP`
from `HALF_EVEN`, and the 150 m case genuinely distinguishes one rounding point from per-term
rounding. There is no `MathContext` anywhere in `src/main`, no `new BigDecimal(double)`, and no clock
read in either migration. **Task 7.5 was not violated** — `LayerRulesTest`,
`FeaturePackagesRunOneWayRuleTest` and `MoneyIsNeverFloatingPointRuleTest` declare zero `@ArchTest`,
`@AnalyzeClasses` and `ArchRule` fields; they are driving tests that call the rules which all still
live in `ArchitectureRulesTest`. `bigDecimalIsNeverBuiltFromADouble` was reproduced firing on real
production code while leaving `BigDecimal.valueOf(double)` untouched. The Dev Agent Record's Debug Log
was reproduced row for row, including the uncomfortable one where removing the `@BeforeEach` did not
turn the class red — that admission is accurate, not spin.

---

## Dev Notes

### Where the formula comes from — all four sources now agree

**FR-18 is safe to cite for the whole formula.** It was not always: until 2026-08-23 it read
*"(base + distance + time) × surge multiplier"*, which adds a price to a length to a duration and is
dimensionally impossible, while the rate-bearing form lived only in `SPEC.md` CAP-2 and
`glossary.md`. FR-18 now carries the rates **and their units** — distance in kilometres, time in
minutes — which is what closes the unit gap D1 exists to guard. See
`planning-artifacts/sprint-change-proposal-2026-08-23-c.md` for the record, so nobody "restores" the
shorter form thinking it was deliberate.

Four of the seven acceptance criteria are **not** sourced from the PRD at all — AC1's
ownership clause, AC5's Money convention, AC6's Coordinates convention and AC7's truncate-and-reseed
all come from `ARCHITECTURE-SPINE.md` (AD-3, the Consistency Conventions table, AD-56). Citing the PRD
for those would be wrong.

### One speed, both distances — ratified, no longer a discrepancy

This used to be the shakiest thing in the story and it is now settled, so do not re-open it.

`glossary.md` defined the assumed speed for **driver-to-pickup ETA** and said *"Not the trip's own
duration"*, while AC4 applied the same speed to trip time for the fare — authorised only by the epic.
The repo owner ratified on 2026-08-23 that **one 30 km/h constant serves both**, and the wording was
corrected so the documents agree:

- `SPEC.md#Constraints` now says one speed *"derives **both** the driver-to-pickup ETA and the trip
  duration the fare prices"*.
- `glossary.md` keeps the distinction that matters — ETA is the wait *before* pickup, not how long the
  ride takes — while stating that the same speed derives the trip duration too.

The old clause was correct about the **term** and misleading about the **constant**. Both now sit in
one sentence.

Note also: neither `haversine` nor any speed appears anywhere in `ARCHITECTURE-SPINE.md` (verified:
zero hits). The spine's only distance mechanism is AD-26's Redis `GEOSEARCH`, which is the *dispatch*
distance and involves no fare. Your pickup→dropoff haversine is new code with no spine precedent.

### What already exists, and what you are adding

`matching-service` today holds **five production classes and zero tables**:

```
src/main/java/com/puber/matching/
  MatchingServiceApplication.java
  config/ClockConfiguration.java
  shared/model/Deadline.java
  shared/strategy/Clock.java
  shared/strategy/SystemClock.java
src/main/resources/db/migration/
  V1__baseline.sql        (comments only, creates nothing)
  README.md
```

There is **no `repository`, `service`, `controller`, `fare`, `ride`, `dispatch` or `quote` package.**
You create `fare` and the `shared/model` money and coordinate types. Nothing else.

### The `Clock` you must use if you touch time at all

Three methods, and the shape matters — PUB-2's code review changed it:

```java
public interface Clock {
    Instant wallClockNow();
    Deadline deadlineIn(Duration duration);
    boolean hasReached(Deadline deadline);
}
```

`Deadline.hasExpired(Clock)` **no longer exists** — it was flipped to `clock.hasReached(deadline)` to
break a `shared.model` ↔ `shared.strategy` package cycle. There is deliberately **no elapsed-duration
accessor**; if you need one, add a *monotonic* accessor and do not give it a `now()`-shaped name.

**This story most likely reads no time at all.** A fare is a pure function of two coordinates and four
numbers. If you find yourself wanting a timestamp, re-read D8 first — the answer is probably that you
do not need one.

The rules that will fail your build if you read time another way, all live and non-vacuous today:
`timeIsReadOnlyThroughTheClock`, `theRealClockIsOnlyEverInjected`, `theLegacyDateApiIsNotUsedAtAll`,
and `DatabaseNeverReadsTimeTest` (which scans `src/main/**/*.sql`, so **it will read your V2 and V3**).

Note one live gap PUB-2 left open: banning `LocalDateTime`/`LocalDate` as **types** in signatures and
DTOs was considered and **not taken** — *"it stays prose in the Timestamps convention."* So a
`FareRule` using `LocalDateTime` would pass every mechanical check. Do not add one (D8 says no
timestamp columns anyway).

### How a bean is wired, if you need one

`ClockConfiguration` is the pattern. `SystemClock` carries **no** Spring annotation; the `@Bean` lives
in `config`, because annotating the class would put Spring inside `shared` and put the wiring
somewhere `config` cannot see. `config` is also the only package permitted to name a concrete strategy
implementation.

`FareRuleRepository` and `CalculateFare` are ordinary Spring components in `fare` — they need no
entry in `config`.

### The shared volume will bite you

`matching-postgres-data` is a **named Docker volume shared by every checkout of this repository on the
machine.** It survives `make stop` and it survives branch switches. Run a branch carrying
`V2__create_fare_rules.sql`, switch back to `main`, and the table is still there.

While you are iterating: **any change to V2 or V3 before they are final means `make clean`** to drop
the volume, or Flyway fails validation with a checksum mismatch. `make clean` is `down -v` and
deliberately keeps `infra/.env`.

This is precisely why Task 4.3's failure message exists — PUB-1 built attribution for this exact
scenario and its text names your files.

### Boot 4.1 / Java 25 traps that apply here

Everything in `project-context.md` → "Boot 4.1 / Java 25" applies. The ones you will actually meet:

- **Flyway is BOM-managed at 12.4.0 — never pin it.** `spring-boot-starter-flyway` plus
  `org.flywaydb:flyway-database-postgresql`, both already present.
- **Add the matching `-test` starter** when you add a capability starter. There is no monolithic
  `spring-boot-starter-test` in Boot 4. You probably need no new starter: `-jdbc-test` is present.
- **Jackson 3** — `tools.jackson.databind.*`. Do not add Jackson 2.
- `TIMESTAMPTZ`, not `TIMESTAMP`. PUB-2 flagged that this story writes the first table and *"the
  column type is a live decision there, not a settled one"* — D8 resolves it by having no timestamp
  columns at all, so the decision is deferred to whoever adds the first one.

### Gradle traps recorded by PUB-1 and PUB-2

- A rename can crash `make format` (Spotless's `build/spotless-clean` copies stop resolving).
  `make analyzer-config` clears them. **You are adding and possibly renaming files, so expect this.**
- `./gradlew check --warning-mode all` currently reports **zero** deprecations. Keep it that way; use
  assignment syntax (`exceptionFormat = 'full'`), never the space form.
- Do not invoke `./gradlew` directly inside a service — it bypasses the analyzer-config copy and
  `build.gradle` throws a `GradleException` telling you to run `make build`.
- **`make build` runs `./gradlew build` with `--no-deps`, so no Postgres is running.** `build`
  includes `check`, which includes the unit suite. **Any test that touches a datasource must live in
  `src/integrationTest/java`, or it fails every single build.** This is the reason the two source
  sets exist.
- **Spotless formats `src/*/java/**/*.java`** — all three source sets. New code in `main`, `test` and
  `integrationTest` alike must be AOSP-formatted, or `make build` goes red.

### How the real ones do it, and where Puber deliberately differs

Checked against Uber, Bolt and FREENOW before implementing, so nothing here is invented. **The
formula is the industry-standard shape, not a Puber invention** — build it as specified.

Uber's own rider help states its price components as *"a base amount, a per minute amount, a per mile
amount and where applicable, an adjustment to minimum price"*, with dynamic pricing and a Booking Fee
on top. Bolt's rider support describes the same three: a base fare for pickup, a minute rate over the
trip, and a distance rate over the route. So `base + per_distance × distance + per_time × time`, all
scaled by a demand multiplier, is exactly what the incumbents do.

**This settles the units question (D1).** Both incumbents price on a **per-distance-unit** and
**per-minute** rate. The column names are right, the conversion belongs at the call site, and the
"rename them to `per_metre` / `per_second`" alternative is now firmly rejected — it would be the only
scheme in the industry.

Four things the incumbents have that Puber does not, each deliberate:

| Real systems | Puber | Why the difference is correct here |
| --- | --- | --- |
| **Route distance** from a routing engine, over the actual road network | **Haversine** straight line | An explicit non-goal, stated five times across the PRD and SPEC: *"Real maps or routing APIs; straight-line/haversine distance and a fixed-speed ETA stand in."* Road distance always exceeds straight-line — typically well over it in a city — so **Puber's fares are systematically lower than a real app's for the same two points.** That is expected, not a defect. |
| **Predicted trip duration** from live traffic (Uber runs a learned ETA model over road segments) | `distance / 30 km/h`, a flat 2 min/km | Same non-goal. There is no traffic signal in this system to predict from. |
| **A booking fee**, added *after* the multiplier and never surged | none | No FR asks for one. Worth knowing that the incumbent shape surges the ride portion only — Puber's formula surges everything, which is consistent because Puber has no non-surged component. |
| **A minimum-fare adjustment**, a first-class component in Uber's wording | none | No FR asks for one, and the base fare already floors the price (a 100 m trip prices at `2.67` on D6's values). YAGNI: do not add one. |

**One consequence of the fixed-speed simplification, and you must not "fix" it.**

Because `time` is derived from `distance` at a constant speed, the two terms are **mathematically
proportional**, so `per_km_rate` and `per_minute_rate` are **not independent parameters**:

```
distance_km  = metres / 1000
time_minutes = 60 / 30 x distance_km = 2 x distance_km        exactly, at 30 km/h

fare = (base + distance_km x (per_km + 2 x per_minute)) x surge
```

The four-column table therefore has **three degrees of freedom**, and on D6's seeded values the
effective rate is exactly `120 + 2 x 25 = 170` minor units per kilometre. (Under the old `8.33 m/s`
this was `170.0200080032...` — an exact figure is checkable in a test; a repeating one is not.)

In a real system the two terms *are* independent, because the time term is what prices congestion —
a 5 km crawl through traffic costs more than a 5 km clear run. Puber has no traffic, so that
independence collapses.

**Keep both columns anyway.** They are specified by `glossary.md#Entities`, `addendum.md#Data Model`
and CAP-2, the schema is what a real implementation needs, and collapsing them would be a contract
change for no gain. But **record the degeneracy** — in the migration README and in a short comment on
the calculation — because two things follow from it that will otherwise waste someone's afternoon:

- Tuning `per_minute_rate` alone looks like it should change the time-sensitivity of the price. It
  cannot. It only moves the effective per-kilometre rate.
- A test that varies distance and time independently cannot be written from two coordinates. Task
  6.4's surge test is genuinely independent; a "time term" test is not — assert the arithmetic on a
  `FareRule` directly if you want to exercise `per_minute_rate` in isolation.

### Why a table and not an environment variable

Raised during story review, recorded so it is not re-litigated. The instinct is reasonable — three of
the four values look like static configuration, and a per-quote database read looks like avoidable
work. It is still the wrong shape, for five reasons in descending order of decisiveness.

1. **Surge is written at runtime; an environment variable cannot be.** Story 4.8 (PUB-35) recomputes
   it *"periodically from the ratio of outstanding ride requests to available drivers"*, from a
   scheduler AD-9 places in `dispatch` *"precisely because it sits above `ride` and `fare`"*. An env
   var changes only on restart, and restarting the service on every surge tick is not an option.
2. **FR-19 puts surge inside the fare rules:** *"Surge is a multiplier held in configurable fare
   rules."* Env vars for the three rates plus a table for surge gives one formula two change
   mechanisms and two sources of truth. It also breaks the story's own purpose — explaining a fare is
   easy when all four inputs come from one queryable row.
3. **A surge change is an audited `SYSTEM` action** (FR-41 lists "surge recomputation"; CAP-31
   requires an audit event per transition). A row change can be audited. An env var change cannot —
   nothing observed it happening.
4. **Env vars are per-process, so replicas drift.** Two replicas holding different rates quote
   different prices for the same trip and nothing detects it.
5. **AD-3 names `fare_rules` as a table `matching-service` owns.** Changing that is a spine
   amendment, not an implementation choice.

**On the read cost, and do not "optimise" this without a measurement.** The read is a single-row
primary-key select — one 8 KB page that stays in Postgres's shared buffers, over an already-pooled
connection to a container on the same Compose network. AD-47 already assumes this shape: *"the read
path barely touches Postgres at all."* **No measurement has been taken**, and AD-47's rule is that
capacity numbers are derived by measurement; Story 7.6 (PUB-59) is where that happens.

**Redis is specifically not the answer**, and this is the part that is easy to get wrong: Redis and
Postgres are both a network round trip to a peer container, so a Redis `GET` and a warm indexed
single-row `SELECT` are the same order of magnitude. Caching here buys no latency and costs an
invalidation layer. Redis also does not exist in this system until Story 4.7 (PUB-34).

If a measurement ever shows this read mattering, the cheap fix is an in-process cache with a short
TTL — no new infrastructure. Note the cost honestly if that day comes: Story 4.8 requires the locked
fare to use *"the multiplier current at that moment"*, and a TTL makes that "current within N
seconds".

**For this story: read the row per calculation. No cache, no in-process memoisation, no
`@Cacheable`.**

### Honest limits of this story

Two things are worth saying plainly rather than discovering in review.

**AC6 is only half-provable here.** Its first clause — latitude `DECIMAL(10,8)`, longitude
`DECIMAL(11,8)`, WGS84 — is fully testable, and `Coordinates` plus a column type settle it. Its
second clause, *"longitude precedes latitude in geo calls"*, **has no geo call in this story to
apply to.** There is no Redis, no `GEOSEARCH`, no geo index until Story 4.7 (PUB-34). So the
ordering rule is carried as a comment on `Coordinates` and enforced by nothing. Do not manufacture a
geo call to make the clause testable; say in the completion notes that the second clause is
groundwork, and let Story 4.7 be the story that proves it.

**AC4's "no maps or routing API is called anywhere" is a prohibition, not a behaviour.** You can
show that the code you wrote calls none, and that no HTTP client is on the fare path. You cannot
prove a negative about future code, and no scanner enforces it. State what you checked.

### Scope boundaries — what is deliberately not here

| Not in this story | Where it lands |
| --- | --- |
| Any HTTP or gRPC surface, any controller, `rider-service`, the gateway route | Story 1.4 (PUB-4) |
| A `quote` package, ETA, driver proximity | Story 2.6 (PUB-10) |
| Writing surge from live demand, the surge scheduler in `dispatch`, the surge metric | Story 4.8 (PUB-35) |
| `rides`, locking a fare onto a ride, the fare breakdown snapshot | Story 3.2 (PUB-13) |
| A `currency` column | **Never** — the spine rejected a currency dimension on purpose |
| Timestamp columns on `fare_rules` | Whenever a story actually writes one (D8) |
| Copying the time rules into a second service | Story 1.4 |

**On the currency column specifically:** `reviews/review-adversarial.md#C10` recommends
`DECIMAL(12,2)` **plus `currency CHAR(3)`**. Both halves are now **superseded** — the
2026-08-23 amendment unifies on integer minor units instead (see D2), and it **rejects the currency
column** — the spine's own run log records removing a currency dimension because it *"invented a
dimension no entity has"*, and no entity list, FR or convention mentions currency. If you find C10 and
implement it whole, you will be adding a column nothing asked for.

**On the fare breakdown:** the same review's finding C3 argues a ride should record every rate used at
lock time, so a past charge can be explained, and concludes *"`fare_rules` is mutable configuration
and is never read to explain a past ride."* That was **never absorbed into the spine.** It concerns
`rides`, so it does not change anything you build — but it does settle what `fare_rules` **is**:
current configuration, not history. Do not add versioning or an effective-from column.

### Previous story intelligence

PUB-1 and PUB-2 are both `done`. The learnings that change what you do:

1. **A test that cannot fail is the review finding that recurs.** PUB-2's review planted two
   deliberate typos into a list of banned methods and watched the entire suite stay green — which
   falsified a code comment claiming that case was covered. That is why Tasks 5.4, 6.8 and 7.3 all ask
   you to plant, run, capture, revert.
2. **A rationale is not evidence.** Three guards written during PUB-1 (`ALLOW_ROOT`,
   `forbidSubstituteDatastores`, `.NOTPARALLEL`) each had a convincing comment and each guarded
   nothing. Before adding a guard, name the failure and check it is real.
3. **PUB-2's review found two of its own comments broke the Comments rule**, because neither the dev
   run nor the review run had `AGENTS.md` open when the code was written. **Read `AGENTS.md` before
   you write code** — it is loaded into `bmad-dev-story` and `bmad-code-review` via
   `_bmad/custom/*.toml`, and nothing else loads it.
4. **Both hooks and both suites are the only gate.** There is no CI server, by decision.
5. `docs/` and `docs/tickets/pb-*.md` are a superseded planning attempt, explicitly
   non-authoritative. If a search surfaces `pb-1.3.md` from git history, **ignore it** — it looks
   like it addresses this story and does not.

### Git intelligence

```
2640dbe PUB-2
41f2540 PUB-1
485e571 PUB-1 defined
f943d1e sprint planning
f502e2e check-implementation-readiness
```

One commit per story, after the full gate passes. PUB-2's commit added `shared/strategy/Clock.java`,
`shared/strategy/SystemClock.java`, `shared/model/Deadline.java`, `config/ClockConfiguration.java`,
`DatabaseNeverReadsTimeTest`, the `rules/fixtures/` directory and `ClockWiringIntegrationTest` — the
exact set of shapes this story extends.

### Pinned versions — do not move any of these

Java/Temurin **25** · Spring Boot **4.1.0** · Gradle wrapper **9.5.1** · PostgreSQL **18.6** ·
Flyway **12.4.0 (BOM-managed, never pinned)** · JUnit Jupiter **6.0.3** · Micrometer **1.17.0** ·
ArchUnit **`archunit-junit6:1.5.0`** · Spotless **8.10.0** · `io.spring.dependency-management` **1.1.7**

No new dependency should be needed for this story. `BigDecimal` and `Math` are in the JDK, and
`spring-boot-starter-jdbc` is already present. **If you think you need a library, stop and say why** —
adding one is an architecture decision here, not a tooling choice.

### Project Structure Notes

Target layout after this story, and nothing beyond it:

```
services/matching-service/src/
  main/java/com/puber/matching/
    config/ClockConfiguration.java                (unchanged)
    shared/model/     Deadline.java  Money.java  Coordinates.java  Distance.java
    shared/strategy/  Clock.java  SystemClock.java                 (unchanged)
    fare/model/       FareRule.java  AssumedSpeed.java
    fare/repository/  FareRuleRepository.java
    fare/service/     CalculateFare.java
  main/resources/db/migration/
    V1__baseline.sql (untouched)  V2__create_fare_rules.sql  V3__seed_fare_rules.sql  README.md
  test/java/com/puber/matching/
    rules/            ArchitectureRulesTest.java (extended)  + new fixtures
    shared/model/     MoneyTest  CoordinatesTest  DistanceTest
    fare/             CalculateFareTest
  integrationTest/java/com/puber/matching/
    HealthMetricsAndSchemaIntegrationTest.java    (edited — Task 4)
    FareRulesIntegrationTest.java                 (new — Tasks 5.4, 5.5, 6.7)
    fixtures/FareRulesFixture.java                (new — Task 5.2, the loader)
  integrationTest/resources/
    fixtures/fare_rules.sql                       (new — Task 5.1, the test baseline)
```

**`db/migration/` is production; `integrationTest/resources/fixtures/` is tests.** The two never cross,
except in Task 5.5's test where the production seed is itself the subject under test.

Java packages are suffix-free (`com.puber.matching`); the directory keeps the `-service` suffix
(AD-12). The domain package is **`model`**, never `entity` — `noPackageIsNamedEntity` enforces it.
No root build: `matching-service` has its own wrapper and build file, and the `Makefile` orchestrates
it (AD-52).

### References

- `_bmad-output/planning-artifacts/epics/epic-1-foundations-fare-quote.md#Story 1.3: Fares are computed from configurable rules` — the seven ACs
- `_bmad-output/planning-artifacts/epics/overview.md#Standing acceptance criteria` — apply whether restated or not
- `ARCHITECTURE-SPINE.md#AD-3` (ownership), `#AD-7` (layers), `#AD-8` (one-way), `#AD-9` (feature order, surge scheduler in `dispatch`), `#AD-10` (no repository interface; the five Strategy seams), `#AD-12` (naming), `#AD-25` (bounds are configuration), `#AD-56` (real stack, truncate-and-reseed, sequential), `#AD-57` (SOLID made testable), `#Consistency Conventions` (Money, Coordinates, expand-only, SQL, transactions), `#Deferred` (surge is global), `#Stack`
- `specs/spec-puber/SPEC.md#Capabilities → CAP-2` (the rate-bearing formula), `#CAP-14` (surge), `#Constraints` (haversine, one 30 km/h speed for both the ETA and the trip, no maps)
- `specs/spec-puber/glossary.md#Ride and money` (Fare, ETA, Surge), `#Entities` (`fare_rules` columns)
- `prds/prd-puber-2026-08-02/prd.md#3. Features → B` (FR-18, FR-19), `#5. Non-Goals` (no maps/routing)
- `prds/prd-puber-2026-08-02/addendum.md#Constraints That Still Hold`, `#Data Model`
- `project-context.md` — binding project rules, and the Boot 4.1 traps
- `AGENTS.md` — coding style. **Read it before writing code; nothing loads it automatically.**
- `implementation-artifacts/deferred-work.md` — the ArchUnit-rules item that names this story
- `implementation-artifacts/PUB-1-*.md`, `PUB-2-*.md` — established patterns and the traps above

---

## Questions for the repo owner

None of these blocks implementation — every one is pinned above so the dev agent has a deterministic
answer. They are here because the answer was **chosen by this story rather than found in a document**,
and a choice nobody ratified is a choice that gets re-argued in review.

1. **Units (D1) — resolved, no longer a question.** Uber and Bolt both price on a per-distance-unit
   and a per-minute rate, so the column names match industry practice and the conversion belongs at
   the call site. The `per_metre`/`per_second` alternative is rejected. Recorded here only so the
   reasoning is visible; see Dev Notes → "How the real ones do it".
2. **Reusing the ETA speed for trip duration — resolved.** Ratified by the repo owner on 2026-08-23:
   one 30 km/h constant serves both the driver-to-pickup ETA and the trip duration. `SPEC.md` and
   `glossary.md` were corrected to say so
   (`planning-artifacts/sprint-change-proposal-2026-08-23-b.md`). No longer an open question.
3. **Money representation (D2) — resolved.** Amended in the spine and `project-context.md` on
   2026-08-23 via `planning-artifacts/sprint-change-proposal-2026-08-23.md`: integer minor units
   everywhere, `BIGINT` at rest, `DECIMAL` only for coefficients. No longer a story-local choice.
   Still story-local and unratified: rounding `HALF_UP` once at the end (D4), earth radius
   6 371 000 m (D5), surge `DECIMAL(4,2)` scale (D3), the singleton constraint (D7). Should these
   become a further spine amendment after PUB-3 lands, or stay `project-context.md` rules?
4. **Seed values `2.50 / 1.20 / 0.25` (D6).** Only the surge `1.00` is specified anywhere. Happy with
   these, or do you want different numbers in the first migration?
5. **Task 7.4 — reshaping `modelDependsOnNothingFrameworkFlavoured`.** It is the one task here that
   changes an existing rule rather than adding to the story's own work, and its live hole is real (it
   bans Jackson 2, which is not on the classpath, and not Jackson 3, which is). Include it in PUB-3
   or split it out?
6. **Should a booking fee and a minimum fare be storied at all?** Both are first-class components at
   Uber and Bolt, and neither appears in any Puber FR. My call is no — YAGNI, and the base fare
   already floors the price. Flagging it because "our fares look nothing like a real app's" is a
   plausible future observation, and this is the answer to it.
7. **FR-18's text — resolved.** Corrected on 2026-08-23
   (`planning-artifacts/sprint-change-proposal-2026-08-23-c.md`): FR-18 and the PRD glossary now
   carry the rate-bearing formula and name the units, so the PRD, SPEC, both glossaries and Story 1.3
   AC2 all agree. **No questions remain open on this story.**

## Dev Agent Record

### Agent Model Used

`claude-opus-5` (Claude Code, `bmad-dev-story`).

### Debug Log References

Every claim below was produced by running something, not by reading code.

| What was planted | Result | Evidence |
| --- | --- | --- |
| `private static final double PLANTED_VIOLATION` on `FareRule` (production) | **red**, then green on revert | `floatingPointIsConfinedToDistance FAILED … com.puber.matching.fare.model.FareRule.PLANTED_VIOLATION uses the floating-point type double` |
| `new BigDecimal(0.1)` inside `CalculateFare.priceOf` (production) | **red**, then green on revert | `bigDecimalIsNeverBuiltFromADouble FAILED … calls constructor <java.math.BigDecimal.<init>(double)> in (CalculateFare.java:42)`. The three `BigDecimal.valueOf(...)` calls in the same method stayed green, so the rule does not ban the correct call. |
| `@BeforeEach` removed from `FareRulesIntegrationTest`, table left as the previous run left it | **green — the plant did not bite** | See the paragraph below. Reported rather than reasoned away. |
| `@BeforeEach` still removed, `fare_rules` truncated from outside the suite (`psql`) | **red**, 2 tests | `IllegalStateException: fare_rules holds no row -- every fare depends on it…` on `reads_the_price_list_from_postgres` and `prices_a_trip_from_the_row_in_postgres` |
| `@BeforeEach` restored, `fare_rules` truncated from outside the suite again | **green** | The reseed is what makes the class independent of the database state it inherits. |

**The uncomfortable result, stated plainly.** Removing the `@BeforeEach` on its own did *not* turn
the class red. JUnit happened to run the two reading tests before the two truncating ones, so the row
left behind by the previous run satisfied them. That does not mean the reseed is decorative — the
second and third plants show it is what makes the class survive an empty inherited table, which is
its actual job — but it does mean **"the suite twice produces identical results" is not by itself
evidence that seeding works.** It would be satisfied by being consistently broken, exactly as the
story warned. The assertion that carries the weight is
`restores_the_price_list_after_a_truncate`, which truncates, checks the table really is empty, calls
the loader, and checks the row came back.

**A guard I did not add, because the failure was not real.** Task 7.4 reshapes
`modelDependsOnNothingFrameworkFlavoured` from a nine-package blocklist into an allowlist
(`java..`, `com.puber..`). I expected to need an escape hatch for primitives, since `Deadline` holds
a `long` and `Distance` a `double`. Ran it without one: it passes
(`LayerRulesTest.accepts_the_real_domain_types` imports `Money`, `Distance` and `Deadline`), so
ArchUnit does not count primitives as dependencies here and the clause would have guarded nothing.

**AC7's second clause, run rather than argued.** `make test` twice: 79 passed / 1 failed both times,
and the two runs' full `Class > display name PASSED|FAILED` lists diff clean.

**The failure I did not cause.** `HealthReportsDownPromptlyIntegrationTest > initializationError`
fails in this sandbox: its own precondition detects that `192.0.2.1:5432` (TEST-NET-1) *accepts* a
TCP connection in 2 ms, so the test would pass vacuously and it refuses to run. `git log -1` on that
file returns `41f2540 PUB-1` — it is untouched by this story, and it is the known environmental red
`project-context.md` names. It is not green and it is not mine.

### Completion Notes List

**What this story added, in plain terms.** `matching-service` now owns one small configuration table
holding a four-number price list, and one function that turns a pickup and a dropoff into a price.
Nothing is exposed over HTTP — that is PUB-4.

| AC | Where it is satisfied | Proven by |
| --- | --- | --- |
| AC1 — the table exists and this service owns it | `V2__create_fare_rules.sql` | `HealthMetricsAndSchemaIntegrationTest.the_schema_holds_exactly_the_tables_this_service_owns` (exactly `fare_rules`, nothing else), `FareRulesIntegrationTest.reads_the_price_list_from_postgres` |
| AC2 — the formula | `CalculateFare` | `CalculateFareTest` (5 km → 1100, no distance → 250, surge 2.00 → 2200), `FareRulesIntegrationTest.prices_a_trip_from_the_row_in_postgres` |
| AC3 — surge seeded at 1.00 | `V3__seed_fare_rules.sql` | `FareRulesIntegrationTest.a_first_start_seeds_the_surge_at_one`, which executes the migration statement itself |
| AC4 — haversine, one 30 km/h speed, no maps | `Coordinates.distanceTo`, `AssumedSpeed` | `CoordinatesTest` (Paris→London within 2 km of 344 km), `DistanceTest`, `AssumedSpeedTest` |
| AC5 — money is integer minor units | `Money`, `FareRule`, `BIGINT` columns | `FareRulesIntegrationTest.stores_money_as_bigint_and_the_surge_as_a_decimal`, plus the two new ArchUnit rules, both proven by planting |
| AC6 — coordinate representation | `Coordinates` | `CoordinatesTest` range rejections. Second clause: see the limits below |
| AC7 — test isolation | `fixtures/fare_rules.sql` + `FareRulesFixture` + `@BeforeEach` | `restores_the_price_list_after_a_truncate`, and `make test` twice |

**Honest limits — read these before reviewing.**

1. **AC6 is only half-covered, and less than the story predicted.** The story expected "`Coordinates`
   plus a column type" to settle the first clause. There is no coordinate column in this story:
   `fare_rules` holds none, and the first table with a latitude/longitude arrives with Story 2.1.
   Nor does the type carry the precision: `Coordinates` validates the *range* only, and a
   `BigDecimal` is unbounded in scale, so nothing in it expresses `DECIMAL(10,8)` /
   `DECIMAL(11,8)` or WGS84. **No code carries the first clause yet** — it is the spine's
   Coordinates convention row and nothing else (the migration README that also carried it was
   deleted as duplication on 2026-08-24). The second clause — longitude precedes latitude in geo
   calls — has no geo call to apply to and is enforced by nothing; it is a comment on `Coordinates`
   and it is Story 4.7's to prove.
2. **AC4's "no maps or routing API is called anywhere" is a prohibition, not a behaviour.** What I
   checked: `Coordinates.distanceTo` uses `java.lang.Math` only, nothing on the fare path constructs
   an HTTP client, and the `fare` package imports nothing but `java..`, `com.puber..` and Spring's
   `stereotype`/`jdbc`. No scanner enforces this, and no test can prove a negative about future code.
3. **~~The singleton `CHECK (id = 1)` is untested.~~ Closed by the code review**, which added
   `refuses_a_second_price_list` and `refuses_a_surge_of_zero`. Both constraints now fail a real
   `INSERT`/`UPDATE` against Postgres rather than being taken on the DDL's word.

**Two implementation choices a reviewer should look at.**

1. **~~`CalculateFare.priceOf(FareRule, Distance)` is package-private.~~ Superseded** — the repo
   owner read that justification for what it was ("a trick for unit testing") and had the class made
   pure instead. See the walkthrough section below; `calculate` is now `public static` and takes the
   rule as an argument, so there is no seam to justify.
2. **`AssumedSpeed` is a final class with a static `minutesToCover`**, so the call site reads
   `AssumedSpeed.minutesToCover(distance)`. D1's snippet showed an instance
   (`ASSUMED_SPEED.minutesToCover(...)`); a singleton instance would carry no state. The parts D1
   pins — kilometres first, minutes from kilometres, the 2 derived from the speed rather than written
   — are as specified.

**Task 7.4 was done, not cut.** The reshaped rule now rejects a `model` type depending on Jackson 3
(`LayerRulesTest.rejects_a_model_type_that_depends_on_the_framework`), which is the exact hole the
old blocklist left open, and it rejects every future framework without anybody remembering to add it.

**New test-source packages that exist only to be scanned.**
`com.puber.matching.ride.model.ARideTypeThatOnlyExistsToBeScanned` stands in for the `ride` feature so
the layered rule has an upper layer to catch `fare` reaching into — delete it when the real `ride`
package arrives in Epic 3. Likewise `rules/fixtures/{controller,entity,model,service}`. All are
excluded from the production scan by `DoNotIncludeTests`, which is why each needs a driving test.

**Ratified by the repo owner on 2026-08-23, after implementation.** The three questions this story
left open are answered, and the answers are recorded where something reads them:

1. **Seed values `2.50 / 1.20 / 0.25` (D6) — approved as implemented.** They stay configuration; a
   later change is a data change, not a migration.
2. **D3, D4, D5 and D7 promoted to the spine.** `ARCHITECTURE-SPINE.md` now carries **AD-62 — A fare
   is a pure function of two coordinates and one row of rules** (the earth radius and the
   `GEOSEARCH` comparison warning, the single-row `fare_rules` shape, the `DECIMAL(4,2) CHECK (> 0)`
   surge column, and the loud failure on a missing row), and the **Money** convention row was
   sharpened to state D4's rounding exactly (`setScale(0, HALF_UP)`, then `longValueExact()`, no
   `MathContext` mid-expression). The capability map's *Fares and surge* row and the *Per-cell surge*
   Deferred item now point at AD-62.
   Because a rule may live in only one place, `project-context.md` and the migration `README.md` were
   **trimmed to point at AD-62** rather than restate it — what stays in `project-context.md` is the
   metres→kilometres→minutes conversion chain, the instruction that Story 2.6 must *move*
   `AssumedSpeed` into `shared`, and the note that the two floating-point rules are enforced.
   `D1`'s units decision and `D8`'s no-timestamps decision were **not** promoted: the first is a code
   shape rather than an architecture decision, and the second follows from the existing "never let the
   database tell the time" rule.
3. **Task 7.4 kept inside PUB-3**, as implemented.

**Noticed in passing, not caused here.** `src/test/java/.../rules/TestNamingRulesTest.java` was
untracked in git before this story ran (created by PUB-2, never committed). It is staged now along
with this story's files.

**Changed after the code review, in a walkthrough with the repo owner (2026-08-23).** Ten changes,
none of them behavioural except where noted. Listed because several reverse a decision recorded
above, and a record that only holds the first answer is worse than no record.

| # | Change | Why |
| --- | --- | --- |
| 1 | `CalculateFare` is now a **pure `public static` function** — `calculate(FareRule, Distance)`. `@Service`, the constructor, the `FareRuleRepository` field and `priceOf` are gone. | The package-private seam existed for the test, and its own javadoc admitted it. The caller now composes `CalculateFare.calculate(fareRules.priceList(), pickup.distanceTo(dropoff))`. Takes a `Distance` rather than two coordinates so the boundary tests keep exact distances (`new Distance(1005)` is 100.500 exactly; the same distance from coordinates is not a clean literal). |
| 2 | `FareRule.baseFare` is a **`Money`**; the two rates stay `long`. | The base fare *is* an amount. A rate is money *per unit*, and typing it as `Money` would make adding it to `baseFare` look reasonable. Does **not** close `perKmRate x minutes`, which still compiles. |
| 3 | SQL is a **literal at the call site**, never a `private static final String`. Recorded as a new `AGENTS.md` rule, `SQL stays a literal at the call site`. | One query per method means one constant per method, so every method becomes two hops and the SQL sits furthest from where it runs. Scoped to the query text only: a literal inside the query still gets a named constant and a bind parameter. |
| 4 | `THE_ONLY_ROW` and the `where id = ?` clause **deleted**. | The table holds one row by construction, so there is nothing to select by. |
| 5 | The `RowMapper` **lambda is inlined** into the `query(...)` call, with `(row, _)` for the unused row number. | Spring's own guidance extracts a mapper to a field when it is *duplicated*; with one query the field was premature. `_` is JEP 456, final in **Java 22** — verified by compiling, and it makes this file require Java 22+. |
| 6 | The zero-distance integration test **deleted**. | The review's `prices_a_real_trip_from_the_rates_in_postgres` covers the same wiring with every term exercised. Nothing could fail the deleted test and pass that one, and a trip that goes nowhere is not a domain case. |
| 7 | `EQUATOR` **deleted**; `ONE_DEGREE_NORTH` now sits on Lisbon's meridian. | Equal longitude is what collapses the haversine to radius x angle, not being at the equator, so one degree of latitude is 111194.9266 m anywhere. Same expectation, one coordinate system in the file. |
| 8 | The derivation comment in that test now **carries its units** and shows where the `2` comes from. | `minutes = km x 2` read as dimensionally impossible. It is `2 min/km`, which is what 30 km/h means: `60 min/h / 30 km/h`. |
| 9 | `private CalculateFare() {}` **removed**; a one-line formula comment added above the `BigDecimal` chain. | The constructor prevented no real failure — YAGNI, the same category as PUB-1's three phantom guards. The formula comment is the one that earns its place: `.add()/.multiply()` hides the algebra it implements. |
| 10 | Comments the repo owner removed were **left removed**, and comments I had added that restate a rule or a line were deleted. | `AGENTS.md` -> Comments. I broke it twice in this session with the file loaded, once by writing a comment and offering to let the reviewer strip it, which makes a rule into a suggestion. |

**One thing this walkthrough gave up, and PUB-4 should pick it up.** With `CalculateFare` pure,
**no production class composes "read the rules, then price"** — the integration test does it. AD-62's
rule that the row is read *per calculation* and never cached was previously enforced by sitting
inside the only method that priced anything; it is now one level up, in a caller that does not exist
yet. PUB-4 is where a field can hold a stale price list and nothing mechanical would notice.

### File List

**Added — production**

- `services/matching-service/src/main/java/com/puber/matching/shared/model/Money.java`
- `services/matching-service/src/main/java/com/puber/matching/shared/model/Coordinates.java`
- `services/matching-service/src/main/java/com/puber/matching/shared/model/Distance.java`
- `services/matching-service/src/main/java/com/puber/matching/fare/model/FareRule.java`
- `services/matching-service/src/main/java/com/puber/matching/fare/model/AssumedSpeed.java`
- `services/matching-service/src/main/java/com/puber/matching/fare/repository/FareRuleRepository.java`
- `services/matching-service/src/main/java/com/puber/matching/fare/service/CalculateFare.java`
- `services/matching-service/src/main/resources/db/migration/V2__create_fare_rules.sql`
- `services/matching-service/src/main/resources/db/migration/V3__seed_fare_rules.sql`

**Added — unit tests and rule fixtures**

- `services/matching-service/src/test/java/com/puber/matching/shared/model/MoneyTest.java`
- `services/matching-service/src/test/java/com/puber/matching/shared/model/CoordinatesTest.java`
- `services/matching-service/src/test/java/com/puber/matching/shared/model/DistanceTest.java`
- `services/matching-service/src/test/java/com/puber/matching/shared/model/SharedTypeThatDependsOnAFeature.java`
- `services/matching-service/src/test/java/com/puber/matching/fare/model/AssumedSpeedTest.java`
- `services/matching-service/src/test/java/com/puber/matching/fare/model/FareTypeThatDependsOnRide.java`
- `services/matching-service/src/test/java/com/puber/matching/fare/service/CalculateFareTest.java`
- `services/matching-service/src/test/java/com/puber/matching/ride/model/ARideTypeThatOnlyExistsToBeScanned.java`
- `services/matching-service/src/test/java/com/puber/matching/rules/LayerRulesTest.java`
- `services/matching-service/src/test/java/com/puber/matching/rules/FeaturePackagesRunOneWayRuleTest.java`
- `services/matching-service/src/test/java/com/puber/matching/rules/MoneyIsNeverFloatingPointRuleTest.java`
- `services/matching-service/src/test/java/com/puber/matching/rules/fixtures/DeclaresFloatingPoint.java`
- `services/matching-service/src/test/java/com/puber/matching/rules/fixtures/BuildsABigDecimalFromADouble.java`
- `services/matching-service/src/test/java/com/puber/matching/rules/fixtures/BuildsABigDecimalSafely.java`
- `services/matching-service/src/test/java/com/puber/matching/rules/fixtures/DependsOnAController.java`
- `services/matching-service/src/test/java/com/puber/matching/rules/fixtures/controller/AControllerNothingMayDependOn.java`
- `services/matching-service/src/test/java/com/puber/matching/rules/fixtures/entity/AClassInAPackageNamedEntity.java`
- `services/matching-service/src/test/java/com/puber/matching/rules/fixtures/model/ModelTypeThatDependsOnJackson.java`
- `services/matching-service/src/test/java/com/puber/matching/rules/fixtures/service/ServiceThatDependsOnAConcreteStrategy.java`

**Added — integration tests and test data**

- `services/matching-service/src/integrationTest/java/com/puber/matching/FareRulesIntegrationTest.java`
- `services/matching-service/src/integrationTest/java/com/puber/matching/fixtures/FareRulesFixture.java`
- `services/matching-service/src/integrationTest/resources/fixtures/fare_rules.sql`

**Modified**

- `services/matching-service/src/test/java/com/puber/matching/rules/ArchitectureRulesTest.java` — two new money rules; `modelDependsOnNothingFrameworkFlavoured` reshaped from blocklist to allowlist
- `services/matching-service/src/integrationTest/java/com/puber/matching/HealthMetricsAndSchemaIntegrationTest.java` — migration ceiling bumped to V3; the table assertion now names the tables this service owns; attribution message and javadoc corrected to the two real filenames
- ~~`services/matching-service/src/main/resources/db/migration/README.md`~~ — **deleted 2026-08-24.** Once AD-62 existed, the file was duplication: its conventions block was verbatim spine, four of its eight decision rows already deferred to AD-62, the seed values and the no-timestamps reasoning are in `V3` and `V2` themselves, and the test-data boundary is a `project-context.md` section. The one thing it alone still carried — the rate degeneracy — moved to `project-context.md`
- `_bmad-output/planning-artifacts/architecture/architecture-puber-2026-08-03/ARCHITECTURE-SPINE.md` — **AD-62** added; Money convention row sharpened with the exact rounding; capability map and the *Per-cell surge* Deferred item cross-referenced; `updated` bumped
- `project-context.md` — new section "Pricing a trip: the unit conversion (PUB-3)", holding only what AD-62 does not
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — PUB-3 `ready-for-dev` → `in-progress` → `review`
- `_bmad-output/implementation-artifacts/PUB-3-fares-are-computed-from-configurable-rules.md` — this record

**Modified — the `snake_case` sweep, added by the code review after the File List omitted them**

The `AGENTS.md` rule this story introduced was applied to the whole existing suite in the same pass.
Method renames only, no behaviour change:

- `services/matching-service/src/integrationTest/java/com/puber/matching/ClockWiringIntegrationTest.java` — 2 methods
- `services/matching-service/src/integrationTest/java/com/puber/matching/HealthReportsDownPromptlyIntegrationTest.java` — 1 method
- `services/matching-service/src/test/java/com/puber/matching/rules/ArchUnitReadsJava25ClassFilesTest.java` — 2 methods
- `services/matching-service/src/test/java/com/puber/matching/rules/DatabaseNeverReadsTimeTest.java` — 5 methods
- `services/matching-service/src/test/java/com/puber/matching/rules/TimeIsReadOnlyThroughTheClockRuleTest.java` — 5 methods
- `services/matching-service/src/test/java/com/puber/matching/shared/model/DeadlineTest.java` — 3 methods
- `services/matching-service/src/test/java/com/puber/matching/shared/strategy/ControllableClockTest.java` — 5 methods
- `services/matching-service/src/test/java/com/puber/matching/shared/strategy/SystemClockTest.java` — 3 methods
- `services/matching-service/src/test/java/com/puber/matching/rules/TestNamingRulesTest.java` — **new**, and untracked in git before this story ran
- `AGENTS.md` — the `snake_case` rule itself. **It was unstaged until the code review staged it**, so
  the commit would otherwise have shipped the enforcement and every rename without the rule behind them.
  It later gained the `SQL stays a literal at the call site` rule from the walkthrough above.

**Added and modified by the code review (2026-08-23)**

- `services/matching-service/src/integrationTest/java/com/puber/matching/rules/TestNamingRulesIntegrationTest.java` — **new**; the naming rule over the integration source set
- `services/matching-service/src/test/java/com/puber/matching/rules/fixtures/model/ModelTypeCarryingAFrameworkAnnotation.java` — **new**; proves the annotation form of a framework dependency is rejected
- `services/matching-service/src/test/java/com/puber/matching/rules/ArchitectureRulesTest.java` — the floating-point rule now walks array components and generic arguments; two javadoc claims corrected
- `services/matching-service/src/test/java/com/puber/matching/rules/fixtures/DeclaresFloatingPoint.java` — array and boxed-collection members added
- `services/matching-service/src/test/java/com/puber/matching/rules/LayerRulesTest.java` — the annotation-form case
- `services/matching-service/src/test/java/com/puber/matching/rules/TestNamingRulesTest.java` — scope limit recorded
- `services/matching-service/src/test/java/com/puber/matching/shared/model/CoordinatesTest.java` — tolerance 2 km to 200 m; pole-to-pole and date-line assertions added
- `services/matching-service/src/test/java/com/puber/matching/shared/model/DistanceTest.java` — the test of the now-private `inMetres()` removed
- `services/matching-service/src/integrationTest/java/com/puber/matching/FareRulesIntegrationTest.java` — a real trip priced from the database row, and both CHECK constraints asserted
- `services/matching-service/src/integrationTest/java/com/puber/matching/HealthMetricsAndSchemaIntegrationTest.java` — the migration ceiling now gates an assertion
- `services/matching-service/src/main/java/com/puber/matching/shared/model/Distance.java` — `inMetres()` made private
- `services/matching-service/src/main/java/com/puber/matching/shared/model/Money.java`, `fare/model/AssumedSpeed.java` — overstated comments corrected
- `project-context.md`, `AGENTS.md` — the two enforcement claims corrected to say what is actually enforced

## Change Log

| Date | Change |
| --- | --- |
| 2026-08-23 | PUB-3 implemented: `fare_rules` (V2 DDL, V3 seed), the `shared/model` money-and-geometry types, the `fare` feature package, the two-tier test data, and the ArchUnit rules that make "never floating point" structural. Status → `review`. `make build` green; `make test` 79 passed / 1 failed, the failure being PUB-1's known environmental precondition. |
| 2026-08-23 | Repo owner ratified the open questions: seed values approved, Task 7.4 kept in scope, and D3/D4/D5/D7 promoted into `ARCHITECTURE-SPINE.md` as AD-62 plus a sharpened Money convention row. `project-context.md` and the migration `README.md` trimmed to point at the spine so no rule lives in two files. `make build` re-run green; `make test` re-run 79 passed / 1 failed (the same known environmental precondition). |
| 2026-08-23 | Code review (`bmad-code-review`, three parallel reviewers plus an independent verification pass). Four holes proven by planting: `calculate` never had its coordinate-to-distance seam tested, integration-suite test names were unenforced, the earth radius was unpinned by a 2 km tolerance, and the floating-point rule was blind to arrays, boxed collections and locals. All 17 patches applied; 4 items deferred to `deferred-work.md`; 5 reviewer claims dismissed, two of them disproved by checking. `AGENTS.md` was unstaged and is now staged. `make build` green; `make test` 20 integration tests / 1 failed, the failure being PUB-1's known environmental precondition. |
| 2026-08-23 | Walkthrough with the repo owner after the review: `CalculateFare` made a pure `public static` function taking the rule as an argument, `FareRule.baseFare` typed as `Money`, SQL inlined as a literal (new `AGENTS.md` rule) with the `where` clause and its constant dropped, the `RowMapper` lambda inlined with `(row, _)`, the redundant zero-distance integration test deleted, the `EQUATOR` constant replaced by Lisbon's meridian, the fare derivation comment given its units, and `private CalculateFare()` removed. `make build` green after each change; `make test` 85 passed / 1 failed throughout, the failure being PUB-1's known environmental precondition. |
| 2026-08-24 | `db/migration/README.md` deleted as duplication now that AD-62 exists; the rate-degeneracy note it alone carried moved into `project-context.md` -> "Pricing a trip". `Coordinates` and `Distance` javadoc trimmed to what is not recorded elsewhere or enforced by a rule. `AGENTS.md` gained `No test-only seams`, written because PUB-3 shipped exactly that shape. `make build` green; `make test` 85 passed / 1 failed (PUB-1's known environmental precondition). |
| 2026-08-24 | Walked the four items the code review deferred and emptied the whole PUB-3 section of `deferred-work.md`. **Value-type validation** routed into `epic-1`'s Story 1.4 note, where `create-story` will read it — the audit trail is never read by anything. **Fixture package placement** recorded in `project-context.md` as a stated exception, and its `ride` half was already obsolete: both stand-in fixtures were deleted once `SharedTypeThatDependsOnAFeature` was shown to trip the layered rule as well. **Missing `@AfterEach`** and **CHECKs on the three money columns** dismissed by the repo owner: nothing writes those columns from code, unlike surge, which is why AD-62 constrains that one alone. Two `ArchitectureRulesTest` private helpers moved to the bottom of the file and `AGENTS.md` gained `Member order`. `make build` green; `make test` 85 passed / 1 failed (PUB-1's known environmental precondition). |
| 2026-08-24 | Closed the PUB-1 code-review item that named this story as its target: all six previously vacuous ArchUnit rules now have a driving test, and the `model` rule is an allowlist rather than a blocklist. Closing it surfaced the one rule that still had no proof it could fail -- `theRealClockIsOnlyEverInjected`, live since PUB-2 and unfalsified -- so `TimeIsReadOnlyThroughTheClockRuleTest` gained `rejects_a_class_outside_config_that_constructs_the_real_clock`, reusing the existing concrete-Strategy fixture and adding no file. `deferred-work.md` is down to two sections, both PUB-1 implementation items routed to Epics 4 and 7. `make build` green; `make test` 85 passed / 1 failed (PUB-1's known environmental precondition). |
| 2026-08-24 | Tasks 3.4 and 8.3 corrected: both instructed updating `db/migration/README.md`, which no longer exists. Struck through with where D1-D8 actually live now, rather than deleted -- the task was done as written before the file was removed. |
