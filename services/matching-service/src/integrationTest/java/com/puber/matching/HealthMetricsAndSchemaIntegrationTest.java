package com.puber.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Boot disables metrics exporters inside @SpringBootTest, so without this /actuator/prometheus
// 404s under test while working in the running service.
@AutoConfigureMetrics
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // The `db` contributor only appears in the body when details are shown. Turned on here
        // rather than in the main configuration: a test requirement is not a production setting.
        properties = "management.endpoint.health.show-details=always")
class HealthMetricsAndSchemaIntegrationTest {

    /** Story 1.3 adds V2 and bumps this, editing one constant instead of three tests. */
    private static final int HIGHEST_VERSION_THIS_STORY_OWNS = 1;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private Flyway flyway;

    @Test
    @DisplayName("AC3: health reports UP once Postgres is reachable")
    void healthReportsUp() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health", String.class);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(
                response.getBody().contains("\"status\":\"UP\""),
                () -> "expected an UP health response, got: " + response.getBody());
    }

    @Test
    @DisplayName("AC2: health is UP because the datastore contributor says so, not despite it")
    void healthIncludesTheDatastoreContributor() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health", String.class);

        // Checking the body merely mentions "db" is not enough: a 503 DOWN body mentions it too,
        // so the test would have been green in exactly the state it claims to rule out. The status
        // code and the contributor's own status are what carry the claim.
        assertEquals(
                200,
                response.getStatusCode().value(),
                "health must be UP here -- this class runs against the live Compose Postgres");
        assertNotNull(response.getBody());

        // Jackson 3 (tools.jackson.*), not Jackson 2 (com.fasterxml.jackson.*) -- Boot 4.1
        // manages Jackson 3, and the old coordinates are not on the classpath at all.
        //
        // Parsed, not pattern-matched. The contributor is a nested object
        // ("db":{"details":{...},"status":"UP"}), so any regex cheap enough to read is either
        // defeated by the nesting or liable to match a *different* component's status and
        // vouch for the wrong thing -- which is the same class of bug as the one being fixed.
        String body = response.getBody();
        JsonNode db = new ObjectMapper().readTree(body).path("components").path("db");

        assertFalse(
                db.isMissingNode(),
                () ->
                        "no datastore health contributor in the response -- health would report UP with Postgres"
                                + " unreachable (AD-1): "
                                + body);
        assertEquals(
                "UP",
                db.path("status").asText(),
                () ->
                        "the datastore contributor is present but not UP, so health is not UP because of it: "
                                + body);
    }

    @Test
    @DisplayName("AC3: the Prometheus endpoint serves Prometheus text exposition format")
    void prometheusEndpointServesPrometheusTextFormat() {
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
    @DisplayName("AC5: Flyway recorded the baseline, and it succeeded")
    void flywayRecordedTheBaseline() {
        List<Map<String, Object>> baselineRows =
                jdbcTemplate.queryForList(
                        "select version, success from flyway_schema_history where version = '1'");

        assertEquals(
                1,
                baselineRows.size(),
                () ->
                        "expected exactly one schema-history row for V1, found "
                                + baselineRows.size()
                                + ": "
                                + baselineRows);
        assertEquals(
                Boolean.TRUE,
                baselineRows.get(0).get("success"),
                "the baseline migration is recorded as failed");
    }

    @Test
    @DisplayName("AC5: a second start applies no migrations and does not fail")
    void aSecondStartAppliesNoMigrations() {
        // The context that booted this class already migrated. Migrating again against the same
        // database IS a second start, which is what AC5 asks about -- and it holds regardless of
        // which order JUnit ran the classes in, or whether any other class enables Flyway at all.
        MigrateResult secondStart = flyway.migrate();

        assertTrue(secondStart.success, "a second start must not fail");
        assertEquals(
                0,
                secondStart.migrationsExecuted,
                () ->
                        "a second start applied "
                                + secondStart.migrationsExecuted
                                + " migration(s) -- the schema is not idempotent on restart: "
                                + secondStart.migrations);
    }

    @Test
    @DisplayName("AC5: the baseline creates no tables -- matching-service owns none yet")
    void baselineCreatesNoTables() {
        List<String> tables =
                jdbcTemplate.queryForList(
                        "select table_name from information_schema.tables where table_schema = 'public'"
                                + " and table_name <> 'flyway_schema_history' order by table_name",
                        String.class);

        assertTrue(
                tables.isEmpty(),
                () -> "V1 is a baseline and creates nothing. " + attribute(tables));
    }

    /**
     * Tells the two causes of a failing table assertion apart, because they need opposite responses
     * and the symptom is identical.
     *
     * <p>{@code matching-postgres-data} is a named volume shared by every checkout of this
     * repository on the machine. Run a branch that adds {@code V2__fare_rules.sql}, switch back,
     * and the table survives -- a failure that has nothing to do with V1. Without this attribution
     * the message blames the baseline and the reader goes looking for a defect that is not there.
     */
    private String attribute(List<String> tables) {
        List<String> laterVersions =
                jdbcTemplate.queryForList(
                        "select version from flyway_schema_history where version is not null"
                                + " and version::numeric > ? order by version::numeric",
                        String.class,
                        HIGHEST_VERSION_THIS_STORY_OWNS);

        if (!laterVersions.isEmpty()) {
            return "Found "
                    + tables
                    + ", but the schema history also carries migration(s) "
                    + laterVersions
                    + " that this story does not ship. This is a stale shared volume from another branch,"
                    + " not a defect in V1 -- run `make clean` and re-run. (matching-postgres-data is shared"
                    + " by every checkout on this machine.)";
        }
        return "fare_rules arrives in Story 1.3 and rides in Story 3.1, and the schema history shows"
                + " nothing beyond V"
                + HIGHEST_VERSION_THIS_STORY_OWNS
                + " -- so a migration this story owns created them. Found: "
                + tables;
    }
}
