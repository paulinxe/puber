package com.puber.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:postgresql://"
                    + HealthReportsDownPromptlyIntegrationTest.UNREACHABLE_HOST
                    + ":"
                    + HealthReportsDownPromptlyIntegrationTest.UNREACHABLE_PORT
                    + "/unreachable",
            "spring.datasource.username=unreachable",
            "spring.datasource.password=unreachable",
            // Migrations against an unreachable datastore would fail context startup, and this
            // test is about the health surface, not about Flyway.
            "spring.flyway.enabled=false",
            // Without this the assertion can pass or fail on a cached answer -- for the wrong
            // reason.
            "management.endpoint.health.cache.time-to-live=0"
        })
class HealthReportsDownPromptlyIntegrationTest {

    /** Constants, not inlined, so the probe and the context under test cannot drift apart. */
    static final String UNREACHABLE_HOST = "192.0.2.1";

    static final int UNREACHABLE_PORT = 5432;

    /**
     * Useless as a readiness probe if exceeded, generous enough not to flake. Hikari's 30s default
     * blows straight through it. Enforced preemptively: plain {@code assertTimeout} only checks
     * elapsed time after the call returns, so an endpoint that blocks forever would hang the suite
     * instead of failing it -- and {@code TestRestTemplate} has no read timeout of its own.
     */
    private static final Duration READINESS_PROBE_BUDGET = Duration.ofSeconds(5);

    /**
     * Matches the configured connection-timeout. If the connect resolves inside the window Hikari
     * would have waited, Hikari never waits, and the bound below holds regardless of that setting.
     */
    private static final Duration CONNECT_MUST_STILL_BE_PENDING_AFTER = Duration.ofSeconds(2);

    @Autowired private TestRestTemplate restTemplate;

    /**
     * Asserts the experiment is valid -- the one thing the timing assertion cannot check itself.
     */
    @BeforeAll
    static void theUnreachableAddressMustNotAnswer() {
        long startedAtNanos = System.nanoTime();
        try (Socket probe = new Socket()) {
            probe.connect(
                    new InetSocketAddress(UNREACHABLE_HOST, UNREACHABLE_PORT),
                    (int) CONNECT_MUST_STILL_BE_PENDING_AFTER.toMillis());
            fail(
                    diagnosis(
                            "accepted a TCP connection",
                            Duration.ofNanos(System.nanoTime() - startedAtNanos),
                            "Something -- most likely a transparent egress proxy on this machine or in this"
                                    + " container's network path -- answers on behalf of every address."));
        } catch (SocketTimeoutException hangingAsRequired) {
            // Still pending: Hikari's connection-timeout is what ends the wait, as required.
        } catch (IOException refusedOrUnroutable) {
            fail(
                    diagnosis(
                            "failed fast with " + refusedOrUnroutable.getClass().getSimpleName(),
                            Duration.ofNanos(System.nanoTime() - startedAtNanos),
                            "The network resolves this address immediately instead of dropping the packets."));
        }
    }

    private static String diagnosis(String whatHappened, Duration elapsed, String likelyCause) {
        return """
        AC4 cannot be proven in this environment, so this test would have passed vacuously.

        %s:%d %s after %dms, rather than hanging until Hikari's connection-timeout expired.
        %s

        Why that invalidates the test: this class proves that health reports DOWN inside a
        readiness-probe budget BECAUSE spring.datasource.hikari.connection-timeout is capped at
        2s. That only follows while the TCP connect hangs. When the connect resolves at once the
        Postgres handshake fails at once, health reports DOWN in milliseconds, and the assertion
        passes with connection-timeout deleted outright -- the exact regression this test exists
        to catch. There is no CI, so this run is the only thing asserting AC4.

        Fix the environment, not the assertion -- do not widen the budget and do not delete this
        precondition. Either run the suite where TEST-NET-1 is dropped, or blackhole it for the
        test runner (`ip route add blackhole 192.0.2.0/24`), or point UNREACHABLE_HOST at an
        address on the Compose network that drops rather than refuses.\
        """
                .formatted(
                        UNREACHABLE_HOST,
                        UNREACHABLE_PORT,
                        whatHappened,
                        elapsed.toMillis(),
                        likelyCause);
    }

    @Test
    @DisplayName("AC4: an unreachable datastore yields DOWN inside a readiness-probe budget")
    void health_reports_down_within_the_probe_budget() {
        ResponseEntity<String> response =
                assertTimeoutPreemptively(
                        READINESS_PROBE_BUDGET,
                        () -> restTemplate.getForEntity("/actuator/health", String.class),
                        "health did not answer within a readiness-probe budget -- the datasource timeouts are"
                                + " almost certainly left at their defaults");

        assertEquals(
                503,
                response.getStatusCode().value(),
                "an unreachable datastore must make the service report itself unavailable");
        assertNotNull(response.getBody());
        assertTrue(
                response.getBody().contains("\"status\":\"DOWN\""),
                () -> "expected a DOWN health response, got: " + response.getBody());
    }
}
