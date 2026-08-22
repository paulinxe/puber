package com.puber.matching.shared.model;

/**
 * A moment in the future, counted on a timer rather than on the calendar.
 *
 * <p>The number inside means nothing on its own -- it is counted from an arbitrary starting point,
 * and only inside the process that took it. Ask the clock that issued it, via {@code
 * Clock.hasReached}.
 */
public record Deadline(long monotonicNanos) {

    /**
     * Subtracts rather than compares, because the timer can be negative and wraps round after about
     * 292 years of uptime -- so {@code reading >= monotonicNanos} is wrong in the general case. The
     * subtraction is the idiom {@code System.nanoTime}'s own documentation prescribes.
     */
    public boolean hasBeenReachedAt(long monotonicReading) {
        return monotonicReading - monotonicNanos >= 0;
    }
}
