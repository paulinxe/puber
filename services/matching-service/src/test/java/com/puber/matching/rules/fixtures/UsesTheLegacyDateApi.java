package com.puber.matching.rules.fixtures;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * Uses the old {@code Date} and {@code Calendar} classes, which are banned outright.
 *
 * <p>{@code new Date(millis)} is in here deliberately: it reads no clock and is still a violation.
 * That is the difference between banning a type and banning its clock-reading methods, and listing
 * methods is how {@code new GregorianCalendar()} got through.
 */
public final class UsesTheLegacyDateApi {

    public Date clockReadingConstructor() {
        return new Date();
    }

    public Date conversionConstructor() {
        return new Date(0L);
    }

    public Calendar calendarFactory() {
        return Calendar.getInstance();
    }

    public GregorianCalendar gregorianCalendarConstructor() {
        return new GregorianCalendar();
    }

    public TimeZone theDefaultZone() {
        return TimeZone.getDefault();
    }
}
