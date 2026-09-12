package com.puber.rider.rules.fixtures.dto;

/**
 * Stands in for a wire DTO, so that depending on one from outside a controller can be shown to
 * fail.
 */
public record AWireShapeOnlyAControllerMaySee(String value) {}
