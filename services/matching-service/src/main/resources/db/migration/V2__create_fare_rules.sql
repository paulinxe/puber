create table fare_rules (
    id               smallint      primary key check (id = 1),
    base_fare        bigint        not null,
    per_km_rate      bigint        not null,
    per_minute_rate  bigint        not null,
    surge_multiplier decimal(4, 2) not null check (surge_multiplier > 0)
);

comment on column fare_rules.base_fare       is 'flat charge per trip, minor units: 250 is 2.50';
comment on column fare_rules.per_km_rate     is 'charge per kilometre, minor units: 120 is 1.20';
comment on column fare_rules.per_minute_rate is 'charge per minute, minor units: 25 is 0.25';
comment on column fare_rules.surge_multiplier is 'demand coefficient, not money: 1.00 is no surge';
