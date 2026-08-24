package com.puber.matching.fare.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.puber.matching.fare.model.FareRule;
import com.puber.matching.shared.model.Distance;
import com.puber.matching.shared.model.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CalculateFareTest {

    private static final BigDecimal NO_SURGE = new BigDecimal("1.00");

    /** No base fare, so a test can put one rate term under the microscope on its own. */
    private static final Money NOTHING_FLAT = Money.ofMinorUnits(0);

    /** The values V3 seeds: 2.50 flat, 1.20 per kilometre, 0.25 per minute. */
    private static final FareRule SEEDED_RULES =
            new FareRule(Money.ofMinorUnits(250), 120, 25, NO_SURGE);

    @Test
    @DisplayName("AC2: a 5 km trip at the seeded rates prices at 1100 minor units (11.00)")
    void prices_a_five_kilometre_trip_from_the_seeded_rules() {
        // 5 km at 30 km/h is 10 minutes exactly.
        // (250 + 120 x 5 + 25 x 10) x 1.00 = 250 + 600 + 250 = 1100
        Money fare = CalculateFare.calculate(SEEDED_RULES, new Distance(5000));

        assertEquals(Money.ofMinorUnits(1100), fare);
    }

    @Test
    @DisplayName("AC2: a trip that goes nowhere still costs the base fare -- 250 (2.50)")
    void prices_a_trip_of_no_distance_at_the_base_fare() {
        // (250 + 120 x 0 + 25 x 0) x 1.00 = 250
        Money fare = CalculateFare.calculate(SEEDED_RULES, new Distance(0));

        assertEquals(Money.ofMinorUnits(250), fare);
    }

    @Test
    @DisplayName("AC2: surge 2.00 doubles the whole price -- 1100 becomes 2200")
    void applies_the_surge_multiplier_to_every_term() {
        FareRule surging = new FareRule(Money.ofMinorUnits(250), 120, 25, new BigDecimal("2.00"));

        // The multiplier is outside the brackets, so the base fare is surged too:
        // (250 + 600 + 250) x 2.00 = 2200
        Money fare = CalculateFare.calculate(surging, new Distance(5000));

        assertEquals(Money.ofMinorUnits(2200), fare);
    }

    @Test
    @DisplayName("AC5: a half minor unit rounds up -- 100.500 prices at 101, not 100")
    void rounds_half_a_minor_unit_up() {
        // 1005 m is 1.005 km. (0 + 100 x 1.005 + 0 x 2.010) x 1.00 = 100.500 exactly.
        // HALF_UP gives 101; HALF_EVEN would give 100, because 100 is even. That is the whole
        // difference between the two, and this is the only assertion that can see it.
        FareRule perKilometreOnly = new FareRule(NOTHING_FLAT, 100, 0, NO_SURGE);

        Money fare = CalculateFare.calculate(perKilometreOnly, new Distance(1005));

        assertEquals(Money.ofMinorUnits(101), fare);
    }

    @Test
    @DisplayName("AC5: rounding happens once, at the end -- not once per term")
    void rounds_once_at_the_end() {
        // 150 m is 0.15 km and 0.30 min. (0 + 2 x 0.15 + 1 x 0.30) x 1.00 = 0.60 -> 1.
        // Rounded per term instead, both terms are 0.30 and both round to 0, so the trip would be
        // free. That is why there is exactly one rounding point.
        FareRule twoSmallTerms = new FareRule(NOTHING_FLAT, 2, 1, NO_SURGE);

        Money fare = CalculateFare.calculate(twoSmallTerms, new Distance(150));

        assertEquals(Money.ofMinorUnits(1), fare);
    }
}
