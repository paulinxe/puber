package com.puber.matching.fare.repository;

import com.puber.matching.fare.model.FareRule;
import com.puber.matching.shared.model.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FareRuleRepository {

    private final JdbcTemplate jdbcTemplate;

    public FareRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public FareRule priceList() {
        return jdbcTemplate
                .query(
                        "select base_fare, per_km_rate, per_minute_rate, surge_multiplier from fare_rules",
                        (row, _) ->
                                new FareRule(
                                        Money.ofMinorUnits(row.getLong("base_fare")),
                                        row.getLong("per_km_rate"),
                                        row.getLong("per_minute_rate"),
                                        row.getBigDecimal("surge_multiplier")))
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "fare_rules holds no row -- every fare depends on it, and"
                                                + " V3__seed_fare_rules.sql should have created it"));
    }
}
