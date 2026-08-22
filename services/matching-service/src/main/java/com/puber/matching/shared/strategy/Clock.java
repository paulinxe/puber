package com.puber.matching.shared.strategy;

import com.puber.matching.shared.model.Deadline;
import java.time.Duration;
import java.time.Instant;

/**
 * The only way this service reads time (AD-10, NFR-9). Reading it any other way fails the build.
 */
public interface Clock {

    /**
     * The current time, UTC, for facts you are about to record.
     *
     * <p>Not for measuring how long something took, and not for deciding whether a deadline has
     * passed: the wall clock jumps when the host is corrected, so subtracting two of these readings
     * gives a wrong answer. Use {@link #deadlineIn} instead. The name is long so the mistake is
     * visible where it would be made.
     */
    Instant wallClockNow();

    /** A deadline this clock can later be asked about. Unaffected by wall-clock corrections. */
    Deadline deadlineIn(Duration duration);

    /**
     * Has this clock reached that deadline yet?
     *
     * <p>Only the clock that issued a deadline can answer: the reading inside it is counted from an
     * arbitrary starting point that belongs to this clock.
     */
    boolean hasReached(Deadline deadline);
}
