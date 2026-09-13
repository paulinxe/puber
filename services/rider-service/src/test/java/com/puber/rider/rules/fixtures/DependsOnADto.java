package com.puber.rider.rules.fixtures;

import com.puber.rider.rules.fixtures.dto.AWireShapeOnlyAControllerMaySee;

/**
 * Reaches past the controller for the wire shape, which is how the DTO becomes the domain and a
 * rename in the JSON reaches into the service layer. Exists to be rejected.
 */
public final class DependsOnADto {

    public String valueOf(AWireShapeOnlyAControllerMaySee wireShape) {
        return wireShape.value();
    }
}
