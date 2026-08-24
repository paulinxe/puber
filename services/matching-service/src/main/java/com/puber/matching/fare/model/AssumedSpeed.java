package com.puber.matching.fare.model;

import com.puber.matching.shared.model.Distance;
import java.math.BigDecimal;

/**
 * The one speed this system assumes a driver travels at. There is no traffic signal and no routing
 * engine to predict a duration from, so a trip's time is derived from its distance.
 *
 * <p>Story 2.6 (PUB-10) needs the same constant for the driver-to-pickup ETA and must <em>move</em>
 * this type into {@code shared}, not copy it. Two speed constants in one JVM is a convention with
 * two answers.
 */
public final class AssumedSpeed {

    private static final int AVERAGE_SPEED_KMH = 30;
    private static final int MINUTES_PER_HOUR = 60;

    /**
     * Derived from the speed rather than written as 2, so the speed cannot be changed without a
     * reader seeing what depends on it.
     *
     * <p>The bare {@code divide} is safe only because 60 / 30 terminates. A speed whose quotient
     * does not terminate -- 45 km/h, say, where 60 / 45 recurs -- fails instead of rounding
     * silently, which is the correct failure: do not silence it with a {@code MathContext}, or the
     * fare quietly acquires a second rounding point. A speed like 40 is fine; 60 / 40 is 1.5.
     */
    private static final BigDecimal MINUTES_PER_KILOMETRE =
            BigDecimal.valueOf(MINUTES_PER_HOUR).divide(BigDecimal.valueOf(AVERAGE_SPEED_KMH));

    private AssumedSpeed() {}

    /** Minutes, from kilometres -- never from metres. */
    public static BigDecimal minutesToCover(Distance distance) {
        return distance.inKilometres().multiply(MINUTES_PER_KILOMETRE);
    }
}
