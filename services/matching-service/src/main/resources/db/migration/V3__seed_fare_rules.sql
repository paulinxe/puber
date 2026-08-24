insert into fare_rules (id, base_fare, per_km_rate, per_minute_rate, surge_multiplier)
values (
    1,     -- the only row there is
    250,   -- base_fare:        2.50 flat
    120,   -- per_km_rate:      1.20 per kilometre
    25,    -- per_minute_rate:  0.25 per minute
    1.00   -- surge_multiplier: no surge
);
