package com.puber.matching.shared.model;

import java.math.BigDecimal;

/** A WGS84 point, latitude first. */
public record Coordinates(BigDecimal latitude, BigDecimal longitude) {

    private static final BigDecimal LATITUDE_LIMIT = BigDecimal.valueOf(90);
    private static final BigDecimal LONGITUDE_LIMIT = BigDecimal.valueOf(180);

    /** IUGG mean Earth radius. An int, not a double: it is a whole number of metres. */
    private static final int EARTH_RADIUS_METRES = 6_371_000;

    public Coordinates {
        requireWithin(latitude, LATITUDE_LIMIT, "latitude");
        requireWithin(longitude, LONGITUDE_LIMIT, "longitude");
    }

    /**
     * The great-circle distance to another point, on a sphere. The only trigonometry in this
     * service.
     *
     * <p>Straight line, never road distance: real maps and routing are an explicit non-goal, so
     * every fare here is lower than a real app's for the same two points.
     */
    public Distance distanceTo(Coordinates destination) {
        double fromLatitude = Math.toRadians(latitude.doubleValue());
        double toLatitude = Math.toRadians(destination.latitude().doubleValue());
        double latitudeDelta = toLatitude - fromLatitude;
        double longitudeDelta =
                Math.toRadians(destination.longitude().subtract(longitude).doubleValue());

        double sinHalfLatitudeDelta = Math.sin(latitudeDelta / 2);
        double sinHalfLongitudeDelta = Math.sin(longitudeDelta / 2);
        double halfChordSquared =
                sinHalfLatitudeDelta * sinHalfLatitudeDelta
                        + Math.cos(fromLatitude)
                                * Math.cos(toLatitude)
                                * sinHalfLongitudeDelta
                                * sinHalfLongitudeDelta;
        // Clamped because rounding can push the root a hair above 1 for near-antipodal points,
        // and asin would then return NaN.
        double centralAngle = 2 * Math.asin(Math.min(1, Math.sqrt(halfChordSquared)));

        return new Distance(EARTH_RADIUS_METRES * centralAngle);
    }

    private static void requireWithin(BigDecimal value, BigDecimal limit, String name) {
        if (value.abs().compareTo(limit) > 0) {
            throw new IllegalArgumentException(
                    name + " is outside +/-" + limit.toPlainString() + ": " + value);
        }
    }
}
