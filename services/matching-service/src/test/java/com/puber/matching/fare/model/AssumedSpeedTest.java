package com.puber.matching.fare.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.puber.matching.shared.model.Distance;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The other half of the unit guard: minutes come from kilometres, so a metres-for-kilometres slip
 * is 1000x here too rather than a plausible-looking price.
 */
class AssumedSpeedTest {

    @Test
    @DisplayName("AC4: 30 km/h is exactly 2 minutes per kilometre -- 5 km takes 10 minutes")
    void takes_two_minutes_per_kilometre() {
        assertEquals(
                0,
                AssumedSpeed.minutesToCover(new Distance(5000)).compareTo(BigDecimal.valueOf(10)));
    }

    @Test
    @DisplayName("AC4: a fractional distance keeps its precision -- 5.327 km takes 10.654 minutes")
    void keeps_a_fractional_distance_exact() {
        assertEquals(
                0,
                AssumedSpeed.minutesToCover(new Distance(5327))
                        .compareTo(new BigDecimal("10.654")));
    }
}
