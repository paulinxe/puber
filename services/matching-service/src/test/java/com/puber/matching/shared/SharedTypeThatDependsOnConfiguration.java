package com.puber.matching.shared;

import com.puber.matching.config.ClockConfiguration;

/**
 * A type in {@code shared} naming this service's own wiring. Exists to be rejected.
 *
 * <p>{@code config} is not a feature, so {@code sharedDependsOnNoFeaturePackage} never looked at
 * it: this fixture is what shows the newer clause is strictly stronger rather than a restatement.
 *
 * <p>It has to live in {@code com.puber.matching.shared} for the rule to see it, because that rule
 * names the package absolutely.
 */
public final class SharedTypeThatDependsOnConfiguration {

    public boolean wiring(ClockConfiguration configuration) {
        return configuration != null;
    }
}
