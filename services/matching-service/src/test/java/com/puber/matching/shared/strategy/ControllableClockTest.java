package com.puber.matching.shared.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puber.matching.shared.model.Deadline;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The calendar and the timer move independently, and a script replays identically. */
class ControllableClockTest {

    private static final Duration WINDOW = Duration.ofSeconds(10);

    /** Chosen for being far from UTC in both directions: +14:00 and -11:00. */
    private static final String EAST_OF_UTC = "Pacific/Kiritimati";

    private static final String WEST_OF_UTC = "Pacific/Niue";

    @Test
    @DisplayName("AC3: advancing moves both sources; shifting the wall clock moves only that one")
    void theTwoSourcesAreIndependent() {
        ControllableClock clock = new ControllableClock();
        long monotonicAtStart = clock.deadlineIn(Duration.ZERO).monotonicNanos();

        clock.advance(WINDOW);
        assertEquals(
                monotonicAtStart + WINDOW.toNanos(),
                clock.deadlineIn(Duration.ZERO).monotonicNanos(),
                "advance() did not move the monotonic source");
        assertEquals(
                ControllableClock.DEFAULT_START.plus(WINDOW),
                clock.wallClockNow(),
                "advance() did not move the wall clock");

        clock.shiftWallClock(Duration.ofHours(-1));
        assertEquals(
                monotonicAtStart + WINDOW.toNanos(),
                clock.deadlineIn(Duration.ZERO).monotonicNanos(),
                "a wall-clock correction moved the monotonic source with it");
    }

    @Test
    @DisplayName("AC3: monotonic readings never decrease, whatever the wall clock does")
    void monotonicReadingsNeverDecrease() {
        ControllableClock clock = new ControllableClock();
        long previous = clock.deadlineIn(Duration.ZERO).monotonicNanos();

        for (Duration step : List.of(Duration.ZERO, Duration.ofNanos(1), WINDOW)) {
            clock.shiftWallClock(Duration.ofHours(-1));
            clock.advance(step);
            long reading = clock.deadlineIn(Duration.ZERO).monotonicNanos();
            assertTrue(reading - previous >= 0, "a monotonic reading went backwards");
            previous = reading;
        }
    }

    private static void setDefaultZoneAndConfirmItMoved(String zoneId) {
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
        assertEquals(
                zoneId,
                TimeZone.getDefault().getID(),
                "the JVM does not know this zone, so it silently fell back to GMT and this test"
                        + " proves nothing about zone independence");
    }

    @Test
    @DisplayName(
            "advancing by a negative duration is rejected rather than rewinding the monotonic source")
    void refusesToRewindTheMonotonicSource() {
        ControllableClock clock = new ControllableClock();
        Deadline deadline = clock.deadlineIn(WINDOW);
        clock.advance(WINDOW);
        assertTrue(clock.hasReached(deadline), "the deadline should have been reached by now");

        // Without the guard this un-expires a passed deadline, which no real clock can do.
        assertThrows(
                IllegalArgumentException.class,
                () -> clock.advance(WINDOW.negated()),
                "advance() rewound the monotonic source instead of rejecting the negative duration");
        assertTrue(
                clock.hasReached(deadline),
                "the rejected advance still moved the clock, so the guard leaks");
    }

    @Test
    @DisplayName("AC5: the same script run twice produces the same observations")
    void theSameScriptReplaysIdentically() {
        Function<ControllableClock, List<String>> script =
                clock -> {
                    List<String> observed = new ArrayList<>();
                    Deadline deadline = clock.deadlineIn(WINDOW);
                    for (int step = 0; step < 4; step++) {
                        clock.advance(WINDOW.dividedBy(3));
                        clock.shiftWallClock(Duration.ofMinutes(-7));
                        observed.add(
                                clock.wallClockNow() + " expired=" + clock.hasReached(deadline));
                    }
                    return observed;
                };

        List<String> firstRun = script.apply(new ControllableClock());
        List<String> secondRun = script.apply(new ControllableClock());

        assertFalse(firstRun.isEmpty(), "the script observed nothing, so it compares nothing");
        assertEquals(firstRun, secondRun, "the same script produced different observations");
    }

    @Test
    @DisplayName("AC3: readings are the same UTC instants whatever the JVM default zone is")
    void readingsAreUtcRegardlessOfTheDefaultZone() {
        TimeZone originalDefault = TimeZone.getDefault();
        try {
            // getTimeZone(unknown) returns GMT instead of failing, so without this both "zones"
            // could be GMT and every assertion below would hold while proving nothing.
            setDefaultZoneAndConfirmItMoved(EAST_OF_UTC);
            ControllableClock clock = new ControllableClock();
            Instant startEastOfUtc = clock.wallClockNow();

            setDefaultZoneAndConfirmItMoved(WEST_OF_UTC);
            assertEquals(
                    startEastOfUtc,
                    clock.wallClockNow(),
                    "the same clock read a different instant after the default zone changed");
            assertEquals(
                    ControllableClock.DEFAULT_START,
                    startEastOfUtc,
                    "the start instant was interpreted in the default zone rather than UTC");

            clock.advance(WINDOW);
            assertEquals(
                    ControllableClock.DEFAULT_START.plus(WINDOW),
                    clock.wallClockNow(),
                    "an advance landed on a different instant under a non-UTC default zone");
        } finally {
            TimeZone.setDefault(originalDefault);
        }
    }
}
