package com.puber.matching.shared.strategy;

import com.puber.matching.shared.model.Deadline;
import java.time.Duration;
import java.time.Instant;

/**
 * The real clock, and the only class allowed to read the host's time.
 *
 * <p>No Spring annotation: the bean is declared in {@code config} so that {@code shared} stays free
 * of the framework.
 */
public final class SystemClock implements Clock {

    @Override
    public Instant wallClockNow() {
        return Instant.now();
    }

    @Override
    public Deadline deadlineIn(Duration duration) {
        return new Deadline(System.nanoTime() + duration.toNanos());
    }

    @Override
    public boolean hasReached(Deadline deadline) {
        return deadline.hasBeenReachedAt(System.nanoTime());
    }
}
