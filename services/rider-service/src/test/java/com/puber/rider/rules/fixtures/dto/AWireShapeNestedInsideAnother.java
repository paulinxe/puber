package com.puber.rider.rules.fixtures.dto;

/**
 * The other half of the proof: a DTO naming another DTO, which is how {@code QuoteRequest} holds
 * its nested {@code Coordinates}. A rule that also rejected this would fail on correct code, and
 * whoever hit that would weaken the rule rather than the code.
 */
public record AWireShapeNestedInsideAnother(AWireShapeOnlyAControllerMaySee inner) {}
