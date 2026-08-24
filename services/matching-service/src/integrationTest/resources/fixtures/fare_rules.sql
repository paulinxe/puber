truncate fare_rules;

insert into fare_rules (id, base_fare, per_km_rate, per_minute_rate, surge_multiplier)
values (1, 250, 120, 25, 1.00);
