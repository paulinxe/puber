package com.puber.matching.rules.fixtures;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Reads time every way the rule is supposed to catch. Nothing calls it -- it exists to be rejected.
 *
 * <p>Every method here must be rejected, and the test asserts that by reflecting over the class
 * rather than re-listing it. So a read added here that the rule misses fails the build.
 *
 * <p>The old {@code Date} and {@code Calendar} classes are not here: they are banned as types, and
 * {@link UsesTheLegacyDateApi} proves that separately.
 */
public final class ReadsTimeDirectly {

    private static final java.time.Clock READS_NOTHING =
            java.time.Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    public Instant instantNow() {
        return Instant.now();
    }

    public Instant instantNowFromAZone() {
        return Instant.now(READS_NOTHING);
    }

    public LocalDate localDateNow() {
        return LocalDate.now();
    }

    public LocalDate localDateNowInAZone() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    public LocalTime localTimeNow() {
        return LocalTime.now();
    }

    public LocalDateTime localDateTimeNow() {
        return LocalDateTime.now();
    }

    public OffsetDateTime offsetDateTimeNow() {
        return OffsetDateTime.now();
    }

    public OffsetTime offsetTimeNow() {
        return OffsetTime.now();
    }

    public ZonedDateTime zonedDateTimeNow() {
        return ZonedDateTime.now();
    }

    public Year yearNow() {
        return Year.now();
    }

    public YearMonth yearMonthNow() {
        return YearMonth.now();
    }

    public MonthDay monthDayNow() {
        return MonthDay.now();
    }

    public long systemCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    public long systemNanoTime() {
        return System.nanoTime();
    }

    public java.time.Clock jdkClockSystemUtc() {
        return java.time.Clock.systemUTC();
    }

    public java.time.Clock jdkClockSystemDefaultZone() {
        return java.time.Clock.systemDefaultZone();
    }

    public java.time.Clock jdkClockSystem() {
        return java.time.Clock.system(ZoneOffset.UTC);
    }

    public java.time.Clock jdkClockTick() {
        return java.time.Clock.tick(READS_NOTHING, Duration.ofSeconds(1));
    }

    public java.time.Clock jdkClockTickMillis() {
        return java.time.Clock.tickMillis(ZoneOffset.UTC);
    }

    public java.time.Clock jdkClockTickSeconds() {
        return java.time.Clock.tickSeconds(ZoneOffset.UTC);
    }

    public java.time.Clock jdkClockTickMinutes() {
        return java.time.Clock.tickMinutes(ZoneOffset.UTC);
    }

    public ZoneId zoneIdSystemDefault() {
        return ZoneId.systemDefault();
    }
}
