package com.puber.rider.dto.v1;

public record QuoteRequest(Coordinates pickup, Coordinates dropoff) {

    public record Coordinates(String latitude, String longitude) {}
}
