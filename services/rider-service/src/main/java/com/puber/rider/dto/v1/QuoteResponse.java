package com.puber.rider.dto.v1;

/**
 * The quote as a client reads it. The names are bare because this system has one money
 * representation and one wire distance unit, so a client learns the units once from the contract
 * rather than from every field name.
 *
 * <p>{@code eta} is always minutes and is null until there are drivers to derive one from; {@code
 * spring.jackson.default-property-inclusion} is what leaves the key out rather than emitting a
 * null.
 */
public record QuoteResponse(long fare, long distance, Long eta) {}
