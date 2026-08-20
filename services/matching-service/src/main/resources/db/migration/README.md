# Migrations — matching-service

**Migrations are append-only. Never edit one that has been applied — add the next.**

Flyway checksums every `V*.sql` file. Edit an applied one and the next start fails `validate`, which
takes every integration test down with it. That includes comment-only migrations, which is why this
text lives here rather than inside `V1`.

## V1 — baseline

`V1__baseline.sql` creates nothing.

`matching-service` will own `rides`, the dispatch `drivers` table, `fare_rules` and the
`payment_standing` projection (AD-1: a service's tables are private to it). None exist yet —
`fare_rules` arrives with Story 1.3, `rides` with Story 3.1.

What V1 delivers is the versioned schema itself: something for Flyway to record, so a second start
applies zero migrations instead of failing. The `aSecondStartAppliesNoMigrations()` integration test
proves that by migrating again and asserting nothing ran.

## Conventions

- **Expand-only**: additive, nullable, no backfill in the same migration.
- Timestamps: `TIMESTAMPTZ`, UTC.
- Money: `DECIMAL` at rest, integer minor units in transit. Never floating point.
- Coordinates: `DECIMAL(10,8)` / `DECIMAL(11,8)`, WGS84, longitude before latitude.
