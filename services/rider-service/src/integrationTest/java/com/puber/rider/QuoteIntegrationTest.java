package com.puber.rider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puber.contracts.quote.v1.GetQuoteRequest;
import com.puber.rider.config.RequestId;
import com.puber.rider.support.StubQuoteService;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The quote over the real HTTP surface, against an in-process stub of matching-service (D7).
 *
 * <p>The subject is this service and nothing else: the HTTP shape, the translation in both
 * directions, the error mapping and the request-id chain. Pricing is matching-service's and is
 * proven there.
 */
@AutoConfigureTestGrpcTransport
@AutoConfigureTestRestTemplate
@Import(StubQuoteService.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // The channel target is environment-driven in production and irrelevant here:
        // @AutoConfigureTestGrpcTransport replaces the channel factory with an in-process one that
        // ignores the address. This only stops the placeholder failing to resolve.
        properties = "spring.grpc.client.channel.matching.target=static://stubbed-in-process")
class QuoteIntegrationTest {

    private static final String QUOTES = "/rider/v1/quotes";

    private static final String RIDER_ID_HEADER = "X-Rider-Id";

    private static final String A_RIDER = "rider-42";

    private static final String PICKUP_LATITUDE = "38.72225000";
    private static final String PICKUP_LONGITUDE = "-9.13933000";
    private static final String DROPOFF_LATITUDE = "38.75775000";
    private static final String DROPOFF_LONGITUDE = "-9.11444000";

    private static final String A_QUOTE_REQUEST =
            """
            {"pickup":{"latitude":"%s","longitude":"%s"},
             "dropoff":{"latitude":"%s","longitude":"%s"}}
            """
                    .formatted(
                            PICKUP_LATITUDE, PICKUP_LONGITUDE, DROPOFF_LATITUDE, DROPOFF_LONGITUDE);

    /**
     * Numbers no fare rule could produce, so nobody reads this as a pricing assertion and tries to
     * keep it in step with {@code fare_rules}. What is under test is the translation.
     */
    private static final long STUBBED_FARE_MINOR_UNITS = 7L;

    private static final long STUBBED_DISTANCE_METRES = 3L;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private StubQuoteService matchingService;

    @BeforeEach
    void resetTheStub() {
        matchingService.reset();
        matchingService.answerWith(STUBBED_FARE_MINOR_UNITS, STUBBED_DISTANCE_METRES);
    }

    @Test
    @DisplayName("AC1b/AC2: a quote answers 200 with fare and distance, and no eta key at all")
    void returns_the_fare_and_the_distance_with_no_eta() {
        ResponseEntity<String> response = quote(A_QUOTE_REQUEST);

        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode body = json(response);
        assertEquals(STUBBED_FARE_MINOR_UNITS, body.path("fare").asLong());
        assertEquals(STUBBED_DISTANCE_METRES, body.path("distance").asLong());
        assertFalse(
                body.has("eta"),
                () ->
                        "no driver means the eta key is absent, not present and null: "
                                + response.getBody());
    }

    @Test
    @DisplayName("AC1b: the coordinates reach matching-service as the strings the client sent")
    void passes_the_coordinates_through_untouched() {
        quote(A_QUOTE_REQUEST);

        GetQuoteRequest received = matchingService.received();
        assertNotNull(received, "matching-service was never called");
        assertEquals(PICKUP_LATITUDE, received.getPickup().getLatitude());
        assertEquals(PICKUP_LONGITUDE, received.getPickup().getLongitude());
        assertEquals(DROPOFF_LATITUDE, received.getDropoff().getLatitude());
        assertEquals(DROPOFF_LONGITUDE, received.getDropoff().getLongitude());
    }

    @Test
    @DisplayName("AC5: a request with no body at all is 400 Problem Details")
    void rejects_a_request_with_no_body() {
        assertIsProblemDetails(quote(""), 400);
    }

    @Test
    @DisplayName("AC5: a body that is not JSON is 400 Problem Details")
    void rejects_a_body_that_is_not_json() {
        assertIsProblemDetails(quote("this is not json"), 400);
    }

    @Test
    @DisplayName("AC5: a body with no dropoff is 400 Problem Details")
    void rejects_a_body_that_is_missing_the_dropoff() {
        assertIsProblemDetails(
                quote("{\"pickup\":{\"latitude\":\"1.0\",\"longitude\":\"2.0\"}}"), 400);
    }

