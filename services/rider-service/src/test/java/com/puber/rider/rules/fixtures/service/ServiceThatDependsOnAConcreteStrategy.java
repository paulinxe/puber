package com.puber.rider.rules.fixtures.service;

import com.puber.rider.rules.fixtures.strategy.AConcreteStrategy;

/**
 * A service holding a concrete Strategy implementation instead of the interface. Nothing calls it
 * -- it exists to be rejected, which is the only way to show the rule can fail.
 */
public final class ServiceThatDependsOnAConcreteStrategy {

    private final AConcreteStrategy strategy = new AConcreteStrategy();

    public boolean hardWiredToAnImplementation() {
        return strategy != null;
    }
}
