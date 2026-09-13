package com.puber.rider.service;

import com.puber.contracts.quote.v1.Coordinates;
import com.puber.contracts.quote.v1.GetQuoteRequest;
import com.puber.contracts.quote.v1.GetQuoteResponse;
import com.puber.contracts.quote.v1.QuoteServiceGrpc;
import com.puber.rider.model.Quote;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;

/** Asks matching-service, which owns fare_rules (AD-3), what a trip costs. */
@Service
public class RequestQuote {

    private final QuoteServiceGrpc.QuoteServiceBlockingStub quotes;

    public RequestQuote(QuoteServiceGrpc.QuoteServiceBlockingStub quotes) {
        this.quotes = quotes;
    }

    public Quote execute(
            String pickupLatitude,
            String pickupLongitude,
            String dropoffLatitude,
            String dropoffLongitude) {
        GetQuoteResponse quoted =
                quotes.getQuote(
                        GetQuoteRequest.newBuilder()
                                .setPickup(point(pickupLatitude, pickupLongitude))
                                .setDropoff(point(dropoffLatitude, dropoffLongitude))
                                .build());

        return new Quote(
                quoted.getFareMinorUnits(),
                quoted.getDistanceMetres(),
                // hasEtaMinutes, not a comparison against zero: the field is `optional`, so absent
                // and zero are different answers.
                quoted.hasEtaMinutes()
                        ? OptionalLong.of(quoted.getEtaMinutes())
                        : OptionalLong.empty());
    }

    private static Coordinates point(String latitude, String longitude) {
        return Coordinates.newBuilder().setLatitude(latitude).setLongitude(longitude).build();
    }
}
