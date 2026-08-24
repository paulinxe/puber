package com.puber.matching.shared.model;

/**
 * An amount of money, in minor units -- 250 is 2.50.
 *
 * <p>There is no currency dimension, on purpose.
 */
public record Money(long minorUnits) {

    public static Money ofMinorUnits(long minorUnits) {
        return new Money(minorUnits);
    }
}
