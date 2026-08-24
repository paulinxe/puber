package com.puber.matching.rules.fixtures;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * The mistake the Money convention exists to make unwritable: {@code new BigDecimal(0.1)} stores
 * 0.1000000000000000055511151231257827, while {@code BigDecimal.valueOf(0.1)} stores 0.1. Both
 * compile and they look identical in review.
 *
 * <p>Both constructors that take a double are here, because a rule that catches one and not the
 * other is half a rule.
 */
public final class BuildsABigDecimalFromADouble {

    public BigDecimal fromADouble() {
        return new BigDecimal(0.1);
    }

    public BigDecimal fromADoubleWithAMathContext() {
        return new BigDecimal(0.1, MathContext.DECIMAL64);
    }
}
