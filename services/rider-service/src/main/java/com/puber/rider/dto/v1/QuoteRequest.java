package com.puber.rider.dto.v1;

/**
 * The quote request as a client sends it. The coordinates stay decimal strings the whole way
 * through: this service parses neither, and matching-service's own value types judge them.
 */
public record QuoteRequest(Coordinates pickup, Coordinates dropoff) {

    public record Coordinates(String latitude, String longitude) {}
}
