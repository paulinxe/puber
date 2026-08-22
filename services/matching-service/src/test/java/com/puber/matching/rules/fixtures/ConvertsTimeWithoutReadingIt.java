package com.puber.matching.rules.fixtures;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The other half of the proof: none of this reads a clock, so the rule must accept it.
 *
 * <p>A rule that also rejected arithmetic and conversion would be argued with and then switched
 * off.
 *
 * <p>{@code ZoneId.of} sits here next to the banned {@code ZoneId.systemDefault} on purpose: naming
 * a zone explicitly is allowed, asking the host which zone it is in is not.
 */
public final class ConvertsTimeWithoutReadingIt {

    public Instant fromEpochMillis(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }

    public long epochSecondOf(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toEpochSecond();
    }

    public Instant plus(Instant instant, Duration duration) {
        return instant.plus(duration);
    }

    public ZoneId anExplicitlyNamedZone() {
        return ZoneId.of("UTC");
    }
}
