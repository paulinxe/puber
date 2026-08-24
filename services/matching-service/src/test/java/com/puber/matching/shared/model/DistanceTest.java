package com.puber.matching.shared.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DistanceTest {

    @Test
    @DisplayName("AC4: 5000 m is 5 km, not 5000")
    void converts_metres_to_kilometres() {
        assertEquals(0, new Distance(5000).inKilometres().compareTo(BigDecimal.valueOf(5)));
    }

    @Test
    @DisplayName("AC4: a fractional distance converts exactly -- 5327 m is 5.327 km")
    void converts_a_fractional_distance_exactly() {
        assertEquals(0, new Distance(5327).inKilometres().compareTo(new BigDecimal("5.327")));
    }
}
