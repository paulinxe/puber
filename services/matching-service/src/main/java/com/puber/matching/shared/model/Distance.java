package com.puber.matching.shared.model;

import java.math.BigDecimal;

public record Distance(double metres) {

    private static final BigDecimal METRES_PER_KILOMETRE = BigDecimal.valueOf(1000);

    private BigDecimal inMetres() {
        return BigDecimal.valueOf(metres);
    }

    /**
     * Dividing by 1000 only shifts the decimal point, so the bare divide is exact. No MathContext.
     */
    public BigDecimal inKilometres() {
        return inMetres().divide(METRES_PER_KILOMETRE);
    }
}
