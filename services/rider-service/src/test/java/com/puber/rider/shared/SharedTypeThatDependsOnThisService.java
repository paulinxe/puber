package com.puber.rider.shared;

import com.puber.rider.model.Quote;

/**
 * A type in {@code shared} naming something only this service has, which is what stops the
 * directory being liftable into the next service. Exists to be rejected.
 *
 * <p>It has to live in {@code com.puber.rider.shared} for the rule to see it, because that rule
 * names the package absolutely -- a copy under {@code rules.fixtures} would match nothing and the
 * rule would scan an empty set and pass forever.
 */
public final class SharedTypeThatDependsOnThisService {

    public long fareOf(Quote quote) {
        return quote.fareMinorUnits();
    }
}
