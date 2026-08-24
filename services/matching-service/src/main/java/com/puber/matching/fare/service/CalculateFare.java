package com.puber.matching.fare.service;

import com.puber.matching.fare.model.AssumedSpeed;
import com.puber.matching.fare.model.FareRule;
import com.puber.matching.shared.model.Distance;
import com.puber.matching.shared.model.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class CalculateFare {

    public static Money calculate(FareRule rule, Distance distance) {
        BigDecimal kilometres = distance.inKilometres();
        BigDecimal minutes = AssumedSpeed.minutesToCover(distance);

        // (base + per_km x km + per_minute x min) x surge
        BigDecimal gross =
                BigDecimal.valueOf(rule.baseFare().minorUnits())
                        .add(BigDecimal.valueOf(rule.perKmRate()).multiply(kilometres))
                        .add(BigDecimal.valueOf(rule.perMinuteRate()).multiply(minutes))
                        .multiply(rule.surgeMultiplier());

        return Money.ofMinorUnits(gross.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }
}
