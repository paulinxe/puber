package com.puber.rider.controller.v1;

import com.puber.rider.dto.v1.QuoteRequest;
import com.puber.rider.dto.v1.QuoteResponse;
import com.puber.rider.model.Quote;
import com.puber.rider.service.RequestQuote;
import com.puber.rider.shared.InvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Served at {@code /rider/v1/quotes}; the prefix comes from {@code ApiVersionConfiguration}. */
@RestController
public class QuotesController {

    private static final String RIDER_ID_HEADER = "X-Rider-Id";

    private static final Logger LOGGER = LoggerFactory.getLogger(QuotesController.class);

    private final RequestQuote requestQuote;

    public QuotesController(RequestQuote requestQuote) {
        this.requestQuote = requestQuote;
    }

    @PostMapping("/quotes")
    public QuoteResponse quote(
            @RequestHeader(RIDER_ID_HEADER) String riderId, @RequestBody QuoteRequest request) {
        if (riderId.isBlank()) {
            throw new InvalidRequestException(RIDER_ID_HEADER + " must not be blank");
        }
        requireCoordinates(request.pickup(), "pickup");
        requireCoordinates(request.dropoff(), "dropoff");

        // The rider id is trusted as-is (FR-48): logged beside the request id and read by nothing.
        LOGGER.info("quoting a trip for rider {}", riderId);

        Quote quote =
                requestQuote.execute(
                        request.pickup().latitude(),
                        request.pickup().longitude(),
                        request.dropoff().latitude(),
                        request.dropoff().longitude());

        return new QuoteResponse(
                quote.fareMinorUnits(),
                quote.distanceMetres(),
                quote.etaMinutes().isPresent() ? quote.etaMinutes().getAsLong() : null);
    }

    /**
     * Presence only, never range or format -- D4 leaves those to {@code matching-service}'s own
     * value types, and two copies of a range check is two answers to one question.
     *
     * <p>The two string fields are checked, not just the object holding them: protobuf's generated
     * setter throws {@code NullPointerException} on a null, so a null that reaches {@code
     * RequestQuote} never gets as far as the service D4 assigns it to, and leaves as a 500.
     */
    private static void requireCoordinates(QuoteRequest.Coordinates point, String side) {
        if (point == null) {
            throw new InvalidRequestException(side + " is required");
        }
        requireValue(point.latitude(), side + " latitude");
        requireValue(point.longitude(), side + " longitude");
    }

    private static void requireValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " is required");
        }
    }
}