    @Test
    @DisplayName("AC5: INVALID_ARGUMENT from matching-service is 400, not a 500")
    void maps_an_invalid_argument_to_a_400() {
        matchingService.rejectWith(
                Status.INVALID_ARGUMENT.withDescription(
                        "pickup latitude must be a decimal number"));

        ResponseEntity<String> response = quote(A_QUOTE_REQUEST);

        assertIsProblemDetails(response, 400);
        assertTrue(
                json(response).path("detail").asString().contains("latitude"),
                () -> "the caller cannot tell which coordinate was wrong: " + response.getBody());
    }

    @Test
    @DisplayName("AC5: UNAVAILABLE from matching-service is 503")
    void maps_an_unavailable_peer_to_a_503() {
        matchingService.rejectWith(Status.UNAVAILABLE);

        assertIsProblemDetails(quote(A_QUOTE_REQUEST), 503);
    }

    @Test
    @DisplayName("AC6: a request with no rider identity is 400")
    void rejects_a_request_carrying_no_rider_identity() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        assertIsProblemDetails(post(A_QUOTE_REQUEST, headers), 400);
    }

    @Test
    @DisplayName("AC6: a rider identity never seen before is trusted as-is")
    void trusts_a_rider_identity_it_has_never_seen() {
        ResponseEntity<String> response =
                quote(A_QUOTE_REQUEST, "rider-nobody-has-ever-registered");

        assertEquals(200, response.getStatusCode().value(), response.getBody());
    }

    @Test
    @DisplayName("AC4b: an incoming request id comes back on the response")
    void echoes_the_request_id_it_was_given() {
        String incoming = "a-request-id-the-caller-chose";
        HttpHeaders headers = quoteHeaders(A_RIDER);
        headers.set(RequestId.HEADER, incoming);

        ResponseEntity<String> response = post(A_QUOTE_REQUEST, headers);

        assertEquals(incoming, response.getHeaders().getFirst(RequestId.HEADER));
    }

    @Test
    @DisplayName("AC4b: a request arriving without a request id gets one minted at entry")
    void mints_a_request_id_when_none_arrives() {
        ResponseEntity<String> response = quote(A_QUOTE_REQUEST);

        String minted = response.getHeaders().getFirst(RequestId.HEADER);
        assertNotNull(minted, "no request id on the response, so nothing was minted");
        assertFalse(minted.isBlank(), "the minted request id is blank");
    }

    @Test
    @DisplayName("AC4b: the request id crosses the gRPC hop to matching-service")
    void carries_the_request_id_over_the_grpc_hop() {
        String incoming = "a-request-id-that-must-cross-the-hop";
        HttpHeaders headers = quoteHeaders(A_RIDER);
        headers.set(RequestId.HEADER, incoming);

        post(A_QUOTE_REQUEST, headers);

        // Asserted on the receiver, not the sender: a stub that saw the metadata is stronger
        // evidence than an interceptor that says it attached some.
        assertEquals(
                incoming,
                matchingService.receivedMetadata(RequestId.METADATA_KEY),
                "matching-service received no request id, so the hop is untraceable");
    }

    @Test
    @DisplayName("AC5: an error body carries the request id the caller can quote back")
    void carries_the_request_id_in_an_error_body() {
        String incoming = "a-request-id-on-a-failing-request";
        HttpHeaders headers = quoteHeaders(A_RIDER);
        headers.set(RequestId.HEADER, incoming);

        ResponseEntity<String> response = post("not json", headers);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(incoming, json(response).path(RequestId.MDC_KEY).asString());
    }

    @Test
    @DisplayName("AC1b: the shorter spellings of the path are 404 -- the prefix is real")
    void serves_the_quote_at_the_prefixed_path_only() {
        assertEquals(
                404,
                post("/quotes", A_QUOTE_REQUEST, quoteHeaders(A_RIDER)).getStatusCode().value(),
                "the controller is still mapped at its bare path as well, so the prefix is"
                        + " decorative");
        assertEquals(
                404,
                post("/v1/quotes", A_QUOTE_REQUEST, quoteHeaders(A_RIDER)).getStatusCode().value(),
                "the service segment of the prefix was not applied");
    }

    @Test
    @DisplayName("AC2: a quote carrying an eta serves it as minutes")
    void serves_an_eta_when_one_is_available() {
        matchingService.answerWith(STUBBED_FARE_MINOR_UNITS, STUBBED_DISTANCE_METRES, 8L);

        ResponseEntity<String> response = quote(A_QUOTE_REQUEST);

        // No production producer until Story 2.6 -- this is the cheapest guard against the
        // present-eta branch never having been written.
        assertEquals(8L, json(response).path("eta").asLong());
        assertNotEquals(
                0, json(response).path("eta").asLong(), "an absent eta was served as a zero");
    }

    @Test
    @DisplayName("AC2: an eta of zero is a value, not an absence")
    void serves_a_zero_eta_as_a_present_zero() {
        matchingService.answerWith(STUBBED_FARE_MINOR_UNITS, STUBBED_DISTANCE_METRES, 0L);

        ResponseEntity<String> response = quote(A_QUOTE_REQUEST);

        assertTrue(
                json(response).has("eta"),
                () ->
                        "zero was read as absent, which is what comparing against zero instead of"
                                + " calling hasEtaMinutes() does: "
                                + response.getBody());
        assertEquals(0L, json(response).path("eta").asLong());
    }

    @Test
    @DisplayName("AC5: a null coordinate value is 400 Problem Details, not a 500")
    void rejects_a_coordinate_whose_value_is_null() {
        ResponseEntity<String> response =
                quote(
                        """
                        {"pickup":{"latitude":null,"longitude":null},
                         "dropoff":{"latitude":"1.0","longitude":"2.0"}}
                        """);

        assertIsProblemDetails(response, 400);
        assertTrue(
                json(response).path("detail").asString().contains("latitude"),
                () -> "the caller cannot tell which coordinate was missing: " + response.getBody());
    }

    @Test
    @DisplayName("AC5: a coordinate object with no fields at all is 400 Problem Details")
    void rejects_a_coordinate_carrying_no_fields() {
        assertIsProblemDetails(
                quote("{\"pickup\":{},\"dropoff\":{\"latitude\":\"1.0\",\"longitude\":\"2.0\"}}"),
                400);
    }

    @Test
    @DisplayName("AC5: an unexpected upstream status is 500 with fixed text, never the peer's")
    void never_puts_the_upstream_description_on_the_wire() {
        matchingService.rejectWith(Status.INTERNAL.withDescription("fare_rules table is empty"));

        ResponseEntity<String> response = quote(A_QUOTE_REQUEST);

        assertIsProblemDetails(response, 500);
        String detail = json(response).path("detail").asString();
        assertEquals("the request could not be answered", detail);
        assertFalse(
                response.getBody().contains("fare_rules"),
                () ->
                        "matching-service's internal detail reached an external caller: "
                                + response.getBody());
    }

    @Test
    @DisplayName("AC4b: a 415 body carries the request id, not Boot's default error shape")
    void carries_the_request_id_when_the_content_type_is_wrong() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.set(RIDER_ID_HEADER, A_RIDER);

        assertIsProblemDetails(post(A_QUOTE_REQUEST, headers), 415);
    }

    @Test
    @DisplayName("AC4b: a 405 body carries the request id")
    void carries_the_request_id_when_the_method_is_wrong() {
        ResponseEntity<String> response =
                restTemplate.exchange(
                        QUOTES,
                        HttpMethod.GET,
                        new HttpEntity<>(null, quoteHeaders(A_RIDER)),
                        String.class);

        assertIsProblemDetails(response, 405);
    }

    private ResponseEntity<String> quote(String body) {
        return quote(body, A_RIDER);
    }

    private ResponseEntity<String> quote(String body, String riderId) {
        return post(body, quoteHeaders(riderId));
    }

    private ResponseEntity<String> post(String body, HttpHeaders headers) {
        return post(QUOTES, body, headers);
    }

    private ResponseEntity<String> post(String path, String body, HttpHeaders headers) {
        return restTemplate.exchange(
                path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpHeaders quoteHeaders(String riderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(RIDER_ID_HEADER, riderId);
        return headers;
    }

    private static void assertIsProblemDetails(ResponseEntity<String> response, int status) {
        assertEquals(status, response.getStatusCode().value(), response.getBody());
        assertNotNull(response.getHeaders().getContentType());
        assertEquals(
                MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                response.getHeaders().getContentType().toString().split(";")[0],
                () -> "RFC 9457 is served as application/problem+json: " + response.getBody());
        assertFalse(
                new ObjectMapper()
                        .readTree(response.getBody())
                        .path(RequestId.MDC_KEY)
                        .asString()
                        .isBlank(),
                () -> "the error body carries no request id: " + response.getBody());
    }

    private static JsonNode json(ResponseEntity<String> response) {
        return new ObjectMapper().readTree(response.getBody());
    }
}
