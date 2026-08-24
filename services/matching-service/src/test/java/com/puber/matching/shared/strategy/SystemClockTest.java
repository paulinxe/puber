package com.puber.matching.shared.strategy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puber.matching.shared.model.Deadline;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The real clock reads real time, and reads each half from its own source. */
class SystemClockTest {

    private static final Duration LONGER_THAN_THIS_TEST_RUN = Duration.ofHours(1);

    private final SystemClock clock = new SystemClock();

    @Test
    @DisplayName("AC3: the wall clock reads real time -- bracketed, never within a tolerance")
    void wall_clock_reads_real_time() {
        // Bracketed between two real readings, not a tolerance window: a tolerance would pass or
        // fail depending on how loaded the machine is.
        Instant before = Instant.now();
        Instant reading = clock.wallClockNow();
        Instant after = Instant.now();

        assertFalse(reading.isBefore(before), () -> reading + " precedes " + before);
        assertFalse(reading.isAfter(after), () -> reading + " follows " + after);
    }

    @Test
    @DisplayName("AC3: monotonic readings never decrease")
    void monotonic_readings_never_decrease() {
        long first = clock.deadlineIn(Duration.ZERO).monotonicNanos();
        long second = clock.deadlineIn(Duration.ZERO).monotonicNanos();

        assertTrue(second - first >= 0, "the monotonic source went backwards");
    }

    @Test
    @DisplayName("AC4: a deadline is evaluated against the monotonic source, in real time too")
    void deadlines_are_evaluated_against_elapsed_monotonic_time() {
        assertTrue(
                clock.hasReached(clock.deadlineIn(Duration.ZERO)),
                "a zero-length deadline had not expired by the time it was read back");

        Deadline farFuture = clock.deadlineIn(LONGER_THAN_THIS_TEST_RUN);
        assertFalse(clock.hasReached(farFuture), "an hour-long deadline expired immediately");
    }
}
