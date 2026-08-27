package com.puber.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.OutputStreamAppender;
import com.puber.contracts.quote.v1.Coordinates;
import com.puber.contracts.quote.v1.GetQuoteRequest;
import com.puber.contracts.quote.v1.GetQuoteResponse;
import com.puber.contracts.quote.v1.QuoteServiceGrpc;
import com.puber.matching.config.RequestIdServerInterceptor;
import com.puber.matching.fixtures.FareRulesFixture;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** The quote, over a real gRPC call against the real Postgres from the Compose stack. */
@AutoConfigureTestGrpcTransport
@SpringBootTest
class QuoteGrpcIntegrationTest {

    private static final Coordinates LISBON = point("38.73690000", "-9.14270000");

    /**
     * One degree of latitude north of {@link #LISBON}, same longitude, so the haversine reduces to
     * radius x angle and the numbers below can be checked by hand rather than by running the code
     * they are meant to check.
     *
     * <p>Two <em>distinct</em> points, never the same one twice: a same-point trip multiplies every
     * rate by zero, which is how PUB-3 shipped a fare test that could not fail.
     */
    private static final Coordinates ONE_DEGREE_NORTH = point("39.73690000", "-9.14270000");

    /**
     * distance = 6371000 m x toRadians(1) = 111194.9266 m, HALF_UP to a whole metre.
     *
     * <p>fare = 250 + 120 x 111.1949266 + 25 x (111.1949266 x 2) = 19153.1375, HALF_UP to 19153.
     */
    private static final long EXPECTED_DISTANCE_METRES = 111_195L;

    private static final long EXPECTED_FARE_MINOR_UNITS = 19_153L;

    @Autowired private DataSource dataSource;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private GrpcChannelFactory channels;

    private ManagedChannel channel;

    private ByteArrayOutputStream capturedLog;

    private OutputStreamAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void openAChannelOverTheSeededPriceList() {
        FareRulesFixture.reseed(dataSource);
        channel = channels.createChannel("matching-service");
        startCapturingTheConfiguredLogOutput();
    }

