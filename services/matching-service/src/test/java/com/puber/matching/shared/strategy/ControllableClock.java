package com.puber.matching.shared.strategy;

import com.puber.matching.shared.model.Deadline;
import java.time.Duration;
import java.time.Instant;

/**
 * A {@link Clock} the test moves by hand, so a ten-second window can be crossed instantly instead
 * of waited out.
 *
 * <p>Nothing here reads real time, construction included, which is why the same script replayed
 * twice sees exactly the same instants.
 *
 * <p>Single-threaded: the fields are unsynchronized, so do not hand this to code reading it on
 * another thread. Add the synchronization when the first test needs to.
 */
public final class ControllableClock implements Clock {

    /** Any instant would do. Being fixed rather than real is the point. */
    public static final Instant DEFAULT_START = Instant.parse("2026-01-01T00:00:00Z");

    private static final long DEFAULT_MONOTONIC_ORIGIN_NANOS = 0L;

    private Instant wallClock;
    private long monotonicNanos;

    public ControllableClock() {
        this(DEFAULT_START, DEFAULT_MONOTONIC_ORIGIN_NANOS);
    }

    /**
     * @param monotonicOriginNanos where the timer starts. A real one starts anywhere and wraps, so
     *     a test can pick a start that wraps mid-window.
     */
    public ControllableClock(Instant start, long monotonicOriginNanos) {
        this.wallClock = start;
        this.monotonicNanos = monotonicOriginNanos;
    }

    /**
     * Ordinary time passing: the calendar and the timer move forward together.
     *
     * <p>Refuses to go backwards. A timer that rewinds would un-expire a deadline that had already
     * passed, silently. To move only the calendar backwards, use {@link #shiftWallClock}.
     */
    public void advance(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException(
                    "a monotonic source cannot go backwards; use shiftWallClock("
                            + duration
                            + ") to move only the wall clock");
        }
        wallClock = wallClock.plus(duration);
        monotonicNanos += duration.toNanos();
    }

    /**
     * The host's clock being corrected: the calendar moves, the timer does not. Negative is valid
     * -- corrections go both ways.
     */
    public void shiftWallClock(Duration duration) {
        wallClock = wallClock.plus(duration);
    }

    @Override
    public Instant wallClockNow() {
        return wallClock;
    }

    @Override
    public Deadline deadlineIn(Duration duration) {
        return new Deadline(monotonicNanos + duration.toNanos());
    }

    @Override
    public boolean hasReached(Deadline deadline) {
        return deadline.hasBeenReachedAt(monotonicNanos);
    }
}
