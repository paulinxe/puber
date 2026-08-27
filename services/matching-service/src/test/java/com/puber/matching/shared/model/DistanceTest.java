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

    @Test
    @DisplayName("D4: a half metre rounds up -- 1000.5 m is 1001")
    void rounds_a_half_metre_up() {
        assertEquals(1001L, new Distance(1000.5).roundedToMetres());
    }

    @Test
    @DisplayName("D4: the metre below a half rounds down -- 1000.4999 m is 1000")
    void rounds_below_a_half_metre_down() {
        assertEquals(1000L, new Distance(1000.4999).roundedToMetres());
    }

    @Test
    @DisplayName("D4: HALF_UP, not HALF_EVEN -- 1001.5 m is 1002, not 1002 by luck")
    void rounds_a_half_metre_up_from_an_odd_metre_too() {
        // HALF_EVEN would give 1002 here and 1000 for 1000.5, so the pair is what tells the two
        // modes apart; either one alone passes under both.
        assertEquals(1002L, new Distance(1001.5).roundedToMetres());
    }
}
