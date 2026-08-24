package com.puber.matching.shared.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoordinatesTest {

    /**
     * Published great-circle figures for Paris to London cluster around 344 km, quoted between
     * different reference points inside each city and on the WGS84 ellipsoid rather than on a
     * sphere, so the expectation cannot be exact.
     *
     * <p>The tolerance is 200 m, not 2 km. At 2 km this assertion passed with the WGS84 equatorial
     * radius substituted for AD-62's IUGG mean -- checked by planting that substitution -- which
     * left the one constant the spine pins guarded by nothing. 200 m still absorbs the
     * ellipsoid-versus-sphere gap (the measurement lands 44 m from the expectation) and rejects
     * every neighbouring radius: equatorial 6 378 137 misses by about 340 m, polar 6 356 752 by
     * about 820 m.
     */
    private static final double PARIS_TO_LONDON_METRES = 343_600;

    private static final double TOLERANCE_METRES = 200;

    /** Half the circumference on AD-62's radius: pi x 6 371 000. Nothing can be farther. */
    private static final double POLE_TO_POLE_METRES = 20_015_086.796;

    private static Coordinates at(String latitude, String longitude) {
        return new Coordinates(new BigDecimal(latitude), new BigDecimal(longitude));
    }

    @Test
    @DisplayName("AC4: the haversine reproduces a published distance -- Paris to London, 344 km")
    void measures_a_published_distance() {
        double measured = at("48.8566", "2.3522").distanceTo(at("51.5074", "-0.1278")).metres();

        assertTrue(
                Math.abs(measured - PARIS_TO_LONDON_METRES) <= TOLERANCE_METRES,
                () ->
                        "expected about "
                                + PARIS_TO_LONDON_METRES
                                + " m within "
                                + TOLERANCE_METRES
                                + " m, measured "
                                + measured);
    }

    @Test
    @DisplayName("AC4: a trip that goes nowhere covers no distance")
    void measures_zero_between_identical_points() {
        Coordinates point = at("38.7369", "-9.1427");

        assertEquals(0.0, point.distanceTo(point).metres());
    }

    @Test
    @DisplayName("AC6: a latitude beyond +/-90 is rejected")
    void rejects_a_latitude_outside_the_poles() {
        IllegalArgumentException rejection =
                assertThrows(IllegalArgumentException.class, () -> at("90.00000001", "0"));

        assertTrue(
                rejection.getMessage().contains("latitude"),
                () -> "the message does not name the offending field: " + rejection.getMessage());
    }

    @Test
    @DisplayName("AC6: a longitude beyond +/-180 is rejected")
    void rejects_a_longitude_outside_the_meridians() {
        IllegalArgumentException rejection =
                assertThrows(IllegalArgumentException.class, () -> at("0", "-180.00000001"));

        assertTrue(
                rejection.getMessage().contains("longitude"),
                () -> "the message does not name the offending field: " + rejection.getMessage());
    }

    @Test
    @DisplayName("AC6: the limits themselves are valid -- the poles and the date line exist")
    void accepts_the_limits() {
        assertDoesNotThrow(
                () -> {
                    at("90", "180");
                    at("-90", "-180");
                });
    }

    @Test
    @DisplayName("AC4: pole to pole is 20 015 km -- the longest trip the formula can be handed")
    void measures_the_longest_possible_trip() {
        double measured = at("90", "0").distanceTo(at("-90", "0")).metres();

        assertEquals(POLE_TO_POLE_METRES, measured, 1.0);
    }

    @Test
    @DisplayName("AC4: crossing the date line takes the short way round, not the long way")
    void measures_across_the_date_line() {
        // 0.2 degrees apart on the equator, but 359.8 degrees apart if the wrap is mishandled.
        // 0.2 degrees is 6371000 x toRadians(0.2) = 22 240 m; the long way would be 40 052 km.
        double measured = at("0", "179.90000000").distanceTo(at("0", "-179.90000000")).metres();

        assertEquals(22_239.985, measured, 1.0);
    }
}
