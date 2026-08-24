package com.puber.matching.shared.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Money is minor units and nothing else.
 *
 * <p>Thin on purpose, and it is not vacuous: the whole convention rests on this factory storing
 * what it was handed. A factory that multiplied by 100, or a second one taking major units, fails
 * here.
 */
class MoneyTest {

    @Test
    @DisplayName("AC5: the factory stores minor units unchanged -- 1100 is 11.00, not 110000")
    void stores_minor_units_unchanged() {
        assertEquals(1100L, Money.ofMinorUnits(1100L).minorUnits());
    }

    @Test
    @DisplayName("AC5: two amounts of the same minor units are the same money")
    void compares_by_value() {
        assertEquals(Money.ofMinorUnits(250L), Money.ofMinorUnits(250L));
    }
}
