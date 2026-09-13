package com.puber.rider.model;

import java.util.OptionalLong;

/**
 * What a rider is told a trip would cost, and how far it is. Plain {@code long}s, so the unit
 * suffixes in the names are the only thing carrying the units. {@code OptionalLong} says "no driver
 * available" without a sentinel value.
 */
public record Quote(long fareMinorUnits, long distanceMetres, OptionalLong etaMinutes) {}
