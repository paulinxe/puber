package com.puber.rider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

/**
 * AC7: the same observability surface matching-service exposes.
 *
 * <p>The surface, not the body: this service owns no database, so its health response has no {@code
 * db} contributor and a body comparison would compare two different things.
 */
// Boot disables metrics exporters inside @SpringBootTest, so without this /actuator/prometheus
// 404s under test while working in the running service.
@AutoConfigureMetrics
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.grpc.client.channel.matching.target=static://unused-by-this-test")
class HealthAndMetricsIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @Test
    @DisplayName("AC7: health reports UP")
    void health_reports_up() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health", String.class);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(
                response.getBody().contains("\"status\":\"UP\""),
                () -> "expected an UP health response, got: " + response.getBody());
    }

    @Test
    @DisplayName("AC7: the Prometheus endpoint serves Prometheus text exposition format")
    void prometheus_endpoint_serves_prometheus_text_format() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getHeaders().getContentType());
        assertEquals(
                "text/plain",
                response.getHeaders().getContentType().toString().split(";")[0],
                "Prometheus scrapes text/plain; a JSON body would not be scrapeable");

        String body = response.getBody();
        assertNotNull(body);
        // The exposition format is line-oriented: metadata lines then samples.
        assertTrue(body.contains("# HELP "), "no # HELP metadata lines in the exposition output");
        assertTrue(body.contains("# TYPE "), "no # TYPE metadata lines in the exposition output");
        assertTrue(
                body.lines().anyMatch(line -> line.startsWith("jvm_")),
                "no sample lines in the exposition output -- the registry exposed no metrics");
    }

    @Test
    @DisplayName("AC7: the actuator sits outside the versioned API prefix")
    void the_actuator_is_not_behind_the_api_prefix() {
        // PUB-4-3's AC3 test depends on this: it asserts the gateway 404s /actuator/health, which
        // only means anything while the actuator sits outside the routed prefix. Both spellings
        // answering 200 would mean the prefix was applied with context-path.
        assertNotEquals(
                200,
                restTemplate
                        .getForEntity("/rider/v1/actuator/health", String.class)
                        .getStatusCode()
                        .value(),
                "the API prefix was applied to the actuator too, so health and metrics moved");
    }
}