    /**
     * Null-guarded because a failure in {@code @BeforeEach} -- an unreachable Postgres, a missing
     * fixture -- leaves these unset, and an NPE from teardown would mask the real cause in every
     * test in this class.
     */
    @AfterEach
    void closeTheChannel() {
        if (logCapture != null) {
            logCapture.stop();
            capturedLogger().detachAppender(logCapture);
        }
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @Test
    @DisplayName("AC1a: the quote RPC answers with the hand-computed fare and distance")
    void answers_with_the_fare_and_the_distance() {
        GetQuoteResponse quote = quoteFor(LISBON, ONE_DEGREE_NORTH);

        assertEquals(EXPECTED_FARE_MINOR_UNITS, quote.getFareMinorUnits());
        assertEquals(EXPECTED_DISTANCE_METRES, quote.getDistanceMetres());
    }

    @Test
    @DisplayName("AC2a: no driver means the ETA is left unset, and the call still succeeds")
    void leaves_the_eta_unset_and_still_succeeds() {
        GetQuoteResponse quote = quoteFor(LISBON, ONE_DEGREE_NORTH);

        assertFalse(
                quote.hasEtaMinutes(),
                () ->
                        "the ETA is set to "
                                + quote.getEtaMinutes()
                                + ", but there are no drivers in this system to derive one from");
    }

    @Test
    @DisplayName("AC1a: a quote creates nothing -- every table this service owns is unchanged")
    void creates_no_rows_anywhere() {
        Map<String, Integer> before = rowCountPerTable();

        quoteFor(LISBON, ONE_DEGREE_NORTH);

        assertEquals(before, rowCountPerTable(), "a quote wrote to this service's tables");
    }

    @Test
    @DisplayName("AC5a/AC10: a latitude that is not a number is INVALID_ARGUMENT naming the field")
    void rejects_a_latitude_that_is_not_a_number() {
        StatusRuntimeException rejected =
                assertThrows(
                        StatusRuntimeException.class,
                        () -> quoteFor(point("abc", "-9.14270000"), ONE_DEGREE_NORTH));

        assertInvalidArgumentNaming(rejected, "pickup", "latitude");
    }

    @Test
    @DisplayName("AC5a/AC10: an empty latitude and an entirely missing pickup are the same case")
    void rejects_an_empty_latitude_and_a_missing_pickup_identically() {
        StatusRuntimeException emptyLatitude =
                assertThrows(
                        StatusRuntimeException.class,
                        () -> quoteFor(point("", "-9.14270000"), ONE_DEGREE_NORTH));

        // proto3 has no null: a request with no pickup at all yields a default Coordinates whose
        // latitude is "". The two requests are therefore indistinguishable by the time they land.
        StatusRuntimeException missingPickup =
                assertThrows(
                        StatusRuntimeException.class,
                        () ->
                                stub().getQuote(
                                                GetQuoteRequest.newBuilder()
                                                        .setDropoff(ONE_DEGREE_NORTH)
                                                        .build()));

        assertInvalidArgumentNaming(emptyLatitude, "pickup", "latitude");
        assertEquals(
                emptyLatitude.getStatus().getDescription(),
                missingPickup.getStatus().getDescription(),
                "an absent pickup must fail exactly as an empty latitude does -- proto3 gives the"
                        + " server no way to tell them apart");
    }

    @Test
    @DisplayName("AC5a/AC10: a latitude outside +/-90 is INVALID_ARGUMENT naming the field")
    void rejects_a_latitude_out_of_range() {
        StatusRuntimeException rejected =
                assertThrows(
                        StatusRuntimeException.class,
                        () -> quoteFor(point("91", "-9.14270000"), ONE_DEGREE_NORTH));

        assertInvalidArgumentNaming(rejected, "pickup", "latitude");
    }

    @Test
    @DisplayName("AC5a/AC10: a longitude outside +/-180 is INVALID_ARGUMENT naming the field")
    void rejects_a_longitude_out_of_range() {
        StatusRuntimeException rejected =
                assertThrows(
                        StatusRuntimeException.class,
                        () -> quoteFor(point("38.73690000", "-181"), ONE_DEGREE_NORTH));

        assertInvalidArgumentNaming(rejected, "pickup", "longitude");
    }

    @Test
    @DisplayName("AC5a/AC10: a bad dropoff is named as the dropoff, not merely as a latitude")
    void names_which_of_the_four_values_failed() {
        StatusRuntimeException rejected =
                assertThrows(
                        StatusRuntimeException.class,
                        () -> quoteFor(LISBON, point("abc", "-9.14270000")));

        assertInvalidArgumentNaming(rejected, "dropoff", "latitude");
        assertFalse(
                rejected.getStatus().getDescription().contains("pickup"),
                () ->
                        "the description blames the pickup for a bad dropoff: "
                                + rejected.getStatus().getDescription());
    }

    @Test
    @DisplayName("D5: a broken deployment is INTERNAL with a description, never a bare UNKNOWN")
    void reports_a_missing_price_list_as_an_internal_failure() {
        jdbcTemplate.execute("truncate table fare_rules");

        StatusRuntimeException failed =
                assertThrows(
                        StatusRuntimeException.class, () -> quoteFor(LISBON, ONE_DEGREE_NORTH));

        // UNKNOWN is what spring-grpc's own fallback produces, and it carries the cause in a field
        // that is never serialized -- so the caller would get a status with no description at all,
        // and PUB-4-2 builds its response body out of exactly these two values.
        assertEquals(
                io.grpc.Status.Code.INTERNAL,
                failed.getStatus().getCode(),
                () -> "a broken deployment must not be UNKNOWN: " + failed.getStatus());
        assertNotNull(
                failed.getStatus().getDescription(),
                "an INTERNAL with no description gives the edge nothing to render");
    }

    @Test
    @DisplayName("D5: a server-side failure is never blamed on the caller as INVALID_ARGUMENT")
    void never_blames_the_caller_for_a_server_side_failure() {
        jdbcTemplate.execute("truncate table fare_rules");

        StatusRuntimeException failed =
                assertThrows(
                        StatusRuntimeException.class, () -> quoteFor(LISBON, ONE_DEGREE_NORTH));

        // QuoteGrpcService catches IllegalArgumentException around the two coordinate parses and
        // nothing else. Widen that try to the whole method and this test goes red: an empty price
        // list would come back as the caller's bad input.
        assertNotEquals(
                io.grpc.Status.Code.INVALID_ARGUMENT,
                failed.getStatus().getCode(),
                () ->
                        "an empty price list was reported as the caller's bad input: "
                                + failed.getStatus());
    }

    @Test
    @DisplayName("AC4a: a request id sent as an empty header is replaced, not carried through")
    void mints_a_request_id_when_the_header_arrives_empty() {
        quoteFor(stubCarryingRequestId(""), LISBON, ONE_DEGREE_NORTH);

        List<String> lines = capturedLines();
        assertFalse(lines.isEmpty(), "the call produced no log output, so this proves nothing");
        assertTrue(
                lines.stream().allMatch(QuoteGrpcIntegrationTest::carriesAMintedRequestId),
                () ->
                        "an empty x-request-id was carried into the MDC, which renders as the same"
                                + " empty brackets as no id at all: "
                                + lines);
    }

    @Test
    @DisplayName("AC4a: a request id in gRPC metadata reaches the log output")
    void carries_an_incoming_request_id_into_every_log_line() {
        String incoming = "a-request-id-" + UUID.randomUUID();

        quoteFor(stubCarryingRequestId(incoming), LISBON, ONE_DEGREE_NORTH);

        List<String> lines = capturedLines();
        assertFalse(lines.isEmpty(), "the call produced no log output, so this proves nothing");
        assertTrue(
                lines.stream().allMatch(line -> line.contains(incoming)),
                () -> "a log line the call produced is missing the request id: " + lines);
    }

    @Test
    @DisplayName("AC4a: a call arriving with no request id still gets one, minted at entry")
    void mints_a_request_id_when_none_arrives() {
        quoteFor(LISBON, ONE_DEGREE_NORTH);

        List<String> lines = capturedLines();
        assertFalse(lines.isEmpty(), "the call produced no log output, so this proves nothing");
        assertTrue(
                lines.stream().allMatch(QuoteGrpcIntegrationTest::carriesAMintedRequestId),
                () -> "a log line carries no minted request id: " + lines);
    }

    private static Coordinates point(String latitude, String longitude) {
        return Coordinates.newBuilder().setLatitude(latitude).setLongitude(longitude).build();
    }

    /**
     * The interceptor mints a UUID, and the configured level pattern renders it inside brackets. An
     * empty pair of brackets is what an unset MDC key looks like, so that is the failure to catch.
     */
    private static boolean carriesAMintedRequestId(String line) {
        int opened = line.indexOf('[');
        int closed = line.indexOf(']', opened + 1);
        if (opened < 0 || closed < 0) {
            return false;
        }
        try {
            UUID.fromString(line.substring(opened + 1, closed));
            return true;
        } catch (IllegalArgumentException notAMintedId) {
            return false;
        }
    }

    private static void assertInvalidArgumentNaming(
            StatusRuntimeException rejected, String side, String field) {
        assertEquals(
                io.grpc.Status.Code.INVALID_ARGUMENT,
                rejected.getStatus().getCode(),
                () -> "a bad coordinate must not surface as " + rejected.getStatus());

        String description = rejected.getStatus().getDescription();
        assertTrue(
                description != null && description.contains(side) && description.contains(field),
                () ->
                        "the description must name "
                                + side
                                + " "
                                + field
                                + " -- PUB-4-2's 400 body is built from it. Got: "
                                + description);
    }

    private QuoteServiceGrpc.QuoteServiceBlockingStub stub() {
        return QuoteServiceGrpc.newBlockingStub(channel);
    }

    private QuoteServiceGrpc.QuoteServiceBlockingStub stubCarryingRequestId(String requestId) {
        Metadata metadata = new Metadata();
        metadata.put(
                Metadata.Key.of(
                        RequestIdServerInterceptor.REQUEST_ID_HEADER,
                        Metadata.ASCII_STRING_MARSHALLER),
                requestId);
        return stub().withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private GetQuoteResponse quoteFor(Coordinates pickup, Coordinates dropoff) {
        return quoteFor(stub(), pickup, dropoff);
    }

    private GetQuoteResponse quoteFor(
            QuoteServiceGrpc.QuoteServiceBlockingStub stub,
            Coordinates pickup,
            Coordinates dropoff) {
        return stub.getQuote(
                GetQuoteRequest.newBuilder().setPickup(pickup).setDropoff(dropoff).build());
    }

    private Map<String, Integer> rowCountPerTable() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table :
                jdbcTemplate.queryForList(
                        "select table_name from information_schema.tables where table_schema ="
                                + " 'public' and table_name <> 'flyway_schema_history' order by"
                                + " table_name",
                        String.class)) {
            counts.put(
                    table,
                    jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class));
        }
        return counts;
    }

    /**
     * Captures what the service actually writes, through the level pattern the running
     * configuration resolved. Re-declaring the pattern here would prove only that this test can
     * format a request id -- the claim is that {@code logging.pattern.level} picked it up.
     */
    private void startCapturingTheConfiguredLogOutput() {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger)
                        LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.setPattern(configuredConsolePattern(root));
        encoder.start();

        capturedLog = new ByteArrayOutputStream();
        logCapture = new OutputStreamAppender<>();
        logCapture.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        logCapture.setEncoder(encoder);
        logCapture.setOutputStream(capturedLog);
        logCapture.start();
        capturedLogger().addAppender(logCapture);
    }

    /**
     * This project's own logger, not the root one. The assertions below are {@code allMatch} over
     * every captured line, so a root appender makes any unrelated line -- a Hikari pool event, a
     * Spring lifecycle message, a stack-trace continuation carrying no pattern prefix -- fail them
     * with a message that blames the request id. The pattern is still read off the root console
     * appender, so what is asserted is the configuration the service actually resolved.
     */
    private static ch.qos.logback.classic.Logger capturedLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.puber");
    }

    private String configuredConsolePattern(ch.qos.logback.classic.Logger root) {
        var appenders = root.iteratorForAppenders();
        while (appenders.hasNext()) {
            if (appenders.next() instanceof ConsoleAppender<ILoggingEvent> console
                    && console.getEncoder() instanceof PatternLayoutEncoder pattern) {
                return pattern.getPattern();
            }
        }
        throw new IllegalStateException(
                "no console appender with a pattern encoder on the root logger -- this test can"
                        + " only assert on the pattern the service is actually configured with");
    }

    private List<String> capturedLines() {
        return capturedLog
                .toString(StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.isBlank())
                .toList();
    }
}
