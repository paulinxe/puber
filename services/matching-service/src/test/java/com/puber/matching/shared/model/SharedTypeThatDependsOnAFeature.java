package com.puber.matching.shared.model;

import com.puber.matching.fare.model.FareRule;

/**
 * A type in {@code shared} reaching up into a feature, which is the dependency that turns {@code
 * shared} into a dumping ground. Exists to be rejected.
 *
 * <p>It has to live in {@code com.puber.matching.shared} for the rule to see it, because that rule
 * names the package absolutely -- the same reason {@code NeighbourStrategyThatReadsTimeDirectly}
 * sits beside the real strategies.
 */
public final class SharedTypeThatDependsOnAFeature {

    public long baseFareOf(FareRule rule) {
        return rule.baseFare().minorUnits();
    }
}
