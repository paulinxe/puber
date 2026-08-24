package com.puber.matching.rules.fixtures.service;

import com.puber.matching.shared.strategy.SystemClock;

/**
 * A service holding a concrete Strategy implementation instead of the interface. Nothing calls it
 * -- it exists to be rejected, which is the only way to show the rule can fail.
 */
public final class ServiceThatDependsOnAConcreteStrategy {

    private final SystemClock clock = new SystemClock();

    public boolean hardWiredToAnImplementation() {
        return clock != null;
    }
}
