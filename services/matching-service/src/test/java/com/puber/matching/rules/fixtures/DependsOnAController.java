package com.puber.matching.rules.fixtures;

import com.puber.matching.rules.fixtures.controller.AControllerNothingMayDependOn;

/**
 * Reaches back into the outermost layer, which inverts the dependency direction. Exists to be
 * rejected.
 */
public final class DependsOnAController {

    private final AControllerNothingMayDependOn controller = new AControllerNothingMayDependOn();

    public String delegate() {
        return controller.handle();
    }
}
