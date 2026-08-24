package com.puber.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puber.matching.fare.model.FareRule;
import com.puber.matching.fare.repository.FareRuleRepository;
import com.puber.matching.fare.service.CalculateFare;
import com.puber.matching.fixtures.FareRulesFixture;
import com.puber.matching.shared.model.Coordinates;
import com.puber.matching.shared.model.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** The price list, against the real Postgres from the Compose stack. */
@SpringBootTest
class FareRulesIntegrationTest {

    private static final Coordinates LISBON =
            new Coordinates(new BigDecimal("38.73690000"), new BigDecimal("-9.14270000"));

    /**
     * One degree of latitude north of {@link #LISBON}, same longitude.
     *
     * <p>The same longitude is the whole point: the haversine then reduces to radius x angle, so
     * the distance is 6371000 x toRadians(1) = 111194.9266 m and the fare below can be checked by
     * hand. With any other pair of points the expected fare would have to come from running the
     * code it is meant to check.
     */
    private static final Coordinates ONE_DEGREE_NORTH =
            new Coordinates(new BigDecimal("39.73690000"), new BigDecimal("-9.14270000"));

    @Autowired private DataSource dataSource;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private FareRuleRepository fareRules;

    @BeforeEach
    void reseedTheFareRules() {
        FareRulesFixture.reseed(dataSource);
    }

    @Test
    @DisplayName("AC1: the repository reads the four values back out of Postgres")
    void reads_the_price_list_from_postgres() {
        FareRule rule = fareRules.priceList();

        assertEquals(Money.ofMinorUnits(250), rule.baseFare());
        assertEquals(120L, rule.perKmRate());
        assertEquals(25L, rule.perMinuteRate());
        assertEquals(0, rule.surgeMultiplier().compareTo(BigDecimal.ONE));
    }

    @Test
    @DisplayName("AC5: the money columns are BIGINT and the surge is DECIMAL(4,2)")
    void stores_money_as_bigint_and_the_surge_as_a_decimal() {
        List<Map<String, Object>> columns =
                jdbcTemplate.queryForList(
                        "select column_name, data_type, numeric_precision, numeric_scale"
                                + " from information_schema.columns where table_schema = 'public'"
                                + " and table_name = 'fare_rules'");
        Map<String, String> types =
                columns.stream()
                        .collect(
                                Collectors.toMap(
                                        column -> (String) column.get("column_name"),
                                        column -> (String) column.get("data_type")));

        // The type is the discriminator: bigint is money in minor units, numeric is a coefficient
        // and never an amount.
        assertEquals("bigint", types.get("base_fare"));
        assertEquals("bigint", types.get("per_km_rate"));
        assertEquals("bigint", types.get("per_minute_rate"));
        assertEquals("numeric", types.get("surge_multiplier"));

        Map<String, Object> surge =
                columns.stream()
                        .filter(column -> "surge_multiplier".equals(column.get("column_name")))
                        .findFirst()
                        .orElseThrow();
        assertEquals(4, ((Number) surge.get("numeric_precision")).intValue());
        assertEquals(2, ((Number) surge.get("numeric_scale")).intValue());
    }

    @Test
    @DisplayName("AC7: the fixture restores the row after a truncate")
    void restores_the_price_list_after_a_truncate() {
        jdbcTemplate.execute("truncate fare_rules");
        assertEquals(
                0, countRules(), "the truncate did not empty the table, so this proves nothing");

        FareRulesFixture.reseed(dataSource);

        assertEquals(1, countRules(), "the fixture did not restore the row it is there to restore");
    }

    @Test
    @DisplayName("AC3: the production seed statement yields surge 1.00")
    void a_first_start_seeds_the_surge_at_one() {
        // The one place a test reads a migration file: the production seed IS the subject here.
        jdbcTemplate.execute("truncate fare_rules");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__seed_fare_rules.sql"))
                .execute(dataSource);

        BigDecimal surge =
                jdbcTemplate.queryForObject(
                        "select surge_multiplier from fare_rules where id = 1", BigDecimal.class);

        assertEquals(0, surge.compareTo(BigDecimal.ONE));
    }

    @Test
    @DisplayName("AC1: reading a missing price list fails loudly rather than pricing a free ride")
    void fails_loudly_when_the_price_list_is_missing() {
        jdbcTemplate.execute("truncate fare_rules");

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> fareRules.priceList());

        assertTrue(
                failure.getMessage().contains("fare_rules"),
                () -> "the failure does not say what is missing: " + failure.getMessage());
    }

    @Test
    @DisplayName(
            "AC2: a real trip is priced from the rates in Postgres -- 111.19 km costs 19153 (191.53)")
    void prices_a_real_trip_from_the_rates_in_postgres() {
        // 2.50 EUR flat, 1.20 EUR per km, 2.5 EUR per minute, no surge
        // 30 km/h = 2 min/km
        //
        //   distance = 6371000 m x toRadians(1)   = 111194.9266 m = 111.1949266 km
        //   minutes  = 111.1949266 km x 2 min/km  = 222.3898533 min
        //   fare     = 250 + 120 x 111.1949266 + 25 x 222.3898533
        //            = 250 + 13343.3912 + 5559.7463 = 19153.1375, HALF_UP to 19153
        //
        // This is the only assertion that puts a non-zero distance through calculate(), so it is
        // the only one that would notice the two coordinates being ignored.
        Money fare =
                CalculateFare.calculate(fareRules.priceList(), LISBON.distanceTo(ONE_DEGREE_NORTH));

        assertEquals(Money.ofMinorUnits(19_153), fare);
    }

    @Test
    @DisplayName("AC1: a second price list is refused -- the table holds one row by construction")
    void refuses_a_second_price_list() {
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbcTemplate.execute(
                                "insert into fare_rules (id, base_fare, per_km_rate,"
                                        + " per_minute_rate, surge_multiplier) values (2, 250, 120,"
                                        + " 25, 1.00)"),
                "a second row was accepted, so the surge scheduler can INSERT where it meant"
                        + " UPDATE and leave two price lists");
    }

    @Test
    @DisplayName("AC1: a surge of zero is refused -- it would make every fare free")
    void refuses_a_surge_of_zero() {
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbcTemplate.execute(
                                "update fare_rules set surge_multiplier = 0 where id = 1"),
                "a zero multiplier was accepted, so a ratio computation can price every trip at"
                        + " nothing");
    }

    private int countRules() {
        return jdbcTemplate.queryForObject("select count(*) from fare_rules", Integer.class);
    }
}
