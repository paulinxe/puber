package com.puber.matching.rules.fixtures;

import java.math.BigDecimal;

/**
 * The other half of the proof. {@code BigDecimal.valueOf} takes a double too and is the correct
 * call, so a rule that rejected it would be unusable and would be switched off within a week.
 */
public final class BuildsABigDecimalSafely {

    public BigDecimal fromAStringLiteral() {
        return new BigDecimal("0.1");
    }

    public BigDecimal fromADoubleTheSafeWay() {
        return BigDecimal.valueOf(0.1);
    }

    public BigDecimal fromALong() {
        return BigDecimal.valueOf(250L);
    }
}
