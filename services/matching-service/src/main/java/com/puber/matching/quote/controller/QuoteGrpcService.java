package com.puber.matching.quote.controller;

import com.puber.contracts.quote.v1.GetQuoteRequest;
import com.puber.contracts.quote.v1.GetQuoteResponse;
import com.puber.contracts.quote.v1.QuoteServiceGrpc;
import com.puber.matching.quote.model.Quote;
import com.puber.matching.quote.service.QuoteTrip;
import com.puber.matching.shared.model.Coordinates;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class QuoteGrpcService extends QuoteServiceGrpc.QuoteServiceImplBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuoteGrpcService.class);

    private final QuoteTrip quoteTrip;

    public QuoteGrpcService(QuoteTrip quoteTrip) {
        this.quoteTrip = quoteTrip;
    }

    @Override
    public void getQuote(
            GetQuoteRequest request, StreamObserver<GetQuoteResponse> responseObserver) {
        Coordinates pickup;
        Coordinates dropoff;
        // Reassigned between the two parses, so a rejection names which point was bad. A caller
        // told only "latitude" has four values to choose from.
        String side = "pickup";
        try {
            pickup =
                    Coordinates.of(
                            request.getPickup().getLatitude(), request.getPickup().getLongitude());

            side = "dropoff";
            dropoff =
                    Coordinates.of(
                            request.getDropoff().getLatitude(),
                            request.getDropoff().getLongitude());
        } catch (IllegalArgumentException rejected) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT
                            .withDescription(side + " " + rejected.getMessage())
                            .asException());
            return;
        }

        Quote quote = quoteTrip.execute(pickup, dropoff);

        // Coordinates are deliberately not logged: they locate a person.
        LOGGER.info(
                "quoted a trip of {} m at {} minor units",
                quote.distance().roundedToMetres(),
                quote.fare().minorUnits());

        responseObserver.onNext(
                GetQuoteResponse.newBuilder()
                        .setFareMinorUnits(quote.fare().minorUnits())
                        .setDistanceMetres(quote.distance().roundedToMetres())
                        .build());
        responseObserver.onCompleted();
    }
}
