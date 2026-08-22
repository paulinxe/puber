package com.puber.matching.shared.strategy;

import java.time.Instant;

/**
 * Reads time directly from inside {@code SystemClock}'s own package.
 *
 * <p>It lives here, not under {@code rules/fixtures}, because that is the point: the rule exempts
 * {@code SystemClock} the class, not its package. Widen the exemption to the package and this
 * fixture is the only thing that notices.
 */
public final class NeighbourStrategyThatReadsTimeDirectly {

    public Instant wallClockByHand() {
        return Instant.now();
    }
}
