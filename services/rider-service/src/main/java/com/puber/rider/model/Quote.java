package com.puber.rider.model;

import java.util.OptionalLong;

/**
 * What a rider is told a trip would cost, and how far it is.
 *
 * <p>Raw {@code long}s with no Money or Distance type to carry the meaning, so the field names are
 * the only thing saying what the numbers are -- which is why they keep their unit suffixes while
 * the wire DTO drops them.
 *
 * <p>{@code OptionalLong} rather than a nullable box: "no driver available" is representable
 * without a sentinel, matching the contract's {@code optional eta_minutes}.
 */
public record Quote(long fareMinorUnits, long distanceMetres, OptionalLong etaMinutes) {}
