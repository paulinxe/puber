package com.puber.matching.rules.fixtures.controller;

/** Stands in for a controller, so that depending on one can be shown to fail. */
public final class AControllerNothingMayDependOn {

    public String handle() {
        return "handled";
    }
}
