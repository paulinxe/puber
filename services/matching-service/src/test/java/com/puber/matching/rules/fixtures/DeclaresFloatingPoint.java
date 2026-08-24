package com.puber.matching.rules.fixtures;

/**
 * Declares floating point every way a class can: a field, a return type and a parameter; boxed,
 * unboxed, in an array and inside a generic argument. Nothing calls it -- every member here must be
 * reported, and the driving test reflects over the class rather than re-listing them, so a member
 * added here that the rule misses fails the build.
 *
 * <p>The array and collection members were added after a review planted them and watched the rule
 * stay green: an exact match on the type name misses {@code double[]}, and a {@code List<Double>}
 * erases to {@code java.util.List}.
 */
public final class DeclaresFloatingPoint {

    public double aDoubleField = 0;

    public float aFloatField = 0;

    public Double aBoxedDoubleField = Double.valueOf(0);

    public Float aBoxedFloatField = Float.valueOf(0);

    public double returnsADouble() {
        return 0;
    }

    public float returnsAFloat() {
        return 0;
    }

    public Double returnsABoxedDouble() {
        return Double.valueOf(0);
    }

    public Float returnsABoxedFloat() {
        return Float.valueOf(0);
    }

    public String takesADouble(double amount) {
        return String.valueOf(amount);
    }

    public String takesABoxedFloat(Float amount) {
        return String.valueOf(amount);
    }

    public double[] anArrayOfDoubles = new double[0];

    public Float[] anArrayOfBoxedFloats = new Float[0];

    public java.util.List<Double> aListOfDoubles = java.util.List.of();

    public java.util.Map<String, Double> aMapValuedByDoubles = java.util.Map.of();

    public java.util.List<Double> returnsAListOfDoubles() {
        return aListOfDoubles;
    }

    public String takesAnArrayOfDoubles(double[] amounts) {
        return String.valueOf(amounts.length);
    }
}
