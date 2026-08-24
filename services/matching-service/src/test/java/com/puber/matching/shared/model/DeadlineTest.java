package com.puber.matching.shared.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puber.matching.shared.strategy.ControllableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Deadline behaviour, proven by moving a clock rather than by waiting. */
class DeadlineTest {

    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final Duration ONE_NANOSECOND = Duration.ofNanos(1);
    private static final Duration NTP_CORRECTION_FORWARD = Duration.ofHours(1);

    /** Twice the forward correction, so the second shift lands before the window even opened. */
    private static final Duration NTP_CORRECTION_BACKWARD = Duration.ofHours(-2);

    @Test
    @DisplayName("AC2: a deadline expires at its boundary -- not before, and still after")
    void expires_exactly_when_its_window_elapses() {
        ControllableClock clock = new ControllableClock();
        Deadline deadline = clock.deadlineIn(WINDOW);

        assertFalse(clock.hasReached(deadline), "expired before any time passed at all");

        clock.advance(WINDOW.minus(ONE_NANOSECOND));
        assertFalse(clock.hasReached(deadline), "expired a nanosecond early");

        clock.advance(ONE_NANOSECOND);
        assertTrue(clock.hasReached(deadline), "did not expire at its boundary");

        clock.advance(WINDOW);
        assertTrue(clock.hasReached(deadline), "un-expired itself once the boundary was past");
    }

    @Test
    @DisplayName("AC4: a wall-clock correction mid-window changes nothing about when it fires")
    void a_wall_clock_correction_neither_fires_it_early_nor_stops_it_firing() {
        ControllableClock clock = new ControllableClock();
        Deadline deadline = clock.deadlineIn(WINDOW);
        clock.advance(WINDOW.dividedBy(2));

        // Each shift is asserted to have happened: a no-op shiftWallClock() would otherwise leave
        // everything below green while proving the opposite.
        Instant beforeCorrection = clock.wallClockNow();
        clock.shiftWallClock(NTP_CORRECTION_FORWARD);
        assertEquals(
                beforeCorrection.plus(NTP_CORRECTION_FORWARD),
                clock.wallClockNow(),
                "the wall clock did not move, so this test proves nothing");
        assertFalse(
                clock.hasReached(deadline),
                "a correction forward past the deadline fired it early -- the window was measured on"
                        + " the wall clock");

        clock.shiftWallClock(NTP_CORRECTION_BACKWARD);
        assertEquals(
                beforeCorrection.plus(NTP_CORRECTION_FORWARD).plus(NTP_CORRECTION_BACKWARD),
                clock.wallClockNow(),
                "the wall clock did not move backwards, so this test proves nothing");
        assertFalse(clock.hasReached(deadline), "a correction backwards fired the deadline early");

        clock.advance(WINDOW.dividedBy(2));
        assertTrue(
                clock.hasReached(deadline),
                "the deadline stopped firing once the wall clock had been corrected");
    }

    @Test
    @DisplayName("a deadline that wraps the monotonic origin still fires at its boundary")
    void survives_monotonic_wraparound() {
        // A real timer starts anywhere and wraps. Compared by value rather than by difference,
        // this deadline reads as already expired at t=0.
        long oneSecondBeforeWrap = Long.MAX_VALUE - Duration.ofSeconds(1).toNanos();
        ControllableClock clock =
                new ControllableClock(ControllableClock.DEFAULT_START, oneSecondBeforeWrap);
        Deadline deadline = clock.deadlineIn(Duration.ofSeconds(2));

        assertFalse(clock.hasReached(deadline), "a wrapped deadline read as already expired");

        clock.advance(Duration.ofSeconds(1));
        assertFalse(clock.hasReached(deadline), "expired a second early across the wrap");

        clock.advance(Duration.ofSeconds(1));
        assertTrue(clock.hasReached(deadline), "never expired at all across the wrap");
    }
}
