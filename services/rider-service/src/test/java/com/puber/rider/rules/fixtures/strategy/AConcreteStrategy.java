package com.puber.rider.rules.fixtures.strategy;

/**
 * Stands in for a concrete Strategy implementation. This service has no {@code strategy} package of
 * its own yet -- it varies nothing -- so the rule that guards the seam would have nothing to catch
 * without this.
 */
public final class AConcreteStrategy {

    public String choose() {
        return "chosen";
    }
}
