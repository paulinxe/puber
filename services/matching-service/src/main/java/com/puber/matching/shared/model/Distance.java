package com.puber.matching.shared.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record Distance(double metres) {

    private static final BigDecimal METRES_PER_KILOMETRE = BigDecimal.valueOf(1000);

    /**
     * Dividing by 1000 only shifts the decimal point, so the bare divide is exact. No MathContext.
     */
    public BigDecimal inKilometres() {
        return inMetres().divide(METRES_PER_KILOMETRE);
    }

    /**
     * Metres to the nearest whole one, HALF_UP. Named for what it costs: this conversion loses
     * information and {@link #inKilometres()} does not, and two sibling accessors that look alike
     * but differ on lossiness is the confusion this type exists to prevent.
     */
    public long roundedToMetres() {
        return inMetres().setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private BigDecimal inMetres() {
        return BigDecimal.valueOf(metres);
    }
}
